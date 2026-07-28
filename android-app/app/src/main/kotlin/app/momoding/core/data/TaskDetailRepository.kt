package app.momoding.core.data

import app.momoding.core.policy.TaskApprovalMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class TaskDetailMessageRecord(
    val stableItemId: String,
    val ordinal: Long,
    val kind: String,
    val rawPayload: String,
)

data class TaskDetailEventRecord(
    val sequence: Long,
    val streamId: String,
    val digest: String,
    val eventJson: String,
)

data class TaskDetailAttentionRecord(
    val callId: String,
    val toolName: String,
    val responseState: AttentionResponseState,
    val receivedAtMillis: Long,
)

data class TaskDetailGoalRecord(
    val goalId: String,
    val instruction: String,
    val state: String,
    val progressSummary: String?,
    val progressMarker: String?,
    val terminalReason: String?,
    val pauseReason: String?,
    val automaticTurnCount: Int,
    val generation: Int,
)

data class TaskDetailChildAgentRecord(
    val parentToolCallId: String,
    val childId: String,
    val childName: String,
    val instruction: String,
    val state: String,
    val resultSummary: String?,
    val resultText: String?,
    val resultTruncated: Boolean,
    val terminalReason: String?,
    val stopReason: String?,
    val model: String?,
    val turnCount: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val contextTokens: Int,
    val costUsd: Double,
    val eventCount: Int,
)

data class TaskDetailSnapshot(
    val taskId: String,
    val title: String,
    val runState: String?,
    val recoveryState: String?,
    val streamId: String?,
    val throughSequence: Long,
    val snapshotVersion: Long?,
    val windowStart: Long,
    val windowEndExclusive: Long,
    val isStreaming: Boolean,
    val queueJson: String,
    val messages: List<TaskDetailMessageRecord>,
    val rawEvents: List<TaskDetailEventRecord>,
    val pendingAttention: List<TaskDetailAttentionRecord>,
    val activeStopFence: Boolean,
    val goal: TaskDetailGoalRecord? = null,
    val childAgents: List<TaskDetailChildAgentRecord> = emptyList(),
    val approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
    val failure: TaskFailure? = null,
)

class TaskDetailRepository(
    database: MomodingDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.p2Dao()
    private val attentionValidator = RoomAttentionLedger(database)

    fun observe(taskId: String): Flow<TaskDetailSnapshot?> {
        require(taskId.isNotBlank()) { "taskId is blank" }
        return dao.observeTaskDetail(taskId).map { entity ->
            entity?.toSnapshot(taskId, attentionValidator)
        }
    }

    suspend fun updateApprovalMode(
        taskId: String,
        mode: TaskApprovalMode,
    ) = withContext(ioDispatcher) {
        require(taskId.isNotBlank()) { "taskId is blank" }
        check(dao.updateTaskApprovalMode(taskId, mode) == 1) {
            "Cannot update an unknown task approval mode"
        }
    }
}

private fun TaskDetailEntity.toSnapshot(
    expectedTaskId: String,
    validator: RoomAttentionLedger,
): TaskDetailSnapshot {
    require(task.taskId == expectedTaskId) { "Task detail query returned another task" }
    val sortedTimeline = timeline.sortedBy(TimelineProjectionEntity::ordinal)
    require(task.windowStart >= 0 && task.windowEndExclusive >= task.windowStart) {
        "Task detail timeline window is invalid"
    }
    require(sortedTimeline.size.toLong() == task.windowEndExclusive - task.windowStart &&
        sortedTimeline.withIndex().all { (index, row) ->
            row.ordinal == task.windowStart + index
        }
    ) {
        "Task detail timeline window is not contiguous"
    }
    val sortedEvents = rawEvents.sortedBy(RawPiEventEntity::sequence)
    sortedEvents.zipWithNext().forEach { (left, right) ->
        require(right.sequence == left.sequence + 1) { "Task detail raw event suffix has a gap" }
        require(right.streamId == left.streamId) { "Task detail raw event suffix changes stream" }
    }
    task.streamId?.let { streamId ->
        require(sortedEvents.all { it.streamId == streamId }) { "Task detail raw event stream differs" }
    }
    sortedEvents.lastOrNull()?.let { tail ->
        require(tail.sequence == task.throughSequence) { "Task detail raw event tail differs from cursor" }
    }
    val attention = validatedAttentionRecords(deviceOperations, localAttention, validator)
        .filter(AttentionLedgerRecord::isTaskEntryVisible)
        .sortedWith(TASK_ATTENTION_PRIMARY_ORDER)
    require(goals.size <= 1) { "Task has more than one Goal row" }
    val goal = goals.singleOrNull()
    require(goal == null || goal.state in setOf(
        "ACTIVE", "PAUSE_PENDING", "PAUSED", "BLOCKED", "LIMITED", "FAILED", "ACHIEVED", "CLEARED",
    )) { "Task Goal state is invalid" }
    val sortedChildren = childAgents.sortedWith(
        compareBy(TaskChildAgentEntity::createdAtMillis, TaskChildAgentEntity::parentToolCallId),
    )
    require(sortedChildren.all { it.state in setOf("RUNNING", "COMPLETED", "FAILED", "CANCELLED") }) {
        "Task child Agent state is invalid"
    }
    return TaskDetailSnapshot(
        taskId = task.taskId,
        title = task.title,
        runState = task.runState,
        recoveryState = task.recoveryState,
        streamId = task.streamId,
        throughSequence = task.throughSequence,
        snapshotVersion = task.snapshotVersion,
        windowStart = task.windowStart,
        windowEndExclusive = task.windowEndExclusive,
        isStreaming = task.isStreaming,
        queueJson = task.queueJson,
        messages = sortedTimeline.map { row ->
            TaskDetailMessageRecord(row.stableItemId, row.ordinal, row.kind, row.rawPayload)
        },
        rawEvents = sortedEvents.map { row ->
            TaskDetailEventRecord(row.sequence, row.streamId, row.digest, row.eventJson)
        },
        pendingAttention = attention.map { row ->
            TaskDetailAttentionRecord(
                callId = row.operation.callId,
                toolName = row.operation.toolName,
                responseState = AttentionResponseState.valueOf(row.attention.responseState),
                receivedAtMillis = row.operation.receivedAtMillis,
            )
        },
        activeStopFence = outboundCommands.any { command ->
            command.kind == "session.stop" && command.stopFenceState == StopFenceState.ACTIVE.name
        },
        goal = goal?.let { row ->
            TaskDetailGoalRecord(
                goalId = row.goalId,
                instruction = row.instruction,
                state = row.state,
                progressSummary = row.progressSummary,
                progressMarker = row.progressMarker,
                terminalReason = row.terminalReason,
                pauseReason = row.pauseReason,
                automaticTurnCount = row.automaticTurnCount,
                generation = row.generation,
            )
        },
        childAgents = sortedChildren.map { row ->
            TaskDetailChildAgentRecord(
                parentToolCallId = row.parentToolCallId,
                childId = row.childId,
                childName = row.childName,
                instruction = row.instruction,
                state = row.state,
                resultSummary = row.resultSummary,
                resultText = row.resultText,
                resultTruncated = row.resultTruncated,
                terminalReason = row.terminalReason,
                stopReason = row.stopReason,
                model = row.model,
                turnCount = row.turnCount,
                inputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cacheReadTokens = row.cacheReadTokens,
                cacheWriteTokens = row.cacheWriteTokens,
                contextTokens = row.contextTokens,
                costUsd = row.costUsd,
                eventCount = row.eventCount,
            )
        },
        approvalMode = task.approvalMode,
        failure = task.storedTaskFailure(),
    )
}
