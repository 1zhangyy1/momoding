package app.momoding.core.data

import app.momoding.wire.DurableTaskProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal data class VerifiedAttentionPiDeliveryProof(
    val callId: String,
    val terminalSemanticSha256: String,
)

/**
 * Pure verifier for the two frozen Pi-native proof sources. It does not synthesize lifecycle
 * events and does not mutate Room; [RoomProjectionTransactionStore] owns the atomic mutation.
 */
internal object AttentionPiDeliveryProofVerifier {
    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }
    private val coreDetailKeys = setOf(
        "callId",
        "toolName",
        "terminalSemanticSha256",
        "sideEffect",
    )
    private val recoveryDetailKeys = coreDetailKeys + setOf(
        "recovery",
        "reconciliationState",
        "requiresManualReview",
    )
    private val contentReadDetailKeys = coreDetailKeys + setOf(
        "contentScope",
        "dataScope",
        "approvalOrigin",
    )
    private val fileCommitDetailKeys = coreDetailKeys + setOf(
        "dataScope",
        "operationId",
        "approvalOrigin",
    )
    private val uiActionDetailKeys = coreDetailKeys + setOf(
        "operationId",
        "approvalOrigin",
    )
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private const val CONTENT_READ_TOOL = "device_files_read"
    private const val FILE_COMMIT_TOOL = "device_files_commit_changes"
    private const val UI_ACTION_TOOL = "device_ui_action"
    private val attentionTools = setOf(
        "request_user_question",
        "request_user_confirmation",
        CONTENT_READ_TOOL,
        FILE_COMMIT_TOOL,
        UI_ACTION_TOOL,
    )
    private val fileApprovalTools = setOf(CONTENT_READ_TOOL, FILE_COMMIT_TOOL)

    fun verify(
        projection: DurableTaskProjection,
        taskOperations: List<DeviceOperationEntity>,
        operationForCallId: (String) -> DeviceOperationEntity?,
        expectationForValidatedPair: (DeviceOperationEntity) -> AttentionPiDeliveryExpectation?,
    ): List<VerifiedAttentionPiDeliveryProof> {
        val candidates = buildList {
            projection.rawEvents.toSortedMap().values.forEach { record ->
                val event = record.event
                if (event.stringOrNull("type") == "tool_execution_end") {
                    val result = event["result"] as? JsonObject
                    add(
                        Candidate(
                            source = "native tool_execution_end",
                            toolCallId = event["toolCallId"],
                            toolName = event["toolName"],
                            content = result?.get("content"),
                            details = result?.get("details"),
                            isError = event["isError"],
                        ),
                    )
                }
            }
            projection.messages.forEach { messageElement ->
                val message = messageElement as? JsonObject ?: return@forEach
                if (message.stringOrNull("role") == "toolResult") {
                    add(
                        Candidate(
                            source = "snapshot toolResult",
                            toolCallId = message["toolCallId"],
                            toolName = message["toolName"],
                            content = message["content"],
                            details = message["details"],
                            isError = message["isError"],
                        ),
                    )
                }
            }
        }

        val verified = linkedMapOf<String, VerifiedAttentionPiDeliveryProof>()
        candidates.forEach { candidate ->
            val operation = resolveTarget(
                projection.taskId,
                candidate,
                taskOperations,
                operationForCallId,
            ) ?: return@forEach
            if (operation.toolName !in attentionTools) return@forEach
            val expectation = expectationForValidatedPair(operation)
            val proof = validateCandidate(candidate, operation, expectation) ?: return@forEach
            val previous = verified.putIfAbsent(proof.callId, proof)
            if (previous != null && previous != proof) {
                corrupt("Conflicting exact Pi delivery proofs target one attention call")
            }
        }
        return verified.values.toList()
    }

    private fun resolveTarget(
        transactionTaskId: String,
        candidate: Candidate,
        taskOperations: List<DeviceOperationEntity>,
        operationForCallId: (String) -> DeviceOperationEntity?,
    ): DeviceOperationEntity? {
        val toolCallIdHint = candidate.toolCallId.stringOrNull()
        val details = candidate.details as? JsonObject
        val callIdHint = details?.stringOrNull("callId")
        val targets = buildList {
            toolCallIdHint?.let { hint ->
                addAll(taskOperations.filter { operation -> operation.piToolCallId == hint })
            }
            callIdHint?.let { hint -> operationForCallId(hint)?.let(::add) }
        }.distinctBy(DeviceOperationEntity::callId)
        if (targets.isEmpty()) return null
        if (targets.size != 1) {
            corrupt("Exact Pi delivery proof has ambiguous attention identity")
        }
        return targets.single().also { operation ->
            if (operation.taskId != transactionTaskId) {
                corrupt("Exact Pi delivery proof belongs to another task")
            }
        }
    }

    private fun validateCandidate(
        candidate: Candidate,
        operation: DeviceOperationEntity,
        expectation: AttentionPiDeliveryExpectation?,
    ): VerifiedAttentionPiDeliveryProof? {
        if (operation.toolName !in attentionTools) return null
        when (operation.toolName) {
            FILE_COMMIT_TOOL,
            UI_ACTION_TOOL,
            -> if (!operation.sideEffect || operation.operationId == null) {
                corrupt("Exact Pi delivery proof targets an unsafe side-effect binding")
            }
            else -> if (operation.sideEffect || operation.operationId != null) {
                corrupt("Exact Pi delivery proof targets an unsafe attention binding")
            }
        }
        if (candidate.toolCallId.requiredString("toolCallId") != operation.piToolCallId) {
            corrupt("Exact Pi delivery proof toolCallId conflicts")
        }
        if (candidate.toolName.requiredString("toolName") != operation.toolName) {
            corrupt("Exact Pi delivery proof toolName conflicts")
        }
        val details = candidate.details as? JsonObject
            ?: corrupt("Exact Pi delivery proof details are missing")
        if (candidate.source == "native tool_execution_end" && details.isEmpty()) {
            return null
        }
        val recovery = details["recovery"]
        // Generic Host recovery messages for file approvals do not carry the exact scoped result
        // contract. They remain observation-only until a normal Pi ToolResult proves delivery.
        if (recovery != null && operation.toolName in fileApprovalTools) return null
        val expectedDetailKeys = when {
            recovery != null -> recoveryDetailKeys
            operation.toolName == CONTENT_READ_TOOL -> contentReadDetailKeys
            operation.toolName == FILE_COMMIT_TOOL -> fileCommitDetailKeys
            operation.toolName == UI_ACTION_TOOL -> uiActionDetailKeys
            else -> coreDetailKeys
        }
        if (details.keys != expectedDetailKeys) {
            corrupt("Exact Pi delivery proof detail fields differ")
        }
        if (recovery != null && recovery.requiredBoolean("details.recovery") != true) {
            corrupt("Exact Pi delivery recovery marker is invalid")
        }
        if (details.getValue("callId").requiredString("details.callId") != operation.callId) {
            corrupt("Exact Pi delivery proof callId conflicts")
        }
        if (details.getValue("toolName").requiredString("details.toolName") != operation.toolName) {
            corrupt("Exact Pi delivery proof detail toolName conflicts")
        }
        if (
            details.getValue("sideEffect").requiredBoolean("details.sideEffect") !=
            operation.sideEffect
        ) {
            corrupt("Exact Pi delivery proof side-effect binding conflicts")
        }
        if (operation.toolName in setOf(FILE_COMMIT_TOOL, UI_ACTION_TOOL)) {
            if (
                details.getValue("operationId").requiredString("details.operationId") !=
                operation.operationId
            ) {
                corrupt("Exact Pi delivery proof operationId conflicts")
            }
        }
        if (operation.toolName in fileApprovalTools || operation.toolName == UI_ACTION_TOOL) {
            val validOrigins = if (operation.toolName == FILE_COMMIT_TOOL) {
                setOf("none", "user", "auto_policy")
            } else {
                setOf("user", "auto_policy")
            }
            if (
                details.getValue("approvalOrigin").requiredString("details.approvalOrigin") !in
                validOrigins
            ) {
                corrupt("Exact Pi delivery proof approval origin is invalid")
            }
        }
        if (operation.toolName in fileApprovalTools) {
            if (
                details.getValue("dataScope").requiredString("details.dataScope") !in
                setOf("android_saf_task_grant", "android_shared_storage_grant")
            ) {
                corrupt("Exact Pi delivery proof data scope conflicts")
            }
        }
        if (
            operation.toolName == CONTENT_READ_TOOL &&
            details.getValue("contentScope").requiredString("details.contentScope") !in
            setOf("android_saf_user_approved", "android_shared_storage_policy")
        ) {
            corrupt("Exact Pi delivery proof content scope conflicts")
        }
        val semanticHash = details.getValue("terminalSemanticSha256")
            .requiredString("details.terminalSemanticSha256")
        if (!sha256Pattern.matches(semanticHash)) {
            corrupt("Exact Pi delivery proof semantic hash is invalid")
        }
        val contentPayload = parseUniqueTextPayload(candidate.content, candidate.source)
        val isError = candidate.isError.requiredBoolean("isError")

        if (recovery != null) {
            val reconciliationState = details.getValue("reconciliationState")
                .requiredString("details.reconciliationState")
            if (reconciliationState !in setOf("succeeded", "failed", "cancelled")) {
                corrupt("Exact Pi delivery recovery state is invalid")
            }
            if (details.getValue("requiresManualReview")
                    .requiredBoolean("details.requiresManualReview")
            ) {
                corrupt("Attention Pi delivery recovery cannot require manual review")
            }
            if ((reconciliationState != "succeeded") != isError) {
                corrupt("Exact Pi delivery recovery state conflicts with isError")
            }
            if (expectation != null && reconciliationState != expectation.recoveryState) {
                corrupt("Exact Pi delivery recovery state conflicts with the local terminal")
            }
        }

        // A Host result without an Android-local terminal remains observation-only. It may prove
        // that another actor answered, but it cannot create or resolve local user state.
        if (expectation == null) return null
        if (semanticHash != expectation.terminalSemanticSha256) {
            corrupt("Exact Pi delivery proof semantic hash conflicts")
        }
        if (isError != expectation.isError) {
            corrupt("Exact Pi delivery proof isError conflicts")
        }
        if (contentPayload != expectation.contentPayload) {
            corrupt("Exact Pi delivery proof content conflicts")
        }
        return VerifiedAttentionPiDeliveryProof(operation.callId, semanticHash)
    }

    private fun parseUniqueTextPayload(value: JsonElement?, source: String): JsonElement {
        val content = value as? JsonArray
            ?: corrupt("$source content is not an array")
        if (content.size != 1) corrupt("$source must contain one text payload")
        val item = content.single() as? JsonObject
            ?: corrupt("$source text payload is not an object")
        if (item.keys != setOf("type", "text") || item.stringOrNull("type") != "text") {
            corrupt("$source text payload fields differ")
        }
        val text = item.getValue("text").requiredString("content.text")
        return try {
            strictJson.parseToJsonElement(text)
        } catch (_: Throwable) {
            corrupt("$source text payload is not JSON")
        }
    }

    private data class Candidate(
        val source: String,
        val toolCallId: JsonElement?,
        val toolName: JsonElement?,
        val content: JsonElement?,
        val details: JsonElement?,
        val isError: JsonElement?,
    )

    private fun JsonObject.stringOrNull(key: String): String? = get(key).stringOrNull()

    private fun JsonElement?.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.contentOrNull else null
    }

    private fun JsonElement?.requiredString(field: String): String =
        stringOrNull() ?: corrupt("Exact Pi delivery proof $field is not a string")

    private fun JsonElement?.requiredBoolean(field: String): Boolean {
        val primitive = this as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            corrupt("Exact Pi delivery proof $field is not a boolean")
        }
        return primitive.booleanOrNull
            ?: corrupt("Exact Pi delivery proof $field is not a boolean")
    }

    private fun corrupt(message: String): Nothing = throw ProjectionCorruptionException(message)
}
