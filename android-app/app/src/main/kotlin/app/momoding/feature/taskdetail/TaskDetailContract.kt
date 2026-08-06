package app.momoding.feature.taskdetail

import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.data.TaskAttentionKind
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.data.TaskFailure
import app.momoding.core.data.TaskFailureKind
import app.momoding.core.data.TaskFailureRecovery
import app.momoding.feature.settings.contractAction

enum class TaskDetailLoadState { LOADING, READY, MISSING, ERROR }

enum class TaskDetailConnectionState { CONNECTED, RECONNECTING, OFFLINE, UNPAIRED, ERROR }

enum class TaskDetailRunState {
    STARTING,
    RUNNING,
    WAITING,
    RETRYING,
    COMPACTING,
    STOPPING,
    STOPPED,
    SETTLED,
    FAILED,
    INTERRUPTED,
    RECOVERING,
    UNKNOWN,
}

enum class TaskComposerMode { PROMPT, STEER, FOLLOW_UP, BLOCKED }

enum class RunningComposerMode { STEER, FOLLOW_UP }

enum class ToolActivityState { RUNNING, SUCCESS, FAILURE, CANCELLED, UNSUPPORTED }

enum class ToolActivityKind { GENERIC, WEB_ACCESS, MOBILE_FILE, TEST, TERMINAL, USER_INPUT }

enum class ToolActivityAction { REVIEW_CHANGES, VIEW_OUTPUTS }

enum class TaskPlanStepState { PENDING, IN_PROGRESS, COMPLETED }

enum class TaskGoalState { ACTIVE, PAUSE_PENDING, PAUSED, BLOCKED, LIMITED, FAILED, ACHIEVED, CLEARED }

enum class GoalEditorMode { CREATE, EDIT }

enum class GoalConfirmation { EDIT, CLEAR }

data class TaskGoalUiModel(
    val goalId: String,
    val instruction: String,
    val state: TaskGoalState,
    val progressSummary: String? = null,
    val progressMarker: String? = null,
    val terminalReason: String? = null,
    val pauseReason: String? = null,
    val automaticTurnCount: Int = 0,
    val generation: Int = 1,
)

enum class TaskChildAgentState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class TaskChildAgentUiModel(
    val parentToolCallId: String,
    val childId: String,
    val name: String,
    val instruction: String,
    val state: TaskChildAgentState,
    val summary: String? = null,
    val result: String? = null,
    val resultTruncated: Boolean = false,
    val terminalReason: String? = null,
    val stopReason: String? = null,
    val model: String? = null,
    val turnCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val contextTokens: Int = 0,
    val costUsd: Double = 0.0,
    val eventCount: Int = 0,
    val expanded: Boolean = false,
)

data class TaskPlanStepUiModel(
    val id: String,
    val text: String,
    val state: TaskPlanStepState,
)

data class ToolSourceUiModel(
    val label: String,
    val url: String? = null,
)

data class ToolResultUiModel(
    val text: String,
    val sources: List<ToolSourceUiModel> = emptyList(),
    val images: List<ToolImageUiModel> = emptyList(),
    val truncated: Boolean = false,
)

data class ToolImageUiModel(
    val attachmentId: String,
    val displayName: String = "Generated image",
    val mimeType: String = "image/png",
    val byteSize: Long = 0,
    val thumbnailPng: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is ToolImageUiModel &&
        attachmentId == other.attachmentId && displayName == other.displayName &&
        mimeType == other.mimeType && byteSize == other.byteSize &&
        thumbnailPng.contentEqualsNullable(other.thumbnailPng)

    override fun hashCode(): Int = 31 * (
        31 * (31 * (31 * attachmentId.hashCode() + displayName.hashCode()) + mimeType.hashCode()) +
            byteSize.hashCode()
    ) + (thumbnailPng?.contentHashCode() ?: 0)
}

data class GeneratedImagePreviewUiModel(
    val attachmentId: String,
    val displayName: String,
    val mimeType: String,
    val imageBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is GeneratedImagePreviewUiModel &&
        attachmentId == other.attachmentId && displayName == other.displayName &&
        mimeType == other.mimeType && imageBytes.contentEquals(other.imageBytes)

    override fun hashCode(): Int =
        31 * (31 * (31 * attachmentId.hashCode() + displayName.hashCode()) + mimeType.hashCode()) +
            imageBytes.contentHashCode()
}

enum class TaskCommandKind { PROMPT, STEER, FOLLOW_UP, STOP }

sealed interface TimelineItem {
    val stableKey: String

