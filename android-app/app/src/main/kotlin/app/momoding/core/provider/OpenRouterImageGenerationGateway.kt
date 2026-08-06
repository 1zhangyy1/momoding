package app.momoding.core.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

sealed interface OpenRouterImageParameterCapability {
    data class EnumValues(val values: Set<String>) : OpenRouterImageParameterCapability
    data class IntegerRange(val minimum: Int, val maximum: Int) : OpenRouterImageParameterCapability
    data object Supported : OpenRouterImageParameterCapability
}

data class OpenRouterImageModelSummary(
    val id: String,
    val name: String,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val supportedParameters: Map<String, OpenRouterImageParameterCapability>,
    val supportsStreaming: Boolean,
) {
    fun supports(parameter: String, value: String): Boolean =
        when (val capability = supportedParameters[parameter]) {
            is OpenRouterImageParameterCapability.EnumValues -> value in capability.values
            OpenRouterImageParameterCapability.Supported -> true
            else -> false
        }
}

data class OpenRouterImageGenerationRequest(
    val model: OpenRouterImageModelSummary,
    val prompt: String,
    val aspectRatio: String? = null,
    val quality: String? = null,
)

data class OpenRouterImageUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
)

data class OpenRouterGeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val createdAtSeconds: Long?,
    val usage: OpenRouterImageUsage?,
) {
    override fun equals(other: Any?): Boolean = other is OpenRouterGeneratedImage &&
        bytes.contentEquals(other.bytes) && mimeType == other.mimeType &&
        createdAtSeconds == other.createdAtSeconds && usage == other.usage

    override fun hashCode(): Int = 31 * (
        31 * (31 * bytes.contentHashCode() + mimeType.hashCode()) +
            (createdAtSeconds?.hashCode() ?: 0)
        ) + (usage?.hashCode() ?: 0)
}

interface OpenRouterImageGateway {
    suspend fun listModels(apiKey: String): List<OpenRouterImageModelSummary>

    suspend fun generate(
        credential: ProviderCredential,
        request: OpenRouterImageGenerationRequest,
    ): OpenRouterGeneratedImage
}

