package app.momoding.core.transport

import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.DeviceCapabilitiesReportClientFrame
import app.momoding.wire.DeviceClientFrameEncoder
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceLedgerState
import app.momoding.wire.DeviceToolCancelFrame
import app.momoding.wire.DeviceToolCancelReason
import app.momoding.wire.DeviceToolProgressClientFrame
import app.momoding.wire.DeviceToolProgressPhase
import app.momoding.wire.DeviceToolReconcileRequestFrame
import app.momoding.wire.DeviceToolReconcileResultClientFrame
import app.momoding.wire.DeviceToolReconcileResultItem
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.wire.ReceivedP1bServerFrame
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.core.data.AttentionAcceptanceScope
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionLedgerRecord
import app.momoding.core.data.AttentionReconcileEntry
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionRequestRecord
import app.momoding.core.data.AttentionTerminalOrigin
import app.momoding.core.data.AttentionTerminalWrite
import app.momoding.core.data.DeviceOperationEntity
import app.momoding.core.data.OutboundCommandRecord
import app.momoding.core.data.OutboundCommandState
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.files.ContentReadExecutionFailure
import app.momoding.core.files.DeviceContentReadHandler
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.files.FileChangeExecutionFailure
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AttentionCommandPurpose {
    CAPABILITY,
    RECONCILE,
}

data class AttentionCoordinatorCommand(
    val request: OutboundWireRequest,
    val purpose: AttentionCommandPurpose,
    val register: Boolean,
)

data class AttentionOneWayFrame(
    val kind: String,
    val canonicalPayload: String,
    val taskId: String? = null,
    val callId: String? = null,
    val discardLiveContentAfterAttempt: Boolean = false,
)

data class AttentionCoordinatorPlan(
    val commands: List<AttentionCoordinatorCommand> = emptyList(),
    val oneWayFrames: List<AttentionOneWayFrame> = emptyList(),
    val terminalCandidates: List<DeviceOperationEntity> = emptyList(),
    val capabilityReady: Boolean? = null,
    val protocolRecoveryReason: String? = null,
    val nextWakeAtMillis: Long? = null,
)

sealed interface AttentionUserDecision {
    val callId: String

    data class Option(override val callId: String, val index: Int) : AttentionUserDecision
    data class Custom(override val callId: String, val text: String) : AttentionUserDecision
    data class Skip(override val callId: String) : AttentionUserDecision
    data class Confirm(override val callId: String) : AttentionUserDecision
    data class Decline(override val callId: String) : AttentionUserDecision
    data class AllowContentRead(override val callId: String) : AttentionUserDecision
    data class DenyContentRead(override val callId: String) : AttentionUserDecision
    data class ApproveFileChanges(override val callId: String) : AttentionUserDecision
    data class RejectFileChanges(override val callId: String) : AttentionUserDecision
}

/**
 * Application owner for attention and device state.
 *
 * Every method is synchronous by design. WssActor invokes it only from its one mailbox turn,
 * persists returned commands through RoomCommandDraftJournal, and remains the sole socket writer.
 */
