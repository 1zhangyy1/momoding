package app.momoding.core.provider

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class OpenRouterChatRequest(
    val modelId: String,
    val messages: JsonArray,
    val functionTools: JsonArray? = null,
    val webSearch: OpenRouterWebSearchConfig? = null,
    val webFetch: OpenRouterWebFetchConfig? = null,
    val maxTokens: Int? = null,
)

/** Typed, bounded OpenRouter server-tool configuration. It never accepts arbitrary provider JSON. */
data class OpenRouterWebSearchConfig(
    val engine: OpenRouterWebSearchEngine = OpenRouterWebSearchEngine.EXA,
    val maxUses: Int = 3,
    val maxResults: Int = 5,
    val maxTotalResults: Int = 15,
    val maxCharactersPerResult: Int = 2_000,
    val maxServerToolCalls: Int = 3,
)

enum class OpenRouterWebSearchEngine(val wireValue: String) {
    EXA("exa"),
}

/** OpenRouter executes this server-side; page bytes never cross the Android tool boundary. */
data class OpenRouterWebFetchConfig(
    val engine: OpenRouterWebFetchEngine = OpenRouterWebFetchEngine.OPENROUTER,
    val maxUses: Int = 3,
    val maxContentTokens: Int = 20_000,
)

enum class OpenRouterWebFetchEngine(val wireValue: String) {
    OPENROUTER("openrouter"),
}

data class OpenRouterStreamResult(
    val generationId: String?,
    val chunkCount: Int,
)

data class OpenRouterModelSummary(
    val id: String,
    val name: String,
    val contextLength: Int?,
    val inputModalities: List<String>,
    val supportedParameters: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
)

enum class OpenRouterFailurePhase {
    BEFORE_STREAM,
    MID_STREAM,
    NETWORK,
}

class OpenRouterRequestException(
    val statusCode: Int?,
    val errorType: String?,
    val retryAfterSeconds: Long?,
    val phase: OpenRouterFailurePhase,
    safeMessage: String,
    cause: Throwable? = null,
) : IOException(safeMessage, cause) {
    fun safeTaskMessage(): String = safeOpenRouterFailureMessage(
        statusCode = statusCode,
        errorType = errorType,
        fallback = message,
    )

    override fun toString(): String =
        "OpenRouterRequestException(statusCode=$statusCode, errorType=$errorType, " +
            "retryAfterSeconds=$retryAfterSeconds, phase=$phase, message=$message)"
}

