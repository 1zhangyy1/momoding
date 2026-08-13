package app.momoding.core.extensions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class ExtensionToolActivityKind { HOST_TOOL, HTTPS }

enum class ExtensionToolActivityState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class ExtensionToolActivityEvent(
    val outerToolCallId: String,
    val seq: Int,
    val kind: ExtensionToolActivityKind,
    val state: ExtensionToolActivityState,
    val packageId: String,
    val name: String? = null,
    val targetTool: String? = null,
    val method: String? = null,
    val origin: String? = null,
    val status: Int? = null,
    val responseBytes: Int? = null,
    val durationMillis: Int? = null,
    val redirects: Int? = null,
    val code: String? = null,
) {
    val identity: String = "$outerToolCallId\u0000$seq\u0000${kind.name}"
    val terminal: Boolean = state != ExtensionToolActivityState.RUNNING
}

/** Strict, shared Android boundary for the PXP-7G run-event and durable UI projection. */
object ExtensionToolActivityContract {
    fun identityOrNull(event: JsonObject): String? {
        if (event.string("type") != "extension_tool_activity") return null
        val outerToolCallId = event.string("toolCallId")
            ?.takeIf(TOOL_CALL_ID::matches)
            ?: return null
        val seq = event.integer("seq")
            ?.takeIf { it in 0..MAX_SEQ }
            ?: return null
        val kind = when (event.string("kind")) {
            "host_tool" -> ExtensionToolActivityKind.HOST_TOOL
            "https" -> ExtensionToolActivityKind.HTTPS
            else -> return null
        }
        return "$outerToolCallId\u0000$seq\u0000${kind.name}"
    }

    fun parse(event: JsonObject): ExtensionToolActivityEvent? {
        if (
            event.string("type") != "extension_tool_activity" ||
            event.toString().toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES ||
            identityOrNull(event) == null
        ) {
            return null
        }
        val outerToolCallId = event.string("toolCallId") ?: return null
        val seq = event.integer("seq")
            ?.takeIf { it in 0..MAX_SEQ }
            ?: return null
        val state = when (event.string("state")) {
            "running" -> ExtensionToolActivityState.RUNNING
            "completed" -> ExtensionToolActivityState.COMPLETED
            "failed" -> ExtensionToolActivityState.FAILED
            "cancelled" -> ExtensionToolActivityState.CANCELLED
            else -> return null
        }
        val packageId = event.string("packageId")
            ?.takeIf { it.length <= 76 && PACKAGE_ID.matches(it) }
            ?: return null
        return when (event.string("kind")) {
            "host_tool" -> parseHost(event, outerToolCallId, seq, state, packageId)
            "https" -> parseHttps(event, outerToolCallId, seq, state, packageId)
            else -> null
        }
    }

    private fun parseHost(
        event: JsonObject,
        outerToolCallId: String,
        seq: Int,
        state: ExtensionToolActivityState,
        packageId: String,
    ): ExtensionToolActivityEvent? {
        val failed = state in setOf(ExtensionToolActivityState.FAILED, ExtensionToolActivityState.CANCELLED)
        if (event.keys != if (failed) HOST_ERROR_KEYS else HOST_KEYS) return null
        val name = event.string("name")?.takeIf(TOOL_NAME::matches) ?: return null
        val targetTool = event.string("targetTool")?.takeIf(HOST_TOOLS::contains) ?: return null
        val code = event.string("code")
        if ((failed && code?.matches(ERROR_CODE) != true) || (!failed && code != null)) return null
        return ExtensionToolActivityEvent(
            outerToolCallId = outerToolCallId,
            seq = seq,
            kind = ExtensionToolActivityKind.HOST_TOOL,
            state = state,
            packageId = packageId,
            name = name,
            targetTool = targetTool,
            code = code,
        )
    }

    private fun parseHttps(
        event: JsonObject,
        outerToolCallId: String,
        seq: Int,
        state: ExtensionToolActivityState,
        packageId: String,
    ): ExtensionToolActivityEvent? {
        val completed = state == ExtensionToolActivityState.COMPLETED
        val failed = state in setOf(ExtensionToolActivityState.FAILED, ExtensionToolActivityState.CANCELLED)
        val expectedKeys = when {
            completed -> HTTPS_COMPLETED_KEYS
            failed -> HTTPS_ERROR_KEYS
            else -> HTTPS_STARTED_KEYS
        }
        if (event.keys != expectedKeys) return null
        val method = event.string("method")?.takeIf(HTTP_METHODS::contains) ?: return null
        val origin = event.string("origin")
            ?.takeIf { value ->
                value.length <= 256 && runCatching { canonicalHttpsOrigin(value) }.getOrNull() == value
            }
            ?: return null
        val code = event.string("code")
        if ((failed && code?.matches(ERROR_CODE) != true) || (!failed && code != null)) return null
        val status = event.integer("status")
        val responseBytes = event.integer("responseBytes")
        val durationMillis = event.integer("durationMillis")
        val redirects = event.integer("redirects")
        if (
            completed && (
                status?.let { it !in 100..599 } != false ||
                    responseBytes?.let { it !in 0..MAX_RESPONSE_BYTES } != false ||
                    durationMillis?.let { it !in 0..MAX_DURATION_MILLIS } != false ||
                    redirects?.let { it !in 0..MAX_REDIRECTS } != false
                ) ||
            !completed && (status != null || responseBytes != null || durationMillis != null || redirects != null)
        ) {
            return null
        }
        return ExtensionToolActivityEvent(
            outerToolCallId = outerToolCallId,
            seq = seq,
            kind = ExtensionToolActivityKind.HTTPS,
            state = state,
            packageId = packageId,
            method = method,
            origin = origin,
            status = status,
            responseBytes = responseBytes,
            durationMillis = durationMillis,
            redirects = redirects,
            code = code,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.integer(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private const val MAX_EVENT_BYTES = 2_048
    private const val MAX_SEQ = 1_000_000
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_DURATION_MILLIS = 600_000
    private const val MAX_REDIRECTS = 3
    private val TOOL_CALL_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private val PACKAGE_ID = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$")
    private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{2,127}$")
    private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    private val HOST_TOOLS = EXTENSION_ANDROID_TOOL_CAPABILITIES.keys
    private val HOST_KEYS = setOf(
        "type", "state", "toolCallId", "seq", "kind", "packageId", "name", "targetTool",
    )
    private val HOST_ERROR_KEYS = HOST_KEYS + "code"
    private val HTTPS_STARTED_KEYS = setOf(
        "type", "state", "toolCallId", "seq", "kind", "packageId", "method", "origin",
    )
    private val HTTPS_ERROR_KEYS = HTTPS_STARTED_KEYS + "code"
    private val HTTPS_COMPLETED_KEYS = HTTPS_STARTED_KEYS + setOf(
        "status", "responseBytes", "durationMillis", "redirects",
    )
}