/** Android-owned client for OpenRouter's dedicated Image API. API keys never enter QuickJS. */
class OpenRouterImageGenerationGateway internal constructor(
    private val endpoint: HttpUrl,
    baseClient: OkHttpClient,
) : OpenRouterImageGateway {
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

    override suspend fun listModels(apiKey: String): List<OpenRouterImageModelSummary> {
        requireApiKey(apiKey)
        val request = Request.Builder()
            .url(imagesModelsUrl())
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("X-OpenRouter-Title", APP_TITLE)
            .build()
        return await(client.newCall(request), ::parseModelsResponse)
    }

    override suspend fun generate(
        credential: ProviderCredential,
        request: OpenRouterImageGenerationRequest,
    ): OpenRouterGeneratedImage {
        ProviderProfilePolicy.validate(credential)
        validateGenerationRequest(request)
        val httpRequest = Request.Builder()
            .url(imagesUrl())
            .post(buildGenerationBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${credential.apiKey}")
            .header("Accept", "application/json")
            .header("X-OpenRouter-Title", APP_TITLE)
            .build()
        return await(client.newCall(httpRequest), ::parseGenerationResponse)
    }

    private suspend fun <T> await(call: Call, parse: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled || call.isCanceled()) return
                        val mapped = OpenRouterRequestException(
                            statusCode = null,
                            errorType = if (e is SocketTimeoutException) "timeout" else "network",
                            retryAfterSeconds = null,
                            phase = OpenRouterFailurePhase.NETWORK,
                            safeMessage = if (e is SocketTimeoutException) {
                                "OpenRouter image request timed out"
                            } else {
                                "OpenRouter image request failed"
                            },
                            cause = e,
                        )
                        if (continuation.isActive) continuation.resumeWithException(mapped)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = runCatching { response.use(parse) }
                        outcome.fold(
                            onSuccess = { value ->
                                if (continuation.isActive) continuation.resume(value)
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
                                        phase = OpenRouterFailurePhase.BEFORE_STREAM,
                                        safeMessage = "OpenRouter returned an invalid image response",
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

    private fun parseModelsResponse(response: Response): List<OpenRouterImageModelSummary> {
        if (!response.isSuccessful) throw parseHttpFailure(response)
        val root = readBoundedJson(response, MAX_MODELS_BODY_BYTES)
        val data = root["data"]?.jsonArray
            ?: throw IllegalArgumentException("OpenRouter image model list has no data")
        require(data.size <= MAX_MODELS) { "OpenRouter returned too many image models" }
        return data.map(::parseModel)
            .filter { "image" in it.outputModalities && it.supportsRasterOutput() }
            .distinctBy(OpenRouterImageModelSummary::id)
    }

    private fun parseModel(element: JsonElement): OpenRouterImageModelSummary {
        val model = element.jsonObject
        val id = model.getValue("id").jsonPrimitive.content
        require(id.length in 3..MAX_MODEL_ID_CHARS && '/' in id) {
            "OpenRouter image model ID is invalid"
        }
        val architecture = model.getValue("architecture").jsonObject
        val parameters = model.getValue("supported_parameters").jsonObject
            .mapValues { (_, value) -> parseCapability(value.jsonObject) }
            .filterValues { it != null }
            .mapValues { requireNotNull(it.value) }
        return OpenRouterImageModelSummary(
            id = id,
            name = model["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_MODEL_NAME_CHARS) ?: id,
            inputModalities = architecture.getValue("input_modalities").stringList(MAX_MODALITIES),
            outputModalities = architecture.getValue("output_modalities").stringList(MAX_MODALITIES),
            supportedParameters = parameters,
            supportsStreaming = model["supports_streaming"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun parseCapability(value: JsonObject): OpenRouterImageParameterCapability? =
        when (value.getValue("type").jsonPrimitive.content) {
            "enum" -> OpenRouterImageParameterCapability.EnumValues(
                value.getValue("values").stringList(MAX_PARAMETER_VALUES).toSet()
                    .also { require(it.isNotEmpty()) { "OpenRouter image enum is empty" } },
            )
            "range" -> {
                val minimum = value.getValue("min").jsonPrimitive.intOrNull
                    ?: throw IllegalArgumentException("OpenRouter image range minimum is invalid")
                val maximum = value.getValue("max").jsonPrimitive.intOrNull
                    ?: throw IllegalArgumentException("OpenRouter image range maximum is invalid")
                require(minimum <= maximum) { "OpenRouter image range is invalid" }
                OpenRouterImageParameterCapability.IntegerRange(minimum, maximum)
            }
            "boolean" -> OpenRouterImageParameterCapability.Supported
            else -> null
        }

    private fun parseGenerationResponse(response: Response): OpenRouterGeneratedImage {
        if (!response.isSuccessful) throw parseHttpFailure(response)
        val root = readBoundedJson(response, MAX_IMAGE_RESPONSE_BYTES)
        val data = root["data"]?.jsonArray
            ?: throw IllegalArgumentException("OpenRouter image response has no data")
        require(data.size == 1) { "OpenRouter image response count is invalid" }
        val item = data.single().jsonObject
        val encoded = item.getValue("b64_json").jsonPrimitive.content
        require(encoded.length in 1..MAX_IMAGE_BASE64_CHARS) {
            "OpenRouter image payload is too large"
        }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("OpenRouter image payload is invalid", error)
        }
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "OpenRouter image payload is too large" }
        val mimeType = item["media_type"]?.jsonPrimitive?.contentOrNull ?: detectImageMime(bytes)
        require(mimeType in SUPPORTED_IMAGE_MIMES) { "OpenRouter image MIME is unsupported" }
        require(detectImageMime(bytes) == mimeType) { "OpenRouter image MIME does not match its bytes" }
        return OpenRouterGeneratedImage(
            bytes = bytes,
            mimeType = mimeType,
            createdAtSeconds = root["created"]?.jsonPrimitive?.longOrNull,
            usage = root["usage"]?.let { parseUsage(it.jsonObject) },
        )
    }

    private fun parseUsage(usage: JsonObject): OpenRouterImageUsage = OpenRouterImageUsage(
        promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
        completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
        totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull,
        costUsd = usage["cost"]?.jsonPrimitive?.doubleOrNull,
    ).also { parsed ->
        require(
            listOf(parsed.promptTokens, parsed.completionTokens, parsed.totalTokens)
                .all { it == null || it >= 0 } &&
                (parsed.costUsd == null || parsed.costUsd >= 0.0),
        ) { "OpenRouter image usage is invalid" }
    }

    private fun buildGenerationBody(request: OpenRouterImageGenerationRequest): JsonObject =
        buildJsonObject {
            put("model", request.model.id)
            put("prompt", request.prompt.trim())
            put("n", 1)
            request.model.preferredRasterOutputFormat()?.let { put("output_format", it) }
            val aspectRatio = request.aspectRatio ?: DEFAULT_ASPECT_RATIO.takeIf {
                request.model.supports("aspect_ratio", it)
            }
            val quality = request.quality ?: DEFAULT_QUALITY.takeIf {
                request.model.supports("quality", it)
            }
            val resolution = DEFAULT_RESOLUTION.takeIf {
                request.model.supports("resolution", it)
            }
            aspectRatio?.let { put("aspect_ratio", it) }
            quality?.let { put("quality", it) }
            resolution?.let { put("resolution", it) }
        }

    private fun validateGenerationRequest(request: OpenRouterImageGenerationRequest) {
        require("image" in request.model.outputModalities) { "Selected model cannot generate images" }
        require(request.model.supportsRasterOutput()) {
            "Selected model cannot return a supported raster image"
        }
        require(request.prompt.trim().length in 1..MAX_PROMPT_CHARS) {
            "Image prompt length is invalid"
        }
        request.aspectRatio?.let {
            require(it in ALLOWED_ASPECT_RATIOS && request.model.supports("aspect_ratio", it)) {
                "Selected image model does not support that aspect ratio"
            }
        }
        request.quality?.let {
            require(it in ALLOWED_QUALITIES && request.model.supports("quality", it)) {
                "Selected image model does not support that quality"
            }
        }
    }

    private fun parseHttpFailure(response: Response): OpenRouterRequestException {
        val errorType = runCatching {
            val root = readBoundedJson(response, MAX_ERROR_BODY_BYTES)
            root["error"]?.jsonObject?.get("metadata")?.jsonObject
                ?.get("error_type")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return OpenRouterRequestException(
            statusCode = response.code,
            errorType = errorType ?: imageStatusErrorType(response.code),
            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                ?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS },
            phase = OpenRouterFailurePhase.BEFORE_STREAM,
            safeMessage = imageSafeStatusMessage(response.code),
        )
    }

    private fun readBoundedJson(response: Response, limit: Int): JsonObject {
        val source = response.body.source()
        source.request(limit.toLong() + 1L)
        require(source.buffer.size <= limit) { "OpenRouter response is too large" }
        return json.parseToJsonElement(source.readUtf8()).jsonObject
    }

    private fun JsonElement.stringList(limit: Int): List<String> {
        val values = jsonArray
        require(values.size <= limit) { "OpenRouter image list is too large" }
        return values.map { value ->
            value.jsonPrimitive.content.also {
                require(it.length in 1..MAX_PARAMETER_VALUE_CHARS) {
                    "OpenRouter image value is invalid"
                }
            }
        }
    }

    private fun OpenRouterImageModelSummary.preferredRasterOutputFormat(): String? =
        when (val capability = supportedParameters["output_format"]) {
            is OpenRouterImageParameterCapability.EnumValues ->
                PREFERRED_OUTPUT_FORMATS.firstOrNull(capability.values::contains)
            OpenRouterImageParameterCapability.Supported -> "png"
            else -> null
        }

    private fun OpenRouterImageModelSummary.supportsRasterOutput(): Boolean {
        val capability = supportedParameters["output_format"]
        return capability !is OpenRouterImageParameterCapability.EnumValues ||
            PREFERRED_OUTPUT_FORMATS.any(capability.values::contains)
    }

    private fun imagesModelsUrl(): HttpUrl = endpoint.newBuilder()
        .addPathSegments("images/models")
        .build()

    private fun imagesUrl(): HttpUrl = endpoint.newBuilder()
        .addPathSegment("images")
        .build()

    private fun requireApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }
        require(apiKey.length <= 4_096) { "OpenRouter API key is invalid" }
    }

    private fun detectImageMime(bytes: ByteArray): String = when {
        bytes.startsWith(PNG_HEADER) -> "image/png"
        bytes.startsWith(JPEG_HEADER) -> "image/jpeg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(RIFF_HEADER) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP_HEADER) -> "image/webp"
        else -> throw IllegalArgumentException("OpenRouter image bytes are unsupported")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val APP_TITLE = "Momoding"
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 300L
        const val CALL_TIMEOUT_SECONDS = 330L
        const val MAX_MODELS_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_IMAGE_RESPONSE_BYTES = 32 * 1024 * 1024
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val MAX_MODELS = 1_000
        const val MAX_MODEL_ID_CHARS = 256
        const val MAX_MODEL_NAME_CHARS = 160
        const val MAX_MODALITIES = 16
        const val MAX_PARAMETER_VALUES = 128
        const val MAX_PARAMETER_VALUE_CHARS = 128
        const val MAX_PROMPT_CHARS = 4_096
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_IMAGE_BASE64_CHARS = 28 * 1024 * 1024
        const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
        const val DEFAULT_ASPECT_RATIO = "1:1"
        const val DEFAULT_QUALITY = "auto"
        const val DEFAULT_RESOLUTION = "1K"
        val ALLOWED_ASPECT_RATIOS = setOf("1:1", "16:9", "9:16", "4:3", "3:4")
        val ALLOWED_QUALITIES = setOf("auto", "low", "medium", "high")
        val SUPPORTED_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp")
        val PREFERRED_OUTPUT_FORMATS = listOf("png", "jpeg", "webp")
        val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val RIFF_HEADER = "RIFF".toByteArray()
        val WEBP_HEADER = "WEBP".toByteArray()
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private fun imageStatusErrorType(statusCode: Int): String = when (statusCode) {
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

private fun imageSafeStatusMessage(statusCode: Int): String = when (statusCode) {
    400 -> "OpenRouter rejected the image request"
    401 -> "OpenRouter API key is invalid"
    402 -> "OpenRouter account has insufficient credits"
    403 -> "OpenRouter image request is not permitted"
    404 -> "OpenRouter image model was not found"
    408, 504 -> "OpenRouter image request timed out"
    429 -> "OpenRouter image rate limit reached"
    502, 503 -> "OpenRouter image provider is unavailable"
    else -> "OpenRouter image request failed"
}
