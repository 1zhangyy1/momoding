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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class CodexResponsesRequest(
    val modelId: String,
    val body: JsonObject,
    val sessionId: String? = null,
)

data class CodexStreamResult(
    val eventCount: Int,
)

enum class CodexTransportFailurePhase {
    BEFORE_STREAM,
    MID_STREAM,
    NETWORK,
}

class CodexTransportException(
    val statusCode: Int?,
    val errorType: String,
    val phase: CodexTransportFailurePhase,
    safeMessage: String,
    cause: Throwable? = null,
) : IOException(safeMessage, cause) {
    override fun toString(): String =
        "CodexTransportException(statusCode=$statusCode, errorType=$errorType, " +
            "phase=$phase, message=$message)"
}

/** Android-owned Codex Responses SSE transport. OAuth credentials never cross into QuickJS. */
class CodexNativeClient internal constructor(
    private val endpoint: HttpUrl,
    private val credentialSource: CodexCredentialSource,
    baseClient: OkHttpClient,
) {
    constructor(
        credentialSource: CodexCredentialSource,
    ) : this(
        endpoint = CODEX_RESPONSES_URL.toHttpUrl(),
        credentialSource = credentialSource,
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
        request: CodexResponsesRequest,
        onEvent: (JsonObject) -> Unit,
    ): CodexStreamResult {
        validateRequest(request)
        var credential = credentialSource.requireValidCredential()
        try {
            return execute(credential, request, onEvent)
        } catch (error: CodexTransportException) {
            if (
                error.statusCode != 401 ||
                error.phase != CodexTransportFailurePhase.BEFORE_STREAM
            ) {
                throw error
            }
            credential = credentialSource.refreshAfterUnauthorized(credential.accessToken)
            return execute(credential, request, onEvent)
        }
    }

    private suspend fun execute(
        credential: CodexOAuthCredential,
        request: CodexResponsesRequest,
        onEvent: (JsonObject) -> Unit,
    ): CodexStreamResult {
        CodexOAuthCredentialPolicy.validate(credential)
        val body = request.body.toString()
        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("chatgpt-account-id", credential.accountId)
            .header("originator", "pi")
            .header("User-Agent", USER_AGENT)
            .header("OpenAI-Beta", "responses=experimental")
            .header("Accept", SSE_MEDIA_TYPE.toString())
        request.sessionId?.let { sessionId ->
            builder.header("session-id", sessionId)
            builder.header("x-client-request-id", sessionId)
        }
        return await(client.newCall(builder.build()), onEvent)
    }

    private suspend fun await(
        call: Call,
        onEvent: (JsonObject) -> Unit,
    ): CodexStreamResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isCancelled || call.isCanceled()) return
                    val mapped = CodexTransportException(
                        statusCode = null,
                        errorType = if (error is SocketTimeoutException) "timeout" else "network",
                        phase = CodexTransportFailurePhase.NETWORK,
                        safeMessage = if (error is SocketTimeoutException) {
                            "Codex request timed out"
                        } else {
                            "Codex network request failed"
                        },
                        cause = error,
                    )
                    if (continuation.isActive) continuation.resumeWithException(mapped)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching { response.use { parseResponse(it, onEvent) } }
                    outcome.fold(
                        onSuccess = { result ->
                            if (continuation.isActive) continuation.resume(result)
                        },
                        onFailure = { error ->
                            if (continuation.isCancelled || call.isCanceled()) return
                            val mapped = when (error) {
                                is CodexTransportException -> error
                                is CancellationException -> error
                                else -> CodexTransportException(
                                    statusCode = null,
                                    errorType = "invalid_response",
                                    phase = CodexTransportFailurePhase.MID_STREAM,
                                    safeMessage = "Codex returned an invalid stream",
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
        onEvent: (JsonObject) -> Unit,
    ): CodexStreamResult {
        if (!response.isSuccessful) throw httpFailure(response)
        val mediaType = response.body.contentType()
        require(mediaType?.type == "text" && mediaType.subtype == "event-stream") {
            "Codex response is not SSE"
        }
        val source = response.body.source()
        val dataLines = mutableListOf<String>()
        var eventCount = 0
        var sawTerminalEvent = false

        fun dispatchEvent() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (data == "[DONE]") return
            require(data.toByteArray().size <= MAX_SSE_EVENT_BYTES) {
                "Codex SSE event is too large"
            }
            val event = json.parseToJsonElement(data) as? JsonObject
                ?: throw IllegalArgumentException("Codex SSE event is not an object")
            val type = event["type"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Codex SSE event has no type")
            if (type in TERMINAL_EVENT_TYPES) sawTerminalEvent = true
            require(++eventCount <= MAX_SSE_EVENTS) { "Codex SSE has too many events" }
            onEvent(event)
        }

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                when {
                    line.isEmpty() -> dispatchEvent()
                    line.startsWith(":") -> Unit
                    line == "data" -> dataLines += ""
                    line.startsWith("data:") ->
                        dataLines += line.removePrefix("data:").removePrefix(" ")
                    else -> Unit
                }
            }
        } catch (error: EOFException) {
            throw IllegalArgumentException("Codex SSE line is truncated", error)
        }
        dispatchEvent()
        require(sawTerminalEvent) { "Codex SSE ended before a terminal response event" }
        return CodexStreamResult(eventCount)
    }

    private fun httpFailure(response: Response): CodexTransportException {
        return CodexTransportException(
            statusCode = response.code,
            errorType = when (response.code) {
                401 -> "authentication"
                403 -> "permission_denied"
                408, 504 -> "timeout"
                429 -> "rate_limit_exceeded"
                else -> if (response.code >= 500) "server" else "request_failed"
            },
            phase = CodexTransportFailurePhase.BEFORE_STREAM,
            safeMessage = when (response.code) {
                401 -> "Codex authorization expired"
                403 -> "Codex request is not permitted"
                408, 504 -> "Codex request timed out"
                429 -> "Codex rate limit reached"
                else -> "Codex request failed"
            },
        )
    }

    private fun validateRequest(request: CodexResponsesRequest) {
        require(MODEL_ID_PATTERN.matches(request.modelId)) { "Codex model ID is invalid" }
        require(request.body["model"]?.jsonPrimitive?.content == request.modelId) {
            "Codex request model does not match body"
        }
        require(request.body["stream"]?.jsonPrimitive?.booleanOrNull == true) {
            "Codex request must stream"
        }
        require(request.body["store"]?.jsonPrimitive?.booleanOrNull == false) {
            "Codex request must not store server state"
        }
        require(request.body.toString().toByteArray().size in 1..MAX_REQUEST_BYTES) {
            "Codex request body is too large"
        }
        request.sessionId?.let {
            require(SESSION_ID_PATTERN.matches(it)) { "Codex session ID is invalid" }
        }
    }

    private companion object {
        const val CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
        const val USER_AGENT = "pi (Android; Momoding)"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val SSE_MEDIA_TYPE = "text/event-stream".toMediaType()
        val MODEL_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        val TERMINAL_EVENT_TYPES = setOf(
            "response.done",
            "response.completed",
            "response.incomplete",
            "response.failed",
            "error",
        )
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 120L
        const val CALL_TIMEOUT_SECONDS = 180L
        const val MAX_REQUEST_BYTES = 12 * 1024 * 1024
        const val MAX_SSE_LINE_BYTES = 1024L * 1024L
        const val MAX_SSE_EVENT_BYTES = 2 * 1024 * 1024
        const val MAX_SSE_EVENTS = 100_000
    }
}
