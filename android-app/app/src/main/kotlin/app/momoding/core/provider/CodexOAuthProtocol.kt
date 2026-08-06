package app.momoding.core.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class CodexDeviceAuthorization(
    val deviceAuthId: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Long,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "CodexDeviceAuthorization(deviceAuthId=[REDACTED], userCode=[REDACTED], " +
            "verificationUri=$verificationUri, intervalSeconds=$intervalSeconds, " +
            "expiresAtMillis=$expiresAtMillis)"
}

data class CodexOAuthCredential(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val accountId: String,
) {
    override fun toString(): String =
        "CodexOAuthCredential(accessToken=[REDACTED], refreshToken=[REDACTED], " +
            "expiresAtMillis=$expiresAtMillis, accountId=[REDACTED])"
}

sealed interface CodexDevicePollResult {
    data class Pending(val nextIntervalSeconds: Long) : CodexDevicePollResult
    data class Authorized(val credential: CodexOAuthCredential) : CodexDevicePollResult
}

class CodexOAuthProtocolException(
    val statusCode: Int?,
    val errorType: String,
    val retryAfterSeconds: Long? = null,
    safeMessage: String,
    cause: Throwable? = null,
) : IOException(safeMessage, cause) {
    override fun toString(): String =
        "CodexOAuthProtocolException(statusCode=$statusCode, errorType=$errorType, " +
            "retryAfterSeconds=$retryAfterSeconds, message=${message})"
}

interface CodexOAuthGateway {
    suspend fun startDeviceAuthorization(): CodexDeviceAuthorization

    suspend fun pollDeviceAuthorization(
        authorization: CodexDeviceAuthorization,
    ): CodexDevicePollResult

    suspend fun refresh(refreshToken: String): CodexOAuthCredential
}

