package app.momoding.core.attachments

import android.net.Uri
import kotlinx.coroutines.flow.Flow

const val MAX_DRAFT_ATTACHMENTS: Int = 5
const val MAX_DRAFT_ATTACHMENT_BYTES: Long = 40L * 1024L * 1024L

enum class AttachmentKind { IMAGE, TEXT_FILE, VIDEO }

enum class AttachmentState { STAGED, PENDING, SENT }

enum class AttachmentSource { PHOTO_PICKER, OPEN_DOCUMENT, ANDROID_SHARE, CAMERA }

data class CameraCaptureRequest(
    val captureId: String,
    val outputUri: Uri,
)

data class SharedAttachmentInput(
    val sourceIndex: Int,
    val uri: Uri,
    val kind: AttachmentKind,
)

data class AttachmentRecord(
    val attachmentId: String,
    val draftId: String,
    val ordinal: Int,
    val kind: AttachmentKind,
    val state: AttachmentState,
    val source: AttachmentSource,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val payloadSha256: String,
    val hasThumbnail: Boolean,
    val width: Int?,
    val height: Int?,
    val createdAtMillis: Long,
)

data class TaskAttachmentRecord(
    val attachmentId: String,
    val taskId: String,
    val messageLocalId: String?,
    val ordinal: Int,
    val kind: AttachmentKind,
    val state: AttachmentState,
    val source: AttachmentSource,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val payloadSha256: String,
    val hasThumbnail: Boolean,
    val width: Int?,
    val height: Int?,
    val createdAtMillis: Long,
)

enum class AttachmentImportFailureCode {
    COUNT_LIMIT,
    TOTAL_BYTES_LIMIT,
    ITEM_TOO_LARGE,
    UNSUPPORTED_TYPE,
    INVALID_CONTENT,
    READ_FAILED,
    STORAGE_FAILED,
}

data class AttachmentImportFailure(
    val sourceIndex: Int,
    val code: AttachmentImportFailureCode,
    val safeMessage: String,
)

data class AttachmentImportBatchResult(
    val imported: List<AttachmentRecord>,
    val failures: List<AttachmentImportFailure>,
)

data class TaskAttachmentImportBatchResult(
    val imported: List<TaskAttachmentRecord>,
    val failures: List<AttachmentImportFailure>,
)

interface DraftAttachmentGateway {
    fun observeDraftAttachments(draftId: String): Flow<List<AttachmentRecord>>

    suspend fun importPhotoPickerSelection(
        draftId: String,
        uris: List<Uri>,
    ): AttachmentImportBatchResult

    suspend fun importOpenDocument(
        draftId: String,
        uri: Uri,
    ): AttachmentImportBatchResult

    suspend fun prepareCameraCapture(draftId: String): CameraCaptureRequest

    suspend fun completeCameraCapture(
        draftId: String,
        captureId: String,
        captured: Boolean,
    ): AttachmentImportBatchResult

    suspend fun thumbnailPng(attachmentId: String): ByteArray?

    suspend fun removeDraftAttachment(draftId: String, attachmentId: String): Boolean
}

interface TaskAttachmentGateway {
    fun observeTaskStagedAttachments(taskId: String): Flow<List<TaskAttachmentRecord>>

    suspend fun importTaskPhotoPickerSelection(
        taskId: String,
        uris: List<Uri>,
    ): TaskAttachmentImportBatchResult

    suspend fun importTaskOpenDocument(
        taskId: String,
        uri: Uri,
    ): TaskAttachmentImportBatchResult

    suspend fun thumbnailPng(attachmentId: String): ByteArray?

    suspend fun removeTaskStagedAttachment(taskId: String, attachmentId: String): Boolean
}

object AttachmentFeatureGate {
    /** Staged images are delivered to the same Pi task session. */
    const val PHOTO_PRODUCT_INPUT_ENABLED: Boolean = true

    /** Task-scoped text reading is bounded and enforced by Android. */
    const val TEXT_FILE_PRODUCT_INPUT_ENABLED: Boolean = true

    /** Camera capture: a system camera intent writes to one app-private temporary URI. */
    const val CAMERA_PRODUCT_INPUT_ENABLED: Boolean = true
}
