package app.momoding.feature.newtask

import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.files.AuthorizedFolderStatus
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.feature.settings.contractAction

const val MAX_PROMPT_UTF16_UNITS: Int = 131_072
const val DRAFT_RESTORED_NOTICE: String = "Draft restored from this phone."

enum class NewTaskConnectionState { READY, OFFLINE, UNAVAILABLE }

sealed interface NewTaskProfileState {
    data object Loading : NewTaskProfileState
    data class Ready(
        val hostAlias: String,
        val provider: String,
        val model: String,
        val thinking: String,
    ) : NewTaskProfileState
    data class Error(val message: String) : NewTaskProfileState
}

enum class NewTaskSendState { IDLE, PERSISTING, CREATING, RECOVERING, PROMPTING, ERROR }

data class NewTaskFolderOption(
    val grantId: String,
    val displayName: String,
    val status: AuthorizedFolderStatus,
    val canRead: Boolean,
)

data class NewTaskAttachmentUiModel(
    val attachmentId: String,
    val kind: AttachmentKind,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val thumbnailPng: ByteArray?,
    val width: Int?,
    val height: Int?,
) {
    override fun equals(other: Any?): Boolean = other is NewTaskAttachmentUiModel &&
        attachmentId == other.attachmentId &&
        kind == other.kind &&
        displayName == other.displayName &&
        mimeType == other.mimeType &&
        byteSize == other.byteSize &&
        thumbnailPng.contentEquals(other.thumbnailPng) &&
        width == other.width &&
        height == other.height

    override fun hashCode(): Int {
        var result = attachmentId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + byteSize.hashCode()
        result = 31 * result + (thumbnailPng?.contentHashCode() ?: 0)
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        return result
    }
}

data class NewTaskUiState(
    val draftId: String,
    val phoneLocal: Boolean = false,
    val draft: TextFieldValue = TextFieldValue(),
    val draftInitialized: Boolean = false,
    val restored: Boolean = false,
    val connection: NewTaskConnectionState = NewTaskConnectionState.UNAVAILABLE,
    val profile: NewTaskProfileState = NewTaskProfileState.Loading,
    val sendState: NewTaskSendState = NewTaskSendState.IDLE,
    val sendError: String? = null,
    val sendRetryable: Boolean = false,
    val notice: String? = null,
    val mobileFoldersLoading: Boolean = false,
    val mobileFolders: List<NewTaskFolderOption> = emptyList(),
    val selectedGrantId: String? = null,
    val folderChooserOpen: Boolean = false,
    val folderSelectionSaving: Boolean = false,
    val planMode: Boolean = false,
    val modeSelectionSaving: Boolean = false,
    val approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
    val approvalModeSaving: Boolean = false,
    val photoAttachmentInputEnabled: Boolean = false,
    val textFileAttachmentInputEnabled: Boolean = false,
    val cameraAttachmentInputEnabled: Boolean = false,
    val imageAttachmentRuntimeReady: Boolean = false,
    val textFileAttachmentRuntimeReady: Boolean = false,
    val attachmentMenuOpen: Boolean = false,
    val attachmentImporting: Boolean = false,
    val cameraCaptureActive: Boolean = false,
    val attachmentError: String? = null,
    val attachments: List<NewTaskAttachmentUiModel> = emptyList(),
) {
    val attachmentInputEnabled: Boolean
        get() = photoAttachmentInputEnabled || textFileAttachmentInputEnabled || cameraAttachmentInputEnabled

    private val attachmentsRuntimeReady: Boolean
        get() = attachments.all { attachment ->
            when (attachment.kind) {
                AttachmentKind.IMAGE -> imageAttachmentRuntimeReady
                AttachmentKind.TEXT_FILE -> textFileAttachmentRuntimeReady
                AttachmentKind.VIDEO -> false
            }
        }

    val sending: Boolean
        get() = sendState in setOf(
            NewTaskSendState.PERSISTING,
            NewTaskSendState.CREATING,
            NewTaskSendState.RECOVERING,
            NewTaskSendState.PROMPTING,
        )

    val sendDisabledReason: String?
        get() = when {
            !draftInitialized -> "Restoring this draft from this phone."
            sending -> "The task is being created."
            folderSelectionSaving -> "The mobile folder selection is being saved."
            modeSelectionSaving -> "The task mode is being saved."
            approvalModeSaving -> "The approval mode is being saved."
            attachmentImporting -> "The selected attachment is being saved on this phone."
            cameraCaptureActive -> "Finish or cancel the camera before sending."
            draft.text.isBlank() && attachments.isEmpty() -> "Enter a task or add an attachment before sending."
            attachments.any { it.kind == AttachmentKind.VIDEO } ->
                "Remove the video before sending because the Agent cannot read video yet."
            attachments.isNotEmpty() && !attachmentsRuntimeReady ->
                "Attachment sending stays disabled until its Pi runtime path is validated."
            draft.text.length > MAX_PROMPT_UTF16_UNITS -> "Task text is longer than the supported limit."
            connection == NewTaskConnectionState.OFFLINE -> "The Host is offline."
            connection == NewTaskConnectionState.UNAVAILABLE -> "The paired Host is unavailable."
            profile is NewTaskProfileState.Loading -> "Host model configuration is loading."
            profile is NewTaskProfileState.Error -> "Host model configuration is unavailable."
            else -> null
        }

    val canSend: Boolean get() = sendDisabledReason == null
}