class AttentionApplicationCoordinator(
    private val ledger: RoomAttentionLedger,
    private val journal: RoomCommandDraftJournal,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val reconcileDigest: (ByteArray) -> ByteArray = ::sha256Bytes,
    private val terminalSentCommit: (String) -> Unit = ledger::markSent,
    private val contentReadHandler: DeviceContentReadHandler? = null,
    private val fileChangeHandler: DeviceFileChangeExecutor? = null,
) {
    private var generation: Long? = null
    private var deviceId: String? = null
    private var capabilityRequestId: String? = null
    private var capabilityWasResumed = false
    private var capabilityExpiresAtMillis: Long? = null
    private var refreshAtMillis: Long? = null
    private var ready = false
    private var reconcileGraceUntilMillis: Long? = null
    private val currentReconcileRequestIds = linkedSetOf<String>()
    private val staleReconcileRequestIds = linkedSetOf<String>()
    private val reconcileCallIdsByRequestId = mutableMapOf<String, List<String>>()
    private val reconcileRawSha256ByRequestId = mutableMapOf<String, String>()

    val isCapabilityReady: Boolean
        get() = ready

    fun beginGeneration(
        generation: Long,
        deviceId: String,
        registeredRequestIds: Set<String> = emptySet(),
    ): AttentionCoordinatorPlan {
        require(generation > 0) { "generation must be positive" }
        require(deviceId.isNotBlank() && deviceId.length <= 128) { "deviceId is invalid" }
        currentReconcileRequestIds.forEach(staleReconcileRequestIds::add)
        currentReconcileRequestIds.clear()
        this.generation = generation
        this.deviceId = deviceId
        ready = false
        capabilityExpiresAtMillis = null
        refreshAtMillis = null
        reconcileGraceUntilMillis = null
        repairInterruptedFileChangeTerminals()
        repairCommittedHostTerminalProofs()

        val pendingCapabilities = journal.pendingCommands().filter {
            it.kind == CAPABILITY_KIND
        }
        if (pendingCapabilities.size > 1) {
            return recovery("Multiple active capability reports are durable")
        }
        val command = pendingCapabilities.singleOrNull()?.let { durable ->
            validateCapabilityPayload(durable, deviceId)
            capabilityRequestId = durable.requestId
            capabilityWasResumed = true
            durable.toCoordinatorCommand(
                AttentionCommandPurpose.CAPABILITY,
                durable.requestId !in registeredRequestIds,
            )
        } ?: newCapabilityCommand(deviceId).also {
            capabilityRequestId = it.request.requestId
            capabilityWasResumed = false
        }
        return AttentionCoordinatorPlan(commands = listOf(command))
    }

    fun onCommandResponse(
        request: PendingWireRequest,
        received: ReceivedP1bServerFrame,
        registeredRequestIds: Set<String> = emptySet(),
    ): AttentionCoordinatorPlan {
        val response = received.frame as? CommandResponseFrame
            ?: return recovery("Device command did not receive a command response")
        if (response.requestId != request.requestId) {
            return recovery("Device command response requestId changed")
        }
        val durable = journal.command(request.requestId)
            ?: return recovery("Device command response has no durable command")
        if (!durableResponseMatches(durable, request, received)) {
            return recovery("Device command response is not exact durable truth")
        }
        return when (request.kind) {
            CAPABILITY_KIND -> onCapabilityResponse(
                request,
                response,
                durable,
                registeredRequestIds,
            )
            RECONCILE_KIND -> onReconcileResponse(request, response)
            else -> AttentionCoordinatorPlan()
        }
    }

    fun handleDeviceRequest(frame: DeviceToolRequestFrame): AttentionCoordinatorPlan {
        requireReadyBinding(frame.deviceId, frame.capabilityVersion)
        val previous = ledger.record(frame.callId)
        val accepted = ledger.acceptRequest(
            AttentionRequestRecord(
                callId = frame.callId,
                taskId = frame.taskId,
                piToolCallId = frame.piToolCallId,
                deviceId = frame.deviceId,
                toolName = frame.toolName,
                arguments = frame.arguments,
                sideEffect = frame.sideEffect,
                operationId = frame.operationId,
                expiresAt = frame.expiresAt,
                capabilityVersion = frame.capabilityVersion,
            ),
            AttentionAcceptanceScope(
                taskId = frame.taskId,
                deviceId = requireNotNull(deviceId),
                capabilityVersion = CAPABILITY_VERSION,
                originFocusKey = "task-${frame.taskId}",
            ),
        )
        var current = accepted
        if (
            frame.toolName == FILE_COMMIT_TOOL &&
            accepted.operation.terminalSha256 == null
        ) {
            current = try {
                requireNotNull(fileChangeHandler) {
                    "File change executor is unavailable"
                }.bindCommit(accepted.operation)
                accepted
            } catch (failure: FileChangeExecutionFailure) {
                ledger.recordTerminal(
                    AttentionTerminalWrite(
                        frame = DeviceToolResultClientFrame(
                            callId = accepted.operation.callId,
                            taskId = accepted.operation.taskId,
                            deviceId = accepted.operation.deviceId,
                            terminal = DeviceToolTerminalKind.FAILED,
                            error = DeviceClientWireError(failure.code, failure.message),
                        ),
                        origin = AttentionTerminalOrigin.USER,
                        nowMillis = nowMillis(),
                    ),
                )
            } catch (_: Throwable) {
                ledger.recordTerminal(
                    AttentionTerminalWrite(
                        frame = DeviceToolResultClientFrame(
                            callId = accepted.operation.callId,
                            taskId = accepted.operation.taskId,
                            deviceId = accepted.operation.deviceId,
                            terminal = DeviceToolTerminalKind.FAILED,
                            error = DeviceClientWireError(
                                "FILE_COMMIT_FAILED",
                                "Android rejected the prepared file commit",
                            ),
                        ),
                        origin = AttentionTerminalOrigin.USER,
                        nowMillis = nowMillis(),
                    ),
                )
            }
        }
        val oneWay = if (previous == null && current.operation.terminalSha256 == null) {
            val awaiting = ledger.markAwaitingUser(frame.callId)
            listOf(progressFrame(awaiting))
        } else {
            emptyList()
        }
        return plan(
            oneWayFrames = oneWay,
            terminalCandidates = drainableTerminals(),
        )
    }

    fun handleDeviceCancel(frame: DeviceToolCancelFrame): AttentionCoordinatorPlan {
        requireReady()
        val existing = ledger.record(frame.callId) ?: return plan()
        if (existing.operation.taskId != frame.taskId) {
            return recovery("Device cancel task binding changed")
        }
        if (existing.operation.toolName == FILE_COMMIT_TOOL) {
            fileChangeHandler?.cancel(existing.operation, "FILE_COMMIT_CANCELLED")
        }
        ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = DeviceToolResultClientFrame(
                    callId = frame.callId,
                    taskId = frame.taskId,
                    deviceId = existing.operation.deviceId,
                    terminal = DeviceToolTerminalKind.CANCELLED,
                    error = cancelError(existing.operation.toolName),
                ),
                origin = AttentionTerminalOrigin.HOST_CANCEL,
                nowMillis = nowMillis(),
                cancelReason = frame.reason.wireValue(),
            ),
        )
        return plan()
    }

    fun handleReconcileRequest(
        received: ReceivedP1bServerFrame,
    ): AttentionCoordinatorPlan {
        requireReady()
        val frame = received.frame as? DeviceToolReconcileRequestFrame
            ?: return recovery("Reconcile handler received another frame kind")
        if (frame.deviceId != deviceId || frame.calls.size !in 1..MAX_RECONCILE_CALLS) {
            return recovery("Reconcile request is outside the current device contract")
        }
        if (frame.calls.map { it.callId }.toSet().size != frame.calls.size) {
            return recovery("Reconcile request repeats a callId")
        }
        if (frame.requestId in staleReconcileRequestIds) {
            return recovery("Current reconcile requestId reuses a stale probe identity")
        }
        val localRecords = try {
            ledger.recordsForReconcile(frame.taskId, frame.calls.map { it.callId })
        } catch (_: IllegalArgumentException) {
            return recovery("Reconcile task binding is not durable")
        } catch (_: IllegalStateException) {
            return recovery("Reconcile task binding is not durable")
        }
        val commandId = deterministicReconcileCommandId(received.rawBytes)
        val rawSha256 = sha256Hex(received.rawBytes)
        val priorRawSha256 = reconcileRawSha256ByRequestId[frame.requestId]
        if (priorRawSha256 == rawSha256) {
            journal.command(frame.requestId)?.let { durable ->
                if (durable.commandId != commandId || durable.kind != RECONCILE_KIND) {
                    return recovery("Exact reconcile request conflicts with durable identity")
                }
                reconcileCallIdsByRequestId[frame.requestId] = validateReconcilePayload(
                    durable,
                    requireNotNull(deviceId),
                )
                currentReconcileRequestIds += frame.requestId
                return plan(commands = listOf(durable.toCoordinatorCommand(
                    AttentionCommandPurpose.RECONCILE,
                    register = true,
                )))
            }
        } else if (priorRawSha256 == null) {
            journal.command(frame.requestId)?.let { durable ->
                if (durable.commandId != commandId || durable.kind != RECONCILE_KIND) {
                    return recovery("Reconcile requestId conflicts with durable identity")
                }
                reconcileRawSha256ByRequestId[frame.requestId] = rawSha256
                reconcileCallIdsByRequestId[frame.requestId] = validateReconcilePayload(
                    durable,
                    requireNotNull(deviceId),
                )
                currentReconcileRequestIds += frame.requestId
                return plan(commands = listOf(durable.toCoordinatorCommand(
                    AttentionCommandPurpose.RECONCILE,
                    register = true,
                )))
            }
        }
        val resultFrame = DeviceToolReconcileResultClientFrame(
            requestId = frame.requestId,
            commandId = commandId,
            taskId = frame.taskId,
            deviceId = frame.deviceId,
            results = frame.calls.map { call ->
                reconcileItem(frame.taskId, call, localRecords[call.callId])
            },
        )
        val canonical = DeviceClientFrameEncoder.encode(resultFrame).decodeToString()
        currentReconcileRequestIds += frame.requestId
        reconcileCallIdsByRequestId[frame.requestId] = frame.calls.map { it.callId }
        reconcileRawSha256ByRequestId[frame.requestId] = rawSha256
        return plan(
            commands = listOf(
                AttentionCoordinatorCommand(
                    request = OutboundWireRequest(
                        requestId = frame.requestId,
                        commandId = commandId,
                        kind = RECONCILE_KIND,
                        taskId = frame.taskId,
                        canonicalPayload = canonical,
                        mutating = true,
                    ),
                    purpose = AttentionCommandPurpose.RECONCILE,
                    register = true,
                ),
            ),
        )
    }

    fun submitDecision(decision: AttentionUserDecision): AttentionCoordinatorPlan {
        requireReady()
        expireDueAttention()
        val record = ledger.record(decision.callId)
            ?: return recovery("Attention decision has no durable request")
        if (record.operation.terminalSha256 != null) return plan(
            terminalCandidates = drainableTerminals(),
        )
        val terminal = terminalForDecision(record, decision)
        ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = terminal,
                origin = AttentionTerminalOrigin.USER,
                nowMillis = nowMillis(),
            ),
        )
        return plan(terminalCandidates = drainableTerminals())
    }

    fun discardLiveContentRead(taskId: String, callId: String) {
        ledger.discardLiveContentRead(taskId, callId)
    }

    suspend fun submitContentReadDecision(
        decision: AttentionUserDecision,
    ): AttentionCoordinatorPlan {
        require(decision is AttentionUserDecision.AllowContentRead ||
            decision is AttentionUserDecision.DenyContentRead
        ) { "Content-read decision type is invalid" }
        requireReady()
        expireDueAttention()
        val record = ledger.record(decision.callId)
            ?: return recovery("Content-read decision has no durable request")
        require(record.operation.toolName == CONTENT_READ_TOOL) {
            "Content-read decision is bound to another tool"
        }
        if (record.operation.terminalSha256 != null) {
            return plan(terminalCandidates = drainableTerminals())
        }
        val terminal = when (decision) {
            is AttentionUserDecision.DenyContentRead -> DeviceToolResultClientFrame(
                callId = record.operation.callId,
                taskId = record.operation.taskId,
                deviceId = record.operation.deviceId,
                terminal = DeviceToolTerminalKind.REJECTED,
                error = CONTENT_READ_DECLINED,
            )
            is AttentionUserDecision.AllowContentRead -> {
                try {
                    val result = requireNotNull(contentReadHandler) {
                        "Content-read executor is unavailable"
                    }.execute(record.operation)
                    DeviceToolResultClientFrame(
                        callId = record.operation.callId,
                        taskId = record.operation.taskId,
                        deviceId = record.operation.deviceId,
                        terminal = DeviceToolTerminalKind.SUCCEEDED,
                        result = result,
                    )
                } catch (failure: ContentReadExecutionFailure) {
                    DeviceToolResultClientFrame(
                        callId = record.operation.callId,
                        taskId = record.operation.taskId,
                        deviceId = record.operation.deviceId,
                        terminal = if (failure.code == "CONTENT_READ_CANCELLED") {
                            DeviceToolTerminalKind.CANCELLED
                        } else {
                            DeviceToolTerminalKind.FAILED
                        },
                        error = DeviceClientWireError(failure.code, failure.message),
                    )
                }
            }
            else -> error("Content-read decision type changed")
        }
        if (terminal.terminal == DeviceToolTerminalKind.SUCCEEDED) {
            val canonical = DeviceClientFrameEncoder.encode(terminal).decodeToString()
            return plan(
                oneWayFrames = listOf(
                    AttentionOneWayFrame(
                        kind = "device.tool.result",
                        canonicalPayload = canonical,
                        taskId = record.operation.taskId,
                        callId = record.operation.callId,
                        discardLiveContentAfterAttempt = true,
                    ),
                ),
            )
        }
        ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = terminal,
                origin = if (terminal.terminal == DeviceToolTerminalKind.CANCELLED) {
                    AttentionTerminalOrigin.HOST_CANCEL
                } else {
                    AttentionTerminalOrigin.USER
                },
                nowMillis = nowMillis(),
                cancelReason = if (terminal.terminal == DeviceToolTerminalKind.CANCELLED) {
                    "session_stop"
                } else {
                    null
                },
            ),
        )
        return plan(terminalCandidates = drainableTerminals())
    }

    suspend fun submitFileChangeDecision(
        decision: AttentionUserDecision,
    ): AttentionCoordinatorPlan {
        require(decision is AttentionUserDecision.ApproveFileChanges ||
            decision is AttentionUserDecision.RejectFileChanges
        ) { "File-change decision type is invalid" }
        requireReady()
        expireDueAttention()
        val record = ledger.record(decision.callId)
            ?: return recovery("File-change decision has no durable request")
        require(record.operation.toolName == FILE_COMMIT_TOOL) {
            "File-change decision is bound to another tool"
        }
        if (record.operation.terminalSha256 != null) {
            return plan(terminalCandidates = drainableTerminals())
        }
        val handler = requireNotNull(fileChangeHandler) {
            "File change executor is unavailable"
        }
        val terminal = when (decision) {
            is AttentionUserDecision.RejectFileChanges -> {
                handler.reject(record.operation)
                DeviceToolResultClientFrame(
                    callId = record.operation.callId,
                    taskId = record.operation.taskId,
                    deviceId = record.operation.deviceId,
                    terminal = DeviceToolTerminalKind.REJECTED,
                    error = DeviceClientWireError(
                        "USER_DECLINED",
                        "User declined the file changes",
                    ),
                )
            }
            is AttentionUserDecision.ApproveFileChanges -> try {
                val result = handler.approveAndCommit(
                    operation = record.operation,
                    durableTerminal = ledger::recordTerminal,
                )
                DeviceToolResultClientFrame(
                    callId = record.operation.callId,
                    taskId = record.operation.taskId,
                    deviceId = record.operation.deviceId,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = result,
                )
            } catch (failure: FileChangeExecutionFailure) {
                DeviceToolResultClientFrame(
                    callId = record.operation.callId,
                    taskId = record.operation.taskId,
                    deviceId = record.operation.deviceId,
                    terminal = if (failure.code == "FILE_COMMIT_CANCELLED") {
                        DeviceToolTerminalKind.CANCELLED
                    } else {
                        DeviceToolTerminalKind.FAILED
                    },
                    error = DeviceClientWireError(failure.code, failure.message),
                )
            }
            else -> error("File-change decision type changed")
        }
        ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = terminal,
                origin = AttentionTerminalOrigin.USER,
                nowMillis = nowMillis(),
            ),
        )
        return plan(terminalCandidates = drainableTerminals())
    }

    fun onProjectionCommitted(taskId: String): AttentionCoordinatorPlan {
        repairCommittedHostTerminalProofs(taskId)
        return plan()
    }

    fun onWake(): AttentionCoordinatorPlan {
        if (generation == null) return AttentionCoordinatorPlan()
        repairInterruptedFileChangeTerminals()
        expireDueAttention()
        val now = nowMillis()
        val expires = capabilityExpiresAtMillis
        if (ready && expires != null && now >= expires) {
            return recovery("Attention capability expired before refresh completed")
        }
        val commands = if (ready && refreshAtMillis?.let { now >= it } == true &&
            capabilityRequestId == null
        ) {
            refreshAtMillis = null
            listOf(newCapabilityCommand(requireNotNull(deviceId)).also {
                capabilityRequestId = it.request.requestId
                capabilityWasResumed = false
            })
        } else {
            emptyList()
        }
        return plan(commands = commands, terminalCandidates = drainableTerminals())
    }

    fun markTerminalSent(callId: String) {
        terminalSentCommit(callId)
    }

    private fun repairCommittedHostTerminalProofs(taskId: String? = null) {
        val operations = if (taskId == null) {
            ledger.terminalOperationsReadyForDelivery(requireNotNull(deviceId))
        } else {
            ledger.records(taskId).map { it.operation }
        }
        check(operations.all { it.deviceId == deviceId }) {
            "Host terminal proof is outside the current device binding"
        }
        operations.forEach { operation ->
            if (operation.terminalSha256 != null &&
                operation.ledgerState != AttentionLedgerState.CANCELLED.name &&
                operation.deliveryState in setOf(
                    AttentionDeliveryState.READY_TO_SEND.name,
                    AttentionDeliveryState.SENT_UNCONFIRMED.name,
                ) &&
                operation.hostObservationState in setOf("terminal", "reconciled")
            ) {
                ledger.markHostTerminalDurable(operation.callId)
            }
        }
    }

    private fun repairInterruptedFileChangeTerminals() {
        val handler = fileChangeHandler ?: return
        ledger.unterminatedRecords()
            .asSequence()
            .filter { it.operation.toolName == FILE_COMMIT_TOOL }
            .forEach { record ->
                check(record.operation.deviceId == deviceId) {
                    "File commit recovery is outside the current device binding"
                }
                handler.repairInterruptedCommit(
                    operation = record.operation,
                    durableTerminal = ledger::recordTerminal,
                )
            }
    }

    private fun onCapabilityResponse(
        request: PendingWireRequest,
        response: CommandResponseFrame,
        durable: OutboundCommandRecord,
        registeredRequestIds: Set<String>,
    ): AttentionCoordinatorPlan {
        if (request.requestId != capabilityRequestId) {
            return recovery("Capability response does not match the active report")
        }
        val openingGeneration = !ready
        capabilityRequestId = null
        if (!isExactEmptySuccess(response)) {
            if (capabilityWasResumed) {
                val fresh = newCapabilityCommand(requireNotNull(deviceId))
                capabilityRequestId = fresh.request.requestId
                capabilityWasResumed = false
                return AttentionCoordinatorPlan(commands = listOf(fresh))
            }
            return recovery("Capability report was rejected")
        }
        val expiresAt = validateCapabilityPayload(durable, requireNotNull(deviceId))
        val now = nowMillis()
        if (expiresAt <= now) return recovery("Accepted capability report is already expired")
        capabilityExpiresAtMillis = expiresAt
        refreshAtMillis = maxOf(now, expiresAt - CAPABILITY_REFRESH_LEAD_MILLIS)
        ready = true
        reconcileGraceUntilMillis = now + RECONCILE_GRACE_MILLIS
        expireDueAttention()

        val reconcileCommands = if (openingGeneration) {
            journal.pendingCommands()
                .filter { it.kind == RECONCILE_KIND }
                .map { durableCommand ->
                    reconcileCallIdsByRequestId[durableCommand.requestId] =
                        validateReconcilePayload(durableCommand, requireNotNull(deviceId))
                    staleReconcileRequestIds += durableCommand.requestId
                    durableCommand.toCoordinatorCommand(
                        AttentionCommandPurpose.RECONCILE,
                        durableCommand.requestId !in registeredRequestIds,
                    )
                }
        } else {
            emptyList()
        }
        return plan(
            commands = reconcileCommands,
            capabilityReady = true,
        )
    }

    private fun onReconcileResponse(
        request: PendingWireRequest,
        response: CommandResponseFrame,
    ): AttentionCoordinatorPlan {
        val stale = staleReconcileRequestIds.remove(request.requestId)
        val current = currentReconcileRequestIds.remove(request.requestId)
        if (!stale && !current) return recovery("Reconcile response has no coordinator owner")
        val exactSuccess = isExactEmptySuccess(response)
        if (!exactSuccess && !stale) {
            return recovery("Current reconcile result was rejected")
        }
        val coveredCallIds = reconcileCallIdsByRequestId.remove(request.requestId).orEmpty().toSet()
        reconcileRawSha256ByRequestId.remove(request.requestId)
        return plan(terminalCandidates = if (exactSuccess) {
            drainableTerminals(ignoreGrace = true, allowedCallIds = coveredCallIds)
        } else {
            emptyList()
        })
    }

    private fun newCapabilityCommand(deviceId: String): AttentionCoordinatorCommand {
        val now = nowMillis()
        val requestId = idFactory()
        val commandId = idFactory()
        val expiresAt = rfc3339(now + CAPABILITY_VALIDITY_MILLIS)
        val canonical = DeviceClientFrameEncoder.encode(
            DeviceCapabilitiesReportClientFrame(
                requestId = requestId,
                commandId = commandId,
                deviceId = deviceId,
                expiresAt = expiresAt,
            ),
        ).decodeToString()
        return AttentionCoordinatorCommand(
            request = OutboundWireRequest(
                requestId = requestId,
                commandId = commandId,
                kind = CAPABILITY_KIND,
                canonicalPayload = canonical,
                mutating = true,
            ),
            purpose = AttentionCommandPurpose.CAPABILITY,
            register = true,
        )
    }

    private fun OutboundCommandRecord.toCoordinatorCommand(
        purpose: AttentionCommandPurpose,
        register: Boolean,
    ): AttentionCoordinatorCommand = AttentionCoordinatorCommand(
        request = OutboundWireRequest(
            requestId = requestId,
            commandId = requireNotNull(commandId),
            kind = kind,
            taskId = taskId,
            canonicalPayload = canonicalPayload,
            mutating = true,
        ),
        purpose = purpose,
        register = register,
    )

    private fun reconcileItem(
        taskId: String,
        call: app.momoding.wire.DeviceToolReconcileCall,
        local: AttentionReconcileEntry?,
    ): DeviceToolReconcileResultItem {
        fun result(
            state: DeviceLedgerState,
            resultSummary: JsonElement? = null,
            error: DeviceClientWireError? = null,
        ) = DeviceToolReconcileResultItem(
            callId = call.callId,
            state = state,
            resultSummary = resultSummary,
            error = error,
            operationId = call.operationId,
        )
        if (local == null) return result(DeviceLedgerState.NEVER_STARTED)
        if (local == AttentionReconcileEntry.Corrupt) {
            return result(DeviceLedgerState.UNKNOWN)
        }
        val operation = (local as AttentionReconcileEntry.Valid).record.operation
        if (
            operation.taskId != taskId ||
            operation.toolName != call.toolName ||
            call.operationId != operation.operationId
        ) {
            return result(DeviceLedgerState.UNKNOWN)
        }
        if (operation.terminalSha256 == null) {
            return result(DeviceLedgerState.RUNNING)
        }
        val terminal = STRICT_JSON.parseToJsonElement(
            requireNotNull(operation.terminalFrameCanonicalJson),
        ).jsonObject
        return when (operation.ledgerState) {
            AttentionLedgerState.CANCELLED.name -> result(
                DeviceLedgerState.CANCELLED,
                error = cancelError(operation.toolName),
            )
            else -> when (terminal.getValue("terminal").jsonPrimitive.content) {
                DeviceToolTerminalKind.SUCCEEDED.wireValue ->
                    if (operation.toolName == CONTENT_READ_TOOL) {
                        // File content is deliberately not reconstructed from durable recovery.
                        // Pi must issue a fresh read and obtain a new Android approval.
                        result(DeviceLedgerState.UNKNOWN)
                    } else {
                        result(
                            DeviceLedgerState.SUCCEEDED,
                            resultSummary = terminal.getValue("result"),
                        )
                    }
                else -> result(
                    DeviceLedgerState.FAILED,
                    error = terminalError(terminal),
                )
            }
        }
    }

    private fun terminalForDecision(
        record: AttentionLedgerRecord,
        decision: AttentionUserDecision,
    ): DeviceToolResultClientFrame {
        val operation = record.operation
        val result: JsonElement?
        val terminal: DeviceToolTerminalKind
        val error: DeviceClientWireError?
        when (decision) {
            is AttentionUserDecision.Option -> {
                require(operation.toolName == QUESTION_TOOL) { "Option is only valid for question" }
                val options = STRICT_JSON.parseToJsonElement(operation.argumentsCanonicalJson)
                    .jsonObject["options"] as? kotlinx.serialization.json.JsonArray
                    ?: throw IllegalArgumentException("Question has no durable options")
                val option = options.getOrNull(decision.index)?.jsonObject
                    ?: throw IllegalArgumentException("Option index is outside the durable request")
                result = buildJsonObject {
                    put("outcome", "answered")
                    put("answer", buildJsonObject {
                        put("kind", "option")
                        put("index", decision.index)
                        put("label", option.getValue("label").jsonPrimitive.content)
                    })
                }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Custom -> {
                require(operation.toolName == QUESTION_TOOL) { "Custom answer is only valid for question" }
                result = buildJsonObject {
                    put("outcome", "answered")
                    put("answer", buildJsonObject {
                        put("kind", "custom")
                        put("text", decision.text)
                    })
                }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Skip -> {
                require(operation.toolName == QUESTION_TOOL) { "Skip is only valid for question" }
                result = buildJsonObject { put("outcome", "skipped") }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Confirm -> {
                require(operation.toolName == CONFIRMATION_TOOL) { "Confirm requires confirmation" }
                result = buildJsonObject { put("outcome", "confirmed") }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Decline -> {
                require(operation.toolName == CONFIRMATION_TOOL) { "Decline requires confirmation" }
                result = null
                terminal = DeviceToolTerminalKind.REJECTED
                error = USER_DECLINED
            }
            is AttentionUserDecision.AllowContentRead,
            is AttentionUserDecision.DenyContentRead,
            -> error("Content-read decisions require the content executor")
            is AttentionUserDecision.ApproveFileChanges,
            is AttentionUserDecision.RejectFileChanges,
            -> error("File-change decisions require the file executor")
        }
        return DeviceToolResultClientFrame(
            callId = operation.callId,
            taskId = operation.taskId,
            deviceId = operation.deviceId,
            terminal = terminal,
            result = result,
            error = error,
        )
    }

    private fun expireDueAttention() {
        val now = nowMillis()
        ledger.unterminatedRecords().forEach { record ->
            if (parseTimestamp(record.operation.expiresAt) <= now) {
                if (record.operation.toolName == FILE_COMMIT_TOOL) {
                    fileChangeHandler?.expire(record.operation)
                }
                ledger.recordTerminal(
                    AttentionTerminalWrite(
                        frame = DeviceToolResultClientFrame(
                            callId = record.operation.callId,
                            taskId = record.operation.taskId,
                            deviceId = record.operation.deviceId,
                            terminal = DeviceToolTerminalKind.TIMED_OUT,
                            error = expiryError(record.operation.toolName),
                        ),
                        origin = AttentionTerminalOrigin.EXPIRY,
                        nowMillis = now,
                    ),
                )
            }
        }
    }

    private fun progressFrame(record: AttentionLedgerRecord): AttentionOneWayFrame {
        val frame = DeviceToolProgressClientFrame(
            callId = record.operation.callId,
            taskId = record.operation.taskId,
            deviceId = record.operation.deviceId,
            progressSequence = record.operation.progressSequence,
            phase = DeviceToolProgressPhase.AWAITING_USER,
            summary = when (record.operation.toolName) {
                CONTENT_READ_TOOL -> "Waiting for file content approval"
                FILE_COMMIT_TOOL -> "Waiting for file change approval"
                UI_ACTION_TOOL -> "Waiting for interface action approval"
                else -> "Waiting for your answer"
            },
        )
        return AttentionOneWayFrame(
            kind = PROGRESS_KIND,
            canonicalPayload = DeviceClientFrameEncoder.encode(frame).decodeToString(),
            taskId = record.operation.taskId,
            callId = record.operation.callId,
        )
    }

    private fun drainableTerminals(
        ignoreGrace: Boolean = false,
        allowedCallIds: Set<String>? = null,
    ): List<DeviceOperationEntity> {
        if (!ready) return emptyList()
        if (!ignoreGrace && reconcileGraceUntilMillis?.let { nowMillis() < it } == true) {
            return emptyList()
        }
        val readyOperations = ledger.terminalOperationsReadyForDelivery(
            requireNotNull(deviceId),
        )
        return readyOperations.filter {
            (allowedCallIds == null || it.callId in allowedCallIds) &&
                it.deliveryState in setOf(
                    AttentionDeliveryState.READY_TO_SEND.name,
                    AttentionDeliveryState.SENT_UNCONFIRMED.name,
                )
        }
    }

    private fun plan(
        commands: List<AttentionCoordinatorCommand> = emptyList(),
        oneWayFrames: List<AttentionOneWayFrame> = emptyList(),
        terminalCandidates: List<DeviceOperationEntity> = emptyList(),
        capabilityReady: Boolean? = null,
    ): AttentionCoordinatorPlan = AttentionCoordinatorPlan(
        commands = commands,
        oneWayFrames = oneWayFrames,
        terminalCandidates = terminalCandidates,
        capabilityReady = capabilityReady,
        nextWakeAtMillis = nextWakeAtMillis(),
    )

    private fun nextWakeAtMillis(): Long? {
        val now = nowMillis()
        val deadlines = buildList {
            refreshAtMillis?.let(::add)
            capabilityExpiresAtMillis?.let(::add)
            reconcileGraceUntilMillis?.let(::add)
            ledger.unterminatedRecords().forEach { add(parseTimestamp(it.operation.expiresAt)) }
        }
        return deadlines.filter { it > now }.minOrNull()
    }

    private fun recovery(reason: String): AttentionCoordinatorPlan = AttentionCoordinatorPlan(
        capabilityReady = if (ready) false else null,
        protocolRecoveryReason = reason,
    ).also { ready = false }

    private fun requireReady() {
        check(ready && generation != null && deviceId != null) {
            "Attention capability is not ready"
        }
    }

    private fun requireReadyBinding(frameDeviceId: String, capabilityVersion: Long) {
        requireReady()
        require(frameDeviceId == deviceId && capabilityVersion == CAPABILITY_VERSION) {
            "Device frame is outside the current capability binding"
        }
    }

    private fun validateCapabilityPayload(record: OutboundCommandRecord, deviceId: String): Long {
        val objectValue = STRICT_JSON.parseToJsonElement(record.canonicalPayload).jsonObject
        require(objectValue.keys == CAPABILITY_KEYS) { "Capability payload fields differ" }
        require(objectValue.getValue("protocolVersion").jsonPrimitive.content == "1")
        require(objectValue.getValue("kind").jsonPrimitive.content == CAPABILITY_KIND)
        require(objectValue.getValue("requestId").jsonPrimitive.content == record.requestId)
        require(objectValue.getValue("commandId").jsonPrimitive.content == record.commandId)
        require(objectValue.getValue("deviceId").jsonPrimitive.content == deviceId)
        require(objectValue.getValue("capabilityVersion").jsonPrimitive.content == "1")
        require(objectValue.getValue("manifest") == app.momoding.wire.DEVICE_CAPABILITY_MANIFEST)
        return parseTimestamp(objectValue.getValue("expiresAt").jsonPrimitive.content)
    }

    private fun durableResponseMatches(
        durable: OutboundCommandRecord,
        request: PendingWireRequest,
        received: ReceivedP1bServerFrame,
    ): Boolean {
        val durableResponse = durable.responseJson ?: return false
        val decoded = try {
            ReliabilityContractDecoder.decode(durableResponse).frame as? CommandResponseFrame
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        return durable.state == OutboundCommandState.TERMINAL &&
            durable.requestId == request.requestId &&
            durable.commandId == request.commandId &&
            durable.kind == request.kind &&
            durable.taskId == request.taskId &&
            durable.canonicalPayload == request.canonicalPayload &&
            durableResponse.encodeToByteArray().contentEquals(received.rawBytes) &&
            decoded == received.frame
    }

    private fun validateReconcilePayload(
        record: OutboundCommandRecord,
        deviceId: String,
    ): List<String> {
        val objectValue = STRICT_JSON.parseToJsonElement(record.canonicalPayload).jsonObject
        require(objectValue.keys == RECONCILE_KEYS) { "Reconcile payload fields differ" }
        require(objectValue.getValue("protocolVersion").jsonPrimitive.content == "1")
        require(objectValue.getValue("kind").jsonPrimitive.content == RECONCILE_KIND)
        require(objectValue.getValue("requestId").jsonPrimitive.content == record.requestId)
        require(objectValue.getValue("commandId").jsonPrimitive.content == record.commandId)
        require(objectValue.getValue("taskId").jsonPrimitive.content == record.taskId)
        require(objectValue.getValue("deviceId").jsonPrimitive.content == deviceId)
        val callIds = objectValue.getValue("results").jsonArray.map { result ->
            result.jsonObject.getValue("callId").jsonPrimitive.content
        }
        require(callIds.size in 1..MAX_RECONCILE_CALLS && callIds.toSet().size == callIds.size) {
            "Reconcile payload result identities are invalid"
        }
        return callIds
    }

    private fun deterministicReconcileCommandId(rawBytes: ByteArray): String {
        val domain = "p2-7d-reconcile-command\u0000".encodeToByteArray()
        val digest = reconcileDigest(domain + rawBytes)
        require(digest.size >= 16) { "Reconcile digest is too short" }
        val bytes = digest.copyOfRange(0, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    private companion object {
        const val CAPABILITY_KIND = "device.capabilities.report"
        const val RECONCILE_KIND = "device.tool.reconcile.result"
        const val PROGRESS_KIND = "device.tool.progress"
        const val QUESTION_TOOL = "request_user_question"
        const val CONFIRMATION_TOOL = "request_user_confirmation"
        const val CONTENT_READ_TOOL = "device_files_read"
        const val FILE_COMMIT_TOOL = "device_files_commit_changes"
        const val UI_ACTION_TOOL = "device_ui_action"
        const val CAPABILITY_VERSION = 1L
        const val MAX_RECONCILE_CALLS = 8
        const val CAPABILITY_VALIDITY_MILLIS = 14 * 60_000L
        const val CAPABILITY_REFRESH_LEAD_MILLIS = 2 * 60_000L
        const val RECONCILE_GRACE_MILLIS = 1_000L
        val CAPABILITY_KEYS = setOf(
            "protocolVersion",
            "kind",
            "requestId",
            "commandId",
            "deviceId",
            "capabilityVersion",
            "manifest",
            "expiresAt",
        )
        val RECONCILE_KEYS = setOf(
            "protocolVersion",
            "kind",
            "requestId",
            "commandId",
            "taskId",
            "deviceId",
            "results",
        )
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
        val ATTENTION_CANCELLED = DeviceClientWireError(
            "ATTENTION_CANCELLED",
            "Attention request was cancelled",
        )
        val ATTENTION_EXPIRED = DeviceClientWireError(
            "ATTENTION_EXPIRED",
            "Attention request expired",
        )
        val USER_DECLINED = DeviceClientWireError(
            "USER_DECLINED",
            "User declined the confirmation",
        )
        val CONTENT_READ_DECLINED = DeviceClientWireError(
            "CONTENT_READ_DECLINED",
            "User declined file content access",
        )
        val CONTENT_READ_CANCELLED = DeviceClientWireError(
            "CONTENT_READ_CANCELLED",
            "File content request was cancelled",
        )
        val CONTENT_READ_EXPIRED = DeviceClientWireError(
            "CONTENT_READ_EXPIRED",
            "File content request expired",
        )

        fun cancelError(toolName: String): DeviceClientWireError =
            if (toolName == CONTENT_READ_TOOL) CONTENT_READ_CANCELLED else ATTENTION_CANCELLED

        fun expiryError(toolName: String): DeviceClientWireError =
            if (toolName == CONTENT_READ_TOOL) CONTENT_READ_EXPIRED else ATTENTION_EXPIRED

        fun isExactEmptySuccess(response: CommandResponseFrame): Boolean =
            response.ok && response.data == null && response.error == null

        fun parseTimestamp(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

        fun rfc3339(value: Long): String = Instant.ofEpochMilli(value).toString()

        fun DeviceToolCancelReason.wireValue(): String = when (this) {
            DeviceToolCancelReason.SESSION_STOP -> "session_stop"
            DeviceToolCancelReason.TOOL_ABORT -> "tool_abort"
            DeviceToolCancelReason.TIMEOUT -> "timeout"
            DeviceToolCancelReason.HOST_SHUTDOWN -> "host_shutdown"
        }

        fun terminalError(frame: JsonObject): DeviceClientWireError {
            val error = frame.getValue("error").jsonObject
            return DeviceClientWireError(
                code = error.getValue("code").jsonPrimitive.content,
                message = error.getValue("message").jsonPrimitive.content,
            )
        }

        fun sha256Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun sha256Hex(bytes: ByteArray): String = sha256Bytes(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
