package app.momoding.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["draftId"]),
        Index(value = ["draftId", "ordinal"], unique = true),
        Index(value = ["taskId", "messageLocalId"]),
        Index(value = ["taskId", "messageLocalId", "ordinal"], unique = true),
        Index(value = ["payloadFileName"], unique = true),
        Index(value = ["thumbnailFileName"], unique = true),
    ],
)
data class AttachmentEntity(
    @androidx.room.PrimaryKey val attachmentId: String,
    val draftId: String?,
    val taskId: String?,
    val messageLocalId: String?,
    val ordinal: Int,
    val kind: String,
    val state: String,
    val source: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val payloadSha256: String,
    val payloadFileName: String,
    val thumbnailFileName: String?,
    val width: Int?,
    val height: Int?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
