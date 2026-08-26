package app.momoding.feature.taskdetail

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Presentation-only state for one user turn's exact capability recovery chain.
 * Pi messages and Room rows remain authoritative and untouched.
 */
internal class CapabilityRecoveryProjection {
    private val invocations = linkedMapOf<String, Invocation>()
    private val candidates = mutableListOf<Candidate>()
    private val grants = mutableListOf<Grant>()
    private var terminalSequence = 0L

    fun resetTurn() {
        invocations.clear()
        candidates.clear()
        grants.clear()
        terminalSequence = 0L
    }

    fun recordStart(callId: String, toolName: String, arguments: JsonObject) {
        val action = if (toolName == CAPABILITY_REQUEST_TOOL) {
            CAPABILITY_REQUEST_ACTION
        } else {
            arguments.string("action") ?: return
        }
        if (callId in invocations || invocations.size >= MAX_INVOCATIONS_PER_TURN) return
        invocations[callId] = Invocation(
            identity = InvocationIdentity(
                toolName = toolName,
                action = action,
                canonicalArgumentsDigest = sha256(canonicalJson(arguments)),
            ),
            arguments = arguments,
        )
    }

    /** Returns the prior failed Tool call that may now be hidden from presentation. */
    fun observeTerminal(
        callId: String,
        toolName: String,
        state: ToolActivityState,
        payload: JsonObject?,
    ): String? {
        val sequence = ++terminalSequence
        val invocation = invocations[callId]
        if (toolName == CAPABILITY_REQUEST_TOOL) {
            observeCapabilityRequest(invocation, state, payload, sequence)
            return null
        }
        if (invocation == null || invocation.identity.toolName != toolName || payload == null) return null
        if (payload.string("action") != invocation.identity.action) return null

        if (state == ToolActivityState.FAILURE && payload.errorCode() == CAPABILITY_NOT_READY) {
            val requirement = payload.requiredCapability(toolName) ?: return null
            if (candidates.size >= MAX_CANDIDATES_PER_TURN) return null
            candidates += Candidate(invocation.identity, requirement, callId, sequence)
            return null
        }
        if (state != ToolActivityState.SUCCESS || payload.strictBoolean("ok") != true) return null

        val candidateIndex = candidates.indexOfLast { it.identity == invocation.identity }
        if (candidateIndex < 0) return null
        val candidate = candidates[candidateIndex]
        val grantIndex = grants.indexOfFirst { grant ->
            grant.requirement == candidate.requirement && grant.sequence > candidate.failureSequence
        }
        if (grantIndex < 0) return null
        val recovered = candidates.removeAt(candidateIndex)
        grants.removeAt(grantIndex)
        return recovered.failureCallId
    }

    private fun observeCapabilityRequest(
        invocation: Invocation?,
        state: ToolActivityState,
        payload: JsonObject?,
        sequence: Long,
    ) {
        if (
            invocation == null ||
            invocation.identity.toolName != CAPABILITY_REQUEST_TOOL ||
            invocation.identity.action != CAPABILITY_REQUEST_ACTION ||
            state != ToolActivityState.SUCCESS ||
            payload == null
        ) return
        val requested = invocation.arguments.capabilityRequirement() ?: return
        val returned = payload.capabilityRequirement() ?: return
        if (
            requested == returned &&
            payload.strictBoolean("ready") == true &&
            payload.string("capability") == requested.capability
        ) {
            if (grants.size < MAX_GRANTS_PER_TURN) grants += Grant(requested, sequence)
        }
    }

    private data class Invocation(
        val identity: InvocationIdentity,
        val arguments: JsonObject,
    )

    private data class InvocationIdentity(
        val toolName: String,
        val action: String,
        val canonicalArgumentsDigest: String,
    )

    private data class Candidate(
        val identity: InvocationIdentity,
        val requirement: CapabilityRequirement,
        val failureCallId: String,
        val failureSequence: Long,
    )

    private data class Grant(
        val requirement: CapabilityRequirement,
        val sequence: Long,
    )

    private data class CapabilityRequirement(
        val capability: String,
        val requiredAccess: String?,
    )

    private fun JsonObject.requiredCapability(toolName: String): CapabilityRequirement? {
        val resolution = (this["error"] as? JsonObject)?.get("resolution")
        return when (resolution) {
            is JsonObject -> resolution
                .takeIf { it.string("kind") == "request_capability" }
                ?.capabilityRequirement()
            null -> DEFAULT_CAPABILITY_BY_TOOL[toolName]?.let { CapabilityRequirement(it, null) }
            is JsonPrimitive -> resolution
                .takeIf(JsonPrimitive::isString)
                ?.let { DEFAULT_CAPABILITY_BY_TOOL[toolName] }
                ?.let { CapabilityRequirement(it, null) }
            else -> null
        }
    }

    private fun JsonObject.capabilityRequirement(): CapabilityRequirement? {
        val capability = string("capability")?.takeIf { it in KNOWN_CAPABILITIES } ?: return null
        val requiredAccess = string("requiredAccess")
        return CapabilityRequirement(capability, requiredAccess)
    }

    private fun JsonObject.errorCode(): String? =
        (this["error"] as? JsonObject)?.string("code")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)

    private fun JsonObject.strictBoolean(key: String): Boolean? = (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (key, element) -> "${JsonPrimitive(key)}:${canonicalJson(element)}" }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> value.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val CAPABILITY_REQUEST_TOOL = "device_capability_request"
        const val CAPABILITY_REQUEST_ACTION = "request_capability"
        const val CAPABILITY_NOT_READY = "CAPABILITY_NOT_READY"
        const val MAX_INVOCATIONS_PER_TURN = 64
        const val MAX_CANDIDATES_PER_TURN = 8
        const val MAX_GRANTS_PER_TURN = 8
        val KNOWN_CAPABILITIES = setOf(
            "calendar",
            "contacts",
            "location",
            "notifications",
            "photo_library",
        )
        val DEFAULT_CAPABILITY_BY_TOOL = mapOf(
            "device_notification" to "notifications",
            "device_media" to "photo_library",
            "device_media_list" to "photo_library",
        )
    }
}
