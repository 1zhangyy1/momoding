package app.momoding.feature.taskdetail

import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.WireErrorCode
import app.momoding.core.data.OutboundCommandRecord
import app.momoding.core.data.OutboundCommandState
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.transport.ClientWireCodec
import app.momoding.core.transport.ExactWireOutcome
import app.momoding.core.transport.OutboundWireRequest
import app.momoding.core.transport.QueueAcceptedSuccess
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.StopAcceptedSuccess
import app.momoding.core.transport.TaskCommandResponseDecoder
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed interface TaskCommandProgress {
    data object Idle : TaskCommandProgress
    data class Persisting(
        val commandId: String,
        val kind: TaskCommandKind,
        val text: String?,
    ) : TaskCommandProgress

    data class Working(
        val commandId: String,
        val kind: TaskCommandKind,
        val text: String?,
        val recovering: Boolean,
    ) : TaskCommandProgress

    data class Completed(
        val commandId: String,
        val kind: TaskCommandKind,
        val queueDepth: Long? = null,
        val runState: String? = null,
    ) : TaskCommandProgress

    data class Failed(
        val commandId: String,
        val kind: TaskCommandKind,
        val code: WireErrorCode,
        val retryable: Boolean,
        val text: String?,
        val safeMessage: String? = null,
    ) : TaskCommandProgress
}

/**
 * Application-scope owner for Task Detail mutations.
 *
 * Every intent crosses the Room accepted boundary before WSS submission. Accepted commands are
 * retried with their exact durable bytes after READY; a stop owns the visible task command state
 * until durable STOPPED proof releases its fence.
 */
