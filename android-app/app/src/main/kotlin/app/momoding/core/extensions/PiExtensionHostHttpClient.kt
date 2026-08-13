package app.momoding.core.extensions

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class PiExtensionCredentialBindingRef(
    val packageId: String,
    val packageDigest: String,
    val slot: String,
    val origin: String,
)

fun interface PiExtensionCredentialResolver {
    suspend fun resolveBearer(binding: PiExtensionCredentialBindingRef): CharArray?
}

fun interface PiExtensionNetworkAddressPolicy {
    fun requireAllowed(host: String, addresses: List<InetAddress>)
}

object PublicPiExtensionNetworkAddressPolicy : PiExtensionNetworkAddressPolicy {
    override fun requireAllowed(host: String, addresses: List<InetAddress>) {
        if (addresses.isEmpty() || addresses.any(::isDeniedAddress)) {
            throw PiExtensionHostHttpException("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
        }
    }

    private fun isDeniedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (bytes.size) {
            4 -> !isGlobalIpv4(bytes)
            16 -> !isGlobalIpv6(bytes)
            else -> true
        }
    }

    private fun isGlobalIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].unsigned()
        val b = bytes[1].unsigned()
        val c = bytes[2].unsigned()
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false // shared CGNAT 100.64.0.0/10
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun isGlobalIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].unsigned()
        val second = bytes[1].unsigned()
        val third = bytes[2].unsigned()
        val fourth = bytes[3].unsigned()
        // Only global-unicast 2000::/3 is eligible. This rejects mapped IPv4, NAT64,
        // discard-only, link-local, unique-local and multicast ranges before exceptions.
        if (first !in 0x20..0x3f) return false
        return when {
            first == 0x20 && second == 0x01 && third <= 0x01 -> false // 2001::/23 special use
            first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8 -> false
            first == 0x20 && second == 0x02 -> false // 6to4
            first == 0x3f && second == 0xfe -> false // former 6bone
            first == 0x3f && second == 0xff && third and 0xf0 == 0 -> false // documentation
            else -> true
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}

data class PiExtensionHostHttpAudit(
    val method: String,
    val origin: String,
    val status: Int,
    val responseBytes: Int,
    val durationMillis: Long,
    val redirects: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("method", method)
        put("origin", origin)
        put("status", status)
        put("responseBytes", responseBytes)
        put("durationMillis", durationMillis)
        put("redirects", redirects)
    }
}

data class PiExtensionHostHttpExecution(
    val response: JsonObject,
    val audit: PiExtensionHostHttpAudit,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("response", response)
        put("audit", audit.toJson())
    }
}

class PiExtensionHostHttpException(
    val code: String,
) : IllegalStateException(code)

class PiExtensionHostHttpClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val credentials: PiExtensionCredentialResolver,
    addressPolicy: PiExtensionNetworkAddressPolicy = PublicPiExtensionNetworkAddressPolicy,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(HOST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .dns(GuardedDns(baseClient.dns, addressPolicy))
        .build()

    suspend fun execute(
        artifact: PiRegisterToolArtifact,
        rawRequest: JsonObject,
    ): PiExtensionHostHttpExecution {
        try {
            requireWorkerHttpRequest(artifact.httpPolicy, rawRequest)
        } catch (error: ExtensionPackageException) {
            failHttp(error.message ?: "EXTENSION_PACKAGE_HTTP_FAILED")
        }
        val request = parseRequest(rawRequest)
        val initialOrigin = canonicalHttpsOrigin(request.url)
        val credentialDeclaration = request.credentialSlot?.let { slot ->
            artifact.httpPolicy.credentialSlots.singleOrNull {
                it.slot == slot && it.origin == initialOrigin
            } ?: failHttp("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_REQUIRED")
        }
        val secret = credentialDeclaration?.let {
            credentials.resolveBearer(PiExtensionCredentialBindingRef(
                packageId = artifact.packageId,
                packageDigest = artifact.packageDigest,
                slot = it.slot,
                origin = it.origin,
            )) ?: failHttp("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_REQUIRED")
        }
        val started = nanoTime()
        try {
            var url = request.url
            var method = request.method
            var body = request.body
            var redirects = 0
            while (true) {
                val origin = canonicalHttpsOrigin(url)
                if (origin !in artifact.httpPolicy.origins || method !in artifact.httpPolicy.methods) {
                    failHttp("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
                }
                if (secret != null && origin != initialOrigin) {
                    failHttp("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
                }
                val call = client.newCall(buildRequest(url, method, request.headers, body, secret))
                val step = try {
                    call.awaitProcessed { current ->
                        val location = current.header("Location")
                        if (current.code in REDIRECT_CODES && location != null) {
                            if (redirects >= MAX_REDIRECTS) {
                                failHttp("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
                            }
                            val next = current.request.url.resolve(location)
                                ?: failHttp("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
                            val useGet = current.code == 303 && method !in setOf("GET", "HEAD")
                            HttpStep.Redirect(
                                url = next.toString(),
                                method = if (useGet) "GET" else method,
                                body = if (useGet) null else body,
                            )
                        } else {
                            val bytes = readBounded(current, MAX_HTTP_RESPONSE_BODY_BYTES)
                            val encodedBody = encodeResponseBody(current, bytes)
                            val secretText = secret?.concatToString()
                            val secretBytes = secretText?.encodeToByteArray()
                            try {
                                if (secretBytes != null && secretBytes.isNotEmpty() &&
                                    responseExposesSecret(
                                        current,
                                        bytes,
                                        encodedBody,
                                        secretText,
                                        secretBytes,
                                    )
                                ) {
                                    failHttp("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_ECHO_DENIED")
                                }
                            } finally {
                                secretBytes?.fill(0)
                            }
                            val duration = TimeUnit.NANOSECONDS.toMillis(
                                (nanoTime() - started).coerceAtLeast(0L),
                            ).coerceAtMost(HOST_TIMEOUT_MILLIS)
                            val responseJson = buildJsonObject {
                                put("status", current.code)
                                put("ok", current.isSuccessful)
                                put("url", current.request.url.toString())
                                put("headers", safeResponseHeaders(current))
                                put("body", encodedBody.first)
                                put("bodyEncoding", encodedBody.second)
                                put("redirected", redirects > 0)
                            }
                            HttpStep.Complete(PiExtensionHostHttpExecution(
                                response = responseJson,
                                audit = PiExtensionHostHttpAudit(
                                    method = request.method,
                                    origin = initialOrigin,
                                    status = current.code,
                                    responseBytes = bytes.size,
                                    durationMillis = duration,
                                    redirects = redirects,
                                ),
                            ))
                        }
                    }
                } catch (error: CancellationException) {
                    call.cancel()
                    throw error
                } catch (error: Throwable) {
                    throw mapNetworkFailure(error)
                }
                when (step) {
                    is HttpStep.Redirect -> {
                        url = step.url
                        method = step.method
                        body = step.body
                        redirects += 1
                    }
                    is HttpStep.Complete -> return step.execution
                }
            }
        } finally {
            secret?.fill('\u0000')
        }
    }

    private fun buildRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        secret: CharArray?,
    ): Request {
        val httpUrl = url.toHttpUrlOrNull() ?: failHttp("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
        val builder = Request.Builder().url(httpUrl)
        headers.forEach { (name, value) ->
            if (sensitiveRequestHeader(name)) {
                failHttp("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED")
            }
            builder.addHeader(name, value)
        }
        if (secret != null) builder.header("Authorization", "Bearer ${secret.concatToString()}")
        val mediaType = headers.entries.firstOrNull { it.key.equals("content-type", true) }
            ?.value?.toMediaTypeOrNull()
        val requestBody = when {
            body != null -> body.encodeToByteArray().toRequestBody(mediaType)
            method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(mediaType)
            else -> null
        }
        return builder.method(method, requestBody).build()
    }

    private fun parseRequest(value: JsonObject): ParsedRequest {
        val headers = (value["headers"] as JsonObject).mapValues { (_, raw) ->
            (raw as JsonPrimitive).content
        }
        return ParsedRequest(
            url = (value["url"] as JsonPrimitive).content,
            method = (value["method"] as JsonPrimitive).content,
            headers = headers,
            body = value["body"].takeUnless { it == JsonNull }?.let { (it as JsonPrimitive).content },
            credentialSlot = value["credentialSlot"].takeUnless { it == JsonNull }
                ?.let { (it as JsonPrimitive).content },
        )
    }

    private data class ParsedRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
        val credentialSlot: String?,
    )

    private sealed interface HttpStep {
        data class Redirect(
            val url: String,
            val method: String,
            val body: String?,
        ) : HttpStep

        data class Complete(val execution: PiExtensionHostHttpExecution) : HttpStep
    }

    private class GuardedDns(
        private val delegate: Dns,
        private val policy: PiExtensionNetworkAddressPolicy,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                delegate.lookup(hostname)
            } catch (error: UnknownHostException) {
                throw error
            }
            policy.requireAllowed(hostname, addresses)
            return addresses
        }
    }

    companion object {
        const val HOST_TIMEOUT_MILLIS = 60_000L
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val READ_TIMEOUT_MILLIS = 30_000L
        private const val WRITE_TIMEOUT_MILLIS = 30_000L
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        internal val SAFE_RESPONSE_HEADERS = setOf(
            "cache-control",
            "content-language",
            "content-length",
            "content-type",
            "etag",
            "expires",
            "last-modified",
        )
    }
}

private suspend fun <T> Call.awaitProcessed(process: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use { current ->
                    val result = process(current)
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

private fun readBounded(response: Response, maximumBytes: Int): ByteArray {
    val declared = response.body.contentLength()
    if (declared > maximumBytes) failHttp("EXTENSION_PACKAGE_RESULT_TOO_LARGE")
    val output = ByteArrayOutputStream(minOf(maximumBytes, 32 * 1024))
    response.body.byteStream().use { input ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximumBytes) {
                failHttp("EXTENSION_PACKAGE_RESULT_TOO_LARGE")
            }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private fun encodeResponseBody(response: Response, bytes: ByteArray): Pair<String, String> {
    val mediaType = response.body.contentType()
    val subtype = mediaType?.subtype?.lowercase(Locale.US).orEmpty()
    val textual = mediaType?.type == "text" || subtype.contains("json") || subtype.contains("xml") ||
        subtype in setOf("x-www-form-urlencoded", "javascript")
    return if (textual || bytes.isEmpty()) {
        val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        String(bytes, charset) to "utf8"
    } else {
        Base64.getEncoder().encodeToString(bytes) to "base64"
    }
}

private fun safeResponseHeaders(response: Response): JsonObject = buildJsonObject {
    response.headers.names().forEach { name ->
        val normalized = name.lowercase(Locale.US)
        if (normalized in PiExtensionHostHttpClient.Companion.SAFE_RESPONSE_HEADERS) {
            put(normalized, response.header(name).orEmpty())
        }
    }
}

private fun sensitiveRequestHeader(name: String): Boolean {
    val normalized = name.lowercase(Locale.US)
    return normalized in setOf(
        "authorization",
        "connection",
        "content-length",
        "cookie",
        "forwarded",
        "host",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "via",
        "x-forwarded",
        "x-http-method",
        "x-http-method-override",
        "x-method-override",
        "x-real-ip",
    ) || normalized.startsWith("proxy-") || normalized.startsWith("x-forwarded-") ||
        normalized.startsWith("x-original-") || normalized.startsWith("x-rewrite-") ||
        normalized.startsWith("x-envoy-")
}

private fun responseExposesSecret(
    response: Response,
    body: ByteArray,
    encodedBody: Pair<String, String>,
    secretText: String,
    secret: ByteArray,
): Boolean = body.contains(secret) ||
    (encodedBody.second == "utf8" && encodedBody.first.encodeToByteArray().contains(secret)) ||
    response.request.url.toString().contains(secretText) ||
    response.request.url.pathSegments.any { it.contains(secretText) } ||
    response.request.url.queryParameterNames.any { name ->
        name.contains(secretText) || response.request.url.queryParameterValues(name).any {
            it?.contains(secretText) == true
        }
    } ||
    response.headers.any { (_, value) -> value.encodeToByteArray().contains(secret) }

private fun mapNetworkFailure(error: Throwable): PiExtensionHostHttpException {
    var current: Throwable? = error
    repeat(8) {
        when (current) {
            is PiExtensionHostHttpException -> return current
            is SocketTimeoutException -> return PiExtensionHostHttpException(
                "EXTENSION_PACKAGE_HOST_TIMEOUT",
            )
        }
        current = current?.cause
    }
    return PiExtensionHostHttpException("EXTENSION_PACKAGE_HTTP_FAILED")
}

private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    val prefix = IntArray(needle.size)
    var matched = 0
    for (index in 1 until needle.size) {
        while (matched > 0 && needle[index] != needle[matched]) {
            matched = prefix[matched - 1]
        }
        if (needle[index] == needle[matched]) matched += 1
        prefix[index] = matched
    }
    matched = 0
    for (value in this) {
        while (matched > 0 && value != needle[matched]) {
            matched = prefix[matched - 1]
        }
        if (value == needle[matched]) matched += 1
        if (matched == needle.size) return true
    }
    return false
}

private fun failHttp(code: String): Nothing = throw PiExtensionHostHttpException(code)
