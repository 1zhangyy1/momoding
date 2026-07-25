package app.momoding.feature.newtask

import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.WireErrorCode
import app.momoding.core.data.DraftRecord
import app.momoding.core.data.OutboundCommandRecord
import app.momoding.core.data.OutboundCommandState
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.transport.ClientWireCodec
import app.momoding.core.transport.ExactWireOutcome
import app.momoding.core.transport.OutboundWireRequest
import app.momoding.core.transport.TaskCreationResponseDecoder
import app.momoding.core.transport.SecureTransportUiPhase
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TaskCreationStage { CREATE, PROMPT }

sealed interface TaskCreationProgress {
    data object Idle : TaskCreationProgress
    data class Working(val stage: TaskCreationStage, val recovering: Boolean) : TaskCreationProgress
    data class Started(val taskId: String) : TaskCreationProgress
    data class Failed(
        val stage: TaskCreationStage,
        val code: WireErrorCode,
        val retryable: Boolean,
        val safeMessage: String? = null,
    ) : TaskCreationProgress
}

/** Application-scope owner for the durable create -> prompt operation. */
class TaskCreationCoordinator(
    private val transportStatus: StateFlow<app.momoding.core.transport.SecureTransportUiStatus>,
    private val submitExact: suspend (OutboundWireRequest) -> app.momoding.wire.ReceivedReliabilityServerFrame,
    private val journal: RoomCommandDraftJournal,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val progress = ConcurrentHashMap<String, MutableStateFlow<TaskCreationProgress>>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val jobs = mutableMapOf<String, Job>()
    private val jobsLock = Any()
    private val pipelineSemaphore = Semaphore(permits = 1)

    init {
        applicationScope.launch {
            transportStatus
                .filter { it.phase == SecureTransportUiPhase.READY }
                .distinctUntilChanged()
                .collect { reconcileAcceptedDrafts() }
        }
    }

    fun observe(draftId: String): StateFlow<TaskCreationProgress> =
        progress.getOrPut(draftId) { MutableStateFlow(TaskCreationProgress.Idle) }

    fun start(draftId: String) = launch(draftId, rotateRetry = false)

    fun retry(draftId: String) = launch(draftId, rotateRetry = true)

    private fun launch(draftId: String, rotateRetry: Boolean) {
        require(draftId.isNotBlank()) { "draftId is blank" }
        synchronized(jobsLock) {
            if (jobs[draftId]?.isActive == true) return
            lateinit var launched: Job
            launched = applicationScope.launch(ioDispatcher) {
                try {
                    pipelineSemaphore.withPermit {
                        mutexes.getOrPut(draftId) { Mutex() }.withLock {
                            if (rotateRetry) {
                                val current = requireNotNull(journal.draft(draftId)) { "Draft is missing" }
                                journal.rotateRetryableAttempt(draftId, current.attemptOrdinal)
                            }
                            runDraft(draftId)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    progressFor(draftId).value = TaskCreationProgress.Failed(
                        stage = runCatching { stageFor(draftId) }.getOrDefault(TaskCreationStage.CREATE),
                        code = WireErrorCode.RECOVERY_REQUIRED,
                        retryable = false,
                    )
                } finally {
                    synchronized(jobsLock) {
                        if (jobs[draftId] === launched) jobs.remove(draftId)
                    }
                }
            }
            jobs[draftId] = launched
        }
    }

    private suspend fun runDraft(draftId: String) {
        var draft = requireNotNull(journal.draft(draftId)) { "Draft is missing" }
        require(draft.text.isNotBlank()) { "Draft is blank" }
        require(draft.text.length <= ClientWireCodec.MAX_PROMPT_UTF16_UNITS) { "Draft exceeds Host limit" }

        val createOutcome = executeCreate(draft)
        when (createOutcome) {
            is ExactWireOutcome.Failure -> {
                publishFailure(draftId, TaskCreationStage.CREATE, createOutcome)
                return
            }
            is ExactWireOutcome.Success -> {
                draft = journal.bindDraftTask(draftId, createOutcome.value.taskId)
            }
        }

        val promptOutcome = executePrompt(draft)
        when (promptOutcome) {
            is ExactWireOutcome.Failure -> publishFailure(draftId, TaskCreationStage.PROMPT, promptOutcome)
            is ExactWireOutcome.Success -> progressFor(draftId).value = TaskCreationProgress.Started(requireNotNull(draft.taskId))
        }
    }

    private suspend fun executeCreate(draft: DraftRecord): ExactWireOutcome<app.momoding.core.transport.TaskCreateSuccess> {
        val durable = journal.commandByCommandId(draft.createCommandId)
        val request = durable?.toExactRequest() ?: run {
            val requestId = requestIdFactory()
            OutboundWireRequest(
                requestId = requestId,
                kind = "task.create",
                canonicalPayload = ClientWireCodec.encodeTaskCreate(
                    requestId,
                    draft.createCommandId,
                    draft.draftId,
                    DraftTitlePolicy.title(draft.text),
                ),
                mutating = true,
                commandId = draft.createCommandId,
            )
        }
        progressFor(draft.draftId).value = TaskCreationProgress.Working(
            TaskCreationStage.CREATE,
            recovering = durable != null,
        )
        val received = awaitExactTerminal(draft.draftId, draft.createCommandId, request)
        return TaskCreationResponseDecoder.decodeCreate(request.requestId, received)
    }

    private suspend fun executePrompt(draft: DraftRecord): ExactWireOutcome<app.momoding.core.transport.PromptAcceptedSuccess> {
        val taskId = requireNotNull(draft.taskId) { "Prompt task binding is missing" }
        val durable = journal.commandByCommandId(draft.promptCommandId)
        val request = durable?.toExactRequest() ?: run {
            val requestId = requestIdFactory()
            OutboundWireRequest(
                requestId = requestId,
                kind = "session.prompt",
                canonicalPayload = ClientWireCodec.encodeSessionPrompt(
                    requestId,
                    draft.promptCommandId,
                    taskId,
                    draft.text,
                ),
                mutating = true,
                commandId = draft.promptCommandId,
                taskId = taskId,
            )
        }
        progressFor(draft.draftId).value = TaskCreationProgress.Working(
            TaskCreationStage.PROMPT,
            recovering = durable != null,
        )
        val received = awaitExactTerminal(draft.draftId, draft.promptCommandId, request)
        return TaskCreationResponseDecoder.decodePrompt(request.requestId, received)
    }

    private suspend fun awaitExactTerminal(
        draftId: String,
        commandId: String,
        original: OutboundWireRequest,
    ): app.momoding.wire.ReceivedReliabilityServerFrame {
        while (true) {
            val durable = journal.commandByCommandId(commandId)
            if (durable?.state == OutboundCommandState.TERMINAL) {
                return ReliabilityContractDecoder.decode(requireNotNull(durable.responseJson))
            }
            if (durable != null) {
                val stage = if (original.kind == "task.create") TaskCreationStage.CREATE else TaskCreationStage.PROMPT
                progressFor(draftId).value = TaskCreationProgress.Working(stage, recovering = true)
            }
            transportStatus.first { it.phase == SecureTransportUiPhase.READY }
            val request = durable?.toExactRequest() ?: original
            try {
                return submitExact(request)
            } catch (_: TimeoutException) {
                // Mutating request remains correlated inside WssActor; poll the durable terminal result.
            } catch (_: IOException) {
                // The exact accepted request will be replayed after the secure actor reconnects.
            } catch (_: IllegalStateException) {
                // The same request may still be pending in the active actor; never rotate here.
            }
            delay(RECOVERY_POLL_MILLIS)
        }
    }

    private fun publishFailure(
        draftId: String,
        stage: TaskCreationStage,
        outcome: ExactWireOutcome.Failure,
    ) {
        progressFor(draftId).value = TaskCreationProgress.Failed(
            stage = stage,
            code = outcome.error.code,
            retryable = outcome.error.retryable,
        )
    }

    private fun OutboundCommandRecord.toExactRequest(): OutboundWireRequest = OutboundWireRequest(
        requestId = requestId,
        kind = kind,
        canonicalPayload = canonicalPayload,
        mutating = true,
        commandId = commandId,
        taskId = taskId,
    )

    private fun progressFor(draftId: String): MutableStateFlow<TaskCreationProgress> =
        progress.getOrPut(draftId) { MutableStateFlow(TaskCreationProgress.Idle) }

    private fun stageFor(draftId: String): TaskCreationStage =
        if (journal.draft(draftId)?.taskId == null) TaskCreationStage.CREATE else TaskCreationStage.PROMPT

    private suspend fun reconcileAcceptedDrafts() = withContext(ioDispatcher) {
        journal.drafts().forEach { draft ->
            val started = journal.commandByCommandId(draft.createCommandId) != null ||
                journal.commandByCommandId(draft.promptCommandId) != null
            if (started) launch(draft.draftId, rotateRetry = false)
        }
    }

    private companion object {
        const val RECOVERY_POLL_MILLIS = 500L
    }
}