class TaskCommandCoordinator(
    private val transportStatus: StateFlow<app.momoding.core.transport.SecureTransportUiStatus>,
    private val submitExact: suspend (OutboundWireRequest) -> app.momoding.wire.ReceivedReliabilityServerFrame,
    private val journal: RoomCommandDraftJournal,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val progress = ConcurrentHashMap<String, MutableStateFlow<TaskCommandProgress>>()
    private val commandMutexes = ConcurrentHashMap<String, Mutex>()
    private val accepting = mutableMapOf<String, MutableSet<TaskCommandKind>>()
    private val durablePending = mutableMapOf<String, MutableMap<String, TaskCommandKind>>()
    private val acceptingLock = Any()
    private val dominantStop = ConcurrentHashMap<String, String>()
    private val suppressedCommands = ConcurrentHashMap.newKeySet<String>()
    private val reconcileMutex = Mutex()

    init {
        applicationScope.launch(ioDispatcher) { hydratePendingCommands() }
        applicationScope.launch {
            transportStatus
                .filter { it.phase == SecureTransportUiPhase.READY }
                .distinctUntilChanged()
                .collect { reconcileAccepted() }
        }
    }

    fun observe(taskId: String): StateFlow<TaskCommandProgress> {
        require(taskId.isNotBlank()) { "taskId is blank" }
        return progress.getOrPut(taskId) { MutableStateFlow(TaskCommandProgress.Idle) }
    }

    fun submit(taskId: String, kind: TaskCommandKind, text: String): Boolean {
        require(kind != TaskCommandKind.STOP) { "Use stop() for session.stop" }
        require(text.isNotBlank() && text.length <= ClientWireCodec.MAX_PROMPT_UTF16_UNITS) {
            "Task command text is invalid"
        }
        if (!reserve(taskId, kind)) return false
        val commandId = idFactory()
        val requestId = idFactory()
        publish(taskId, commandId, TaskCommandProgress.Persisting(commandId, kind, text))
        applicationScope.launch(ioDispatcher) {
            try {
                val existing = journal.pendingCommands().firstOrNull { command ->
                    command.taskId == taskId && command.kind in TASK_COMMAND_KINDS
                }
                if (existing != null) {
                    val existingCommandId = requireNotNull(existing.commandId)
                    markDurablePending(taskId, existingCommandId, existing.kind.toUiKind())
                    publish(
                        taskId,
                        existingCommandId,
                        TaskCommandProgress.Working(
                            existingCommandId,
                            existing.kind.toUiKind(),
                            existing.commandText(),
                            recovering = true,
                        ),
                    )
                    executeOnce(existing, recovering = true)
                    return@launch
                }
                val request = exactRequest(taskId, kind, text, requestId, commandId)
                val durable = journal.persistAccepted(
                    requestId = request.requestId,
                    commandId = request.commandId,
                    kind = request.kind,
                    taskId = taskId,
                    canonicalPayload = request.canonicalPayload,
                )
                markDurablePending(taskId, commandId, kind)
                publish(taskId, commandId, TaskCommandProgress.Working(commandId, kind, text, recovering = false))
                executeOnce(durable, recovering = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(
                    taskId,
                    commandId,
                    TaskCommandProgress.Failed(
                        commandId,
                        kind,
                        WireErrorCode.RECOVERY_REQUIRED,
                        retryable = false,
                        text = text,
                    ),
                )
            } finally {
                release(taskId, kind)
            }
        }
        return true
    }

    fun stop(taskId: String): Boolean {
        if (!reserve(taskId, TaskCommandKind.STOP)) return false
        val commandId = idFactory()
        val requestId = idFactory()
        dominantStop[taskId] = commandId
        suppressOtherPending(taskId, commandId)
        publish(
            taskId,
            commandId,
            TaskCommandProgress.Persisting(commandId, TaskCommandKind.STOP, text = null),
        )
        applicationScope.launch(ioDispatcher) {
            try {
                if (journal.hasPendingStopFence(taskId)) {
                    val pending = journal.pendingCommands().firstOrNull {
                        it.taskId == taskId && it.kind == "session.stop"
                    }
                    if (pending != null) {
                        dominantStop[taskId] = requireNotNull(pending.commandId)
                        markDurablePending(
                            taskId,
                            requireNotNull(pending.commandId),
                            TaskCommandKind.STOP,
                        )
                        suppressOtherPending(taskId, requireNotNull(pending.commandId))
                        executeOnce(pending, recovering = true)
                    }
                    return@launch
                }
                val request = exactRequest(
                    taskId = taskId,
                    kind = TaskCommandKind.STOP,
                    text = null,
                    requestId = requestId,
                    commandId = commandId,
                )
                val durable = journal.persistAccepted(
                    requestId = request.requestId,
                    commandId = request.commandId,
                    kind = request.kind,
                    taskId = taskId,
                    canonicalPayload = request.canonicalPayload,
                )
                markDurablePending(taskId, commandId, TaskCommandKind.STOP)
                publish(
                    taskId,
                    commandId,
                    TaskCommandProgress.Working(commandId, TaskCommandKind.STOP, null, recovering = false),
                )
                executeOnce(durable, recovering = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dominantStop.remove(taskId, commandId)
                publish(
                    taskId,
                    commandId,
                    TaskCommandProgress.Failed(
                        commandId,
                        TaskCommandKind.STOP,
                        WireErrorCode.RECOVERY_REQUIRED,
                        retryable = false,
                        text = null,
                    ),
                )
            } finally {
                release(taskId, TaskCommandKind.STOP)
            }
        }
        return true
    }

    fun acknowledge(taskId: String, commandId: String) {
        val state = progress[taskId] ?: return
        val current = state.value
        if (current is TaskCommandProgress.Completed && current.commandId == commandId) {
            if (current.kind == TaskCommandKind.STOP) dominantStop.remove(taskId, commandId)
            state.value = TaskCommandProgress.Idle
        }
    }

    private suspend fun reconcileAccepted() = withContext(ioDispatcher) {
        reconcileMutex.withLock {
            val pending = journal.pendingCommands()
                .filter { it.kind in TASK_COMMAND_KINDS && it.taskId != null }
                .sortedWith(
                    compareBy<OutboundCommandRecord> { if (it.kind == "session.stop") 0 else 1 }
                        .thenBy(OutboundCommandRecord::createdAtMillis)
                        .thenBy(OutboundCommandRecord::requestId),
                )
            pending.forEach { record ->
                markDurablePending(
                    requireNotNull(record.taskId),
                    requireNotNull(record.commandId),
                    record.kind.toUiKind(),
                )
            }
            pending.filter { it.kind == "session.stop" }.forEach { stop ->
                val taskId = requireNotNull(stop.taskId)
                val commandId = requireNotNull(stop.commandId)
                dominantStop[taskId] = commandId
                suppressOtherPending(taskId, commandId)
            }
            pending.forEach { record ->
                executeOnce(record, recovering = true)
            }
        }
    }

    private suspend fun executeOnce(record: OutboundCommandRecord, recovering: Boolean) {
        val commandId = requireNotNull(record.commandId) { "Task command is missing commandId" }
        val taskId = requireNotNull(record.taskId) { "Task command is missing taskId" }
        val kind = record.kind.toUiKind()
        val text = record.commandText()
        commandMutexes.getOrPut(commandId) { Mutex() }.withLock {
            val current = requireNotNull(journal.commandByCommandId(commandId)) {
                "Accepted task command disappeared"
            }
            if (current.state == OutboundCommandState.TERMINAL) {
                completeFromDurable(current, kind, text)
                return
            }
            if (transportStatus.value.phase != SecureTransportUiPhase.READY) {
                publish(taskId, commandId, TaskCommandProgress.Working(commandId, kind, text, recovering = true))
                return
            }
            publish(taskId, commandId, TaskCommandProgress.Working(commandId, kind, text, recovering))
            try {
                val received = submitExact(current.toExactRequest())
                completeFromOutcome(taskId, commandId, kind, text, TaskCommandResponseDecoder.decode(current.kind, current.requestId, received))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TimeoutException) {
                publish(taskId, commandId, TaskCommandProgress.Working(commandId, kind, text, recovering = true))
            } catch (_: IOException) {
                publish(taskId, commandId, TaskCommandProgress.Working(commandId, kind, text, recovering = true))
            } catch (_: IllegalStateException) {
                publish(taskId, commandId, TaskCommandProgress.Working(commandId, kind, text, recovering = true))
            } catch (_: Exception) {
                publish(
                    taskId,
                    commandId,
                    TaskCommandProgress.Failed(
                        commandId,
                        kind,
                        WireErrorCode.RECOVERY_REQUIRED,
                        retryable = false,
                        text = text,
                    ),
                )
            }
        }
    }

    private fun completeFromDurable(
        record: OutboundCommandRecord,
        kind: TaskCommandKind,
        text: String?,
    ) {
        val received = ReliabilityContractDecoder.decode(
            requireNotNull(record.responseJson) { "Terminal task command is missing response" },
        )
        completeFromOutcome(
            taskId = requireNotNull(record.taskId),
            commandId = requireNotNull(record.commandId),
            kind = kind,
            text = text,
            outcome = TaskCommandResponseDecoder.decode(record.kind, record.requestId, received),
        )
    }

    private fun completeFromOutcome(
        taskId: String,
        commandId: String,
        kind: TaskCommandKind,
        text: String?,
        outcome: ExactWireOutcome<Any>,
    ) {
        when (outcome) {
            is ExactWireOutcome.Failure -> publish(
                taskId,
                commandId,
                TaskCommandProgress.Failed(
                    commandId,
                    kind,
                    outcome.error.code,
                    outcome.error.retryable,
                    text,
                ),
            )
            is ExactWireOutcome.Success -> {
                val queueDepth = (outcome.value as? QueueAcceptedSuccess)?.queueDepth
                val runState = when (val value = outcome.value) {
                    is StopAcceptedSuccess -> value.runState
                    is app.momoding.core.transport.PromptAcceptedSuccess -> value.runState
                    else -> null
                }
                publish(
                    taskId,
                    commandId,
                    TaskCommandProgress.Completed(commandId, kind, queueDepth, runState),
                )
            }
        }
        clearDurablePending(taskId, commandId)
    }

    private fun exactRequest(
        taskId: String,
        kind: TaskCommandKind,
        text: String?,
        requestId: String,
        commandId: String,
    ): OutboundWireRequest {
        val wireKind = kind.toWireKind()
        val payload = when (kind) {
            TaskCommandKind.PROMPT -> ClientWireCodec.encodeSessionPrompt(
                requestId,
                commandId,
                taskId,
                requireNotNull(text),
            )
            TaskCommandKind.STEER -> ClientWireCodec.encodeSessionSteer(
                requestId,
                commandId,
                taskId,
                requireNotNull(text),
            )
            TaskCommandKind.FOLLOW_UP -> ClientWireCodec.encodeSessionFollowUp(
                requestId,
                commandId,
                taskId,
                requireNotNull(text),
            )
            TaskCommandKind.STOP -> ClientWireCodec.encodeSessionStop(
                requestId,
                commandId,
                taskId,
                STOP_REASON,
            )
        }
        return OutboundWireRequest(
            requestId = requestId,
            kind = wireKind,
            canonicalPayload = payload,
            mutating = true,
            commandId = commandId,
            taskId = taskId,
        )
    }

    private fun OutboundCommandRecord.toExactRequest() = OutboundWireRequest(
        requestId = requestId,
        kind = kind,
        canonicalPayload = canonicalPayload,
        mutating = true,
        commandId = commandId,
        taskId = taskId,
    )

    private fun OutboundCommandRecord.commandText(): String? {
        if (kind == "session.stop") return null
        val payload = strictJson.parseToJsonElement(canonicalPayload) as JsonObject
        return (payload["text"] as? JsonPrimitive)?.contentOrNull
    }

    private fun reserve(taskId: String, kind: TaskCommandKind): Boolean = synchronized(acceptingLock) {
        require(taskId.isNotBlank()) { "taskId is blank" }
        val kinds = accepting.getOrPut(taskId) { mutableSetOf() }
        val pendingKinds = durablePending[taskId]?.values.orEmpty()
        val allowed = when (kind) {
            TaskCommandKind.STOP -> TaskCommandKind.STOP !in kinds && TaskCommandKind.STOP !in pendingKinds
            else -> kinds.isEmpty() && pendingKinds.isEmpty() && dominantStop[taskId] == null
        }
        if (allowed) kinds += kind
        allowed
    }

    private fun release(taskId: String, kind: TaskCommandKind) = synchronized(acceptingLock) {
        accepting[taskId]?.let { kinds ->
            kinds -= kind
            if (kinds.isEmpty()) accepting.remove(taskId)
        }
    }

    private fun publish(taskId: String, commandId: String, value: TaskCommandProgress) {
        if (commandId in suppressedCommands) return
        val stop = dominantStop[taskId]
        if (stop != null && stop != commandId) {
            suppressedCommands += commandId
            return
        }
        progress.getOrPut(taskId) { MutableStateFlow(TaskCommandProgress.Idle) }.value = value
    }

    private fun hydratePendingCommands() {
        journal.pendingCommands()
            .filter { it.kind in TASK_COMMAND_KINDS && it.taskId != null && it.commandId != null }
            .forEach { record ->
                markDurablePending(
                    requireNotNull(record.taskId),
                    requireNotNull(record.commandId),
                    record.kind.toUiKind(),
                )
                if (record.kind == "session.stop") {
                    dominantStop[requireNotNull(record.taskId)] = requireNotNull(record.commandId)
                }
            }
    }

    private fun markDurablePending(taskId: String, commandId: String, kind: TaskCommandKind) =
        synchronized(acceptingLock) {
            durablePending.getOrPut(taskId) { mutableMapOf() }[commandId] = kind
        }

    private fun clearDurablePending(taskId: String, commandId: String) = synchronized(acceptingLock) {
        durablePending[taskId]?.let { commands ->
            commands.remove(commandId)
            if (commands.isEmpty()) durablePending.remove(taskId)
        }
    }

    private fun suppressOtherPending(taskId: String, stopCommandId: String) = synchronized(acceptingLock) {
        durablePending[taskId]
            ?.keys
            ?.filter { it != stopCommandId }
            ?.forEach { commandId -> suppressedCommands += commandId }
    }

    private fun TaskCommandKind.toWireKind(): String = when (this) {
        TaskCommandKind.PROMPT -> "session.prompt"
        TaskCommandKind.STEER -> "session.steer"
        TaskCommandKind.FOLLOW_UP -> "session.follow_up"
        TaskCommandKind.STOP -> "session.stop"
    }

    private fun String.toUiKind(): TaskCommandKind = when (this) {
        "session.prompt" -> TaskCommandKind.PROMPT
        "session.steer" -> TaskCommandKind.STEER
        "session.follow_up" -> TaskCommandKind.FOLLOW_UP
        "session.stop" -> TaskCommandKind.STOP
        else -> throw IllegalArgumentException("Unsupported task command kind: $this")
    }

    private companion object {
        const val STOP_REASON = "user"
        val TASK_COMMAND_KINDS = setOf(
            "session.prompt",
            "session.steer",
            "session.follow_up",
            "session.stop",
        )
        val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }
    }
}