    data class UserMessage(
        override val stableKey: String,
        val text: String,
        val attachmentIds: List<String> = emptyList(),
    ) : TimelineItem

    data class AssistantText(
        override val stableKey: String,
        val text: String,
        val partial: Boolean,
    ) : TimelineItem

    data class ThinkingSummary(
        override val stableKey: String,
        val text: String,
    ) : TimelineItem

    data class ToolActivity(
        override val stableKey: String,
        val toolCallId: String,
        val title: String,
        val detail: String,
        val state: ToolActivityState,
        val kind: ToolActivityKind = ToolActivityKind.GENERIC,
        val result: ToolResultUiModel? = null,
        val action: ToolActivityAction? = null,
        val expanded: Boolean = false,
    ) : TimelineItem

    data class Plan(
        override val stableKey: String,
        val toolCallId: String,
        val explanation: String,
        val steps: List<TaskPlanStepUiModel>,
        val planDigest: String,
    ) : TimelineItem

    data class RunStatus(
        override val stableKey: String,
        val label: String,
    ) : TimelineItem

    data class Error(
        override val stableKey: String,
        val message: String,
    ) : TimelineItem

    data class Completion(
        override val stableKey: String,
        val label: String,
    ) : TimelineItem

    data class UnsupportedActivity(
        override val stableKey: String,
        val label: String = "Unsupported activity",
    ) : TimelineItem
}

data class TimelineWindow(
    val settledItems: List<TimelineItem> = emptyList(),
    val activeItem: TimelineItem? = null,
    val liveRegionStartIndex: Int? = null,
) {
    val allItems: List<TimelineItem>
        get() = if (activeItem == null) settledItems else settledItems + activeItem
}

enum class TaskRecoveryKind { WIRE_REPLAY, DEVICE_RECONCILIATION }

data class TaskRecoveryUiModel(
    val kind: TaskRecoveryKind,
    val replayedEventCount: Long? = null,
)

enum class TaskTimelineFollowMode { FOLLOWING, DETACHED }

data class TaskTimelineFollowState(
    val mode: TaskTimelineFollowMode = TaskTimelineFollowMode.FOLLOWING,
    val unseenCount: Int = 0,
)

internal fun TaskTimelineFollowState.withViewport(nearBottom: Boolean, userScrolling: Boolean): TaskTimelineFollowState = when {
    nearBottom -> TaskTimelineFollowState()
    userScrolling -> copy(mode = TaskTimelineFollowMode.DETACHED)
    else -> this
}

internal fun TaskTimelineFollowState.withNewContent(): TaskTimelineFollowState = if (
    mode == TaskTimelineFollowMode.DETACHED
) {
    copy(unseenCount = unseenCount + 1)
} else {
    this
}

data class QueueItemUiModel(
    val stableKey: String,
    val kind: RunningComposerMode,
    val text: String,
    val position: Int,
)

data class TaskDetailAttachmentUiModel(
    val attachmentId: String,
    val displayName: String,
    val byteSize: Long,
    val kind: AttachmentKind = AttachmentKind.IMAGE,
    val thumbnailPng: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is TaskDetailAttachmentUiModel &&
        attachmentId == other.attachmentId && displayName == other.displayName &&
        byteSize == other.byteSize && kind == other.kind &&
        thumbnailPng.contentEqualsNullable(other.thumbnailPng)

    override fun hashCode(): Int = 31 * (
        31 * (31 * attachmentId.hashCode() + displayName.hashCode()) + byteSize.hashCode()
    ) + 31 * kind.hashCode() + (thumbnailPng?.contentHashCode() ?: 0)
}

data class TaskAttentionUiModel(
    val callId: String,
    val label: String,
    val fileChanges: Boolean = false,
    val kind: TaskAttentionKind = TaskAttentionKind.UNSUPPORTED,
)

enum class TaskAttentionNavigationNotice {
    UNAVAILABLE,
}

const val ATTENTION_UNAVAILABLE_NOTICE = "This request is no longer available."
const val TASK_DETAIL_TITLE_FOCUS_KEY = "task-detail-title"

sealed interface TaskCommandUiState {
    data object Idle : TaskCommandUiState
    data class Persisting(val kind: TaskCommandKind) : TaskCommandUiState
    data class Sending(val kind: TaskCommandKind) : TaskCommandUiState
    data class Recovering(val kind: TaskCommandKind) : TaskCommandUiState
    data class Failed(
        val kind: TaskCommandKind,
        val code: String,
        val retryable: Boolean,
        val text: String?,
        val safeMessage: String? = null,
    ) : TaskCommandUiState
}

