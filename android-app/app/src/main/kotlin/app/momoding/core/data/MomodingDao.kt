package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.momoding.core.policy.TaskApprovalMode
import kotlinx.coroutines.flow.Flow

@Dao
interface MomodingDao {
    @Query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    fun tableNames(): List<String>

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    fun task(taskId: String): TaskEntity?

    @Query(
        "SELECT * FROM tasks WHERE listedByHost = 1 " +
            "ORDER BY COALESCE(hostUpdatedAtMillis, updatedAtMillis) DESC, taskId ASC",
    )
    fun listedTasks(): List<TaskEntity>

    @Transaction
    @Query(
        "SELECT * FROM tasks WHERE listedByHost = 1 " +
            "ORDER BY COALESCE(hostUpdatedAtMillis, updatedAtMillis) DESC, taskId ASC",
    )
    fun observeTaskListRows(): Flow<List<TaskListRowEntity>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    fun observeTaskDetail(taskId: String): Flow<TaskDetailEntity?>

    @Query("SELECT * FROM tasks ORDER BY taskId")
    fun allTasks(): List<TaskEntity>

    @Upsert
    fun upsertTask(entity: TaskEntity)

    @Query("UPDATE tasks SET approvalMode = :approvalMode WHERE taskId = :taskId")
    fun updateTaskApprovalMode(taskId: String, approvalMode: TaskApprovalMode): Int

    @Query("SELECT * FROM task_goals WHERE taskId = :taskId")
    fun taskGoal(taskId: String): TaskGoalEntity?

    @Query("SELECT * FROM task_goals WHERE taskId = :taskId")
    fun observeTaskGoal(taskId: String): Flow<TaskGoalEntity?>

    @Query("SELECT * FROM task_goals ORDER BY taskId")
    fun allTaskGoals(): List<TaskGoalEntity>

    @Upsert
    fun upsertTaskGoal(entity: TaskGoalEntity)

    @Query(
        "SELECT * FROM task_child_agents WHERE taskId = :taskId " +
            "ORDER BY createdAtMillis, parentToolCallId",
    )
    fun taskChildAgents(taskId: String): List<TaskChildAgentEntity>

    @Query(
        "SELECT * FROM task_child_agents WHERE taskId = :taskId " +
            "AND parentToolCallId = :parentToolCallId",
    )
    fun taskChildAgent(taskId: String, parentToolCallId: String): TaskChildAgentEntity?

    @Upsert
    fun upsertTaskChildAgents(entities: List<TaskChildAgentEntity>)

    @Query(
        "SELECT * FROM task_child_agent_events WHERE taskId = :taskId " +
            "AND parentToolCallId = :parentToolCallId ORDER BY eventOrdinal",
    )
    fun taskChildAgentEvents(
        taskId: String,
        parentToolCallId: String,
    ): List<TaskChildAgentEventEntity>

    @Query(
        "SELECT * FROM task_child_agent_events WHERE taskId = :taskId " +
            "AND parentToolCallId = :parentToolCallId AND eventOrdinal = :eventOrdinal",
    )
    fun taskChildAgentEvent(
        taskId: String,
        parentToolCallId: String,
        eventOrdinal: Int,
    ): TaskChildAgentEventEntity?

    @Query(
        "SELECT MAX(eventOrdinal) FROM task_child_agent_events WHERE taskId = :taskId " +
            "AND parentToolCallId = :parentToolCallId",
    )
    fun lastTaskChildAgentEventOrdinal(taskId: String, parentToolCallId: String): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTaskChildAgentEvents(entities: List<TaskChildAgentEventEntity>)

    @Query(
        "UPDATE task_child_agents SET state = 'CANCELLED', " +
            "terminalReason = :reason, stopReason = 'aborted', updatedAtMillis = :updatedAtMillis " +
            "WHERE state = 'RUNNING'",
    )
    fun recoverRunningTaskChildAgents(reason: String, updatedAtMillis: Long): Int

    @Query(
        "UPDATE tasks SET title = :title, titleSource = 'USER' " +
            "WHERE taskId = :taskId",
    )
    fun renameTask(taskId: String, title: String): Int

