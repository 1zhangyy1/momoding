package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE draftId = :draftId ORDER BY ordinal, attachmentId")
    fun observeDraftAttachments(draftId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE draftId = :draftId ORDER BY ordinal, attachmentId")
    fun draftAttachments(draftId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE taskId = :taskId AND messageLocalId IS NULL ORDER BY ordinal, attachmentId")
    fun observeTaskStagedAttachments(taskId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE taskId = :taskId AND messageLocalId IS NULL ORDER BY ordinal, attachmentId")
    fun taskStagedAttachments(taskId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY createdAtMillis, ordinal, attachmentId")
    fun taskAttachments(taskId: String): List<AttachmentEntity>

    @Query("SELECT DISTINCT taskId FROM attachments WHERE state = :state AND taskId IS NOT NULL ORDER BY taskId")
    fun taskIdsWithAttachmentState(state: String): List<String>

    @Query("SELECT * FROM attachments ORDER BY attachmentId")
    fun allAttachments(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE attachmentId = :attachmentId")
    fun attachment(attachmentId: String): AttachmentEntity?

    @Query("SELECT COUNT(*) FROM attachments WHERE draftId = :draftId")
    fun draftAttachmentCount(draftId: String): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM attachments WHERE draftId = :draftId")
    fun draftAttachmentBytes(draftId: String): Long

    @Query("SELECT MAX(ordinal) FROM attachments WHERE draftId = :draftId")
    fun lastDraftOrdinal(draftId: String): Int?

    @Query("SELECT COUNT(*) FROM attachments WHERE taskId = :taskId")
    fun taskAttachmentCount(taskId: String): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM attachments WHERE taskId = :taskId")
    fun taskAttachmentBytes(taskId: String): Long

    @Query("SELECT MAX(ordinal) FROM attachments WHERE taskId = :taskId AND messageLocalId IS NULL")
    fun lastTaskStagedOrdinal(taskId: String): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAttachment(entity: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE attachmentId = :attachmentId AND draftId = :draftId")
    fun deleteDraftAttachmentRow(draftId: String, attachmentId: String): Int

    @Query("DELETE FROM attachments WHERE attachmentId = :attachmentId AND taskId = :taskId AND messageLocalId IS NULL")
    fun deleteTaskStagedAttachmentRow(taskId: String, attachmentId: String): Int

    @Query("UPDATE attachments SET draftId = NULL, taskId = :taskId, messageLocalId = :messageLocalId, state = :state, updatedAtMillis = :updatedAtMillis WHERE draftId = :draftId")
    fun bindDraftAttachments(
        draftId: String,
        taskId: String,
        messageLocalId: String,
        state: String,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE attachments SET messageLocalId = :messageLocalId, state = :state, updatedAtMillis = :updatedAtMillis WHERE taskId = :taskId AND messageLocalId IS NULL")
    fun bindTaskStagedAttachments(
        taskId: String,
        messageLocalId: String,
        state: String,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE attachments SET state = :state, updatedAtMillis = :updatedAtMillis WHERE taskId = :taskId AND attachmentId IN (:attachmentIds)")
    fun updateTaskAttachmentStates(
        taskId: String,
        attachmentIds: List<String>,
        state: String,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE attachments SET messageLocalId = NULL, state = :stagedState, updatedAtMillis = :updatedAtMillis WHERE taskId = :taskId AND messageLocalId = :messageLocalId AND state = :pendingState")
    fun releasePendingTaskAttachments(
        taskId: String,
        messageLocalId: String,
        pendingState: String,
        stagedState: String,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE attachments SET messageLocalId = NULL, state = :stagedState, updatedAtMillis = :updatedAtMillis WHERE taskId = :taskId AND attachmentId IN (:attachmentIds) AND state = :pendingState")
    fun releasePendingTaskAttachmentIds(
        taskId: String,
        attachmentIds: List<String>,
        pendingState: String,
        stagedState: String,
        updatedAtMillis: Long,
    ): Int

    @Transaction
    fun insertStagedDraftAttachment(
        entity: AttachmentEntity,
        maxCount: Int,
        maxTotalBytes: Long,
    ): AttachmentEntity {
        require(entity.draftId != null && entity.taskId == null && entity.messageLocalId == null) {
            "ATTACHMENT_INVALID_DRAFT_OWNER"
        }
        check(draftAttachmentCount(entity.draftId) < maxCount) {
            "ATTACHMENT_COUNT_LIMIT"
        }
        check(draftAttachmentBytes(entity.draftId) + entity.byteSize <= maxTotalBytes) {
            "ATTACHMENT_TOTAL_BYTES_LIMIT"
        }
        val exact = entity.copy(ordinal = (lastDraftOrdinal(entity.draftId) ?: -1) + 1)
        insertAttachment(exact)
        return exact
    }

    @Transaction
    fun insertStagedTaskAttachment(
        entity: AttachmentEntity,
        maxCount: Int,
        maxTotalBytes: Long,
    ): AttachmentEntity {
        require(entity.draftId == null && entity.taskId != null && entity.messageLocalId == null) {
            "ATTACHMENT_INVALID_TASK_OWNER"
        }
        check(taskAttachmentCount(entity.taskId) < maxCount) { "ATTACHMENT_COUNT_LIMIT" }
        check(taskAttachmentBytes(entity.taskId) + entity.byteSize <= maxTotalBytes) {
            "ATTACHMENT_TOTAL_BYTES_LIMIT"
        }
        val exact = entity.copy(ordinal = (lastTaskStagedOrdinal(entity.taskId) ?: -1) + 1)
        insertAttachment(exact)
        return exact
    }

    @Transaction
    fun deleteDraftAttachment(draftId: String, attachmentId: String): AttachmentEntity? {
        val existing = attachment(attachmentId) ?: return null
        if (existing.draftId != draftId) return null
        check(deleteDraftAttachmentRow(draftId, attachmentId) == 1) {
            "ATTACHMENT_DELETE_LOST"
        }
        return existing
    }


    @Transaction
    fun deleteTaskStagedAttachment(taskId: String, attachmentId: String): AttachmentEntity? {
        val existing = attachment(attachmentId) ?: return null
        if (existing.taskId != taskId || existing.messageLocalId != null) return null
        check(deleteTaskStagedAttachmentRow(taskId, attachmentId) == 1) {
            "ATTACHMENT_DELETE_LOST"
        }
        return existing
    }
}
