package app.momoding.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class TaskAttentionKind {
    NONE,
    QUESTION,
    CONFIRMATION,
    FILE_CONTENT,
    MULTIPLE,
    UNSUPPORTED,
}

data class TaskListRow(
    val taskId: String,
    val title: String,
    val runState: String?,
    val recoveryState: String?,
    val isUnread: Boolean,
    val attentionKind: TaskAttentionKind,
    val primaryAttentionCallId: String?,
    val primaryAttentionToolName: String?,
    val primaryAttentionResponseState: AttentionResponseState? = null,
    val attentionCount: Int,
    val updatedAtMillis: Long,
    val pinnedAtMillis: Long? = null,
    val archivedAtMillis: Long? = null,
    val failure: TaskFailure? = null,
)

/** The only S1 task-row read model: one observable Room query, never per-row lookups. */
class TaskRepository(
    private val database: MomodingDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.p2Dao()
    private val attentionValidator = RoomAttentionLedger(database)

    fun observeTaskRows(): Flow<List<TaskListRow>> = dao.observeTaskListRows().map { rows ->
        rows.map { row -> row.toTaskListRow(attentionValidator) }
    }

    suspend fun markRead(taskId: String): Boolean = withContext(ioDispatcher) {
        require(taskId.isNotBlank()) { "taskId is blank" }
        dao.markTaskRead(taskId) != null
    }

    suspend fun rename(taskId: String, requestedTitle: String): String = withContext(ioDispatcher) {
        val title = normalizeTaskTitle(requestedTitle)
        require(dao.renameTask(requireTaskId(taskId), title) == 1) { "Task not found" }
        title
    }

    suspend fun setPinned(taskId: String, pinned: Boolean): Boolean = withContext(ioDispatcher) {
        val exactTaskId = requireTaskId(taskId)
        val task = requireNotNull(dao.task(exactTaskId)) { "Task not found" }
        require(task.archivedAtMillis == null) { "Archived tasks cannot be pinned" }
        require(dao.setTaskPinnedAt(exactTaskId, if (pinned) nowMillis() else null) == 1)
        pinned
    }

    suspend fun archive(taskId: String): Unit = withContext(ioDispatcher) {
        val exactTaskId = requireTaskId(taskId)
        val task = requireNotNull(dao.task(exactTaskId)) { "Task not found" }
        require(task.isSettledForManagement()) { "Stop the task before archiving it" }
        require(dao.archiveSettledTask(exactTaskId, nowMillis()) == 1) {
            "The task became active before it could be archived"
        }
    }

    suspend fun restore(taskId: String): Unit = withContext(ioDispatcher) {
        val exactTaskId = requireTaskId(taskId)
        val task = requireNotNull(dao.task(exactTaskId)) { "Task not found" }
        require(task.archivedAtMillis != null) { "Task is not archived" }
        require(dao.restoreArchivedTask(exactTaskId) == 1) { "Task is not archived" }
    }

    suspend fun deleteArchived(taskId: String): Unit = withContext(ioDispatcher) {
        val exactTaskId = requireTaskId(taskId)
        val task = requireNotNull(dao.task(exactTaskId)) { "Task not found" }
        require(task.archivedAtMillis != null) { "Only archived tasks can be deleted" }
        require(task.isSettledForManagement()) { "Stop the task before deleting it" }
        require(dao.deleteArchivedSettledTaskAndPayload(exactTaskId) == 1) {
            "The archived task became active before it could be deleted"
        }
    }

    private fun requireTaskId(taskId: String): String = taskId.trim().also {
        require(it.isNotEmpty()) { "taskId is blank" }
    }
}

private fun TaskListRowEntity.toTaskListRow(
    validator: RoomAttentionLedger,
): TaskListRow {
    val visibleAttention = validatedAttentionRecords(
        deviceOperations,
        localAttention,
        validator,
    ).filter(AttentionLedgerRecord::isTaskEntryVisible)
        .sortedWith(TASK_ATTENTION_PRIMARY_ORDER)
    val primary = visibleAttention.firstOrNull()
    val count = visibleAttention.size
    val primaryToolName = primary?.operation?.toolName
    return TaskListRow(
        taskId = task.taskId,
        title = task.title,
        runState = task.runState,
        recoveryState = task.recoveryState,
        isUnread = task.readState != "READ",
        attentionKind = when {
            count <= 0 -> TaskAttentionKind.NONE
            count > 1 -> TaskAttentionKind.MULTIPLE
            primaryToolName == "request_user_question" -> TaskAttentionKind.QUESTION
            primaryToolName == "request_user_confirmation" -> TaskAttentionKind.CONFIRMATION
            primaryToolName == "device_media_list" -> TaskAttentionKind.CONFIRMATION
            primaryToolName == "device_ui_action" -> TaskAttentionKind.CONFIRMATION
            primaryToolName == "device_files_read" -> TaskAttentionKind.FILE_CONTENT
            else -> TaskAttentionKind.UNSUPPORTED
        },
        primaryAttentionCallId = primary?.operation?.callId,
        primaryAttentionToolName = primaryToolName,
        primaryAttentionResponseState = primary?.attention?.responseState?.let(
            AttentionResponseState::valueOf,
        ),
        attentionCount = count,
        updatedAtMillis = task.hostUpdatedAtMillis ?: task.updatedAtMillis,
        pinnedAtMillis = task.pinnedAtMillis,
        archivedAtMillis = task.archivedAtMillis,
        failure = taskFailureFromStored(
            task.failureKind,
            task.failureMessage,
            task.failureRecovery,
        ),
    )
}

internal fun normalizeTaskTitle(requestedTitle: String): String {
    val normalized = requestedTitle.trim().replace(Regex("\\s+"), " ")
    require(normalized.isNotEmpty()) { "Task title is blank" }
    require(normalized.codePointCount(0, normalized.length) <= 80) {
        "Task title must be 80 characters or fewer"
    }
    return normalized
}

private fun TaskEntity.isSettledForManagement(): Boolean =
    !isStreaming && runState !in setOf(
        "STARTING",
        "RUNNING",
        "WAITING",
        "RETRYING",
        "COMPACTING",
        "STOPPING",
    )

internal fun validatedAttentionRecords(
    operations: List<DeviceOperationEntity>,
    attention: List<PendingAttentionEntity>,
    validator: RoomAttentionLedger,
): List<AttentionLedgerRecord> {
    val attentionByCall = attention.groupBy(PendingAttentionEntity::callId)
    return operations.mapNotNull { operation ->
        val projection = attentionByCall[operation.callId]?.singleOrNull() ?: return@mapNotNull null
        try {
            validator.validatePair(operation, projection)
            AttentionLedgerRecord(operation, projection)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }
}

internal fun AttentionLedgerRecord.isTaskEntryVisible(): Boolean =
    operation.ledgerState != AttentionLedgerState.FAILED_CLOSED.name &&
        attention.responseState in setOf(
            AttentionResponseState.PENDING.name,
            AttentionResponseState.VALIDATION_ERROR.name,
            AttentionResponseState.RESPONDING.name,
        )

internal val TASK_ATTENTION_PRIMARY_ORDER: Comparator<AttentionLedgerRecord> =
    compareBy<AttentionLedgerRecord> { record ->
        when (record.attention.responseState) {
            AttentionResponseState.PENDING.name,
            AttentionResponseState.VALIDATION_ERROR.name,
            -> 0
            else -> 1
        }
    }.thenBy { it.operation.receivedAtMillis }
        .thenBy { it.operation.callId }