    @Query(
        "UPDATE tasks SET title = :title, titleSource = 'AUTOMATIC' " +
            "WHERE taskId = :taskId AND titleSource NOT IN ('USER', 'AUTOMATIC')",
    )
    fun setAutomaticTaskTitle(taskId: String, title: String): Int

    @Query(
        "UPDATE tasks SET pinnedAtMillis = :pinnedAtMillis " +
            "WHERE taskId = :taskId AND archivedAtMillis IS NULL",
    )
    fun setTaskPinnedAt(taskId: String, pinnedAtMillis: Long?): Int

    @Query(
        "UPDATE tasks SET archivedAtMillis = :archivedAtMillis, pinnedAtMillis = NULL " +
            "WHERE taskId = :taskId AND archivedAtMillis IS NULL AND isStreaming = 0 " +
            "AND (runState IS NULL OR runState NOT IN " +
            "('STARTING','RUNNING','WAITING','RETRYING','COMPACTING','STOPPING')) " +
            "AND NOT EXISTS (SELECT 1 FROM outbound_commands command " +
            "WHERE command.taskId = tasks.taskId AND command.state = 'ACCEPTED')",
    )
    fun archiveSettledTask(taskId: String, archivedAtMillis: Long): Int

    @Query(
        "UPDATE tasks SET archivedAtMillis = NULL " +
            "WHERE taskId = :taskId AND archivedAtMillis IS NOT NULL",
    )
    fun restoreArchivedTask(taskId: String): Int

    @Query(
        "DELETE FROM tasks WHERE taskId = :taskId AND archivedAtMillis IS NOT NULL " +
            "AND isStreaming = 0 AND (runState IS NULL OR runState NOT IN " +
            "('STARTING','RUNNING','WAITING','RETRYING','COMPACTING','STOPPING')) " +
            "AND NOT EXISTS (SELECT 1 FROM outbound_commands command " +
            "WHERE command.taskId = tasks.taskId AND command.state = 'ACCEPTED')",
    )
    fun deleteArchivedSettledTaskRow(taskId: String): Int

    @Query(
        "DELETE FROM outbound_commands WHERE taskId = :taskId OR commandId IN (" +
            "SELECT createCommandId FROM drafts WHERE taskId = :taskId UNION " +
            "SELECT promptCommandId FROM drafts WHERE taskId = :taskId)",
    )
    fun deleteTaskOutboundCommands(taskId: String): Int

    @Query("DELETE FROM drafts WHERE taskId = :taskId")
    fun deleteTaskDrafts(taskId: String): Int

    @Transaction
    fun deleteArchivedSettledTaskAndPayload(taskId: String): Int {
        val deleted = deleteArchivedSettledTaskRow(taskId)
        if (deleted != 1) return deleted
        deleteTaskOutboundCommands(taskId)
        deleteTaskDrafts(taskId)
        return deleted
    }

    @Query("SELECT * FROM pi_session_snapshots WHERE taskId = :taskId")
    fun piSessionSnapshot(taskId: String): PiSessionSnapshotEntity?

    @Upsert
    fun upsertPiSessionSnapshot(entity: PiSessionSnapshotEntity)

    @Query("DELETE FROM pi_session_snapshots WHERE taskId = :taskId")
    fun deletePiSessionSnapshot(taskId: String): Int

    @Query(
        "UPDATE tasks SET listedByHost = 0 " +
            "WHERE lastListSyncGeneration IS NULL OR lastListSyncGeneration != :generation",
    )
    fun markTasksMissingFromList(generation: String): Int

    @Query("SELECT * FROM raw_pi_events WHERE taskId = :taskId ORDER BY sequence")
    fun rawEvents(taskId: String): List<RawPiEventEntity>

    @Query("DELETE FROM raw_pi_events WHERE taskId = :taskId")
    fun deleteRawEvents(taskId: String)

    @Insert
    fun insertRawEvents(entities: List<RawPiEventEntity>)

    @Query("SELECT * FROM pending_resync WHERE taskId = :taskId")
    fun pendingResync(taskId: String): PendingResyncEntity?