data class TaskDetailUiState(
    val taskId: String,
    val title: String = "Task",
    val hostAlias: String = "Self-hosted Pi Host",
    val loadState: TaskDetailLoadState = TaskDetailLoadState.LOADING,
    val connection: TaskDetailConnectionState = TaskDetailConnectionState.CONNECTED,
    val runState: TaskDetailRunState = TaskDetailRunState.UNKNOWN,
    val timeline: TimelineWindow = TimelineWindow(),
    val queue: List<QueueItemUiModel> = emptyList(),
    val attention: TaskAttentionUiModel? = null,
    val attentionNavigationNotice: TaskAttentionNavigationNotice? = null,
    val composerMode: TaskComposerMode = TaskComposerMode.BLOCKED,
    val runningMode: RunningComposerMode = RunningComposerMode.FOLLOW_UP,
    val composer: TextFieldValue = TextFieldValue(),
    val command: TaskCommandUiState = TaskCommandUiState.Idle,
    val recovery: TaskRecoveryUiModel? = null,
    val activeStopFence: Boolean = false,
    val phoneLocal: Boolean = false,
    val composerBlockedReason: String? = "Task history is loading.",
    val planMode: Boolean = false,
    val latestPlanDigest: String? = null,
    val planActionPending: Boolean = false,
    val planError: String? = null,
    val approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
    val approvalModeSaving: Boolean = false,
    val approvalModeError: String? = null,
    val goal: TaskGoalUiModel? = null,
    val goalEditorOpen: Boolean = false,
    val goalEditorMode: GoalEditorMode = GoalEditorMode.CREATE,
    val goalDraft: TextFieldValue = TextFieldValue(),
    val goalConfirmation: GoalConfirmation? = null,
    val childAgents: List<TaskChildAgentUiModel> = emptyList(),
    val imageAttachmentInputEnabled: Boolean = false,
    val textFileAttachmentInputEnabled: Boolean = false,
    val attachmentMenuOpen: Boolean = false,
    val attachmentImporting: Boolean = false,
    val attachmentError: String? = null,
    val attachments: List<TaskDetailAttachmentUiModel> = emptyList(),
    val generatedImagePreview: GeneratedImagePreviewUiModel? = null,
    val failure: TaskFailure? = null,
) {
    val latestError: TimelineItem.Error?
        get() = timeline.allItems.filterIsInstance<TimelineItem.Error>().lastOrNull()

    val retryOriginalText: String?
        get() = timeline.allItems
            .filterIsInstance<TimelineItem.UserMessage>()
            .lastOrNull()
            ?.text
            ?.takeIf(String::isNotBlank)

    val providerRecoveryAvailable: Boolean
        get() = failureRecoveryAvailable &&
            failure?.recovery == TaskFailureRecovery.FIX_PROVIDER

    val failureRecoveryAvailable: Boolean
        get() = phoneLocal &&
            runState == TaskDetailRunState.FAILED &&
            latestError != null &&
            failure != null &&
            retryOriginalText != null

    val canRetryOriginal: Boolean
        get() = failureRecoveryAvailable &&
            failure?.recovery == TaskFailureRecovery.RETRY &&
            loadState == TaskDetailLoadState.READY &&
            connection == TaskDetailConnectionState.CONNECTED &&
            !commandPending &&
            !planActionPending &&
            !approvalModeSaving &&
            !activeStopFence &&
            composer.text.isBlank()

    val failureTitle: String
        get() = when (failure?.kind) {
            TaskFailureKind.PROVIDER_AUTH,
            TaskFailureKind.PROVIDER_CREDITS,
            TaskFailureKind.PROVIDER_POLICY,
            TaskFailureKind.PROVIDER_MODEL,
            TaskFailureKind.PROVIDER_RATE_LIMIT,
            TaskFailureKind.PROVIDER_TIMEOUT,
            TaskFailureKind.PROVIDER_UNAVAILABLE,
            TaskFailureKind.PROVIDER_OTHER,
            -> "Provider needs attention"
            TaskFailureKind.RUNTIME -> "Task stopped unexpectedly"
            TaskFailureKind.INTERRUPTED -> "Run interrupted"
            TaskFailureKind.UNKNOWN, null -> "Task needs attention"
        }

    val commandPending: Boolean
        get() = command is TaskCommandUiState.Persisting ||
            command is TaskCommandUiState.Sending ||
            command is TaskCommandUiState.Recovering

    val canSubmit: Boolean
        get() = loadState == TaskDetailLoadState.READY &&
            connection == TaskDetailConnectionState.CONNECTED &&
            composerMode != TaskComposerMode.BLOCKED &&
            !commandPending &&
            !planActionPending &&
            !approvalModeSaving &&
            !activeStopFence &&
            !attachmentImporting &&
            (composer.text.isNotBlank() || attachments.isNotEmpty()) &&
            composer.text.length <= MAX_TASK_COMMAND_UTF16_UNITS

    val canEditComposer: Boolean
        get() = loadState == TaskDetailLoadState.READY &&
            composerMode != TaskComposerMode.BLOCKED &&
            !commandPending &&
            !planActionPending &&
            !activeStopFence

    val canTogglePlanMode: Boolean
        get() = phoneLocal &&
            loadState == TaskDetailLoadState.READY &&
            connection == TaskDetailConnectionState.CONNECTED &&
            runState in setOf(
                TaskDetailRunState.STOPPED,
                TaskDetailRunState.SETTLED,
                TaskDetailRunState.FAILED,
                TaskDetailRunState.INTERRUPTED,
            ) &&
            goal?.state != TaskGoalState.ACTIVE &&
            !commandPending &&
            !planActionPending &&
            !activeStopFence

    val canSelectApprovalMode: Boolean
        get() = loadState == TaskDetailLoadState.READY &&
            !approvalModeSaving

    val goalActionSafe: Boolean
        get() = phoneLocal &&
            loadState == TaskDetailLoadState.READY &&
            connection == TaskDetailConnectionState.CONNECTED &&
            runState in setOf(
                TaskDetailRunState.STOPPED,
                TaskDetailRunState.SETTLED,
                TaskDetailRunState.FAILED,
                TaskDetailRunState.INTERRUPTED,
            ) &&
            !commandPending &&
            !planActionPending &&
            !activeStopFence

    val canCreateGoal: Boolean
        get() = goalActionSafe && !planMode && (goal == null || goal.state in setOf(
            TaskGoalState.ACHIEVED,
            TaskGoalState.FAILED,
            TaskGoalState.LIMITED,
            TaskGoalState.CLEARED,
        ))

    val canPauseGoal: Boolean
        get() = phoneLocal && goal?.state == TaskGoalState.ACTIVE

    val canResumeGoal: Boolean
        get() = goalActionSafe && !planMode &&
            goal?.state in setOf(TaskGoalState.PAUSED, TaskGoalState.BLOCKED)

    val canEditGoal: Boolean
        get() = goalActionSafe && !planMode && goal != null && goal.state != TaskGoalState.ACTIVE &&
            goal.state != TaskGoalState.PAUSE_PENDING

    val canClearGoal: Boolean
        get() = goalActionSafe && !planMode && goal != null && goal.state != TaskGoalState.CLEARED

    val canImplementPlan: Boolean
        get() = canTogglePlanMode && planMode && latestPlanDigest != null

    val canStop: Boolean
        get() = runState in setOf(
            TaskDetailRunState.STARTING,
            TaskDetailRunState.RUNNING,
            TaskDetailRunState.WAITING,
            TaskDetailRunState.RETRYING,
            TaskDetailRunState.COMPACTING,
        ) && !activeStopFence

    val attachmentInputEnabled: Boolean
        get() = imageAttachmentInputEnabled || textFileAttachmentInputEnabled
}

