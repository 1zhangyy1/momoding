package app.momoding.core.data

import app.momoding.wire.P1bProtocol
import app.momoding.core.policy.TaskApprovalMode
import java.util.UUID

data class HostTaskSummary(
    val taskId: String,
    val title: String?,
    val hostUpdatedAtMillis: Long,
    val runState: String,
    val recoveryState: String,
    val snapshotVersion: Long,
)

class RoomTaskListMerger(
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val generationFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.momodingDao()

    fun mergeCompleteList(
        listRevision: Long,
        summaries: List<HostTaskSummary>,
    ): String {
        require(listRevision in 1..P1bProtocol.MAX_SAFE_INTEGER) {
            "listRevision must be a positive safe integer"
        }
        require(summaries.size <= MAX_TASKS) { "Task list exceeds $MAX_TASKS summaries" }
        validateSummaries(summaries)
        val generation = canonicalUuid(generationFactory(), "generation")
        val insertedAtMillis = nowMillis()

        return database.runInTransaction<String> {
            summaries.forEach { summary ->
                val previous = dao.task(summary.taskId)
                val draftMode = dao.draftForTaskSync(summary.taskId)?.approvalMode
                dao.upsertTask(
                    summary.toEntity(previous, draftMode, generation, listRevision, insertedAtMillis),
                )
            }
            dao.markTasksMissingFromList(generation)
            generation
        }
    }

    fun listedTasks(): List<TaskEntity> = dao.listedTasks()

    private fun validateSummaries(summaries: List<HostTaskSummary>) {
        val taskIds = HashSet<String>(summaries.size)
        summaries.forEach { summary ->
            canonicalUuid(summary.taskId, "taskId")
            require(taskIds.add(summary.taskId)) { "Task list contains duplicate taskId" }
            require(summary.title == null || summary.title.length <= MAX_TITLE_CHARS) {
                "Task title is too long"
            }
            require(summary.hostUpdatedAtMillis >= 0) { "Host updatedAt is invalid" }
            require(summary.runState in RUN_STATES) { "Host runState is invalid" }
            require(summary.recoveryState in RECOVERY_STATES) { "Host recoveryState is invalid" }
            require(summary.snapshotVersion in 1..P1bProtocol.MAX_SAFE_INTEGER) {
                "Host snapshotVersion is invalid"
            }
        }
        summaries.zipWithNext().forEach { (left, right) ->
            require(
                left.hostUpdatedAtMillis > right.hostUpdatedAtMillis ||
                    left.hostUpdatedAtMillis == right.hostUpdatedAtMillis &&
                    left.taskId < right.taskId,
            ) { "Task list ordering is invalid" }
        }
    }

    private fun HostTaskSummary.toEntity(
        previous: TaskEntity?,
        draftMode: TaskApprovalMode?,
        generation: String,
        listRevision: Long,
        insertedAtMillis: Long,
    ): TaskEntity {
        val failure = taskFailureForRunState(
            runState = runState,
            previous = previous
                ?.takeIf { it.snapshotVersion == snapshotVersion }
                ?.storedTaskFailure(),
        )
        return TaskEntity(
            taskId = taskId,
            title = title.orEmpty(),
            runState = runState.uppercase(),
            recoveryState = recoveryState.uppercase(),
            readState = previous?.readState ?: "UNREAD",
            attentionState = previous?.attentionState ?: "NONE",
            streamId = previous?.streamId,
            throughSequence = previous?.throughSequence ?: 0,
            snapshotVersion = snapshotVersion,
            windowStart = previous?.windowStart ?: 0,
            windowEndExclusive = previous?.windowEndExclusive ?: 0,
            nextStageBatchOrdinal = previous?.nextStageBatchOrdinal ?: 1,
            queueJson = previous?.queueJson ?: "[]",
            piSessionId = previous?.piSessionId,
            isStreaming = previous?.isStreaming ?: false,
            updatedAtMillis = previous?.updatedAtMillis ?: insertedAtMillis,
            listedByHost = true,
            lastListSyncGeneration = generation,
            lastListRevision = listRevision,
            hostUpdatedAtMillis = hostUpdatedAtMillis,
            titleSource = previous?.titleSource ?: "LEGACY",
            pinnedAtMillis = previous?.pinnedAtMillis,
            archivedAtMillis = previous?.archivedAtMillis,
            approvalMode = previous?.approvalMode ?: draftMode ?: TaskApprovalMode.REQUEST_APPROVAL,
            failureKind = failure?.kind?.name,
            failureMessage = failure?.message,
            failureRecovery = failure?.recovery?.name,
        )
    }

    private companion object {
        const val MAX_TASKS = 10_000
        const val MAX_TITLE_CHARS = 4_096
        val RUN_STATES = setOf(
            "idle",
            "starting",
            "running",
            "waiting",
            "stopping",
            "stopped",
            "completed",
            "failed",
            "interrupted",
        )
        val RECOVERY_STATES = setOf("normal", "interrupted", "reconciling_device_calls")
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        fun canonicalUuid(value: String, field: String): String {
            require(UUID_PATTERN.matches(value)) { "$field must be a canonical UUID" }
            return value
        }
    }
}