    @Query("DELETE FROM pending_resync WHERE taskId = :taskId")
    fun deletePendingResync(taskId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertPendingResync(entity: PendingResyncEntity)

    @Query(
        "SELECT * FROM staged_raw_frames WHERE taskId = :taskId " +
            "ORDER BY batchOrdinal, frameOrdinal",
    )
    fun stagedRawFrames(taskId: String): List<StagedRawFrameEntity>

    @Query("DELETE FROM staged_raw_frames WHERE taskId = :taskId")
    fun deleteStagedRawFrames(taskId: String)

    @Query(
        "DELETE FROM staged_raw_frames WHERE taskId = :taskId " +
            "AND batchOrdinal < :firstRetainedBatchOrdinal",
    )
    fun deleteStagedRawFramesBefore(taskId: String, firstRetainedBatchOrdinal: Long)

    @Insert
    fun insertStagedRawFrames(entities: List<StagedRawFrameEntity>)

    @Query("SELECT * FROM timeline_projections WHERE taskId = :taskId ORDER BY ordinal")
    fun timeline(taskId: String): List<TimelineProjectionEntity>

    @Query("DELETE FROM timeline_projections WHERE taskId = :taskId")
    fun deleteTimeline(taskId: String)

    @Insert
    fun insertTimeline(entities: List<TimelineProjectionEntity>)

    @Query(
        "SELECT * FROM host_pending_attention_observations " +
            "WHERE taskId = :taskId ORDER BY ordinal",
    )
    fun hostPendingAttention(taskId: String): List<HostPendingAttentionEntity>

    @Query(
        "SELECT * FROM host_pending_attention_observations " +
            "WHERE taskId = :taskId AND callId = :callId ORDER BY ordinal",
    )
    fun hostPendingAttentionByCallId(
        taskId: String,
        callId: String,
    ): List<HostPendingAttentionEntity>

    @Query("DELETE FROM host_pending_attention_observations WHERE taskId = :taskId")
    fun deleteHostPendingAttention(taskId: String)

    @Insert
    fun insertHostPendingAttention(entities: List<HostPendingAttentionEntity>)

    @Query("UPDATE tasks SET readState = 'READ' WHERE taskId = :taskId")
    fun markTaskReadInternal(taskId: String): Int

    @Transaction
    fun markTaskRead(taskId: String): TaskEntity? {
        if (markTaskReadInternal(taskId) != 1) return null
        return task(taskId)
    }

    @Query(
        "SELECT * FROM host_device_call_observations " +
            "WHERE taskId = :taskId ORDER BY ordinal",
    )
    fun hostDeviceCallObservations(taskId: String): List<HostDeviceCallObservationEntity>

    @Query(
        "SELECT * FROM host_device_call_observations " +
            "WHERE taskId = :taskId AND callId = :callId",
    )
    fun hostDeviceCallObservation(
        taskId: String,
        callId: String,
    ): HostDeviceCallObservationEntity?

    @Query("DELETE FROM host_device_call_observations WHERE taskId = :taskId")
    fun deleteHostDeviceCallObservations(taskId: String)

    @Insert
    fun insertHostDeviceCallObservations(entities: List<HostDeviceCallObservationEntity>)

    @Query("SELECT * FROM device_operations WHERE callId = :callId")
    fun deviceOperation(callId: String): DeviceOperationEntity?

    @Query("SELECT * FROM device_operations WHERE taskId = :taskId ORDER BY receivedAtMillis, callId")
    fun deviceOperations(taskId: String): List<DeviceOperationEntity>

    @Query(
        "SELECT * FROM device_operations WHERE terminalSha256 IS NULL " +
            "ORDER BY expiresAt, receivedAtMillis, callId",
    )
    fun unterminatedDeviceOperations(): List<DeviceOperationEntity>

    @Query(
        "SELECT * FROM device_operations WHERE terminalSha256 IS NOT NULL " +
            "AND deviceId = :deviceId " +
            "AND deliveryState IN ('READY_TO_SEND', 'SENT_UNCONFIRMED') " +
            "ORDER BY receivedAtMillis, callId",
    )
    fun terminalOperationsReadyForDelivery(deviceId: String): List<DeviceOperationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDeviceOperation(entity: DeviceOperationEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun updateDeviceOperation(entity: DeviceOperationEntity): Int

    @Query("DELETE FROM device_operations WHERE taskId = :taskId AND callId = :callId")
    fun deleteDeviceOperation(taskId: String, callId: String): Int

    @Query("SELECT * FROM pending_attention WHERE callId = :callId")
    fun pendingAttention(callId: String): PendingAttentionEntity?

    @Transaction
    @Query(
        "SELECT * FROM device_operations " +
            "WHERE taskId = :taskId AND callId = :callId",
    )
    fun observeLocalAttention(
        taskId: String,
        callId: String,
    ): Flow<LocalAttentionPairEntity?>

    @Transaction
    @Query(
        "SELECT * FROM device_operations WHERE taskId = :taskId " +
            "ORDER BY receivedAtMillis, callId",
    )
    fun observeLocalTaskAttention(taskId: String): Flow<List<LocalAttentionPairEntity>>

    @Query(
        "SELECT * FROM pending_attention WHERE taskId = :taskId " +
            "ORDER BY updatedAtMillis, callId",
    )
    fun localPendingAttention(taskId: String): List<PendingAttentionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertPendingAttention(entity: PendingAttentionEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun updatePendingAttention(entity: PendingAttentionEntity): Int

    @Query("SELECT * FROM task_content_grants WHERE callId = :callId")
    suspend fun taskContentGrant(callId: String): TaskContentGrantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskContentGrant(entity: TaskContentGrantEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateTaskContentGrant(entity: TaskContentGrantEntity): Int

    @Query(
        "UPDATE task_content_grants SET revokedAtMillis = :nowMillis, " +
            "updatedAtMillis = :nowMillis WHERE grantId = :grantId AND revokedAtMillis IS NULL",
    )
    suspend fun revokeTaskContentGrants(grantId: String, nowMillis: Long): Int

    @Query("SELECT * FROM file_change_sets WHERE preparedId = :preparedId")
    fun fileChangeSet(preparedId: String): FileChangeSetEntity?

    @Query("SELECT * FROM file_change_sets WHERE commitCallId = :callId")
    fun fileChangeSetByCommitCall(callId: String): FileChangeSetEntity?

    @Query(
        "SELECT * FROM file_change_sets WHERE taskId = :taskId " +
            "ORDER BY createdAtMillis DESC, preparedId DESC LIMIT 1",
    )
    fun latestFileChangeSet(taskId: String): FileChangeSetEntity?

    @Query(
        "SELECT * FROM file_change_sets WHERE taskId = :taskId " +
            "ORDER BY createdAtMillis DESC, preparedId DESC LIMIT 1",
    )
    fun observeLatestFileChangeSet(taskId: String): Flow<FileChangeSetEntity?>

    @Query("SELECT * FROM file_change_sets WHERE commitCallId = :callId")
    fun observeFileChangeSetByCommitCall(callId: String): Flow<FileChangeSetEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertFileChangeSet(entity: FileChangeSetEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun updateFileChangeSet(entity: FileChangeSetEntity): Int

    @Query(
        "UPDATE file_change_sets SET state = 'CANCELLED', failureCode = :failureCode, " +
            "updatedAtMillis = :nowMillis WHERE taskId = :taskId AND state = 'PREPARED' " +
            "AND commitCallId IS NULL",
    )
    fun cancelUnboundPreparedFileChanges(
        taskId: String,
        failureCode: String,
        nowMillis: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM outbound_commands WHERE taskId = :taskId " +
            "AND kind = 'session.stop' AND stopFenceState = 'ACTIVE'",
    )
    fun activeStopFenceCount(taskId: String): Int

    @Query("SELECT * FROM outbound_commands WHERE requestId = :requestId")
    fun outboundCommand(requestId: String): OutboundCommandEntity?

    @Query("SELECT * FROM outbound_commands WHERE commandId = :commandId")
    fun outboundCommandByCommandId(commandId: String): OutboundCommandEntity?

    @Query("SELECT * FROM outbound_commands ORDER BY createdAtMillis, requestId")
    fun outboundCommands(): List<OutboundCommandEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertOutboundCommand(entity: OutboundCommandEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun updateOutboundCommand(entity: OutboundCommandEntity): Int

    @Query(
        "UPDATE outbound_commands SET stopFenceState = 'RELEASED', " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE taskId = :taskId AND kind = 'session.stop' " +
            "AND stopFenceState = 'ACTIVE'",
    )
    fun releaseActiveStopFences(taskId: String, updatedAtMillis: Long): Int

    @Query("SELECT * FROM drafts WHERE draftId = :draftId")
    fun draft(draftId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE draftId = :draftId")
    fun observeDraft(draftId: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE taskId = :taskId LIMIT 1")
    suspend fun draftForTask(taskId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE taskId = :taskId LIMIT 1")
    fun draftForTaskSync(taskId: String): DraftEntity?

    @Query("SELECT * FROM drafts ORDER BY updatedAtMillis DESC, draftId")
    fun drafts(): List<DraftEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDraft(entity: DraftEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun updateDraft(entity: DraftEntity): Int

    @Query(
        "UPDATE drafts SET text = :text, selectionStart = :selectionStart, " +
            "selectionEnd = :selectionEnd, updatedAtMillis = :updatedAtMillis " +
            "WHERE draftId = :draftId",
    )
    fun updateDraftTextAndSelection(
        draftId: String,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE drafts SET selectedGrantId = :grantId, updatedAtMillis = :updatedAtMillis " +
            "WHERE draftId = :draftId AND taskId IS NULL",
    )
    fun updateDraftGrant(
        draftId: String,
        grantId: String?,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE drafts SET selectedMode = :mode, updatedAtMillis = :updatedAtMillis " +
            "WHERE draftId = :draftId AND taskId IS NULL",
    )
    fun updateDraftMode(
        draftId: String,
        mode: String?,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE drafts SET approvalMode = :mode, updatedAtMillis = :updatedAtMillis " +
            "WHERE draftId = :draftId AND taskId IS NULL",
    )
    fun updateDraftApprovalMode(
        draftId: String,
        mode: TaskApprovalMode,
        updatedAtMillis: Long,
    ): Int

    @Query("DELETE FROM drafts")
    suspend fun deleteAllDrafts()

    @Query("DELETE FROM outbound_commands")
    suspend fun deleteAllOutboundCommands()

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    /**
     * Removes data owned by the current Host binding without touching phone-local capabilities.
     *
     * Deleting tasks cascades to their event, projection, attention, and device-operation rows.
     * SAF grants are intentionally independent and remain visible until the user revokes them.
     */
    @Transaction
    suspend fun eraseHostScopedData() {
        deleteAllDrafts()
        deleteAllOutboundCommands()
        deleteAllTasks()
    }

    @Query("SELECT * FROM authorized_folders ORDER BY createdAtMillis, grantId")
    suspend fun authorizedFolders(): List<AuthorizedFolderEntity>

    @Query("SELECT * FROM authorized_folders WHERE grantId = :grantId")
    suspend fun authorizedFolder(grantId: String): AuthorizedFolderEntity?

    @Query("SELECT * FROM authorized_folders WHERE treeUriSha256 = :treeUriSha256")
    suspend fun authorizedFolderByTreeHash(treeUriSha256: String): AuthorizedFolderEntity?

    @Upsert
    suspend fun upsertAuthorizedFolder(entity: AuthorizedFolderEntity)

    @Query("DELETE FROM authorized_folders WHERE grantId = :grantId")
    suspend fun deleteAuthorizedFolder(grantId: String): Int

    @Transaction
    suspend fun revokeAuthorizedFolderAndContentGrants(
        grantId: String,
        nowMillis: Long,
    ): Boolean {
        revokeTaskContentGrants(grantId, nowMillis)
        return deleteAuthorizedFolder(grantId) == 1
    }
}