sealed interface NewTaskAction {
    data class EditDraft(val value: TextFieldValue) : NewTaskAction
    data object ClearDraft : NewTaskAction
    data object Send : NewTaskAction
    data object RetrySend : NewTaskAction
    data object RetryConnection : NewTaskAction
    data object RetryModelLoad : NewTaskAction
    data object OpenFolderChooser : NewTaskAction
    data object DismissFolderChooser : NewTaskAction
    data class SelectFolder(val grantId: String?) : NewTaskAction
    data object TogglePlanMode : NewTaskAction
    data class SelectApprovalMode(val mode: TaskApprovalMode) : NewTaskAction
    data object OpenAttachmentMenu : NewTaskAction
    data object DismissAttachmentMenu : NewTaskAction
    data class ImportPhotos(val uris: List<Uri>) : NewTaskAction
    data class ImportFile(val uri: Uri) : NewTaskAction
    data object RequestCameraCapture : NewTaskAction
    data class CameraCaptureFinished(
        val captureId: String,
        val captured: Boolean,
        val launchFailed: Boolean = false,
    ) : NewTaskAction
    data class RemoveAttachment(val attachmentId: String) : NewTaskAction
    data object DismissIme : NewTaskAction
    data object Back : NewTaskAction
    data object RestorationAnnouncementConsumed : NewTaskAction
}

enum class NewTaskInteraction(val contractName: String) {
    EDIT_DRAFT("EditDraft"),
    CLEAR_DRAFT("ClearDraft"),
    SEND("Send"),
    RETRY_SEND("RetrySend"),
    RETRY_CONNECTION("RetryConnection"),
    RETRY_MODEL_LOAD("RetryModelLoad"),
    OPEN_FOLDER_CHOOSER("OpenFolderChooser"),
    SELECT_FOLDER("SelectFolder"),
    TOGGLE_PLAN_MODE("TogglePlanMode"),
    SELECT_APPROVAL_MODE("SelectApprovalMode"),
    OPEN_ATTACHMENT_MENU("OpenAttachmentMenu"),
    IMPORT_PHOTOS("ImportPhotos"),
    IMPORT_FILE("ImportFile"),
    IMPORT_CAMERA("ImportCamera"),
    REMOVE_ATTACHMENT("RemoveAttachment"),
    DISMISS_IME("DismissIme"),
    BACK("Back"),
}

fun Modifier.newTaskContractAction(
    policy: NewTaskInteractionPolicy,
    interaction: NewTaskInteraction,
): Modifier = if (policy.allows(interaction)) {
    semantics { contractAction = interaction.contractName }
} else {
    this
}

data class NewTaskInteractionPolicy(
    val allowed: Set<NewTaskInteraction> = NewTaskInteraction.entries.toSet(),
) {
    fun allows(interaction: NewTaskInteraction): Boolean = interaction in allowed

    companion object {
        val All = NewTaskInteractionPolicy()
    }
}