class OpenRouterNativeClient internal constructor(
    private val endpoint: HttpUrl,
    baseClient: OkHttpClient,
) {
    constructor() : this(
        endpoint = ProviderProfilePolicy.OPENROUTER_BASE_URL.toHttpUrl(),
        baseClient = OkHttpClient(),
    )

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        credential: ProviderCredential,
        request: OpenRouterChatRequest,
        onChunk: (JsonObject) -> Unit,
    ): OpenRouterStreamResult {
        ProviderProfilePolicy.validate(credential)
        validateRequest(credential.profile, request)
        val requestBody = buildRequestBody(request)
        val httpRequest = Request.Builder()
            .url(chatCompletionsUrl())
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${credential.apiKey}")
            .header("Accept", SSE_MEDIA_TYPE.toString())
            .header("X-OpenRouter-Title", APP_TITLE)
            .build()
        val call = client.newCall(httpRequest)
        return awaitStream(call, onChunk)
    }

    suspend fun listModels(apiKey: String?): List<OpenRouterModelSummary> {
        val requestBuilder = Request.Builder()
            .url(modelsUrl())
            .get()
            .header("Accept", "application/json")
            .header("X-OpenRouter-Title", APP_TITLE)
        apiKey?.trim()?.takeIf(String::isNotEmpty)?.let { key ->
            requestBuilder.header("Authorization", "Bearer $key")
        }
        return awaitModels(client.newCall(requestBuilder.build()))
    }

    private suspend fun awaitModels(call: Call): List<OpenRouterModelSummary> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled || call.isCanceled()) return
                        if (continuation.isActive) continuation.resumeWithException(
                            OpenRouterRequestException(
                                statusCode = null,
                                errorType = "network",
                                retryAfterSeconds = null,
                                phase = OpenRouterFailurePhase.NETWORK,
                                safeMessage = "OpenRouter model list request failed",
                                cause = e,
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = runCatching { response.use(::parseModelsResponse) }
                        outcome.fold(
                            onSuccess = { models ->
                                if (continuation.isActive) continuation.resume(models)
                            },
                            onFailure = { error ->
                                if (continuation.isCancelled || call.isCanceled()) return
                                val mapped = error as? OpenRouterRequestException
                                    ?: OpenRouterRequestException(
                                        statusCode = null,
                                        errorType = "invalid_response",
                                        retryAfterSeconds = null,
                                        phase = OpenRouterFailurePhase.BEFORE_STREAM,
                                        safeMessage = "OpenRouter returned an invalid model list",
                                        cause = error,
                                    )
                                if (continuation.isActive) continuation.resumeWithException(mapped)
                            },
                        )
                    }
                },
            )
        }

    private fun parseModelsResponse(response: Response): List<OpenRouterModelSummary> {
        if (!response.isSuccessful) throw parseHttpFailure(response)
        val source = response.body.source()
        source.request(MAX_MODELS_BODY_BYTES.toLong() + 1L)
        require(source.buffer.size <= MAX_MODELS_BODY_BYTES) {
            "OpenRouter model list is too large"
        }
        val root = json.parseToJsonElement(source.readUtf8()).jsonObject
        val data = root["data"]?.jsonArray
            ?: throw IllegalArgumentException("OpenRouter model list has no data")
        require(data.size <= MAX_MODELS) { "OpenRouter returned too many models" }
        return data.map { element ->
            val model = element.jsonObject
            val id = model.getValue("id").jsonPrimitive.content
            require(id.length in 3..MAX_MODEL_ID_CHARS && '/' in id) {
                "OpenRouter model ID is invalid"
            }
            val name = model["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_MODEL_NAME_CHARS)
                ?: id
            val architecture = model["architecture"]
                ?.let { runCatching { it.jsonObject }.getOrNull() }
            OpenRouterModelSummary(
                id = id,
                name = name,
                contextLength = model["context_length"]?.jsonPrimitive?.intOrNull,
                inputModalities = architecture?.get("input_modalities")
                    ?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { value -> value.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                supportedParameters = model["supported_parameters"]
                    ?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { value -> value.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                outputModalities = architecture?.get("output_modalities")
                    ?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { value -> value.jsonPrimitive.contentOrNull }
                    .orEmpty(),
            )
        }.distinctBy(OpenRouterModelSummary::id)
    }

    private suspend fun awaitStream(
        call: Call,
        onChunk: (JsonObject) -> Unit,
    ): OpenRouterStreamResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled || call.isCanceled()) return
                    val mapped = when (e) {
                        is SocketTimeoutException -> OpenRouterRequestException(
                            statusCode = null,
                            errorType = "timeout",
                            retryAfterSeconds = null,
                            phase = OpenRouterFailurePhase.NETWORK,
                            safeMessage = "OpenRouter request timed out",
                            cause = e,
                        )
                        else -> OpenRouterRequestException(
                            statusCode = null,
                            errorType = "network",
                            retryAfterSeconds = null,
                            phase = OpenRouterFailurePhase.NETWORK,
                            safeMessage = "OpenRouter network request failed",
                            cause = e,
                        )
                    }
                    if (continuation.isActive) continuation.resumeWithException(mapped)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching {
                        response.use { parseResponse(it, onChunk) }
                    }
                    outcome.fold(
                        onSuccess = { result ->
                            if (continuation.isActive) continuation.resume(result)
                        },
                        onFailure = { error ->
                            if (continuation.isCancelled || call.isCanceled()) return
                            val mapped = when (error) {
                                is OpenRouterRequestException -> error
                                is CancellationException -> error
                                else -> OpenRouterRequestException(
                                    statusCode = null,
                                    errorType = "invalid_response",
                                    retryAfterSeconds = null,
                                    phase = OpenRouterFailurePhase.MID_STREAM,
                                    safeMessage = "OpenRouter returned an invalid stream",
                                    cause = error,
                                )
                            }
                            if (continuation.isActive) continuation.resumeWithException(mapped)
                        },
                    )
                }
            },
        )
    }

    private fun parseResponse(
        response: Response,
        onChunk: (JsonObject) -> Unit,
    ): OpenRouterStreamResult {
        if (!response.isSuccessful) {
            throw parseHttpFailure(response)
        }
        val mediaType = response.body.contentType()
        require(
            mediaType?.type == "text" && mediaType.subtype == "event-stream",
        ) {
            "OpenRouter response is not SSE"
        }
        val source = response.body.source()
        var chunkCount = 0
        var done = false
        val dataLines = mutableListOf<String>()

        fun dispatchEvent() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (data == "[DONE]") {
                done = true
                return
            }
            require(data.toByteArray().size <= MAX_SSE_EVENT_BYTES) {
                "OpenRouter SSE event is too large"
            }
            val chunk = json.parseToJsonElement(data).jsonObject
            chunk["error"]?.let {
                throw parseStreamFailure(
                    error = it.jsonObject,
                    retryAfterSeconds = parseRetryAfter(response),
                )
            }
            onChunk(chunk)
            chunkCount += 1
        }

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                when {
                    line.isEmpty() -> dispatchEvent()
                    line.startsWith(":") -> Unit
                    line == "data" -> dataLines += ""
                    line.startsWith("data:") -> {
                        val value = line.removePrefix("data:").removePrefix(" ")
                        dataLines += value
                    }
                    else -> Unit
                }
                if (done) break
            }
        } catch (error: EOFException) {
            throw IllegalArgumentException("OpenRouter SSE line is truncated", error)
        }
        if (!done) dispatchEvent()
        require(done) { "OpenRouter SSE ended before [DONE]" }
        return OpenRouterStreamResult(
            generationId = response.header("X-Generation-Id"),
            chunkCount = chunkCount,
        )
    }

    private fun parseHttpFailure(response: Response): OpenRouterRequestException {
        val error = readBoundedErrorBody(response)
        return OpenRouterRequestException(
            statusCode = response.code,
            errorType = error?.errorType ?: statusErrorType(response.code),
            retryAfterSeconds = parseRetryAfter(response),
            phase = OpenRouterFailurePhase.BEFORE_STREAM,
            safeMessage = safeStatusMessage(response.code),
        )
    }

    private fun parseStreamFailure(
        error: JsonObject,
        retryAfterSeconds: Long?,
    ): OpenRouterRequestException {
        val code = error["code"].let { value ->
            (value as? JsonPrimitive)?.intOrNull
        }
        val errorType = error["metadata"]
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("error_type")
            ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: code?.let(::statusErrorType)
            ?: "provider_error"
        return OpenRouterRequestException(
            statusCode = code,
            errorType = errorType,
            retryAfterSeconds = retryAfterSeconds,
            phase = OpenRouterFailurePhase.MID_STREAM,
            safeMessage = "OpenRouter stream failed",
        )
    }

    private fun readBoundedErrorBody(response: Response): ParsedProviderError? {
        val source = response.body.source()
        source.request(MAX_ERROR_BODY_BYTES.toLong() + 1L)
        val bytes = source.readByteArray(
            minOf(source.buffer.size, MAX_ERROR_BODY_BYTES.toLong()),
        )
        if (bytes.isEmpty()) return null
        return runCatching {
            val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val error = root["error"]?.jsonObject ?: return@runCatching null
            ParsedProviderError(
                errorType = error["metadata"]
                    ?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?.get("error_type")
                    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
            )
        }.getOrNull()
    }

    private fun buildRequestBody(request: OpenRouterChatRequest): JsonObject =
        buildJsonObject {
            put("model", request.modelId)
            put("messages", request.messages)
            put("stream", true)
            put(
                "stream_options",
                buildJsonObject {
                    put("include_usage", true)
                },
            )
            val combinedTools = buildJsonArray {
                request.functionTools?.forEach(::add)
                request.webSearch?.let { config ->
                    add(
                        buildJsonObject {
                            put("type", "openrouter:web_search")
                            put(
                                "parameters",
                                buildJsonObject {
                                    put("engine", config.engine.wireValue)
                                    put("max_uses", config.maxUses)
                                    put("max_results", config.maxResults)
                                    put("max_total_results", config.maxTotalResults)
                                    put("max_characters", config.maxCharactersPerResult)
                                },
                            )
                        },
                    )
                }
                request.webFetch?.let { config ->
                    add(
                        buildJsonObject {
                            put("type", "openrouter:web_fetch")
                            put(
                                "parameters",
                                buildJsonObject {
                                    put("engine", config.engine.wireValue)
                                    put("max_uses", config.maxUses)
                                    put("max_content_tokens", config.maxContentTokens)
                                },
                            )
                        },
                    )
                }
            }
            if (combinedTools.isNotEmpty()) put("tools", combinedTools)
            request.serverToolCallBudget()?.let { put("max_tool_calls", it) }
            request.maxTokens?.let { put("max_completion_tokens", it) }
        }

    private fun validateRequest(
        profile: ProviderProfile,
        request: OpenRouterChatRequest,
    ) {
        require(request.modelId == profile.modelId) {
            "Request model does not match the active Provider profile"
        }
        require(request.messages.size in 1..MAX_MESSAGES) {
            "OpenRouter message count is invalid"
        }
        require(request.messages.toString().toByteArray().size <= MAX_REQUEST_MESSAGES_BYTES) {
            "OpenRouter messages are too large"
        }
        request.functionTools?.let {
            val reservedServerTools = listOf(request.webSearch, request.webFetch).count { it != null }
            require(it.size + reservedServerTools <= MAX_TOOLS) {
                "OpenRouter tool count is invalid"
            }
            require(it.toString().toByteArray().size <= MAX_REQUEST_TOOLS_BYTES) {
                "OpenRouter tools are too large"
            }
        }
        request.webSearch?.let { config ->
            require(config.maxUses in 1..MAX_WEB_SEARCH_USES) {
                "OpenRouter web search use count is invalid"
            }
            require(config.maxResults in 1..MAX_WEB_SEARCH_RESULTS) {
                "OpenRouter web search result count is invalid"
            }
            require(config.maxTotalResults in config.maxResults..MAX_WEB_SEARCH_TOTAL_RESULTS) {
                "OpenRouter web search total result count is invalid"
            }
            require(config.maxCharactersPerResult in 1..MAX_WEB_SEARCH_CHARACTERS) {
                "OpenRouter web search character budget is invalid"
            }
            require(config.maxServerToolCalls in 1..MAX_SERVER_TOOL_CALLS) {
                "OpenRouter server tool call budget is invalid"
            }
            require(config.maxServerToolCalls >= config.maxUses) {
                "OpenRouter server tool budget cannot be lower than web search uses"
            }
        }
        request.webFetch?.let { config ->
            require(config.maxUses in 1..MAX_WEB_FETCH_USES) {
                "OpenRouter web fetch use count is invalid"
            }
            require(config.maxContentTokens in 1..MAX_WEB_FETCH_CONTENT_TOKENS) {
                "OpenRouter web fetch content budget is invalid"
            }
            require(request.serverToolCallBudget()!! >= config.maxUses) {
                "OpenRouter server tool budget cannot be lower than web fetch uses"
            }
        }
        request.maxTokens?.let {
            require(it in 1..MAX_COMPLETION_TOKENS) {
                "OpenRouter max token count is invalid"
            }
        }
    }

    private fun chatCompletionsUrl(): HttpUrl =
        endpoint.newBuilder()
            .addPathSegments("chat/completions")
            .build()

    private fun modelsUrl(): HttpUrl = endpoint.newBuilder()
        .addPathSegment("models")
        .addQueryParameter("sort", "most-popular")
        .build()

    private fun parseRetryAfter(response: Response): Long? =
        response.header("Retry-After")
            ?.toLongOrNull()
            ?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }

    private data class ParsedProviderError(
        val errorType: String?,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val SSE_MEDIA_TYPE = "text/event-stream".toMediaType()
        const val APP_TITLE = "Momoding"
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 120L
        const val CALL_TIMEOUT_SECONDS = 180L
        const val MAX_MESSAGES = 512
        const val MAX_TOOLS = 128
        const val MAX_WEB_SEARCH_USES = 3
        const val MAX_WEB_SEARCH_RESULTS = 25
        const val MAX_WEB_SEARCH_TOTAL_RESULTS = 50
        const val MAX_WEB_SEARCH_CHARACTERS = 10_000
        const val MAX_WEB_FETCH_USES = 3
        const val MAX_WEB_FETCH_CONTENT_TOKENS = 50_000
        const val MAX_SERVER_TOOL_CALLS = 5
        const val MAX_COMPLETION_TOKENS = 1_000_000
        const val MAX_REQUEST_MESSAGES_BYTES = 12 * 1024 * 1024
        const val MAX_REQUEST_TOOLS_BYTES = 512 * 1024
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val MAX_MODELS_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_MODELS = 2_000
        const val MAX_MODEL_ID_CHARS = 256
        const val MAX_MODEL_NAME_CHARS = 160
        const val MAX_SSE_LINE_BYTES = 1024L * 1024L
        const val MAX_SSE_EVENT_BYTES = 2 * 1024 * 1024
        const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
    }
}

