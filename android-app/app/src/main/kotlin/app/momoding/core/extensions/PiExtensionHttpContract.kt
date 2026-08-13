package app.momoding.core.extensions

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class PiRegisterToolHttpPolicy(
    val origins: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val credentialSlots: List<PiRegisterToolCredentialSlot> = emptyList(),
)

@Serializable
data class PiRegisterToolCredentialSlot(
    val slot: String,
    val origin: String,
    val placement: String = "authorization_bearer",
)

internal fun requirePiExtensionHttpPolicy(value: PiRegisterToolHttpPolicy) {
    val allowedMethods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    if (value.origins.size > 8 || value.methods.size > allowedMethods.size ||
        value.credentialSlots.size > 8 || value.origins.distinct().size != value.origins.size ||
        value.methods.distinct().size != value.methods.size ||
        value.methods.any { it !in allowedMethods } ||
        value.credentialSlots.map { it.slot }.distinct().size != value.credentialSlots.size
    ) {
        failHttpContract("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
    }
    value.origins.forEach { origin ->
        if (canonicalHttpsOrigin(origin) != origin) {
            failHttpContract("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
        }
    }
    value.credentialSlots.forEach { binding ->
        if (!HTTP_SLOT.matches(binding.slot) || binding.origin !in value.origins ||
            binding.placement != "authorization_bearer"
        ) {
            failHttpContract("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
        }
    }
}

internal fun requireWorkerHttpRequest(
    policy: PiRegisterToolHttpPolicy,
    request: JsonObject,
) {
    if (request.keys != setOf("url", "method", "headers", "body", "credentialSlot")) {
        failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")
    }
    val url = request.requiredHttpString("url", 2_048)
    val origin = canonicalHttpsOrigin(url)
    if (origin !in policy.origins) failHttpContract("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
    val method = request.requiredHttpString("method", 6)
    if (method !in policy.methods) {
        failHttpContract("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED")
    }
    val headers = request["headers"] as? JsonObject
        ?: failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")
    if (headers.size > 32 || headers.toString().toByteArray().size > 8 * 1024 ||
        headers.any { (name, rawValue) ->
            !HTTP_HEADER_NAME.matches(name) ||
                (rawValue as? JsonPrimitive)?.takeIf { it.isString }
                    ?.contentOrNull?.let { it.length <= 4_096 && '\r' !in it && '\n' !in it } != true
        }
    ) {
        failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")
    }
    val body = request["body"]
    if (body != JsonNull &&
        (body as? JsonPrimitive)?.takeIf { it.isString }
            ?.contentOrNull?.toByteArray()?.size?.let { it <= MAX_HTTP_REQUEST_BODY_BYTES } != true
    ) {
        failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")
    }
    if (method in setOf("GET", "HEAD") && body != JsonNull) {
        failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")
    }
    val slot = request["credentialSlot"]
    if (slot != JsonNull) {
        val slotName = (slot as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")
        if (policy.credentialSlots.none { it.slot == slotName && it.origin == origin }) {
            failHttpContract("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_REQUIRED")
        }
    }
}

internal fun canonicalHttpsOrigin(value: String): String {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        failHttpContract("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
    }
    val host = uri.host?.lowercase() ?: failHttpContract("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
        uri.fragment != null || host.length > 253 || !HTTP_HOST.matches(host) || isIpLiteral(host)
    ) {
        failHttpContract("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
    }
    val port = when (uri.port) {
        -1, 443 -> 443
        in 1..65_535 -> uri.port
        else -> failHttpContract("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED")
    }
    return "https://$host${if (port == 443) "" else ":$port"}"
}

private fun JsonObject.requiredHttpString(key: String, maximum: Int): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotEmpty() && it.length <= maximum && '\u0000' !in it }
        ?: failHttpContract("EXTENSION_PACKAGE_HTTP_REQUEST_INVALID")

private fun isIpLiteral(host: String): Boolean =
    ':' in host || Regex("^[0-9]+(?:\\.[0-9]+){3}$").matches(host)

private fun failHttpContract(code: String): Nothing = throw ExtensionPackageException(code)

internal const val MAX_HTTP_REQUEST_BODY_BYTES = 256 * 1024
internal const val MAX_HTTP_RESPONSE_BODY_BYTES = 1024 * 1024
internal const val MAX_HTTP_RESUME_BYTES = 1_500 * 1024
private val HTTP_SLOT = Regex("^[a-z][a-z0-9._-]{0,63}$")
private val HTTP_HOST = Regex("^[a-z0-9.-]+$")
private val HTTP_HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
