package app.momoding.core.data

import app.momoding.wire.DeviceClientFrameEncoder
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AttentionLedgerState {
    RECEIVED,
    AWAITING_USER,
    TERMINAL,
    CANCELLED,
    TIMED_OUT,
    FAILED_CLOSED,
}

enum class AttentionDeliveryState {
    NOT_READY,
    READY_TO_SEND,
    SENT_UNCONFIRMED,
    HOST_TERMINAL_DURABLE,
    PI_DELIVERED,
    ABANDONED,
}

enum class AttentionResponseState {
    PENDING,
    RESPONDING,
    RESOLVED,
    REJECTED,
    SKIPPED,
    EXPIRED,
    CANCELLED,
    ALREADY_ANSWERED,
    VALIDATION_ERROR,
}

enum class AttentionTerminalOrigin {
    USER,
    AUTO_POLICY,
    LOCAL_RECOVERY,
    EXPIRY,
    HOST_CANCEL,
    FAILED_CLOSED,
}

data class AttentionRequestRecord(
    val callId: String,
    val taskId: String,
    val piToolCallId: String,
    val deviceId: String,
    val toolName: String,
    val arguments: JsonElement,
    val sideEffect: Boolean,
    val operationId: String?,
    val expiresAt: String,
    val capabilityVersion: Long,
)

data class AttentionAcceptanceScope(
    val taskId: String,
    val deviceId: String,
    val capabilityVersion: Long,
    val originFocusKey: String,
)

data class AttentionLedgerRecord(
    val operation: DeviceOperationEntity,
    val attention: PendingAttentionEntity,
)

internal data class AttentionPiDeliveryExpectation(
    val terminalSemanticSha256: String,
    val contentPayload: JsonElement,
    val isError: Boolean,
    val recoveryState: String,
)

sealed interface AttentionReconcileEntry {
    data class Valid(val record: AttentionLedgerRecord) : AttentionReconcileEntry
    data object Corrupt : AttentionReconcileEntry
}

data class AttentionTerminalWrite(
    val frame: DeviceToolResultClientFrame,
    val origin: AttentionTerminalOrigin,
    val nowMillis: Long,
    val cancelReason: String? = null,
)

class AttentionLedgerConflictException(message: String) : IllegalStateException(message)

/**
 * The Android-authoritative P2 attention ledger.
 *
 * Host snapshots never create, replace, or delete these rows. The WSS coordinator added in
 * The single-owner IO actor invokes this API before exposing or sending state.
 */