sealed interface TaskDetailAction {
    data object Back : TaskDetailAction
    data class EditComposer(val value: TextFieldValue) : TaskDetailAction
    data class SelectRunningMode(val mode: RunningComposerMode) : TaskDetailAction
    data object Submit : TaskDetailAction
    data object Stop : TaskDetailAction
    data object RetryConnection : TaskDetailAction
    data object FixProvider : TaskDetailAction
    data object RetryOriginal : TaskDetailAction
    data class ToggleTool(val stableKey: String) : TaskDetailAction
    data class ToggleChildAgent(val parentToolCallId: String) : TaskDetailAction
    data class CancelChildAgent(val parentToolCallId: String) : TaskDetailAction
    data object TogglePlanMode : TaskDetailAction
    data class SelectApprovalMode(val mode: TaskApprovalMode) : TaskDetailAction
    data class ImplementPlan(val planDigest: String) : TaskDetailAction
    data object OpenCreateGoal : TaskDetailAction
    data object OpenEditGoal : TaskDetailAction
    data object CloseGoalEditor : TaskDetailAction
    data class EditGoalDraft(val value: TextFieldValue) : TaskDetailAction
    data object SubmitGoal : TaskDetailAction
    data object PauseGoal : TaskDetailAction
    data object ResumeGoal : TaskDetailAction
    data class ConfirmGoal(val action: GoalConfirmation) : TaskDetailAction
    data object DismissGoalConfirmation : TaskDetailAction
    data object ApplyGoalConfirmation : TaskDetailAction
    data object OpenOutputs : TaskDetailAction
    data object OpenDiff : TaskDetailAction
    data class OpenAttention(val callId: String) : TaskDetailAction
    data object OpenAttachmentMenu : TaskDetailAction
    data object DismissAttachmentMenu : TaskDetailAction
    data class ImportPhotos(val uris: List<Uri>) : TaskDetailAction
    data class ImportTextFile(val uri: Uri) : TaskDetailAction
    data class RemoveAttachment(val attachmentId: String) : TaskDetailAction
    data class OpenGeneratedImage(val attachmentId: String) : TaskDetailAction
    data object DismissGeneratedImage : TaskDetailAction
    data class CopyGeneratedImage(val attachmentId: String) : TaskDetailAction
    data class DownloadGeneratedImage(val attachmentId: String) : TaskDetailAction
}

