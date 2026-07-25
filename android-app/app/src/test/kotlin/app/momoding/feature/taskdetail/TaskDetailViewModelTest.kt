package app.momoding.feature.taskdetail

import androidx.compose.ui.text.input.TextFieldValue
import app.momoding.core.data.TaskDetailGoalRecord
import app.momoding.core.data.TaskDetailChildAgentRecord
import app.momoding.core.data.TaskDetailSnapshot
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.core.transport.TaskReplayProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDetailViewModelTest {
    @Test
    fun `pending attention uses its matching timeline tool and falls back only when missing`() {
        val attentionCallId = "question-1"
        val matching = TimelineItem.ToolActivity(
            stableKey = "tool:$attentionCallId",
            toolCallId = attentionCallId,
            title = "Asked a question",
            detail = "Waiting for your answer",
            state = ToolActivityState.RUNNING,
        )

        assertTrue(TimelineWindow(settledItems = listOf(matching)).containsAttentionCall(attentionCallId))
        assertTrue(TimelineWindow(activeItem = matching).containsAttentionCall(attentionCallId))
        assertFalse(TimelineWindow().containsAttentionCall(attentionCallId))
        assertFalse(
            TimelineWindow(settledItems = listOf(matching)).containsAttentionCall("another-question"),
        )
    }

    @Test
    fun `composer cannot submit text while a photo import is still mutating the attachment set`() {
        val ready = TaskDetailUiState(
            taskId = TASK_ID,
            loadState = TaskDetailLoadState.READY,
            connection = TaskDetailConnectionState.CONNECTED,
            runState = TaskDetailRunState.SETTLED,
            composerMode = TaskComposerMode.PROMPT,
            composer = TextFieldValue("Send after import"),
            composerBlockedReason = null,
        )

        assertTrue(ready.canSubmit)
        assertFalse(ready.copy(attachmentImporting = true).canSubmit)
        assertFalse(ready.copy(approvalModeSaving = true).canSubmit)
    }

    @Test
    fun `task projection restores exact approval mode and preserves an optimistic selection while saving`() {
        val durable = projectTaskDetailUiState(
            current = TaskDetailUiState(TASK_ID),
            snapshot = snapshot(false).copy(approvalMode = TaskApprovalMode.AUTO_APPROVE),
            projection = PiUiProjection(TaskDetailRunState.SETTLED, TimelineWindow(), emptyList(), null, null),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY, "this phone"),
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
        )
        assertEquals(TaskApprovalMode.AUTO_APPROVE, durable.approvalMode)

        val optimistic = projectTaskDetailUiState(
            current = durable.copy(
                approvalMode = TaskApprovalMode.FULL_ACCESS,
                approvalModeSaving = true,
            ),
            snapshot = snapshot(false).copy(approvalMode = TaskApprovalMode.AUTO_APPROVE),
            projection = PiUiProjection(TaskDetailRunState.SETTLED, TimelineWindow(), emptyList(), null, null),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY, "this phone"),
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
        )
        assertEquals(TaskApprovalMode.FULL_ACCESS, optimistic.approvalMode)
    }

    @Test
    fun `running stopped and failed states expose only their real direct Pi composer path`() {
        val running = project(TaskDetailRunState.RUNNING)
        assertEquals(TaskComposerMode.FOLLOW_UP, running.composerMode)
        assertTrue(running.canStop)

        val legacySteerPreference = project(
            TaskDetailRunState.RUNNING,
            current = TaskDetailUiState(TASK_ID, runningMode = RunningComposerMode.STEER),
        )
        assertEquals(TaskComposerMode.FOLLOW_UP, legacySteerPreference.composerMode)

        val stopped = project(TaskDetailRunState.STOPPED)
        assertEquals(TaskComposerMode.PROMPT, stopped.composerMode)
        assertFalse(stopped.canStop)
        val failed = project(TaskDetailRunState.FAILED)
        assertEquals(TaskComposerMode.PROMPT, failed.composerMode)
    }

    @Test
    fun `attention retry compaction recovery and durable stop fence block composer`() {
        val attention = project(
            TaskDetailRunState.WAITING,
            attention = TaskAttentionUiModel("question-1", "The agent asked a question"),
        )
        assertEquals(TaskComposerMode.BLOCKED, attention.composerMode)
        assertFalse(attention.canEditComposer)
        assertEquals("question-1", attention.attention?.callId)
        assertTrue(attention.canStop)

        assertEquals(TaskComposerMode.BLOCKED, project(TaskDetailRunState.RETRYING).composerMode)
        assertFalse(project(TaskDetailRunState.RETRYING).canEditComposer)
        assertEquals(TaskComposerMode.BLOCKED, project(TaskDetailRunState.COMPACTING).composerMode)
        assertEquals(TaskComposerMode.BLOCKED, project(TaskDetailRunState.RECOVERING).composerMode)

        val fenced = project(TaskDetailRunState.RUNNING, stopFenced = true)
        assertEquals(TaskComposerMode.BLOCKED, fenced.composerMode)
        assertTrue(fenced.activeStopFence)
        assertFalse(fenced.canStop)
    }

    @Test
    fun `offline preserves last known running state and permits durable stop but not submit`() {
        val offline = project(TaskDetailRunState.RUNNING, phase = SecureTransportUiPhase.OFFLINE)
        assertEquals(TaskDetailConnectionState.OFFLINE, offline.connection)
        assertTrue(offline.canStop)
        assertFalse(offline.canSubmit)
        assertTrue(offline.canEditComposer)
        assertEquals("Reconnect to the Host to send a message.", offline.composerBlockedReason)
    }

    @Test
    fun `task open typed outcomes reach missing error and success waiting states`() {
        val base = TaskDetailUiState(TASK_ID)
        val transport = SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio")
        val missing = projectTaskDetailUiState(
            current = base,
            snapshot = null,
            projection = null,
            transport = transport,
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
            openResolution = TaskOpenResolution.MISSING,
        )
        assertEquals(TaskDetailLoadState.MISSING, missing.loadState)
        val failed = projectTaskDetailUiState(
            current = base,
            snapshot = null,
            projection = null,
            transport = transport,
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
            openResolution = TaskOpenResolution.ERROR,
        )
        assertEquals(TaskDetailLoadState.ERROR, failed.loadState)
        val accepted = projectTaskDetailUiState(
            current = base,
            snapshot = null,
            projection = null,
            transport = transport,
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
            openResolution = TaskOpenResolution.ACCEPTED,
        )
        assertEquals(TaskDetailLoadState.LOADING, accepted.loadState)
    }

    @Test
    fun `wire replay overrides live state until the per task fence completes`() {
        val replaying = projectTaskDetailUiState(
            current = TaskDetailUiState(TASK_ID),
            snapshot = snapshot(false),
            projection = PiUiProjection(TaskDetailRunState.RUNNING, TimelineWindow(), emptyList(), null, null),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.SYNCHRONIZING, "Mac Studio"),
            replay = TaskReplayProgress(TASK_ID, replayedEventCount = 7, throughSequence = 9),
            commandProgress = TaskCommandProgress.Idle,
        )
        assertEquals(TaskDetailRunState.RECOVERING, replaying.runState)
        assertEquals(TaskRecoveryKind.WIRE_REPLAY, replaying.recovery?.kind)
        assertEquals(7L, replaying.recovery?.replayedEventCount)
        assertEquals(TaskComposerMode.BLOCKED, replaying.composerMode)
        assertEquals(TaskDetailConnectionState.CONNECTED, replaying.connection)
    }

    @Test
    fun `a completed task resumes while another task keeps the transport synchronizing`() {
        val released = projectTaskDetailUiState(
            current = TaskDetailUiState(TASK_ID),
            snapshot = snapshot(false),
            projection = PiUiProjection(TaskDetailRunState.RUNNING, TimelineWindow(), emptyList(), null, null),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.SYNCHRONIZING, "Mac Studio"),
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
        )

        assertEquals(TaskDetailRunState.RUNNING, released.runState)
        assertEquals(TaskComposerMode.FOLLOW_UP, released.composerMode)
        assertEquals(null, released.recovery)
        assertTrue(released.canEditComposer)
    }

    @Test
    fun `attention navigation accepts only the current exact local primary call`() {
        val state = TaskDetailUiState(
            taskId = TASK_ID,
            attention = TaskAttentionUiModel("call-exact", "Needs attention"),
        )

        assertEquals(
            TaskDetailOneShot.OpenAttention("call-exact"),
            taskDetailAttentionOneShot(state, "call-exact"),
        )
        assertEquals(null, taskDetailAttentionOneShot(state, "call-stale"))
        assertEquals(null, taskDetailAttentionOneShot(state, ""))
        assertEquals(null, taskDetailAttentionOneShot(state.copy(attention = null), "call-exact"))
        assertEquals(
            null,
            taskDetailAttentionOneShot(
                state.copy(attention = TaskAttentionUiModel("call-new-primary", "Needs attention")),
                "call-exact",
            ),
        )
    }

    @Test
    fun `phone local Provider failure exposes exact recovery and retry contract`() {
        val timeline = TimelineWindow(
            settledItems = listOf(
                TimelineItem.UserMessage("user-1", "Summarize the project"),
                TimelineItem.Error("error-1", "OpenRouter API key is invalid. Update it in Settings."),
            ),
        )
        val failed = projectTaskDetailUiState(
            current = TaskDetailUiState(TASK_ID, phoneLocal = true),
            snapshot = snapshot(false),
            projection = PiUiProjection(
                TaskDetailRunState.FAILED,
                timeline,
                emptyList(),
                null,
                null,
            ),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY, "this phone"),
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
        )

        assertTrue(failed.providerRecoveryAvailable)
        assertEquals("Summarize the project", failed.retryOriginalText)
        assertTrue(failed.canRetryOriginal)
        assertFalse(failed.copy(composer = TextFieldValue("Keep this draft")).canRetryOriginal)
        assertFalse(failed.copy(phoneLocal = false).providerRecoveryAvailable)
    }

    @Test
    fun `durable Goal projection exposes only lifecycle actions that are currently safe`() {
        val paused = projectTaskDetailUiState(
            current = TaskDetailUiState(TASK_ID, phoneLocal = true),
            snapshot = snapshot(false).copy(
                isStreaming = false,
                goal = TaskDetailGoalRecord(
                    goalId = "goal-1",
                    instruction = "Finish the verification work.",
                    state = "PAUSED",
                    progressSummary = "One checkpoint is complete.",
                    progressMarker = "1/2",
                    terminalReason = null,
                    pauseReason = "user_requested",
                    automaticTurnCount = 1,
                    generation = 1,
                ),
            ),
            projection = PiUiProjection(
                TaskDetailRunState.SETTLED,
                TimelineWindow(),
                emptyList(),
                null,
                null,
            ),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY, "this phone"),
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
        )

        assertEquals(TaskGoalState.PAUSED, paused.goal?.state)
        assertEquals("1/2", paused.goal?.progressMarker)
        assertTrue(paused.canResumeGoal)
        assertTrue(paused.canEditGoal)
        assertTrue(paused.canClearGoal)
        assertFalse(paused.canPauseGoal)
        assertFalse(paused.canCreateGoal)

        val pausedInPlanMode = paused.copy(planMode = true)
        assertFalse(pausedInPlanMode.canResumeGoal)
        assertFalse(pausedInPlanMode.canEditGoal)
        assertFalse(pausedInPlanMode.canClearGoal)

        val active = paused.copy(
            runState = TaskDetailRunState.RUNNING,
            goal = paused.goal?.copy(state = TaskGoalState.ACTIVE),
        )
        assertTrue(active.canPauseGoal)
        assertFalse(active.canResumeGoal)
        assertFalse(active.canEditGoal)
        assertFalse(active.canTogglePlanMode)
    }

    @Test
    fun `durable child projection overrides a stale delegate and preserves expanded detail`() {
        val timeline = TimelineWindow(
            activeItem = TimelineItem.ToolActivity(
                stableKey = "tool:delegate-a",
                toolCallId = "delegate-a",
                title = "delegate",
                detail = "Working",
                state = ToolActivityState.RUNNING,
            ),
        )
        val child = TaskDetailChildAgentRecord(
            parentToolCallId = "delegate-a",
            childId = "child-1",
            childName = "Build analyst",
            instruction = "Analyze build reproducibility.",
            state = "CANCELLED",
            resultSummary = null,
            resultText = null,
            resultTruncated = false,
            terminalReason = "runtime_rebuilt",
            stopReason = "aborted",
            model = null,
            turnCount = 0,
            inputTokens = 0,
            outputTokens = 0,
            cacheReadTokens = 0,
            cacheWriteTokens = 0,
            contextTokens = 0,
            costUsd = 0.0,
            eventCount = 2,
        )
        val projected = projectTaskDetailUiState(
            current = TaskDetailUiState(
                TASK_ID,
                phoneLocal = true,
                childAgents = listOf(
                    TaskChildAgentUiModel(
                        parentToolCallId = "delegate-a",
                        childId = "child-1",
                        name = "Build analyst",
                        instruction = child.instruction,
                        state = TaskChildAgentState.RUNNING,
                        expanded = true,
                    ),
                ),
            ),
            snapshot = snapshot(false).copy(childAgents = listOf(child)),
            projection = PiUiProjection(TaskDetailRunState.INTERRUPTED, timeline, emptyList(), null, null),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY, "this phone"),
            replay = null,
            commandProgress = TaskCommandProgress.Idle,
        )

        assertEquals(TaskChildAgentState.CANCELLED, projected.childAgents.single().state)
        assertTrue(projected.childAgents.single().expanded)
        val delegate = projected.timeline.activeItem as TimelineItem.ToolActivity
        assertEquals(ToolActivityState.CANCELLED, delegate.state)
        assertEquals("Delegated to Build analyst", delegate.title)
        assertEquals("runtime_rebuilt", delegate.detail)
    }

    private fun project(
        runState: TaskDetailRunState,
        current: TaskDetailUiState = TaskDetailUiState(TASK_ID),
        attention: TaskAttentionUiModel? = null,
        stopFenced: Boolean = false,
        phase: SecureTransportUiPhase = SecureTransportUiPhase.READY,
    ) = projectTaskDetailUiState(
        current = current,
        snapshot = snapshot(stopFenced),
        projection = PiUiProjection(runState, TimelineWindow(), emptyList(), attention, recovery = null),
        transport = SecureTransportUiStatus(phase, "Mac Studio"),
        replay = null,
        commandProgress = TaskCommandProgress.Idle,
    )

    private fun snapshot(stopFenced: Boolean) = TaskDetailSnapshot(
        taskId = TASK_ID,
        title = "Task shell",
        runState = "RUNNING",
        recoveryState = "NORMAL",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = 0,
        isStreaming = true,
        queueJson = "[]",
        messages = emptyList(),
        rawEvents = emptyList(),
        pendingAttention = emptyList(),
        activeStopFence = stopFenced,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
    }
}
