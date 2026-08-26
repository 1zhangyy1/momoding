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
    private val calendarMutationDetailKeys = coreDetailKeys + setOf(
        "operationId",
        "approvalOrigin",
    )
    private val contactsMutationDetailKeys = coreDetailKeys + setOf(
        "operationId",
        "approvalOrigin",
    )
    private val clipboardMutationDetailKeys = coreDetailKeys + setOf(
        "operationId",
        "approvalOrigin",
    )
    private val liveLocationDetailKeys = setOf(
        "liveOnly",
        "dataClass",
        "contentSha256",
        "precision",
    )
    private val liveClipboardDetailKeys = setOf(
        "liveOnly",
        "dataClass",
        "contentSha256",
    )
    private val notificationMutationDetailKeys = coreDetailKeys + setOf(
        "operationId",
        "approvalOrigin",
    )
    private val mediaMutationDetailKeys = coreDetailKeys + setOf(
        "operationId",
        "approvalOrigin",
        "systemConsent",
    )
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private const val CONTENT_READ_TOOL = "device_files_read"
    private const val FILE_COMMIT_TOOL = "device_files_commit_changes"
    private const val CALENDAR_TOOL = "device_calendar"
    private const val CONTACTS_TOOL = "device_contacts"
    private const val LOCATION_TOOL = "device_location"
    private const val CLIPBOARD_TOOL = "device_clipboard"
    private const val NOTIFICATION_TOOL = "device_notification"
    private const val MEDIA_TOOL = "device_media"
    private const val UI_ACTION_TOOL = "device_ui_action"
    private val attentionTools = setOf(
        "request_user_question",
        "request_user_confirmation",
        CONTENT_READ_TOOL,
        FILE_COMMIT_TOOL,
        CALENDAR_TOOL,
        CONTACTS_TOOL,
        LOCATION_TOOL,
        CLIPBOARD_TOOL,
        NOTIFICATION_TOOL,
        MEDIA_TOOL,
        UI_ACTION_TOOL,
    )
    private val fileApprovalTools = setOf(CONTENT_READ_TOOL, FILE_COMMIT_TOOL)
    private val liveTextTools = setOf(LOCATION_TOOL, CLIPBOARD_TOOL)
    private val liveTextDataClasses = setOf("location", "clipboard")

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
            val details = candidate.details as? JsonObject
            // Auto-approve and Full access reads intentionally have no Attention operation. The
            // durable boundary must still reject raw or malformed live-only personal data before
            // attempting to resolve an optional approval record.
            val candidateToolName = candidate.toolName.stringOrNull()
            val candidateDataClass = details?.stringOrNull("dataClass")
            if (
                details?.get("liveOnly") != null &&
                (
                    candidateToolName in liveTextTools ||
                        candidateDataClass in liveTextDataClasses
                    )
            ) {
                validateLiveOnlyRead(
                    candidate = candidate,
                    toolName = candidate.toolName.requiredString("toolName"),
                    details = details,
                )
            }
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
        if (operation.deliveryState == AttentionDeliveryState.PI_DELIVERED.name) return null
        when (operation.toolName) {
            FILE_COMMIT_TOOL,
            UI_ACTION_TOOL,
            CALENDAR_TOOL,
            CONTACTS_TOOL,
            CLIPBOARD_TOOL,
            NOTIFICATION_TOOL,
            MEDIA_TOOL,
            -> if (!operation.sideEffect || operation.operationId == null) {
                if (
                    operation.toolName !in
                    setOf(
                        CALENDAR_TOOL,
                        CONTACTS_TOOL,
                        CLIPBOARD_TOOL,
                        NOTIFICATION_TOOL,
                        MEDIA_TOOL,
                    )
                ) {
                    corrupt("Exact Pi delivery proof targets an unsafe side-effect binding")
                }
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
        if (details["liveOnly"] != null) {
            if (operation.sideEffect || operation.operationId != null) {
                corrupt("Live-only Pi observation targets a side effect")
            }
            return null
        }
        val expectedDetailKeys = when {
            recovery != null -> recoveryDetailKeys
            operation.toolName == CONTENT_READ_TOOL -> contentReadDetailKeys
            operation.toolName == FILE_COMMIT_TOOL -> fileCommitDetailKeys
            operation.toolName == UI_ACTION_TOOL -> uiActionDetailKeys
            operation.toolName == CALENDAR_TOOL && operation.sideEffect ->
                calendarMutationDetailKeys
            operation.toolName == CONTACTS_TOOL && operation.sideEffect ->
                contactsMutationDetailKeys
            operation.toolName == CLIPBOARD_TOOL && operation.sideEffect ->
                clipboardMutationDetailKeys
            operation.toolName == NOTIFICATION_TOOL && operation.sideEffect ->
                notificationMutationDetailKeys
            operation.toolName == MEDIA_TOOL && operation.sideEffect ->
                mediaMutationDetailKeys
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
        if (
            operation.toolName in setOf(FILE_COMMIT_TOOL, UI_ACTION_TOOL) ||
            (operation.toolName == CALENDAR_TOOL && operation.sideEffect) ||
            (operation.toolName == CONTACTS_TOOL && operation.sideEffect)
            || (operation.toolName == CLIPBOARD_TOOL && operation.sideEffect)
            || (operation.toolName == NOTIFICATION_TOOL && operation.sideEffect)
            || (operation.toolName == MEDIA_TOOL && operation.sideEffect)
        ) {
            if (
                details.getValue("operationId").requiredString("details.operationId") !=
                operation.operationId
            ) {
                corrupt("Exact Pi delivery proof operationId conflicts")
            }
        }
        if (
            operation.toolName in fileApprovalTools ||
            operation.toolName == UI_ACTION_TOOL ||
            (operation.toolName == CALENDAR_TOOL && operation.sideEffect) ||
            (operation.toolName == CONTACTS_TOOL && operation.sideEffect)
            || (operation.toolName == CLIPBOARD_TOOL && operation.sideEffect)
            || (operation.toolName == NOTIFICATION_TOOL && operation.sideEffect)
            || (operation.toolName == MEDIA_TOOL && operation.sideEffect)
        ) {
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
        if (
            operation.toolName == MEDIA_TOOL &&
            details.getValue("systemConsent").requiredBoolean("details.systemConsent") !=
            (operation.progressSequence >= MEDIA_MUTATION_DISPATCHED_SEQUENCE)
        ) {
            corrupt("Exact Pi delivery proof system consent conflicts")
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

    /**
     * Location and clipboard reads are intentionally available to one Provider turn only. Pi
     * replaces their text with a digest-bound placeholder before emitting a durable event. That
     * event is observation-only: it must never be mistaken for an exact terminal-delivery proof.
     */
    private fun validateLiveOnlyRead(
        candidate: Candidate,
        toolName: String,
        details: JsonObject,
    ) {
        val expectedKeys = when (toolName) {
            LOCATION_TOOL -> liveLocationDetailKeys
            CLIPBOARD_TOOL -> liveClipboardDetailKeys
            else -> corrupt("Live-only Pi observation targets an unsupported tool")
        }
        if (details.keys != expectedKeys) {
            corrupt("Live-only Pi observation detail fields differ")
        }
        if (details.getValue("liveOnly").requiredBoolean("details.liveOnly") != true) {
            corrupt("Live-only Pi observation marker is invalid")
        }
        val dataClass = details.getValue("dataClass").requiredString("details.dataClass")
        val expectedDataClass = if (toolName == LOCATION_TOOL) "location" else "clipboard"
        if (dataClass != expectedDataClass) {
            corrupt("Live-only Pi observation data class conflicts")
        }
        val contentSha256 = details.getValue("contentSha256")
            .requiredString("details.contentSha256")
        if (!sha256Pattern.matches(contentSha256)) {
            corrupt("Live-only Pi observation digest is invalid")
        }
        val precision = if (toolName == LOCATION_TOOL) {
            details.getValue("precision").requiredString("details.precision").also { value ->
                if (value !in setOf("approximate", "precise")) {
                    corrupt("Live-only Pi observation precision is invalid")
                }
            }
        } else {
            null
        }
        if (candidate.isError.requiredBoolean("isError")) {
            corrupt("Live-only Pi observation cannot be an error")
        }
        val content = candidate.content as? JsonArray
            ?: corrupt("${candidate.source} live-only content is not an array")
        if (content.size != 1) {
            corrupt("${candidate.source} live-only observation must contain one placeholder")
        }
        val item = content.single() as? JsonObject
            ?: corrupt("${candidate.source} live-only placeholder is not an object")
        if (item.keys != setOf("type", "text") || item.stringOrNull("type") != "text") {
            corrupt("${candidate.source} live-only placeholder fields differ")
        }
        val expectedPlaceholder = if (toolName == LOCATION_TOOL) {
            "[live Android location expired sha256=$contentSha256 precision=$precision]"
        } else {
            "[live Android clipboard expired sha256=$contentSha256]"
        }
        if (item.getValue("text").requiredString("content.text") != expectedPlaceholder) {
            corrupt("${candidate.source} live-only placeholder conflicts")
        }
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