sealed interface TaskDetailOneShot {
    data object Back : TaskDetailOneShot
    data object OpenOutputs : TaskDetailOneShot
    data object OpenDiff : TaskDetailOneShot
    data object OpenProvider : TaskDetailOneShot
    data object OpenFullAccessSetup : TaskDetailOneShot
    data class CopyGeneratedImage(
        val uri: Uri,
        val displayName: String,
    ) : TaskDetailOneShot
    data object GeneratedImageSaved : TaskDetailOneShot
    data class GeneratedImageActionFailed(val message: String) : TaskDetailOneShot
    data class OpenAttention(
        val callId: String,
        val fileChanges: Boolean = false,
    ) : TaskDetailOneShot
}

enum class TaskDetailInteraction(val contractName: String) {
    BACK("Back"),
    EDIT_COMPOSER("EditComposer"),
    SELECT_STEER("SelectSteer"),
    SELECT_FOLLOW_UP("SelectFollowUp"),
    SUBMIT_PROMPT("SubmitPrompt"),
    SUBMIT_STEER("SubmitSteer"),
    SUBMIT_FOLLOW_UP("SubmitFollowUp"),
    STOP("Stop"),
    RETRY_CONNECTION("RetryConnection"),
    FIX_PROVIDER("FixProvider"),
    RETRY_ORIGINAL("RetryOriginal"),
    OPEN_TOOL("OpenTool"),
    OPEN_CHILD_AGENT("OpenChildAgent"),
    CANCEL_CHILD_AGENT("CancelChildAgent"),
    TOGGLE_PLAN_MODE("TogglePlanMode"),
    SELECT_APPROVAL_MODE("SelectApprovalMode"),
    IMPLEMENT_PLAN("ImplementPlan"),
    CREATE_GOAL("CreateGoal"),
    EDIT_GOAL("EditGoal"),
    PAUSE_GOAL("PauseGoal"),
    RESUME_GOAL("ResumeGoal"),
    CLEAR_GOAL("ClearGoal"),
    OPEN_OUTPUTS("OpenOutputs"),
    OPEN_DIFF("OpenDiff"),
    OPEN_ATTENTION("OpenAttention"),
    JUMP_TO_LATEST("JumpToLatest"),
    OPEN_ATTACHMENT_MENU("OpenAttachmentMenu"),
    IMPORT_PHOTOS("ImportPhotos"),
    IMPORT_TEXT_FILE("ImportTextFile"),
    REMOVE_ATTACHMENT("RemoveAttachment"),
}

fun Modifier.taskDetailContractAction(
    policy: TaskDetailInteractionPolicy,
    interaction: TaskDetailInteraction,
): Modifier = if (policy.allows(interaction)) {
    semantics { contractAction = interaction.contractName }
} else {
    this
}

data class TaskDetailInteractionPolicy(val allowed: Set<TaskDetailInteraction>) {
    fun allows(interaction: TaskDetailInteraction): Boolean = interaction in allowed

    companion object {
        val All = TaskDetailInteractionPolicy(TaskDetailInteraction.entries.toSet())
    }
}

const val MAX_TASK_COMMAND_UTF16_UNITS: Int = 131_072

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this === other -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