class RoomAttentionLedger(
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.p2Dao()

    fun record(callId: String): AttentionLedgerRecord? =
        database.runInTransaction<AttentionLedgerRecord?> {
            val normalized = normalizeUuid(callId, "callId")
            val operation = dao.deviceOperation(normalized) ?: return@runInTransaction null
            val attention = dao.pendingAttention(normalized)
                ?: throw AttentionLedgerConflictException(
                    "Device operation is missing attention projection",
                )
            assertPair(operation, attention)
            AttentionLedgerRecord(operation, attention)
        }

    fun records(taskId: String): List<AttentionLedgerRecord> =
        database.runInTransaction<List<AttentionLedgerRecord>> {
            val normalizedTaskId = normalizeUuid(taskId, "taskId")
            val attentionByCall = dao.localPendingAttention(normalizedTaskId).associateBy { it.callId }
            dao.deviceOperations(normalizedTaskId).map { operation ->
                val attention = attentionByCall[operation.callId]
                    ?: throw AttentionLedgerConflictException(
                        "Device operation is missing attention projection",
                    )
                assertPair(operation, attention)
                AttentionLedgerRecord(operation, attention)
            }
        }

    fun unterminatedRecords(): List<AttentionLedgerRecord> =
        database.runInTransaction<List<AttentionLedgerRecord>> {
            dao.unterminatedDeviceOperations().map { operation ->
                val attention = dao.pendingAttention(operation.callId)
                    ?: throw AttentionLedgerConflictException(
                        "Device operation is missing attention projection",
                    )
                assertPair(operation, attention)
                AttentionLedgerRecord(operation, attention)
            }
        }

    /** Returns one immutable local snapshot for an ordered, task-bound reconcile batch. */
    fun recordsForReconcile(
        taskId: String,
        callIds: List<String>,
    ): Map<String, AttentionReconcileEntry> =
        database.runInTransaction<Map<String, AttentionReconcileEntry>> {
            val normalizedTaskId = normalizeUuid(taskId, "taskId")
            require(dao.task(normalizedTaskId) != null) { "Reconcile task is not durable" }
            require(callIds.isNotEmpty()) { "Reconcile batch is empty" }
            val normalized = callIds.map { normalizeUuid(it, "callId") }
            require(normalized.toSet().size == normalized.size) {
                "Reconcile batch repeats a callId"
            }
            buildMap {
                normalized.forEach { callId ->
                    val operation = dao.deviceOperation(callId) ?: return@forEach
                    require(operation.taskId == normalizedTaskId) {
                        "Reconcile call belongs to another task"
                    }
                    val attention = dao.pendingAttention(callId)
                    if (attention == null) {
                        put(callId, AttentionReconcileEntry.Corrupt)
                        return@forEach
                    }
                    try {
                        assertPair(operation, attention)
                        put(
                            callId,
                            AttentionReconcileEntry.Valid(AttentionLedgerRecord(operation, attention)),
                        )
                    } catch (_: IllegalArgumentException) {
                        put(callId, AttentionReconcileEntry.Corrupt)
                    } catch (_: AttentionLedgerConflictException) {
                        put(callId, AttentionReconcileEntry.Corrupt)
                    }
                }
            }
        }

    fun terminalOperationsReadyForDelivery(deviceId: String): List<DeviceOperationEntity> =
        database.runInTransaction<List<DeviceOperationEntity>> {
            val normalizedDeviceId = normalizeString(deviceId, "deviceId", 128)
            dao.terminalOperationsReadyForDelivery(normalizedDeviceId).onEach { operation ->
                require(operation.deviceId == normalizedDeviceId) {
                    "Ready terminal query crossed the owning device binding"
                }
                val attention = dao.pendingAttention(operation.callId)
                    ?: throw AttentionLedgerConflictException(
                        "Ready terminal is missing attention projection",
                    )
                assertPair(operation, attention)
            }
        }

    /**
     * Drops a successful content-read request after its one live socket delivery attempt.
     *
     * Raw file content is never written to this ledger. Deleting the operation also cascades to
     * the pending consent row and one-call content grant, so reconnect/restart can only request a
     * fresh Pi call and fresh user approval.
     */
    fun discardLiveContentRead(taskId: String, callId: String) {
        database.runInTransaction {
            val normalizedTaskId = normalizeUuid(taskId, "taskId")
            val normalizedCallId = normalizeUuid(callId, "callId")
            val operation = dao.deviceOperation(normalizedCallId)
                ?: return@runInTransaction
            require(operation.taskId == normalizedTaskId) {
                "Live content read belongs to another task"
            }
            require(operation.toolName == "device_files_read") {
                "Only content reads may use live-only delivery"
            }
            require(operation.terminalSha256 == null &&
                operation.terminalFrameCanonicalJson == null
            ) { "Live content read was persisted as a terminal" }
            check(dao.deleteDeviceOperation(normalizedTaskId, normalizedCallId) == 1) {
                "Live content read changed before disposal"
            }
        }
    }

    fun acceptRequest(
        request: AttentionRequestRecord,
        scope: AttentionAcceptanceScope,
    ): AttentionLedgerRecord =
        database.runInTransaction<AttentionLedgerRecord> {
            val normalizedScope = normalizeScope(scope)
            val normalized = normalizeRequest(request, normalizedScope)
            check(dao.task(normalized.taskId) != null) { "Attention request task is not durable" }
            val existing = dao.deviceOperation(normalized.callId)
            if (existing != null) {
                if (existing.requestSha256 != normalized.requestSha256) {
                    throw AttentionLedgerConflictException(
                        "Attention callId conflicts with immutable request binding",
                    )
                }
                val attention = dao.pendingAttention(normalized.callId)
                    ?: throw AttentionLedgerConflictException(
                        "Duplicate request is missing attention projection",
                    )
                assertPair(existing, attention)
                return@runInTransaction AttentionLedgerRecord(existing, attention)
            }
            val timestamp = nowMillis()
            val hostObservation = validatePriorHostObservation(normalized)
            val hostAlreadyTerminal = hostObservation in setOf("terminal", "reconciled")
            val systemTerminal = when {
                hostAlreadyTerminal -> null
                timestamp >= parseTimestampMillis(normalized.expiresAt) ->
                    systemTerminal(normalized, AttentionTerminalOrigin.EXPIRY)
                !normalized.productValid ->
                    systemTerminal(normalized, AttentionTerminalOrigin.FAILED_CLOSED)
                else -> null
            }
            val ledgerState = when {
                hostAlreadyTerminal -> AttentionLedgerState.RECEIVED
                systemTerminal?.origin == AttentionTerminalOrigin.EXPIRY ->
                    AttentionLedgerState.TIMED_OUT
                systemTerminal?.origin == AttentionTerminalOrigin.FAILED_CLOSED ->
                    AttentionLedgerState.FAILED_CLOSED
                else -> AttentionLedgerState.RECEIVED
            }
            val responseState = when {
                hostAlreadyTerminal -> AttentionResponseState.ALREADY_ANSWERED
                systemTerminal?.origin == AttentionTerminalOrigin.EXPIRY ->
                    AttentionResponseState.EXPIRED
                systemTerminal?.origin == AttentionTerminalOrigin.FAILED_CLOSED ->
                    AttentionResponseState.CANCELLED
                else -> AttentionResponseState.PENDING
            }
            val operation = DeviceOperationEntity(
                callId = normalized.callId,
                taskId = normalized.taskId,
                piToolCallId = normalized.piToolCallId,
                deviceId = normalized.deviceId,
                toolName = normalized.toolName,
                argumentsCanonicalJson = normalized.argumentsCanonicalJson,
                sideEffect = normalized.sideEffect,
                operationId = normalized.operationId,
                expiresAt = normalized.expiresAt,
                capabilityVersion = normalized.capabilityVersion,
                requestSha256 = normalized.requestSha256,
                ledgerState = ledgerState.name,
                terminalKind = systemTerminal?.frame?.terminal?.wireValue,
                terminalFrameCanonicalJson = systemTerminal?.frameJson,
                terminalSha256 = systemTerminal?.sha256,
                hostObservationState = hostObservation,
                cancelObservationReason = null,
                cancelObservedAtMillis = null,
                deliveryState = when {
                    hostAlreadyTerminal -> AttentionDeliveryState.HOST_TERMINAL_DURABLE.name
                    systemTerminal != null -> AttentionDeliveryState.READY_TO_SEND.name
                    else -> AttentionDeliveryState.NOT_READY.name
                },
                progressSequence = 0,
                receivedAtMillis = timestamp,
                updatedAtMillis = timestamp,
            )
            val attention = PendingAttentionEntity(
                callId = normalized.callId,
                taskId = normalized.taskId,
                toolName = normalized.toolName,
                originFocusKey = normalizedScope.originFocusKey,
                responseState = responseState.name,
                selectedOptionIndex = null,
                customAnswer = "",
                selectionStart = 0,
                selectionEnd = 0,
                validationCode = null,
                dismissedAtMillis = null,
                terminalDisplayJson = systemTerminal?.displayJson,
                updatedAtMillis = timestamp,
            )
            dao.insertDeviceOperation(operation)
            dao.insertPendingAttention(attention)
            AttentionLedgerRecord(operation, attention)
        }

    private fun validatePriorHostObservation(normalized: NormalizedRequest): String? {
        val deviceObservation = dao.hostDeviceCallObservation(
            normalized.taskId,
            normalized.callId,
        )
        deviceObservation?.let { observation ->
            require(observation.operationId == normalized.operationId) {
                "Host observation conflicts with the request operationId"
            }
            validateHostState(observation.hostState)
        }
        val pending = dao.hostPendingAttentionByCallId(normalized.taskId, normalized.callId)
        require(pending.size <= 1) { "Host snapshot repeats an attention callId" }
        pending.singleOrNull()?.let { observation ->
            observation.toolName?.let { require(it == normalized.toolName) {
                "Host attention tool conflicts with the request"
            } }
            observation.argumentsJson?.let { rawArguments ->
                require(canonicalJson(STRICT_JSON.parseToJsonElement(rawArguments)) ==
                    normalized.argumentsCanonicalJson
                ) { "Host attention arguments conflict with the request" }
            }
            observation.expiresAt?.let { require(it == normalized.expiresAt) {
                "Host attention expiry conflicts with the request"
            } }
        }
        return deviceObservation?.hostState
    }

    fun markAwaitingUser(callId: String): AttentionLedgerRecord = updatePair(callId) { operation, attention ->
        if (operation.terminalSha256 != null) return@updatePair operation to attention
        require(attention.responseState == AttentionResponseState.PENDING.name) {
            "Non-actionable attention cannot return to awaiting-user"
        }
        require(operation.ledgerState in setOf(
            AttentionLedgerState.RECEIVED.name,
            AttentionLedgerState.AWAITING_USER.name,
        )) { "Attention request cannot return to awaiting-user" }
        val timestamp = nowMillis()
        operation.copy(
            ledgerState = AttentionLedgerState.AWAITING_USER.name,
            progressSequence = maxOf(operation.progressSequence, 1),
            updatedAtMillis = timestamp,
        ) to attention.copy(
            responseState = AttentionResponseState.PENDING.name,
            updatedAtMillis = timestamp,
        )
    }

    fun saveDraft(
        taskId: String,
        callId: String,
        selectedOptionIndex: Int?,
        customAnswer: String,
        selectionStart: Int,
        selectionEnd: Int,
        validationCode: String? = null,
    ): AttentionLedgerRecord = updatePair(taskId, callId) { operation, attention ->
        require(operation.terminalSha256 == null) { "Terminal attention cannot change its draft" }
        require(attention.responseState in setOf(
            AttentionResponseState.PENDING.name,
            AttentionResponseState.VALIDATION_ERROR.name,
        )) { "Non-actionable attention draft is not editable" }
        require(operation.ledgerState in setOf(
            AttentionLedgerState.RECEIVED.name,
            AttentionLedgerState.AWAITING_USER.name,
        )) { "Attention draft is not editable" }
        require(customAnswer.length <= MAX_DURABLE_ANSWER_UTF16) { "Custom answer is too long" }
        require(selectionStart in 0..customAnswer.length && selectionEnd in selectionStart..customAnswer.length) {
            "Custom answer selection is outside UTF-16 bounds"
        }
        if (customAnswer.isNotEmpty()) {
            require(selectedOptionIndex == null) { "Custom answer and option selection are exclusive" }
        }
        selectedOptionIndex?.let { index ->
            require(index >= 0 && index < optionCount(operation.argumentsCanonicalJson)) {
                "Selected option index is outside the durable request"
            }
        }
        validateDraftSemantics(selectedOptionIndex, customAnswer, validationCode)
        val timestamp = nowMillis()
        operation.copy(
            ledgerState = AttentionLedgerState.AWAITING_USER.name,
            updatedAtMillis = timestamp,
        ) to attention.copy(
            responseState = if (validationCode == null) {
                AttentionResponseState.PENDING.name
            } else {
                AttentionResponseState.VALIDATION_ERROR.name
            },
            selectedOptionIndex = selectedOptionIndex,
            customAnswer = customAnswer,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            validationCode = validationCode,
            dismissedAtMillis = null,
            updatedAtMillis = timestamp,
        )
    }

    fun dismiss(taskId: String, callId: String): AttentionLedgerRecord =
        updatePair(taskId, callId) { operation, attention ->
            require(operation.terminalSha256 == null) {
                "Terminal attention cannot be dismissed as pending"
            }
            require(attention.responseState in setOf(
                AttentionResponseState.PENDING.name,
                AttentionResponseState.VALIDATION_ERROR.name,
            )) { "Responding attention cannot be dismissed" }
            val timestamp = nowMillis()
            operation to attention.copy(
                dismissedAtMillis = timestamp,
                updatedAtMillis = timestamp,
            )
        }

    fun recordTerminal(write: AttentionTerminalWrite): AttentionLedgerRecord =
        database.runInTransaction<AttentionLedgerRecord> {
            val frameBytes = DeviceClientFrameEncoder.encode(write.frame)
            val frameJson = frameBytes.decodeToString(throwOnInvalidSequence = true)
            val frameObject = STRICT_JSON.parseToJsonElement(frameJson).jsonObject
            val callId = normalizeUuid(write.frame.callId, "callId")
            val operation = dao.deviceOperation(callId)
                ?: throw AttentionLedgerConflictException("Terminal has no durable request")
            val attention = dao.pendingAttention(callId)
                ?: throw AttentionLedgerConflictException("Terminal has no attention projection")
            assertPair(operation, attention)
            require(write.frame.taskId == operation.taskId && write.frame.deviceId == operation.deviceId) {
                "Terminal binding differs from the durable request"
            }
            validateTerminalForOperation(operation, frameObject)
            validateTerminalOrigin(write)
            val terminalSha256 = sha256(frameBytes)
            if (operation.terminalSha256 != null) {
                if (write.origin == AttentionTerminalOrigin.HOST_CANCEL) {
                    val reason = requireCancelReason(write.cancelReason)
                    if (operation.cancelObservationReason != null &&
                        operation.cancelObservationReason != reason
                    ) {
                        throw AttentionLedgerConflictException(
                            "Attention callId has a conflicting cancel observation",
                        )
                    }
                }
                if (operation.terminalSha256 == terminalSha256 &&
                    operation.terminalFrameCanonicalJson == frameJson
                ) {
                    return@runInTransaction AttentionLedgerRecord(operation, attention)
                }
                if (write.origin !in setOf(
                        AttentionTerminalOrigin.USER,
                        AttentionTerminalOrigin.LOCAL_RECOVERY,
                    ) ||
                    operation.ledgerState in setOf(
                        AttentionLedgerState.CANCELLED.name,
                        AttentionLedgerState.TIMED_OUT.name,
                        AttentionLedgerState.FAILED_CLOSED.name,
                    )
                ) {
                    if (write.origin == AttentionTerminalOrigin.HOST_CANCEL) {
                        val reason = requireCancelReason(write.cancelReason)
                        if (operation.cancelObservationReason == reason) {
                            return@runInTransaction AttentionLedgerRecord(operation, attention)
                        }
                        if (operation.cancelObservationReason != null) {
                            throw AttentionLedgerConflictException(
                                "Attention callId has a conflicting cancel observation",
                            )
                        }
                        val updated = operation.copy(
                            cancelObservationReason = reason,
                            cancelObservedAtMillis = write.nowMillis,
                            updatedAtMillis = nowMillis(),
                        )
                        check(dao.updateDeviceOperation(updated) == 1)
                        return@runInTransaction AttentionLedgerRecord(updated, attention)
                    }
                    return@runInTransaction AttentionLedgerRecord(operation, attention)
                }
                throw AttentionLedgerConflictException("Attention callId has another terminal hash")
            }
            val expiresAtMillis = parseTimestampMillis(operation.expiresAt)
            when (write.origin) {
                AttentionTerminalOrigin.USER -> {
                    require(write.nowMillis < expiresAtMillis) { "Attention request has expired" }
                    require(dao.activeStopFenceCount(operation.taskId) == 0) {
                        "Task stop fence blocks attention response"
                    }
                }
                AttentionTerminalOrigin.AUTO_POLICY -> {
                    require(write.nowMillis < expiresAtMillis) { "Automatic action has expired" }
                    require(dao.activeStopFenceCount(operation.taskId) == 0) {
                        "Task stop fence blocks automatic action"
                    }
                    require(operation.toolName in setOf(
                        CONTENT_READ_TOOL,
                        FILE_COMMIT_TOOL,
                        UI_ACTION_TOOL,
                    )) {
                        "Automatic policy origin is only valid for policy-controlled actions"
                    }
                }
                AttentionTerminalOrigin.LOCAL_RECOVERY -> require(
                    operation.toolName == FILE_COMMIT_TOOL,
                ) { "Local terminal recovery is only valid for a durable file commit" }
                AttentionTerminalOrigin.EXPIRY -> {
                    require(write.nowMillis >= expiresAtMillis) { "Attention request has not expired" }
                }
                AttentionTerminalOrigin.HOST_CANCEL,
                AttentionTerminalOrigin.FAILED_CLOSED,
                -> Unit
            }
            val timestamp = nowMillis()
            val ledgerState = when (write.origin) {
                AttentionTerminalOrigin.USER,
                AttentionTerminalOrigin.AUTO_POLICY,
                AttentionTerminalOrigin.LOCAL_RECOVERY,
                -> AttentionLedgerState.TERMINAL
                AttentionTerminalOrigin.EXPIRY -> AttentionLedgerState.TIMED_OUT
                AttentionTerminalOrigin.HOST_CANCEL -> AttentionLedgerState.CANCELLED
                AttentionTerminalOrigin.FAILED_CLOSED -> AttentionLedgerState.FAILED_CLOSED
            }
            val responseState = when (write.origin) {
                AttentionTerminalOrigin.USER,
                AttentionTerminalOrigin.AUTO_POLICY,
                AttentionTerminalOrigin.LOCAL_RECOVERY,
                -> AttentionResponseState.RESPONDING
                AttentionTerminalOrigin.EXPIRY -> AttentionResponseState.EXPIRED
                AttentionTerminalOrigin.HOST_CANCEL -> AttentionResponseState.CANCELLED
                AttentionTerminalOrigin.FAILED_CLOSED -> AttentionResponseState.CANCELLED
            }
            val terminalDisplay = frameObject["result"] ?: frameObject["error"]
            val updatedOperation = operation.copy(
                ledgerState = ledgerState.name,
                terminalKind = write.frame.terminal.wireValue,
                terminalFrameCanonicalJson = frameJson,
                terminalSha256 = terminalSha256,
                cancelObservationReason = if (write.origin == AttentionTerminalOrigin.HOST_CANCEL) {
                    requireCancelReason(write.cancelReason)
                } else {
                    operation.cancelObservationReason
                },
                cancelObservedAtMillis = if (write.origin == AttentionTerminalOrigin.HOST_CANCEL) {
                    write.nowMillis
                } else {
                    operation.cancelObservedAtMillis
                },
                deliveryState = if (write.origin == AttentionTerminalOrigin.HOST_CANCEL) {
                    AttentionDeliveryState.NOT_READY.name
                } else {
                    AttentionDeliveryState.READY_TO_SEND.name
                },
                updatedAtMillis = timestamp,
            )
            val updatedAttention = attention.copy(
                responseState = responseState.name,
                validationCode = null,
                terminalDisplayJson = terminalDisplay?.toString(),
                updatedAtMillis = timestamp,
            )
            check(dao.updateDeviceOperation(updatedOperation) == 1)
            check(dao.updatePendingAttention(updatedAttention) == 1)
            AttentionLedgerRecord(updatedOperation, updatedAttention)
        }

    fun markSent(callId: String): AttentionLedgerRecord = advanceDelivery(
        callId,
        AttentionDeliveryState.SENT_UNCONFIRMED,
    )

    fun markHostTerminalDurable(callId: String): AttentionLedgerRecord = advanceDelivery(
        callId,
        AttentionDeliveryState.HOST_TERMINAL_DURABLE,
    )

    /**
     * Verified mutation seam used only by [RoomProjectionTransactionStore]. The caller must first
     * validate an exact Pi-native proof inside the same outer Room reliability transaction.
     */
    internal fun markPiDeliveredAfterVerifiedProof(
        taskId: String,
        callId: String,
        terminalSemanticSha256: String,
    ): AttentionLedgerRecord = updatePair(taskId, callId) { operation, attention ->
        val expectation = requireNotNull(piDeliveryExpectationForValidatedPair(operation)) {
            "Pi delivery proof requires a durable local terminal"
        }
        require(expectation.terminalSemanticSha256 == terminalSemanticSha256) {
            "Verified Pi delivery proof differs from the durable local terminal"
        }
        advanceDeliveryPair(operation, attention, AttentionDeliveryState.PI_DELIVERED)
    }

    /**
     * Closes a phone-local response that was durable but can no longer reach its destroyed Pi
     * execution. The immutable terminal is retained for audit, but it must never be replayed into a
     * future task run.
     */
    internal fun markLocalDeliveryAbandoned(
        callId: String,
        expectedDeviceId: String,
    ): AttentionLedgerRecord = updatePair(callId) { operation, attention ->
        require(operation.deviceId == expectedDeviceId) {
            "Only the owning phone-local runtime may abandon delivery"
        }
        require(operation.ledgerState in setOf(
            AttentionLedgerState.TERMINAL.name,
            AttentionLedgerState.TIMED_OUT.name,
            AttentionLedgerState.FAILED_CLOSED.name,
        )) {
            "Only a locally terminal request may abandon delivery"
        }
        require(operation.terminalSha256 != null) {
            "Local delivery abandonment requires a durable terminal"
        }
        require(operation.deliveryState in setOf(
            AttentionDeliveryState.READY_TO_SEND.name,
            AttentionDeliveryState.SENT_UNCONFIRMED.name,
            AttentionDeliveryState.ABANDONED.name,
        )) { "Local delivery is not abandonable" }
        if (operation.deliveryState == AttentionDeliveryState.ABANDONED.name) {
            return@updatePair operation to attention
        }
        val timestamp = nowMillis()
        operation.copy(
            deliveryState = AttentionDeliveryState.ABANDONED.name,
            updatedAtMillis = timestamp,
        ) to attention.copy(
            responseState = when (operation.ledgerState) {
                AttentionLedgerState.TIMED_OUT.name -> AttentionResponseState.EXPIRED.name
                else -> AttentionResponseState.CANCELLED.name
            },
            updatedAtMillis = timestamp,
        )
    }

    private fun advanceDelivery(
        callId: String,
        target: AttentionDeliveryState,
    ): AttentionLedgerRecord = updatePair(callId) { operation, attention ->
        advanceDeliveryPair(operation, attention, target)
    }

    private fun advanceDeliveryPair(
        operation: DeviceOperationEntity,
        attention: PendingAttentionEntity,
        target: AttentionDeliveryState,
    ): Pair<DeviceOperationEntity, PendingAttentionEntity> {
        require(operation.terminalSha256 != null) { "Delivery proof requires a durable terminal" }
        val current = parseDeliveryState(operation.deliveryState)
        require(target.ordinal >= current.ordinal) { "Delivery proof cannot move backwards" }
        if (target == current) return operation to attention
        val timestamp = nowMillis()
        val responseState = if (target == AttentionDeliveryState.PI_DELIVERED) {
            terminalResponseState(operation)
        } else {
            attention.responseState
        }
        return operation.copy(
            deliveryState = target.name,
            updatedAtMillis = timestamp,
        ) to attention.copy(
            responseState = responseState,
            updatedAtMillis = timestamp,
        )
    }

    private fun updatePair(
        taskId: String,
        callId: String,
        transform: (DeviceOperationEntity, PendingAttentionEntity) ->
            Pair<DeviceOperationEntity, PendingAttentionEntity>,
    ): AttentionLedgerRecord = updatePairExpected(taskId, callId, transform)

    private fun updatePair(
        callId: String,
        transform: (DeviceOperationEntity, PendingAttentionEntity) ->
            Pair<DeviceOperationEntity, PendingAttentionEntity>,
    ): AttentionLedgerRecord = updatePairExpected(null, callId, transform)

    private fun updatePairExpected(
        taskId: String?,
        callId: String,
        transform: (DeviceOperationEntity, PendingAttentionEntity) ->
            Pair<DeviceOperationEntity, PendingAttentionEntity>,
    ): AttentionLedgerRecord = database.runInTransaction<AttentionLedgerRecord> {
        val normalizedTaskId = taskId?.let { normalizeUuid(it, "taskId") }
        val normalized = normalizeUuid(callId, "callId")
        val currentOperation = dao.deviceOperation(normalized)
            ?: throw AttentionLedgerConflictException("Attention callId is unknown")
        if (normalizedTaskId != null && currentOperation.taskId != normalizedTaskId) {
            throw AttentionLedgerConflictException("Attention call belongs to another task")
        }
        val currentAttention = dao.pendingAttention(normalized)
            ?: throw AttentionLedgerConflictException("Attention projection is missing")
        assertPair(currentOperation, currentAttention)
        val (operation, attention) = transform(currentOperation, currentAttention)
        assertPair(operation, attention)
        if (operation != currentOperation) check(dao.updateDeviceOperation(operation) == 1)
        if (attention != currentAttention) check(dao.updatePendingAttention(attention) == 1)
        AttentionLedgerRecord(operation, attention)
    }

    private fun normalizeScope(scope: AttentionAcceptanceScope): NormalizedScope {
        val taskId = normalizeUuid(scope.taskId, "scope.taskId")
        val deviceId = normalizeString(scope.deviceId, "scope.deviceId", 128)
        require(scope.capabilityVersion == 1L) { "Scope capabilityVersion must be 1" }
        return NormalizedScope(
            taskId = taskId,
            deviceId = deviceId,
            capabilityVersion = scope.capabilityVersion,
            originFocusKey = normalizeString(scope.originFocusKey, "originFocusKey", 256),
        )
    }

    private fun normalizeRequest(
        request: AttentionRequestRecord,
        scope: NormalizedScope,
    ): NormalizedRequest {
        val callId = normalizeUuid(request.callId, "callId")
        val taskId = normalizeUuid(request.taskId, "taskId")
        val piToolCallId = normalizeString(request.piToolCallId, "piToolCallId", 256)
        val deviceId = normalizeString(request.deviceId, "deviceId", 128)
        require(taskId == scope.taskId) { "Attention request is outside the current task binding" }
        require(deviceId == scope.deviceId) { "Attention request is outside the current device binding" }
        require(request.capabilityVersion == scope.capabilityVersion) {
            "Attention request capabilityVersion differs from the current binding"
        }
        require(request.toolName.isNotEmpty() && request.toolName.length <= 128) {
            "toolName is outside the Wire string bound"
        }
        val operationId = request.operationId?.let { normalizeUuid(it, "operationId") }
        val expiresAt = normalizeTimestamp(request.expiresAt)
        val argumentsCanonicalJson = canonicalJson(request.arguments)
        val safeBinding = if (request.toolName in SIDE_EFFECT_TOOLS) {
            request.sideEffect && operationId != null
        } else {
            !request.sideEffect && operationId == null
        }
        val productValid = if (request.toolName !in ATTENTION_TOOLS || !safeBinding) {
            false
        } else {
            try {
                validateAttentionArguments(request.toolName, request.arguments)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        val binding = buildJsonObject {
            put("callId", callId)
            put("taskId", taskId)
            put("piToolCallId", piToolCallId)
            put("deviceId", deviceId)
            put("toolName", request.toolName)
            put("arguments", STRICT_JSON.parseToJsonElement(argumentsCanonicalJson))
            put("sideEffect", request.sideEffect)
            operationId?.let { put("operationId", it) }
            put("expiresAt", expiresAt)
            put("capabilityVersion", request.capabilityVersion)
        }
        return NormalizedRequest(
            callId,
            taskId,
            piToolCallId,
            deviceId,
            request.toolName,
            argumentsCanonicalJson,
            request.sideEffect,
            operationId,
            expiresAt,
            request.capabilityVersion,
            productValid,
            sha256(canonicalJson(binding).encodeToByteArray()),
        )
    }

    private fun systemTerminal(
        request: NormalizedRequest,
        origin: AttentionTerminalOrigin,
    ): StoredSystemTerminal {
        val terminal = when (origin) {
            AttentionTerminalOrigin.EXPIRY -> DeviceToolTerminalKind.TIMED_OUT
            AttentionTerminalOrigin.FAILED_CLOSED -> DeviceToolTerminalKind.FAILED
            else -> error("Only expiry and failed-closed are request-classification terminals")
        }
        val error = when (origin) {
            AttentionTerminalOrigin.EXPIRY -> if (request.toolName == CONTENT_READ_TOOL) {
                DeviceClientWireError(
                    "CONTENT_READ_EXPIRED",
                    "File content request expired",
                )
            } else {
                DeviceClientWireError(
                    "ATTENTION_EXPIRED",
                    "Attention request expired",
                )
            }
            AttentionTerminalOrigin.FAILED_CLOSED -> DeviceClientWireError(
                "UNSUPPORTED_DEVICE_CAPABILITY",
                "Device capability is unavailable",
            )
            else -> error("Only expiry and failed-closed are request-classification terminals")
        }
        val frame = DeviceToolResultClientFrame(
            callId = request.callId,
            taskId = request.taskId,
            deviceId = request.deviceId,
            terminal = terminal,
            error = error,
        )
        val bytes = DeviceClientFrameEncoder.encode(frame)
        val frameJson = bytes.decodeToString(throwOnInvalidSequence = true)
        val display = STRICT_JSON.parseToJsonElement(frameJson).jsonObject.getValue("error")
        return StoredSystemTerminal(
            origin = origin,
            frame = frame,
            frameJson = frameJson,
            sha256 = sha256(bytes),
            displayJson = display.toString(),
        )
    }

    private fun assertPair(operation: DeviceOperationEntity, attention: PendingAttentionEntity) {
        if (operation.callId != attention.callId ||
            operation.taskId != attention.taskId ||
            operation.toolName != attention.toolName
        ) {
            throw AttentionLedgerConflictException("Attention projection binding is corrupt")
        }
        require(normalizeUuid(operation.callId, "callId") == operation.callId)
        require(normalizeUuid(operation.taskId, "taskId") == operation.taskId)
        require(normalizeString(operation.piToolCallId, "piToolCallId", 256) == operation.piToolCallId)
        require(normalizeString(operation.deviceId, "deviceId", 128) == operation.deviceId)
        require(operation.toolName.isNotEmpty() && operation.toolName.length <= 128)
        operation.operationId?.let { require(normalizeUuid(it, "operationId") == it) }
        require(normalizeTimestamp(operation.expiresAt) == operation.expiresAt)
        require(operation.capabilityVersion == 1L)
        val arguments = STRICT_JSON.parseToJsonElement(operation.argumentsCanonicalJson)
        require(canonicalJson(arguments) == operation.argumentsCanonicalJson) {
            "Attention arguments are not canonical"
        }
        val requestBinding = buildJsonObject {
            put("callId", operation.callId)
            put("taskId", operation.taskId)
            put("piToolCallId", operation.piToolCallId)
            put("deviceId", operation.deviceId)
            put("toolName", operation.toolName)
            put("arguments", arguments)
            put("sideEffect", operation.sideEffect)
            operation.operationId?.let { put("operationId", it) }
            put("expiresAt", operation.expiresAt)
            put("capabilityVersion", operation.capabilityVersion)
        }
        require(operation.requestSha256 == sha256(
            canonicalJson(requestBinding).encodeToByteArray(),
        )) { "Attention request binding hash is corrupt" }
        val ledgerState = parseLedgerState(operation.ledgerState)
        val deliveryState = parseDeliveryState(operation.deliveryState)
        val responseState = parseResponseState(attention.responseState)
        operation.hostObservationState?.let(::validateHostState)
        require((operation.cancelObservationReason == null) ==
            (operation.cancelObservedAtMillis == null)
        ) { "Cancel observation columns are only partially durable" }
        operation.cancelObservationReason?.let(::requireCancelReason)
        require(operation.progressSequence >= 0L) { "Progress sequence is corrupt" }
        require(operation.receivedAtMillis >= 0L && operation.updatedAtMillis >= 0L) {
            "Attention timestamps are corrupt"
        }
        val terminalFields = listOf(
            operation.terminalKind,
            operation.terminalFrameCanonicalJson,
            operation.terminalSha256,
        )
        require(terminalFields.all { it == null } || terminalFields.all { it != null }) {
            "Terminal columns are only partially durable"
        }
        val terminalFrame = operation.terminalFrameCanonicalJson
        if (terminalFrame == null) {
            require(ledgerState in setOf(
                AttentionLedgerState.RECEIVED,
                AttentionLedgerState.AWAITING_USER,
            )) { "Non-terminal attention has a terminal ledger state" }
            val hostAlreadyTerminal = operation.hostObservationState in setOf("terminal", "reconciled")
            require(deliveryState == AttentionDeliveryState.NOT_READY ||
                (hostAlreadyTerminal && deliveryState == AttentionDeliveryState.HOST_TERMINAL_DURABLE)
            ) { "Non-terminal attention has invalid delivery proof" }
        } else {
            require(operation.terminalSha256 == sha256(terminalFrame.encodeToByteArray())) {
                "Terminal frame hash is corrupt"
            }
            val frame = STRICT_JSON.parseToJsonElement(terminalFrame).jsonObject
            require(frame.toString() == terminalFrame) { "Terminal frame is not canonical" }
            val terminal = frame.getValue("terminal").jsonPrimitive.content
            val expectedKeys = if (terminal == DeviceToolTerminalKind.SUCCEEDED.wireValue) {
                TERMINAL_BASE_KEYS + "result"
            } else {
                TERMINAL_BASE_KEYS + "error"
            }
            require(frame.keys == expectedKeys) { "Terminal frame fields differ" }
            require(frame.getValue("protocolVersion").jsonPrimitive.content == "1")
            require(frame.getValue("kind").jsonPrimitive.content == "device.tool.result")
            require(frame.getValue("callId").jsonPrimitive.content == operation.callId)
            require(frame.getValue("taskId").jsonPrimitive.content == operation.taskId)
            require(frame.getValue("deviceId").jsonPrimitive.content == operation.deviceId)
            require(operation.terminalKind == terminal) { "Terminal kind column is corrupt" }
            validateTerminalForOperation(operation, frame)
            when (ledgerState) {
                AttentionLedgerState.TERMINAL -> require(terminal in setOf(
                    DeviceToolTerminalKind.SUCCEEDED.wireValue,
                    DeviceToolTerminalKind.REJECTED.wireValue,
                    DeviceToolTerminalKind.FAILED.wireValue,
                )) { "User terminal ledger state is corrupt" }
                AttentionLedgerState.CANCELLED -> require(
                    terminal == DeviceToolTerminalKind.CANCELLED.wireValue,
                ) { "Cancelled ledger state is corrupt" }
                AttentionLedgerState.TIMED_OUT -> require(
                    terminal == DeviceToolTerminalKind.TIMED_OUT.wireValue,
                ) { "Timed-out ledger state is corrupt" }
                AttentionLedgerState.FAILED_CLOSED -> require(
                    terminal == DeviceToolTerminalKind.FAILED.wireValue,
                ) { "Failed-closed ledger state is corrupt" }
                AttentionLedgerState.RECEIVED,
                AttentionLedgerState.AWAITING_USER,
                -> throw AttentionLedgerConflictException(
                    "Terminal frame has a non-terminal ledger state",
                )
            }
            if (ledgerState == AttentionLedgerState.CANCELLED) {
                require(deliveryState in setOf(
                    AttentionDeliveryState.NOT_READY,
                    AttentionDeliveryState.PI_DELIVERED,
                )) {
                    "Host-cancel terminal has invalid delivery proof"
                }
            } else {
                require(deliveryState != AttentionDeliveryState.NOT_READY) {
                    "Local terminal is missing delivery intent"
                }
            }
        }
        require(normalizeString(attention.originFocusKey, "originFocusKey", 256) ==
            attention.originFocusKey
        ) { "Attention origin focus key is corrupt" }
        require(attention.updatedAtMillis >= 0L) { "Attention timestamp is corrupt" }
        require(attention.customAnswer.length <= MAX_DURABLE_ANSWER_UTF16) {
            "Attention draft exceeds the durable editor bound"
        }
        require(attention.selectionStart in 0..attention.customAnswer.length &&
            attention.selectionEnd in attention.selectionStart..attention.customAnswer.length
        ) { "Attention selection is outside UTF-16 bounds" }
        require(attention.customAnswer.isEmpty() || attention.selectedOptionIndex == null) {
            "Attention option and custom draft are both selected"
        }
        attention.selectedOptionIndex?.let { index ->
            require(operation.toolName == "request_user_question") {
                "Only a question can contain an option selection"
            }
            require(index in 0 until optionCount(operation.argumentsCanonicalJson)) {
                "Attention option selection is outside the durable request"
            }
        }
        attention.validationCode?.let { code ->
            require(code in ATTENTION_VALIDATION_CODES) { "Attention validation code is corrupt" }
        }
        if (responseState in setOf(
            AttentionResponseState.PENDING,
            AttentionResponseState.VALIDATION_ERROR,
        )) {
            validateDraftSemantics(
                attention.selectedOptionIndex,
                attention.customAnswer,
                attention.validationCode,
            )
        }
        require((responseState == AttentionResponseState.VALIDATION_ERROR) ==
            (attention.validationCode != null)
        ) { "Attention validation state and code differ" }
        if (ledgerState != AttentionLedgerState.FAILED_CLOSED) {
            require(operation.toolName in ATTENTION_TOOLS) {
                "Actionable attention request tool is unsafe"
            }
            if (operation.toolName in SIDE_EFFECT_TOOLS) {
                require(operation.sideEffect && operation.operationId != null) {
                    "Side-effect attention binding is unsafe"
                }
            } else {
                require(!operation.sideEffect && operation.operationId == null) {
                    "Attention request binding is unsafe"
                }
            }
            validateAttentionArguments(operation.toolName, arguments)
        }
        if (operation.toolName != "request_user_question") {
            require(attention.selectedOptionIndex == null &&
                attention.customAnswer.isEmpty() &&
                attention.selectionStart == 0 &&
                attention.selectionEnd == 0 &&
                attention.validationCode == null
            ) { "Non-question attention cannot contain an answer draft" }
        }
        val expectedResponseState = when (ledgerState) {
            AttentionLedgerState.RECEIVED,
            AttentionLedgerState.AWAITING_USER,
            -> if (operation.hostObservationState in setOf("terminal", "reconciled")) {
                AttentionResponseState.ALREADY_ANSWERED
            } else if (attention.validationCode != null) {
                AttentionResponseState.VALIDATION_ERROR
            } else {
                AttentionResponseState.PENDING
            }
            AttentionLedgerState.TERMINAL -> when (deliveryState) {
                AttentionDeliveryState.PI_DELIVERED ->
                    AttentionResponseState.valueOf(terminalResponseState(operation))
                AttentionDeliveryState.ABANDONED -> AttentionResponseState.CANCELLED
                else -> AttentionResponseState.RESPONDING
            }
            AttentionLedgerState.CANCELLED,
            AttentionLedgerState.FAILED_CLOSED,
            -> AttentionResponseState.CANCELLED
            AttentionLedgerState.TIMED_OUT -> AttentionResponseState.EXPIRED
        }
        require(responseState == expectedResponseState) {
            "Attention response state differs from durable ledger proof"
        }
        val expectedDisplay = terminalFrame?.let { raw ->
            val frame = STRICT_JSON.parseToJsonElement(raw).jsonObject
            (frame["result"] ?: frame["error"])?.toString()
        }
        require(attention.terminalDisplayJson == expectedDisplay) {
            "Attention terminal display differs from the durable terminal"
        }
    }

    /** Pure validation seam shared by Room-backed UI read models. */
    internal fun validatePair(
        operation: DeviceOperationEntity,
        attention: PendingAttentionEntity,
    ) {
        assertPair(operation, attention)
    }

    /** Derives the Host-frozen semantic hash and text payload from an already validated pair. */
    internal fun piDeliveryExpectationForValidatedPair(
        operation: DeviceOperationEntity,
    ): AttentionPiDeliveryExpectation? {
        val terminalFrame = operation.terminalFrameCanonicalJson ?: return null
        val frame = STRICT_JSON.parseToJsonElement(terminalFrame).jsonObject
        val terminal = frame.getValue("terminal").jsonPrimitive.content
        val isError = terminal != DeviceToolTerminalKind.SUCCEEDED.wireValue
        val semantic = buildJsonObject {
            put("terminal", terminal)
            if (isError) put("error", frame.getValue("error"))
            else put("result", frame.getValue("result"))
        }
        val contentPayload = if (isError) {
            val error = frame.getValue("error").jsonObject
            buildJsonObject {
                put("code", error.getValue("code"))
                put("message", error.getValue("message"))
            }
        } else {
            frame.getValue("result")
        }
        val recoveryState = when (terminal) {
            DeviceToolTerminalKind.SUCCEEDED.wireValue -> "succeeded"
            DeviceToolTerminalKind.CANCELLED.wireValue -> "cancelled"
            DeviceToolTerminalKind.REJECTED.wireValue,
            DeviceToolTerminalKind.TIMED_OUT.wireValue,
            DeviceToolTerminalKind.FAILED.wireValue,
            -> "failed"
            else -> throw AttentionLedgerConflictException("Durable terminal kind is invalid")
        }
        return AttentionPiDeliveryExpectation(
            terminalSemanticSha256 = sha256(canonicalJson(semantic).encodeToByteArray()),
            contentPayload = contentPayload,
            isError = isError,
            recoveryState = recoveryState,
        )
    }

    private fun terminalResponseState(operation: DeviceOperationEntity): String = when (
        operation.terminalKind
    ) {
        DeviceToolTerminalKind.SUCCEEDED.wireValue -> {
            val frame = STRICT_JSON.parseToJsonElement(
                requireNotNull(operation.terminalFrameCanonicalJson),
            ).jsonObject
            val outcome = (frame["result"] as? JsonObject)
                ?.get("outcome")?.jsonPrimitive?.contentOrNull
            if (outcome == "skipped") AttentionResponseState.SKIPPED.name
            else AttentionResponseState.RESOLVED.name
        }
        DeviceToolTerminalKind.REJECTED.wireValue -> AttentionResponseState.REJECTED.name
        DeviceToolTerminalKind.TIMED_OUT.wireValue -> AttentionResponseState.EXPIRED.name
        DeviceToolTerminalKind.CANCELLED.wireValue,
        DeviceToolTerminalKind.FAILED.wireValue,
        -> AttentionResponseState.CANCELLED.name
        else -> throw AttentionLedgerConflictException("Durable terminal kind is invalid")
    }

    private data class NormalizedRequest(
        val callId: String,
        val taskId: String,
        val piToolCallId: String,
        val deviceId: String,
        val toolName: String,
        val argumentsCanonicalJson: String,
        val sideEffect: Boolean,
        val operationId: String?,
        val expiresAt: String,
        val capabilityVersion: Long,
        val productValid: Boolean,
        val requestSha256: String,
    )

    private data class NormalizedScope(
        val taskId: String,
        val deviceId: String,
        val capabilityVersion: Long,
        val originFocusKey: String,
    )

    private data class StoredSystemTerminal(
        val origin: AttentionTerminalOrigin,
        val frame: DeviceToolResultClientFrame,
        val frameJson: String,
        val sha256: String,
        val displayJson: String,
    )

    private companion object {
        const val MAX_CUSTOM_ANSWER_UTF16 = 4_096
        const val MAX_DURABLE_ANSWER_UTF16 = 8_192
        const val CONTENT_READ_TOOL = "device_files_read"
        const val FILE_COMMIT_TOOL = "device_files_commit_changes"
        const val MEDIA_LIST_TOOL = "device_media_list"
        const val UI_ACTION_TOOL = "device_ui_action"
        val SIDE_EFFECT_TOOLS = setOf(FILE_COMMIT_TOOL, UI_ACTION_TOOL)
        val ATTENTION_TOOLS = setOf(
            "request_user_question",
            "request_user_confirmation",
            CONTENT_READ_TOOL,
            FILE_COMMIT_TOOL,
            MEDIA_LIST_TOOL,
            UI_ACTION_TOOL,
        )
        val ATTENTION_VALIDATION_CODES = setOf("ANSWER_REQUIRED", "ANSWER_TOO_LONG")
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
        val RFC3339_PATTERN = Regex(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{3})?(Z|[+-]\\d{2}:\\d{2})$",
        )
        val TERMINAL_BASE_KEYS = setOf(
            "protocolVersion",
            "kind",
            "callId",
            "taskId",
            "deviceId",
            "terminal",
        )
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        fun normalizeUuid(value: String, field: String): String {
            val normalized = normalizeString(value, field, 36)
            require(UUID_PATTERN.matches(normalized)) { "$field is not a UUID" }
            return normalized.lowercase()
        }

        fun normalizeString(value: String, field: String, maximum: Int): String {
            val normalized = value.trim()
            require(normalized.isNotEmpty() && normalized.length <= maximum) { "$field is invalid" }
            return normalized
        }

        fun normalizeTimestamp(value: String): String {
            val normalized = normalizeString(value, "expiresAt", 64)
            require(RFC3339_PATTERN.matches(normalized)) { "expiresAt is not RFC3339" }
            try {
                OffsetDateTime.parse(normalized)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("expiresAt is not RFC3339", error)
            }
            return normalized
        }

        fun parseTimestampMillis(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

        fun validateAttentionArguments(toolName: String, value: JsonElement) {
            val objectValue = value as? JsonObject
                ?: throw IllegalArgumentException("Attention arguments must be an object")
            if (toolName == "request_user_question") {
                requireExactKeys(objectValue, setOf("question"), setOf("options"))
                requireSizedString(objectValue.getValue("question"), "question", 1, 4_096, false)
                val options = objectValue["options"] ?: return
                val array = options as? JsonArray
                    ?: throw IllegalArgumentException("options must be an array")
                require(array.size in 1..10) { "options must contain 1..10 items" }
                val labels = mutableSetOf<String>()
                var recommended = 0
                array.forEachIndexed { index, element ->
                    val option = element as? JsonObject
                        ?: throw IllegalArgumentException("options[$index] must be an object")
                    requireExactKeys(
                        option,
                        setOf("label"),
                        setOf("description", "recommended"),
                    )
                    val label = requireSizedString(
                        option.getValue("label"),
                        "options[$index].label",
                        1,
                        256,
                        true,
                    )
                    require(labels.add(label)) { "Option labels must be unique" }
                    option["description"]?.let { description ->
                        requireSizedString(description, "options[$index].description", 1, 1_024, false)
                    }
                    option["recommended"]?.let { recommendedValue ->
                        require(recommendedValue.jsonPrimitive.booleanOrNull != null) {
                            "options[$index].recommended must be boolean"
                        }
                        if (recommendedValue.jsonPrimitive.booleanOrNull == true) recommended += 1
                    }
                }
                require(recommended <= 1) { "At most one option may be recommended" }
                return
            }
            if (toolName == CONTENT_READ_TOOL) {
                validateContentReadArguments(objectValue)
                return
            }
            if (toolName == FILE_COMMIT_TOOL) {
                requireExactKeys(
                    objectValue,
                    setOf("preparedId", "planDigest"),
                    emptySet(),
                )
                normalizeUuid(
                    requireSizedString(
                        objectValue.getValue("preparedId"),
                        "preparedId",
                        36,
                        36,
                        true,
                    ),
                    "preparedId",
                )
                val digest = requireSizedString(
                    objectValue.getValue("planDigest"),
                    "planDigest",
                    64,
                    64,
                    true,
                )
                require(SHA256_PATTERN.matches(digest)) { "planDigest is invalid" }
                return
            }
            if (toolName == MEDIA_LIST_TOOL) {
                requireExactKeys(objectValue, setOf("purpose"), setOf("limit"))
                requireSizedString(objectValue.getValue("purpose"), "purpose", 1, 512, true)
                objectValue["limit"]?.let { limit ->
                    require(limit.jsonPrimitive.intOrNull in 1..20) { "limit is outside media policy" }
                }
                return
            }
            if (toolName == UI_ACTION_TOOL) {
                validateUiActionArguments(objectValue)
                return
            }
            requireExactKeys(objectValue, setOf("summary"), setOf("details"))
            requireSizedString(objectValue.getValue("summary"), "summary", 1, 4_096, false)
            objectValue["details"]?.let { details ->
                requireSizedString(details, "details", 1, 8_192, false)
            }
        }

        fun validateContentReadArguments(value: JsonObject) {
            requireExactKeys(
                value,
                setOf("grantId", "purpose", "documents", "totalMaxBytes"),
                emptySet(),
            )
            normalizeUuid(
                requireSizedString(value.getValue("grantId"), "grantId", 36, 36, true),
                "grantId",
            )
            requireSizedString(value.getValue("purpose"), "purpose", 1, 1_024, true)
            val documents = value["documents"] as? JsonArray
                ?: throw IllegalArgumentException("documents must be an array")
            require(documents.size in 1..16) { "documents must contain 1..16 items" }
            val aliases = linkedSetOf<String>()
            documents.forEachIndexed { index, documentValue ->
                val document = documentValue as? JsonObject
                    ?: throw IllegalArgumentException("documents[$index] must be an object")
                requireExactKeys(
                    document,
                    setOf("alias", "expectedMimeType", "maxBytes"),
                    emptySet(),
                )
                val alias = requireSizedString(
                    document.getValue("alias"),
                    "documents[$index].alias",
                    28,
                    28,
                    true,
                )
                require(DOCUMENT_ALIAS.matches(alias)) { "Document alias is invalid" }
                require(aliases.add(alias)) { "Document aliases must be unique" }
                requireSizedString(
                    document.getValue("expectedMimeType"),
                    "documents[$index].expectedMimeType",
                    1,
                    128,
                    true,
                )
                val maxBytes = document["maxBytes"]?.jsonPrimitive?.longOrNull
                    ?: throw IllegalArgumentException("maxBytes must be an integer")
                require(maxBytes in 1..MAX_PER_FILE_CONTENT_BYTES) {
                    "maxBytes is outside content policy"
                }
            }
            val total = value["totalMaxBytes"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("totalMaxBytes must be an integer")
            require(total in 1..MAX_TOTAL_CONTENT_BYTES) {
                "totalMaxBytes is outside content policy"
            }
        }

        fun validateUiActionArguments(value: JsonObject) {
            requireExactKeys(
                value,
                setOf("snapshotId", "action"),
                setOf(
                    "nodeHandle",
                    "text",
                    "direction",
                    "approvalSummary",
                    "approvalDetails",
                ),
            )
            val snapshotId = requireSizedString(
                value.getValue("snapshotId"),
                "snapshotId",
                35,
                35,
                true,
            )
            require(UI_SNAPSHOT_ID.matches(snapshotId)) { "snapshotId is invalid" }
            val action = requireSizedString(value.getValue("action"), "action", 4, 11, true)
            require(action in setOf("click", "scroll", "input_draft", "back")) {
                "action is invalid"
            }
            val nodeHandle = value["nodeHandle"]?.let {
                requireSizedString(it, "nodeHandle", 38, 320, true)
            }
            val text = value["text"]?.let {
                requireSizedString(it, "text", 1, 4_096, false)
            }
            val direction = value["direction"]?.let {
                requireSizedString(it, "direction", 2, 5, true)
            }
            value["approvalSummary"]?.let {
                requireSizedString(it, "approvalSummary", 1, 4_096, false)
            }
            value["approvalDetails"]?.let {
                requireSizedString(it, "approvalDetails", 1, 8_192, false)
            }
            when (action) {
                "click" -> require(
                    nodeHandle?.startsWith("$snapshotId:n") == true &&
                        text == null &&
                        direction == null,
                )
                "scroll" -> require(
                    nodeHandle?.startsWith("$snapshotId:n") == true &&
                        text == null &&
                        direction in setOf("up", "down", "left", "right"),
                )
                "input_draft" -> require(
                    nodeHandle?.startsWith("$snapshotId:n") == true &&
                        text != null &&
                        direction == null,
                )
                "back" -> require(nodeHandle == null && text == null && direction == null)
            }
        }

        fun requireExactKeys(
            value: JsonObject,
            required: Set<String>,
            optional: Set<String>,
        ) {
            require(value.keys == required + optional.intersect(value.keys)) {
                "Attention argument fields differ from the contract"
            }
            require(required.all(value::containsKey)) { "Attention argument is missing a field" }
        }

        fun requireSizedString(
            value: JsonElement,
            field: String,
            minimum: Int,
            maximum: Int,
            rejectBlank: Boolean,
        ): String {
            val primitive = value as? JsonPrimitive
            require(primitive?.isString == true) { "$field must be a string" }
            val result = primitive!!.content
            require(result.length in minimum..maximum && (!rejectBlank || result.isNotBlank())) {
                "$field is outside UTF-16 bounds"
            }
            return result
        }

        fun optionCount(argumentsCanonicalJson: String): Int {
            val objectValue = STRICT_JSON.parseToJsonElement(argumentsCanonicalJson).jsonObject
            return (objectValue["options"] as? JsonArray)?.size ?: 0
        }

        fun validateDraftSemantics(
            selectedOptionIndex: Int?,
            customAnswer: String,
            validationCode: String?,
        ) {
            require(validationCode == null || validationCode in ATTENTION_VALIDATION_CODES) {
                "Validation code is invalid"
            }
            when (validationCode) {
                "ANSWER_REQUIRED" -> require(
                    selectedOptionIndex == null && customAnswer.isBlank(),
                ) { "ANSWER_REQUIRED differs from the durable draft" }
                "ANSWER_TOO_LONG" -> require(
                    selectedOptionIndex == null &&
                        customAnswer.length in (MAX_CUSTOM_ANSWER_UTF16 + 1)..MAX_DURABLE_ANSWER_UTF16,
                ) { "ANSWER_TOO_LONG differs from the durable draft" }
                null -> require(customAnswer.length <= MAX_CUSTOM_ANSWER_UTF16) {
                    "Over-limit durable draft requires ANSWER_TOO_LONG"
                }
            }
        }

        fun validateTerminalForOperation(
            operation: DeviceOperationEntity,
            frame: JsonObject,
        ) {
            val terminal = frame.getValue("terminal").jsonPrimitive.content
            if (terminal == DeviceToolTerminalKind.SUCCEEDED.wireValue) {
                val result = frame["result"] as? JsonObject
                    ?: throw IllegalArgumentException("Succeeded attention requires object result")
                if (operation.toolName == CONTENT_READ_TOOL) {
                    validateContentReadResult(operation, result)
                    return
                }
                if (operation.toolName == FILE_COMMIT_TOOL) {
                    validateFileCommitResult(operation, result)
                    return
                }
                if (operation.toolName == MEDIA_LIST_TOOL) {
                    validateMediaListResult(result)
                    return
                }
                if (operation.toolName == UI_ACTION_TOOL) {
                    validateUiActionResult(operation, result)
                    return
                }
                if (operation.toolName == "request_user_question") {
                    val outcome = result["outcome"]?.jsonPrimitive?.contentOrNull
                    if (outcome == "skipped") {
                        requireExactKeys(result, setOf("outcome"), emptySet())
                        return
                    }
                    require(outcome == "answered") { "Question outcome must be answered or skipped" }
                    requireExactKeys(result, setOf("outcome", "answer"), emptySet())
                    val answer = result["answer"] as? JsonObject
                        ?: throw IllegalArgumentException("Question answer must be an object")
                    val kind = answer["kind"]?.jsonPrimitive?.contentOrNull
                    if (kind == "custom") {
                        requireExactKeys(answer, setOf("kind", "text"), emptySet())
                        requireSizedString(answer.getValue("text"), "answer.text", 1, 4_096, true)
                        return
                    }
                    require(kind == "option") { "Question answer kind is invalid" }
                    requireExactKeys(answer, setOf("kind", "index", "label"), emptySet())
                    val index = answer["index"]?.jsonPrimitive?.intOrNull
                        ?: throw IllegalArgumentException("Option index must be an integer")
                    val label = requireSizedString(
                        answer.getValue("label"),
                        "answer.label",
                        1,
                        256,
                        true,
                    )
                    val arguments = STRICT_JSON.parseToJsonElement(
                        operation.argumentsCanonicalJson,
                    ).jsonObject
                    val options = arguments["options"] as? JsonArray
                        ?: throw IllegalArgumentException("Option answer has no durable options")
                    val selected = options.getOrNull(index) as? JsonObject
                    require(selected?.get("label")?.jsonPrimitive?.contentOrNull == label) {
                        "Option answer differs from the durable request"
                    }
                    return
                }
                requireExactKeys(result, setOf("outcome"), emptySet())
                require(result["outcome"]?.jsonPrimitive?.contentOrNull == "confirmed") {
                    "Confirmation outcome must be confirmed"
                }
                return
            }

            val error = frame["error"] as? JsonObject
                ?: throw IllegalArgumentException("Non-success attention requires object error")
            if (operation.toolName == CONTENT_READ_TOOL) {
                validateContentReadError(terminal, error)
                return
            }
            if (operation.toolName == FILE_COMMIT_TOOL) {
                validateFileCommitError(terminal, error)
                return
            }
            if (operation.toolName == MEDIA_LIST_TOOL) {
                validateMediaListError(terminal, error)
                return
            }
            if (operation.toolName == UI_ACTION_TOOL) {
                validateUiActionError(terminal, error)
                return
            }
            val expected = when (terminal) {
                DeviceToolTerminalKind.REJECTED.wireValue -> {
                    require(operation.toolName == "request_user_confirmation") {
                        "Question cannot use rejected terminal"
                    }
                    "USER_DECLINED" to "User declined the confirmation"
                }
                DeviceToolTerminalKind.TIMED_OUT.wireValue ->
                    "ATTENTION_EXPIRED" to "Attention request expired"
                DeviceToolTerminalKind.CANCELLED.wireValue ->
                    "ATTENTION_CANCELLED" to "Attention request was cancelled"
                DeviceToolTerminalKind.FAILED.wireValue ->
                    "UNSUPPORTED_DEVICE_CAPABILITY" to "Device capability is unavailable"
                else -> throw IllegalArgumentException("Attention terminal kind is invalid")
            }
            require(error["code"]?.jsonPrimitive?.contentOrNull == expected.first &&
                error["message"]?.jsonPrimitive?.contentOrNull == expected.second &&
                error["retryable"]?.jsonPrimitive?.booleanOrNull == false
            ) { "Attention error differs from the fixed contract" }
        }

        fun validateMediaListResult(result: JsonObject) {
            requireExactKeys(result, setOf("access", "limit", "returnedCount", "items"), emptySet())
            require(result["access"]?.jsonPrimitive?.contentOrNull in setOf("full", "partial")) {
                "Media access scope is invalid"
            }
            val limit = result["limit"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("Media limit must be an integer")
            require(limit in 1..20) { "Media limit is outside policy" }
            val items = result["items"] as? JsonArray
                ?: throw IllegalArgumentException("Media items must be an array")
            val returnedCount = result["returnedCount"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("Media returnedCount must be an integer")
            require(returnedCount == items.size && items.size <= limit) {
                "Media item count differs from the bounded result"
            }
            items.forEachIndexed { index, value ->
                val item = value as? JsonObject
                    ?: throw IllegalArgumentException("Media item must be an object")
                requireExactKeys(
                    item,
                    setOf("index", "mimeType"),
                    setOf("byteCount", "width", "height", "capturedAtMillis", "addedAtMillis"),
                )
                require(item["index"]?.jsonPrimitive?.intOrNull == index + 1) {
                    "Media item index is invalid"
                }
                requireSizedString(item.getValue("mimeType"), "mimeType", 6, 128, true)
                    .also { require(it.startsWith("image/")) { "Media mimeType is not an image" } }
                item["byteCount"]?.let { require(it.jsonPrimitive.longOrNull?.let { n -> n >= 0L } == true) }
                item["width"]?.let { require(it.jsonPrimitive.intOrNull?.let { n -> n > 0 } == true) }
                item["height"]?.let { require(it.jsonPrimitive.intOrNull?.let { n -> n > 0 } == true) }
                item["capturedAtMillis"]?.let { require(it.jsonPrimitive.longOrNull?.let { n -> n > 0L } == true) }
                item["addedAtMillis"]?.let { require(it.jsonPrimitive.longOrNull?.let { n -> n > 0L } == true) }
            }
        }

        fun validateUiActionResult(
            operation: DeviceOperationEntity,
            result: JsonObject,
        ) {
            requireExactKeys(
                result,
                setOf(
                    "ok",
                    "action",
                    "beforeSnapshotId",
                    "afterSnapshotId",
                    "foregroundPackage",
                    "targetChanged",
                    "changed",
                    "noChangeCount",
                    "sessionPaused",
                    "actionCount",
                ),
                emptySet(),
            )
            require(result["ok"]?.jsonPrimitive?.booleanOrNull == true)
            val request = STRICT_JSON.parseToJsonElement(
                operation.argumentsCanonicalJson,
            ).jsonObject
            require(result.getValue("action") == request.getValue("action")) {
                "UI action result changed action"
            }
            require(result.getValue("beforeSnapshotId") == request.getValue("snapshotId")) {
                "UI action result changed snapshot binding"
            }
            require(
                UI_SNAPSHOT_ID.matches(
                    requireSizedString(
                        result.getValue("afterSnapshotId"),
                        "afterSnapshotId",
                        35,
                        35,
                        true,
                    ),
                ),
            )
            requireSizedString(
                result.getValue("foregroundPackage"),
                "foregroundPackage",
                1,
                255,
                true,
            )
            require(result["targetChanged"]?.jsonPrimitive?.booleanOrNull != null)
            require(result["changed"]?.jsonPrimitive?.booleanOrNull != null)
            require(result["sessionPaused"]?.jsonPrimitive?.booleanOrNull != null)
            require(result["noChangeCount"]?.jsonPrimitive?.intOrNull in 0..3)
            require(result["actionCount"]?.jsonPrimitive?.intOrNull in 1..20)
        }

        fun validateUiActionError(terminal: String, error: JsonObject) {
            val code = error["code"]?.jsonPrimitive?.contentOrNull
            val message = error["message"]?.jsonPrimitive?.contentOrNull
            require(error["retryable"]?.jsonPrimitive?.booleanOrNull == false)
            val allowed = when (terminal) {
                DeviceToolTerminalKind.REJECTED.wireValue -> setOf("USER_DECLINED")
                DeviceToolTerminalKind.TIMED_OUT.wireValue -> setOf("ATTENTION_EXPIRED")
                DeviceToolTerminalKind.CANCELLED.wireValue ->
                    setOf("ATTENTION_CANCELLED", "UI_ACTION_CANCELLED")
                DeviceToolTerminalKind.FAILED.wireValue -> UI_ACTION_FAILURE_CODES
                else -> throw IllegalArgumentException("UI action terminal kind is invalid")
            }
            require(code in allowed && !message.isNullOrBlank() && message.length <= 512) {
                "UI action error differs from the fixed contract"
            }
        }

        fun validateMediaListError(terminal: String, error: JsonObject) {
            val expected = when (terminal) {
                DeviceToolTerminalKind.REJECTED.wireValue ->
                    "USER_DECLINED" to "User declined the confirmation"
                DeviceToolTerminalKind.TIMED_OUT.wireValue ->
                    "ATTENTION_EXPIRED" to "Attention request expired"
                DeviceToolTerminalKind.CANCELLED.wireValue ->
                    "ATTENTION_CANCELLED" to "Attention request was cancelled"
                DeviceToolTerminalKind.FAILED.wireValue -> when (
                    error["code"]?.jsonPrimitive?.contentOrNull
                ) {
                    "PHOTO_LIBRARY_PERMISSION_REQUIRED" ->
                        "PHOTO_LIBRARY_PERMISSION_REQUIRED" to "Enable photo-library access in Device capabilities"
                    "DEVICE_TOOL_TIMEOUT" ->
                        "DEVICE_TOOL_TIMEOUT" to "Photo-library lookup timed out"
                    "PHOTO_LIBRARY_UNAVAILABLE" ->
                        "PHOTO_LIBRARY_UNAVAILABLE" to "Photo-library metadata is temporarily unavailable"
                    else -> throw IllegalArgumentException("Media error code is invalid")
                }
                else -> throw IllegalArgumentException("Media terminal kind is invalid")
            }
            require(
                error["code"]?.jsonPrimitive?.contentOrNull == expected.first &&
                    error["message"]?.jsonPrimitive?.contentOrNull == expected.second &&
                    error["retryable"]?.jsonPrimitive?.booleanOrNull == false
            ) { "Media error differs from the fixed contract" }
        }

        fun validateContentReadResult(
            operation: DeviceOperationEntity,
            result: JsonObject,
        ) {
            requireExactKeys(
                result,
                setOf("grantId", "documents", "totalBytes"),
                emptySet(),
            )
            val request = STRICT_JSON.parseToJsonElement(
                operation.argumentsCanonicalJson,
            ).jsonObject
            require(result.getValue("grantId") == request.getValue("grantId")) {
                "Content-read grant binding changed"
            }
            val requested = request.getValue("documents") as JsonArray
            val returned = result["documents"] as? JsonArray
                ?: throw IllegalArgumentException("Content-read result documents must be an array")
            require(returned.size == requested.size) {
                "Content-read result document count changed"
            }
            var totalBytes = 0L
            returned.forEachIndexed { index, returnedValue ->
                val expected = requested[index].jsonObject
                val document = returnedValue as? JsonObject
                    ?: throw IllegalArgumentException("Content-read result document is invalid")
                requireExactKeys(
                    document,
                    setOf("alias", "mimeType", "byteCount", "content"),
                    emptySet(),
                )
                require(document.getValue("alias") == expected.getValue("alias") &&
                    document.getValue("mimeType") == expected.getValue("expectedMimeType")
                ) { "Content-read alias or MIME binding changed" }
                val content = requireSizedString(
                    document.getValue("content"),
                    "documents[$index].content",
                    0,
                    MAX_PER_FILE_CONTENT_BYTES.toInt(),
                    false,
                )
                val byteCount = document["byteCount"]?.jsonPrimitive?.longOrNull
                    ?: throw IllegalArgumentException("Content-read byteCount is invalid")
                val actualBytes = content.encodeToByteArray().size.toLong()
                val approved = expected["maxBytes"]?.jsonPrimitive?.longOrNull
                    ?: throw IllegalArgumentException("Content-read request budget is invalid")
                require(byteCount == actualBytes && actualBytes <= approved) {
                    "Content-read per-file byte budget changed"
                }
                totalBytes += actualBytes
            }
            val resultTotal = result["totalBytes"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("Content-read totalBytes is invalid")
            val approvedTotal = request["totalMaxBytes"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("Content-read total budget is invalid")
            require(resultTotal == totalBytes && totalBytes <= approvedTotal) {
                "Content-read total byte budget changed"
            }
        }

        fun validateFileCommitResult(
            operation: DeviceOperationEntity,
            result: JsonObject,
        ) {
            requireExactKeys(
                result,
                setOf(
                    "preparedId",
                    "planDigest",
                    "outcome",
                    "appliedCount",
                    "approvalReceiptId",
                    "results",
                ),
                emptySet(),
            )
            val arguments = STRICT_JSON.parseToJsonElement(
                operation.argumentsCanonicalJson,
            ).jsonObject
            require(
                result.getValue("preparedId").jsonPrimitive.content ==
                    arguments.getValue("preparedId").jsonPrimitive.content,
            ) { "Commit result preparedId changed" }
            require(
                result.getValue("planDigest").jsonPrimitive.content ==
                    arguments.getValue("planDigest").jsonPrimitive.content,
            ) { "Commit result planDigest changed" }
            require(
                result.getValue("outcome").jsonPrimitive.content in setOf(
                    "completed",
                    "failed",
                    "partially_failed",
                    "cancelled",
                    "partially_cancelled",
                    "unknown",
                    "partially_unknown",
                ),
            ) { "Commit outcome is invalid" }
            val appliedCount = result.getValue("appliedCount").jsonPrimitive.intOrNull
                ?: throw IllegalArgumentException("Commit appliedCount is invalid")
            require(appliedCount >= 0)
            normalizeUuid(
                result.getValue("approvalReceiptId").jsonPrimitive.content,
                "approvalReceiptId",
            )
            val results = result.getValue("results") as? JsonArray
                ?: throw IllegalArgumentException("Commit results must be an array")
            require(results.size in 1..16)
            val operationIds = linkedSetOf<String>()
            results.forEachIndexed { index, item ->
                val objectValue = item as? JsonObject
                    ?: throw IllegalArgumentException("Commit result item is invalid")
                requireExactKeys(
                    objectValue,
                    setOf("operationId", "kind", "state"),
                    setOf("resultAlias", "errorCode"),
                )
                val operationId = normalizeUuid(
                    objectValue.getValue("operationId").jsonPrimitive.content,
                    "results[$index].operationId",
                )
                require(operationIds.add(operationId))
                require(
                    objectValue.getValue("kind").jsonPrimitive.content in setOf(
                        "create_file",
                        "create_directory",
                        "rename",
                        "move",
                        "write_file",
                        "delete_file",
                    ),
                )
                val state = objectValue.getValue("state").jsonPrimitive.content
                require(state in setOf("succeeded", "failed", "cancelled", "unknown"))
                objectValue["resultAlias"]?.let {
                    require(DOCUMENT_ALIAS.matches(it.jsonPrimitive.content))
                    require(state == "succeeded")
                }
                objectValue["errorCode"]?.let {
                    requireSizedString(it, "results[$index].errorCode", 1, 128, true)
                    require(state != "succeeded")
                }
            }
            require(appliedCount == results.count {
                it.jsonObject.getValue("state").jsonPrimitive.content == "succeeded"
            })
        }

        fun validateFileCommitError(terminal: String, error: JsonObject) {
            val code = error["code"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("File commit error code is missing")
            val message = error["message"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("File commit error message is missing")
            require(error["retryable"]?.jsonPrimitive?.booleanOrNull == false)
            val allowed = when (terminal) {
                DeviceToolTerminalKind.REJECTED.wireValue -> setOf("USER_DECLINED")
                DeviceToolTerminalKind.TIMED_OUT.wireValue ->
                    setOf("ATTENTION_EXPIRED", "FILE_CHANGE_EXPIRED")
                DeviceToolTerminalKind.CANCELLED.wireValue ->
                    setOf("ATTENTION_CANCELLED", "FILE_COMMIT_CANCELLED")
                DeviceToolTerminalKind.FAILED.wireValue -> FILE_COMMIT_FAILURE_CODES
                else -> throw IllegalArgumentException("File commit terminal kind is invalid")
            }
            require(code in allowed && message.isNotBlank() && message.length <= 512)
        }

        fun validateContentReadError(
            terminal: String,
            error: JsonObject,
        ) {
            val code = error["code"]?.jsonPrimitive?.contentOrNull
            val message = error["message"]?.jsonPrimitive?.contentOrNull
            val expected = when (terminal) {
                DeviceToolTerminalKind.REJECTED.wireValue ->
                    "CONTENT_READ_DECLINED" to "User declined file content access"
                DeviceToolTerminalKind.TIMED_OUT.wireValue ->
                    "CONTENT_READ_EXPIRED" to "File content request expired"
                DeviceToolTerminalKind.CANCELLED.wireValue ->
                    "CONTENT_READ_CANCELLED" to "File content request was cancelled"
                DeviceToolTerminalKind.FAILED.wireValue -> CONTENT_READ_FAILURES[code]
                    ?: throw IllegalArgumentException("Content-read failure code is invalid")
                else -> throw IllegalArgumentException("Content-read terminal kind is invalid")
            }
            require(code == expected.first &&
                message == expected.second &&
                error["retryable"]?.jsonPrimitive?.booleanOrNull == false
            ) { "Content-read error differs from the fixed contract" }
        }

        fun validateTerminalOrigin(write: AttentionTerminalWrite) {
            when (write.origin) {
                AttentionTerminalOrigin.USER -> require(write.frame.terminal in setOf(
                    DeviceToolTerminalKind.SUCCEEDED,
                    DeviceToolTerminalKind.REJECTED,
                    DeviceToolTerminalKind.FAILED,
                )) { "User attention response uses an invalid terminal" }
                AttentionTerminalOrigin.AUTO_POLICY -> require(write.frame.terminal in setOf(
                    DeviceToolTerminalKind.SUCCEEDED,
                    DeviceToolTerminalKind.FAILED,
                )) { "Automatic policy action uses an invalid terminal" }
                AttentionTerminalOrigin.EXPIRY -> require(
                    write.frame.terminal == DeviceToolTerminalKind.TIMED_OUT,
                ) { "Expiry must use timed_out terminal" }
                AttentionTerminalOrigin.HOST_CANCEL -> {
                    require(write.frame.terminal == DeviceToolTerminalKind.CANCELLED) {
                        "Host cancel must use cancelled terminal"
                    }
                    requireCancelReason(write.cancelReason)
                }
                AttentionTerminalOrigin.LOCAL_RECOVERY -> require(
                    write.frame.terminal == DeviceToolTerminalKind.SUCCEEDED,
                ) { "Local file recovery must restore an exact successful result" }
                AttentionTerminalOrigin.FAILED_CLOSED -> require(
                    write.frame.terminal == DeviceToolTerminalKind.FAILED,
                ) { "Failed-closed transition must use failed terminal" }
            }
        }

        const val MAX_PER_FILE_CONTENT_BYTES = 262_144L
        const val MAX_TOTAL_CONTENT_BYTES = 524_288L
        val DOCUMENT_ALIAS = Regex("^doc-[0-9a-f]{24}$")
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val UI_SNAPSHOT_ID = Regex("^ui-[0-9a-f]{32}$")
        val UI_ACTION_FAILURE_CODES = setOf(
            "UI_ACTION_ARGUMENTS_INVALID",
            "UI_ACTION_CONTEXT_TRUNCATED",
            "UI_ACTION_HARD_DENY",
            "UI_ACTION_POLICY_DENIED",
            "UI_ACTION_REAPPROVAL_REQUIRED",
            "UI_ACTION_LIMIT_REACHED",
            "UI_ACTION_OUTCOME_UNKNOWN",
            "UI_ACTION_REJECTED",
            "UI_ACTION_STOPPED",
            "UI_ACTION_TARGET_CHANGED",
            "UI_ACTION_TARGET_RETURN_TIMEOUT",
            "UI_ACTION_UNSUPPORTED",
            "UI_ACTION_USER_TAKEOVER",
            "UI_CONTROL_DEVICE_LOCKED",
            "UI_CONTROL_SESSION_REQUIRED",
            "UI_NODE_NOT_FOUND",
            "UI_NODE_STALE",
            "UI_SNAPSHOT_NOT_FOUND",
            "UI_SNAPSHOT_STALE",
            "UI_SNAPSHOT_TASK_MISMATCH",
        )
        val FILE_COMMIT_FAILURE_CODES = setOf(
            "UNSUPPORTED_DEVICE_CAPABILITY",
            "PREPARED_CHANGE_NOT_FOUND",
            "FILE_CHANGE_EXPIRED",
            "FILE_COMMIT_CONFLICT",
            "FILE_COMMIT_NOT_ACTIONABLE",
            "FILE_COMMIT_CANCELLED",
            "FILE_COMMIT_OUTCOME_UNKNOWN",
            "TASK_FILE_GRANT_REQUIRED",
            "AUTHORIZED_FOLDER_UNAVAILABLE",
            "FILE_PRECONDITION_FAILED",
            "FILE_COMMIT_FAILED",
        )
        val CONTENT_READ_FAILURES = mapOf(
            "UNSUPPORTED_DEVICE_CAPABILITY" to (
                "UNSUPPORTED_DEVICE_CAPABILITY" to "Device capability is unavailable"
                ),
            "CONTENT_READ_TIMEOUT" to (
                "CONTENT_READ_TIMEOUT" to "Android file content read timed out"
                ),
            "TASK_FILE_GRANT_REQUIRED" to (
                "TASK_FILE_GRANT_REQUIRED" to "This task has no matching mobile folder grant"
                ),
            "CONTENT_SCOPE_CONFLICT" to (
                "CONTENT_SCOPE_CONFLICT" to "File content approval no longer matches the request"
                ),
            "CONTENT_SCOPE_UNAVAILABLE" to (
                "CONTENT_SCOPE_UNAVAILABLE" to "File content approval is unavailable"
                ),
            "CONTENT_SCOPE_EXPIRED" to (
                "CONTENT_SCOPE_EXPIRED" to "File content approval expired or was revoked"
                ),
            "AUTHORIZED_FOLDER_UNAVAILABLE" to (
                "AUTHORIZED_FOLDER_UNAVAILABLE" to "Mobile folder authorization is unavailable"
                ),
            "DOCUMENTS_PROVIDER_UNAVAILABLE" to (
                "DOCUMENTS_PROVIDER_UNAVAILABLE" to "Android Documents Provider is unavailable"
                ),
            "CONTENT_READ_POLICY_BLOCKED" to (
                "CONTENT_READ_POLICY_BLOCKED" to "Android blocked this file content request"
                ),
            "CONTENT_READ_FAILED" to (
                "CONTENT_READ_FAILED" to "Android could not complete this file content request"
                ),
            "CONTENT_READ_CANCELLED" to (
                "CONTENT_READ_CANCELLED" to "File content request was cancelled"
                ),
        )

        fun canonicalJson(value: JsonElement): String = when (value) {
            is JsonObject -> buildJsonObject {
                value.keys.sorted().forEach { key -> put(key, canonicalElement(value.getValue(key))) }
            }.toString()
            else -> canonicalElement(value).toString()
        }

        fun canonicalElement(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> buildJsonObject {
                value.keys.sorted().forEach { key -> put(key, canonicalElement(value.getValue(key))) }
            }
            is JsonArray -> buildJsonArray { value.forEach { add(canonicalElement(it)) } }
            else -> value
        }

        fun parseDeliveryState(value: String): AttentionDeliveryState = try {
            AttentionDeliveryState.valueOf(value)
        } catch (error: IllegalArgumentException) {
            throw AttentionLedgerConflictException("Delivery state is corrupt")
        }

        fun parseLedgerState(value: String): AttentionLedgerState = try {
            AttentionLedgerState.valueOf(value)
        } catch (error: IllegalArgumentException) {
            throw AttentionLedgerConflictException("Ledger state is corrupt")
        }

        fun parseResponseState(value: String): AttentionResponseState = try {
            AttentionResponseState.valueOf(value)
        } catch (error: IllegalArgumentException) {
            throw AttentionLedgerConflictException("Attention response state is corrupt")
        }

        fun validateHostState(value: String) {
            require(value in setOf("created", "sent", "running", "terminal", "reconciled")) {
                "Host observation state is invalid"
            }
        }

        fun requireCancelReason(value: String?): String {
            require(value in setOf("session_stop", "tool_abort", "timeout", "host_shutdown")) {
                "Host cancel reason is invalid"
            }
            return requireNotNull(value)
        }

        fun sha256(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }
}