/** Exact Android-owned implementation of the Codex device-code OAuth flow used by Pi 0.80.6. */
class CodexOAuthProtocol internal constructor(
    private val authBaseUrl: HttpUrl,
    baseClient: OkHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CodexOAuthGateway {
    constructor() : this(
        authBaseUrl = AUTH_BASE_URL.toHttpUrl(),
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

    override suspend fun startDeviceAuthorization(): CodexDeviceAuthorization {
        val body = buildJsonObject { put("client_id", CLIENT_ID) }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url("api/accounts/deviceauth/usercode"))
            .post(body)
            .header("Accept", "application/json")
            .build()
        return await(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw httpFailure(response, "device_start")
            val root = readBoundedJson(response)
            val interval = root.requiredLong("interval", 1L..MAX_POLL_INTERVAL_SECONDS)
            CodexDeviceAuthorization(
                deviceAuthId = root.requiredToken("device_auth_id", MAX_DEVICE_AUTH_ID_CHARS),
                userCode = root.requiredToken("user_code", MAX_USER_CODE_CHARS),
                verificationUri = DEVICE_VERIFICATION_URI,
                intervalSeconds = interval,
                expiresAtMillis = safeFutureMillis(DEVICE_CODE_TIMEOUT_SECONDS),
            )
        }
    }

    override suspend fun pollDeviceAuthorization(
        authorization: CodexDeviceAuthorization,
    ): CodexDevicePollResult {
        validateAuthorization(authorization)
        if (nowMillis() >= authorization.expiresAtMillis) {
            throw protocolFailure("expired", "Codex device authorization expired")
        }
        val body = buildJsonObject {
            put("device_auth_id", authorization.deviceAuthId)
            put("user_code", authorization.userCode)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url("api/accounts/deviceauth/token"))
            .post(body)
            .header("Accept", "application/json")
            .build()
        val result = await(client.newCall(request)) { response ->
            if (response.isSuccessful) {
                val root = readBoundedJson(response)
                RawDevicePollResult.AuthorizationCode(
                    code = root.requiredToken(
                        "authorization_code",
                        MAX_AUTHORIZATION_CODE_CHARS,
                    ),
                    verifier = root.requiredToken("code_verifier", MAX_CODE_VERIFIER_CHARS),
                )
            } else {
                val errorCode = readErrorCode(response)
                when {
                    errorCode == "slow_down" || response.code == 429 ->
                        RawDevicePollResult.Pending(
                            nextIntervalSeconds = maxOf(
                                authorization.intervalSeconds + SLOW_DOWN_SECONDS,
                                response.retryAfterSeconds() ?: 0L,
                            ).coerceAtMost(MAX_POLL_INTERVAL_SECONDS),
                        )
                    errorCode == "deviceauth_authorization_pending" ->
                        RawDevicePollResult.Pending(authorization.intervalSeconds)
                    errorCode != null -> throw httpFailure(response, "device_poll", errorCode)
                    response.code == 403 || response.code == 404 ->
                        RawDevicePollResult.Pending(authorization.intervalSeconds)
                    else -> throw httpFailure(response, "device_poll", errorCode)
                }
            }
        }
        if (nowMillis() >= authorization.expiresAtMillis) {
            throw protocolFailure("expired", "Codex device authorization expired")
        }
        return when (result) {
            is RawDevicePollResult.Pending -> CodexDevicePollResult.Pending(
                result.nextIntervalSeconds,
            )
            is RawDevicePollResult.AuthorizationCode -> CodexDevicePollResult.Authorized(
                exchange(result.code, result.verifier),
            )
        }
    }

    override suspend fun refresh(refreshToken: String): CodexOAuthCredential {
        requireToken(refreshToken, MAX_OAUTH_TOKEN_CHARS, "refresh token")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        return tokenRequest(body, "refresh")
    }

    private suspend fun exchange(code: String, verifier: String): CodexOAuthCredential {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", DEVICE_REDIRECT_URI)
            .build()
        return tokenRequest(body, "exchange")
    }

    private suspend fun tokenRequest(body: FormBody, operation: String): CodexOAuthCredential {
        val request = Request.Builder()
            .url(url("oauth/token"))
            .post(body)
            .header("Accept", "application/json")
            .build()
        return await(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw httpFailure(response, "token_$operation")
            parseCredential(readBoundedJson(response))
        }
    }

    private fun parseCredential(root: JsonObject): CodexOAuthCredential {
        val accessToken = root.requiredToken("access_token", MAX_OAUTH_TOKEN_CHARS)
        val refreshToken = root.requiredToken("refresh_token", MAX_OAUTH_TOKEN_CHARS)
        val expiresIn = root.requiredLong("expires_in", 1L..MAX_TOKEN_LIFETIME_SECONDS)
        return CodexOAuthCredential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = safeFutureMillis(expiresIn),
            accountId = accountIdFromJwt(accessToken),
        )
    }

    private fun accountIdFromJwt(accessToken: String): String {
        val parts = accessToken.split('.')
        require(parts.size == 3) { "Codex access token is not a JWT" }
        val payload = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Codex access token payload is invalid", error)
        }
        require(payload.size in 1..MAX_JWT_PAYLOAD_BYTES) { "Codex access token payload is invalid" }
        val root = json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        val auth = root[JWT_CLAIM_PATH]?.jsonObject
            ?: throw IllegalArgumentException("Codex access token has no auth claim")
        return auth.requiredToken("chatgpt_account_id", MAX_ACCOUNT_ID_CHARS)
    }

    private suspend fun <T> await(call: Call, parse: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled || call.isCanceled()) return
                        val error = CodexOAuthProtocolException(
                            statusCode = null,
                            errorType = if (e is SocketTimeoutException) "timeout" else "network",
                            safeMessage = if (e is SocketTimeoutException) {
                                "Codex sign-in request timed out"
                            } else {
                                "Codex sign-in request failed"
                            },
                            cause = e,
                        )
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = runCatching { response.use(parse) }
                        outcome.fold(
                            onSuccess = { if (continuation.isActive) continuation.resume(it) },
                            onFailure = { error ->
                                val mapped = when (error) {
                                    is CodexOAuthProtocolException -> error
                                    else -> protocolFailure(
                                        "invalid_response",
                                        "Codex sign-in returned an invalid response",
                                        error,
                                    )
                                }
                                if (continuation.isActive) continuation.resumeWithException(mapped)
                            },
                        )
                    }
                },
            )
        }

    private fun readBoundedJson(response: Response): JsonObject {
        val source = response.body.source()
        source.request(MAX_RESPONSE_BYTES.toLong() + 1L)
        require(source.buffer.size <= MAX_RESPONSE_BYTES) { "Codex OAuth response is too large" }
        return json.parseToJsonElement(source.readUtf8()).jsonObject
    }

    private fun readErrorCode(response: Response): String? = runCatching {
        val root = readBoundedJson(response)
        when (val error = root["error"]) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> error["code"]?.jsonPrimitive?.contentOrNull
            else -> null
        }?.take(MAX_ERROR_CODE_CHARS)
    }.getOrNull()

    private fun httpFailure(
        response: Response,
        phase: String,
        knownCode: String? = null,
    ): CodexOAuthProtocolException {
        val type = knownCode?.takeIf(SAFE_ERROR_CODE::matches) ?: "http_${response.code}"
        return CodexOAuthProtocolException(
            statusCode = response.code,
            errorType = "${phase}_${type}".take(MAX_ERROR_CODE_CHARS),
            retryAfterSeconds = response.retryAfterSeconds(),
            safeMessage = when (response.code) {
                400 -> "Codex sign-in request was rejected"
                401 -> "Codex sign-in authorization is invalid"
                403 -> "Codex device authorization is not enabled or permitted"
                404 -> "Codex device authorization is unavailable"
                408, 504 -> "Codex sign-in request timed out"
                429 -> "Codex sign-in is temporarily rate limited"
                else -> if (response.code >= 500) {
                    "Codex sign-in service is unavailable"
                } else {
                    "Codex sign-in request failed"
                }
            },
        )
    }

    private fun protocolFailure(
        type: String,
        message: String,
        cause: Throwable? = null,
    ) = CodexOAuthProtocolException(
        statusCode = null,
        errorType = type,
        safeMessage = message,
        cause = cause,
    )

    private fun validateAuthorization(value: CodexDeviceAuthorization) {
        requireToken(value.deviceAuthId, MAX_DEVICE_AUTH_ID_CHARS, "device authorization")
        requireToken(value.userCode, MAX_USER_CODE_CHARS, "user code")
        require(value.verificationUri == DEVICE_VERIFICATION_URI) {
            "Codex verification URI is invalid"
        }
        require(value.intervalSeconds in 1..MAX_POLL_INTERVAL_SECONDS) {
            "Codex poll interval is invalid"
        }
        require(value.expiresAtMillis > 0) { "Codex authorization expiry is invalid" }
    }

    private fun requireToken(value: String, maxChars: Int, name: String) {
        require(value.length in 1..maxChars && value.all { it.code in VISIBLE_ASCII }) {
            "Codex $name is invalid"
        }
    }

    private fun safeFutureMillis(seconds: Long): Long {
        val delta = Math.multiplyExact(seconds, 1_000L)
        return Math.addExact(nowMillis(), delta)
    }

    private fun JsonObject.requiredToken(name: String, maxChars: Int): String =
        getValue(name).jsonPrimitive.let { value ->
            require(value.isString) { "Codex $name is invalid" }
            value.content.also { requireToken(it, maxChars, name) }
        }

    private fun JsonObject.requiredLong(name: String, range: LongRange): Long {
        val primitive = getValue(name).jsonPrimitive
        val value = primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
        require(value != null && value in range) { "Codex $name is invalid" }
        return value
    }

    private fun url(path: String): HttpUrl = authBaseUrl.newBuilder().addPathSegments(path).build()

    private fun Response.retryAfterSeconds(): Long? = header("Retry-After")?.toLongOrNull()
        ?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }

    private companion object {
        const val AUTH_BASE_URL = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEVICE_VERIFICATION_URI = "https://auth.openai.com/codex/device"
        const val DEVICE_REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"
        const val JWT_CLAIM_PATH = "https://api.openai.com/auth"
        const val DEVICE_CODE_TIMEOUT_SECONDS = 15L * 60L
        const val SLOW_DOWN_SECONDS = 5L
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 40L
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_JWT_PAYLOAD_BYTES = 32 * 1024
        const val MAX_DEVICE_AUTH_ID_CHARS = 512
        const val MAX_USER_CODE_CHARS = 64
        const val MAX_AUTHORIZATION_CODE_CHARS = 8 * 1024
        const val MAX_CODE_VERIFIER_CHARS = 512
        const val MAX_OAUTH_TOKEN_CHARS = 6 * 1024
        const val MAX_ACCOUNT_ID_CHARS = 512
        const val MAX_ERROR_CODE_CHARS = 96
        const val MAX_POLL_INTERVAL_SECONDS = 60L
        const val MAX_TOKEN_LIFETIME_SECONDS = 7L * 24L * 60L * 60L
        const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val VISIBLE_ASCII = 0x21..0x7e
        val SAFE_ERROR_CODE = Regex("^[A-Za-z0-9._-]{1,64}$")
    }

    private sealed interface RawDevicePollResult {
        data class Pending(val nextIntervalSeconds: Long) : RawDevicePollResult
        data class AuthorizationCode(val code: String, val verifier: String) : RawDevicePollResult
    }
}

class CodexDeviceAuthorizationCoordinator internal constructor(
    private val gateway: CodexOAuthGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun awaitCredential(
        authorization: CodexDeviceAuthorization,
        onPoll: (Long) -> Unit = {},
    ): CodexOAuthCredential {
        var intervalSeconds = authorization.intervalSeconds
        while (true) {
            val remaining = authorization.expiresAtMillis - nowMillis()
            if (remaining <= 0) {
                throw CodexOAuthProtocolException(
                    statusCode = null,
                    errorType = "expired",
                    safeMessage = "Codex device authorization expired",
                )
            }
            val sleep = minOf(Math.multiplyExact(intervalSeconds, 1_000L), remaining)
            onPoll(sleep)
            sleepMillis(sleep)
            when (val result = gateway.pollDeviceAuthorization(authorization)) {
                is CodexDevicePollResult.Pending -> {
                    require(result.nextIntervalSeconds in 1..60) {
                        "Codex poll interval is invalid"
                    }
                    intervalSeconds = result.nextIntervalSeconds
                }
                is CodexDevicePollResult.Authorized -> return result.credential
            }
        }
    }
}