private fun OpenRouterChatRequest.serverToolCallBudget(): Int? = listOfNotNull(
    webSearch?.maxServerToolCalls,
    webFetch?.maxUses,
).maxOrNull()

private fun statusErrorType(statusCode: Int): String = when (statusCode) {
    400 -> "invalid_request"
    401 -> "authentication"
    402 -> "payment_required"
    403 -> "permission_denied"
    404 -> "not_found"
    408, 504 -> "timeout"
    429 -> "rate_limit_exceeded"
    502 -> "provider_unavailable"
    503 -> "provider_overloaded"
    else -> if (statusCode >= 500) "server" else "request_failed"
}

private fun safeStatusMessage(statusCode: Int): String = when (statusCode) {
    400 -> "OpenRouter rejected the request"
    401 -> "OpenRouter API key is invalid"
    402 -> "OpenRouter account has insufficient credits"
    403 -> "OpenRouter request is not permitted"
    404 -> "OpenRouter model was not found"
    408, 504 -> "OpenRouter request timed out"
    429 -> "OpenRouter rate limit reached"
    502, 503 -> "OpenRouter provider is unavailable"
    else -> "OpenRouter request failed"
}

private fun safeOpenRouterFailureMessage(
    statusCode: Int?,
    errorType: String?,
    fallback: String?,
): String {
    statusCode?.let { return safeStatusMessage(it) }
    val normalizedType = errorType.orEmpty().lowercase()
    return when {
        normalizedType.contains("auth") || normalizedType.contains("api_key") ->
            "OpenRouter API key is invalid"
        normalizedType.contains("credit") || normalizedType.contains("payment") ->
            "OpenRouter account has insufficient credits"
        normalizedType.contains("permission") || normalizedType.contains("policy") ->
            "OpenRouter request is not permitted"
        normalizedType.contains("model") && normalizedType.contains("not_found") ->
            "OpenRouter model was not found"
        normalizedType.contains("rate") || normalizedType.contains("quota") ->
            "OpenRouter rate limit reached"
        normalizedType.contains("timeout") -> "OpenRouter request timed out"
        normalizedType.contains("network") -> "OpenRouter network request failed"
        normalizedType.contains("overload") ||
            normalizedType.contains("unavailable") ||
            normalizedType == "server" -> "OpenRouter provider is unavailable"
        normalizedType == "invalid_response" -> "OpenRouter returned an invalid stream"
        fallback in SAFE_NETWORK_MESSAGES -> requireNotNull(fallback)
        else -> "OpenRouter request failed"
    }
}

private val SAFE_NETWORK_MESSAGES = setOf(
    "OpenRouter request timed out",
    "OpenRouter network request failed",
    "OpenRouter returned an invalid stream",
)
