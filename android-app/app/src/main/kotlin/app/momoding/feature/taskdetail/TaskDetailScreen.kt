package app.momoding.feature.taskdetail

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import app.momoding.wire.WireErrorCode
import app.momoding.core.data.TaskAttentionKind
import app.momoding.core.data.TaskFailureRecovery
import app.momoding.feature.attention.AttentionIdentity
import app.momoding.feature.attention.AttentionIntent
import app.momoding.feature.attention.AttentionUiState
import app.momoding.feature.attention.QuestionComposerDock
import app.momoding.ui.components.MomodingBannerTone
import app.momoding.core.attachments.MAX_DRAFT_ATTACHMENTS
import app.momoding.core.attachments.AttachmentKind
import app.momoding.ui.components.MomodingStatusBanner
import app.momoding.ui.components.ComposerDock
import app.momoding.ui.components.ComposerMenuItem
import app.momoding.ui.components.MessageGroup
import app.momoding.ui.components.MessageRole
import app.momoding.ui.components.MomodingPresence
import app.momoding.ui.components.ProductTopBar
import app.momoding.ui.components.WorkBlock
import app.momoding.ui.components.WorkBlockTone
import app.momoding.feature.settings.structuralAction
import app.momoding.ui.navigation.taskDetailAttentionFocusKey
import app.momoding.ui.theme.LocalMomodingStatusColors
import app.momoding.ui.theme.LocalMomodingBrandColors
import app.momoding.ui.icons.MomodingFilledIcons
import app.momoding.ui.icons.MomodingIcons
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    onAction: (TaskDetailAction) -> Unit,
    interactionPolicy: TaskDetailInteractionPolicy = TaskDetailInteractionPolicy.All,
    questionState: AttentionUiState? = null,
    onQuestionIntent: (AttentionIntent) -> Unit = {},
    restoreFocusKey: String? = null,
    onFocusRestored: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_DRAFT_ATTACHMENTS),
    ) { uris ->
        if (uris.isNotEmpty()) onAction(TaskDetailAction.ImportPhotos(uris))
    }
    val textFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(TaskDetailAction.ImportTextFile(uri))
    }
    val titleFocusRequester = remember { FocusRequester() }
    val attentionFocusRequester = remember { FocusRequester() }
    var titleFocused by remember { mutableStateOf(false) }
    var attentionFocused by remember { mutableStateOf(false) }
    LaunchedEffect(restoreFocusKey, state.attention?.callId) {
        if (restoreFocusKey == null) return@LaunchedEffect
        val exactAttentionExists = state.attention?.callId?.let { callId ->
            restoreFocusKey == taskDetailAttentionFocusKey(state.taskId, callId)
        } == true
        repeat(30) {
            if (exactAttentionExists) {
                attentionFocusRequester.requestFocus()
            } else {
                titleFocusRequester.requestFocus()
            }
            withFrameNanos { }
            if ((exactAttentionExists && attentionFocused) || (!exactAttentionExists && titleFocused)) {
                onFocusRestored()
                return@LaunchedEffect
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ProductTopBar(
                title = state.title,
                subtitle = state.statusSubtitle(),
                titleModifier = Modifier
                    .focusRequester(titleFocusRequester)
                    .onFocusChanged { titleFocused = it.isFocused }
                    .focusable()
                    .testTag(TASK_DETAIL_TITLE_FOCUS_KEY),
                onBack = if (interactionPolicy.allows(TaskDetailInteraction.BACK)) {
                    { onAction(TaskDetailAction.Back) }
                } else null,
                backModifier = Modifier
                    .testTag("action-Back")
                    .taskDetailContractAction(interactionPolicy, TaskDetailInteraction.BACK),
            )
            TaskTimeline(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onAction = onAction,
                policy = interactionPolicy,
                attentionFocusModifier = Modifier
                    .focusRequester(attentionFocusRequester)
                    .onFocusChanged { attentionFocused = it.isFocused }
                    .focusable(),
            )
            val activeQuestion = state.attention?.takeIf { it.kind == TaskAttentionKind.QUESTION }
            if (activeQuestion != null) {
                QuestionComposerDock(
                    state = questionState ?: AttentionUiState.Loading(
                        AttentionIdentity(state.taskId, activeQuestion.callId),
                    ),
                    onIntent = onQuestionIntent,
                    modifier = Modifier
                        .focusRequester(attentionFocusRequester)
                        .onFocusChanged { attentionFocused = it.isFocused }
                        .focusable(),
                )
            } else {
                TaskComposerDock(
                    state = state,
                    focusManager = focusManager,
                    onAction = onAction,
                    policy = interactionPolicy,
                )
            }
        }
    }
    if (state.attachmentMenuOpen && (state.attachmentInputEnabled || state.phoneLocal)) {
        TaskAttachmentPickerSheet(
            onDismiss = { onAction(TaskDetailAction.DismissAttachmentMenu) },
            onPhotos = {
                onAction(TaskDetailAction.DismissAttachmentMenu)
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onTextFile = {
                onAction(TaskDetailAction.DismissAttachmentMenu)
                textFilePicker.launch(
                    arrayOf(
                        "text/*",
                        "application/json",
                        "application/xml",
                        "application/javascript",
                    ),
                )
            },
            onGoal = {
                onAction(TaskDetailAction.DismissAttachmentMenu)
                if (state.goal == null) {
                    onAction(TaskDetailAction.OpenCreateGoal)
                } else {
                    onAction(TaskDetailAction.OpenEditGoal)
                }
            },
            onTogglePlanMode = {
                onAction(TaskDetailAction.DismissAttachmentMenu)
                onAction(TaskDetailAction.TogglePlanMode)
            },
            photosEnabled = state.imageAttachmentInputEnabled,
            textFileEnabled = state.textFileAttachmentInputEnabled,
            goal = state.goal,
            goalEnabled = if (state.goal == null) state.canCreateGoal else state.canEditGoal,
            planAvailable = state.phoneLocal,
            planEnabled = state.planMode,
            planSaving = state.planActionPending,
            planToggleEnabled = state.canTogglePlanMode,
            attachmentCapacityAvailable = state.attachments.size < MAX_DRAFT_ATTACHMENTS,
            policy = interactionPolicy,
        )
    }
    if (state.goalEditorOpen) {
        GoalEditorDialog(state, onAction)
    }
    state.goalConfirmation?.let { confirmation ->
        GoalConfirmationDialog(confirmation, onAction)
    }
}

@Composable
private fun TaskTimeline(
    state: TaskDetailUiState,
    modifier: Modifier,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
    attentionFocusModifier: Modifier,
) {
    val listState = rememberLazyListState()
    val liveStatusIndex = state.timeline.liveRegionStartIndex
        ?.coerceIn(0, state.timeline.settledItems.size)
        ?: state.timeline.settledItems.size
    val beforeLiveStatus = state.timeline.settledItems.subList(0, liveStatusIndex)
        .withoutProviderRecoveryError(state)
        .withoutPendingQuestion(state.attention)
    val afterLiveStatus = state.timeline.settledItems.subList(
        liveStatusIndex,
        state.timeline.settledItems.size,
    ).withoutProviderRecoveryError(state).withoutPendingQuestion(state.attention)
    val contentVersion = remember(
        state.timeline,
        state.queue,
        state.recovery,
        state.command,
        state.planMode,
        state.planActionPending,
        state.childAgents,
        state.attention,
    ) {
        buildString {
            append(state.timeline.settledItems.size).append(':')
            append(state.timeline.settledItems.lastOrNull()?.stableKey).append(':')
            state.timeline.activeItem?.let { item ->
                append(item.stableKey).append(':')
                when (item) {
                    is TimelineItem.AssistantText -> append(item.text.length)
                    is TimelineItem.ToolActivity -> append(item.state).append(':').append(item.detail.hashCode())
                    is TimelineItem.Plan -> append(item.planDigest)
                    is TimelineItem.RunStatus -> append(item.label.hashCode())
                    else -> Unit
                }
            }
            append(':').append(state.queue.size).append(':').append(state.queue.lastOrNull()?.stableKey)
            append(':').append(state.childAgents.hashCode())
            append(':').append(state.attention?.callId)
            append(state.recovery).append(':').append(state.command)
        }
    }
    var priorContentVersion by remember { mutableStateOf<String?>(null) }
    var followState by remember { mutableStateOf(TaskTimelineFollowState()) }
    var viewportInitialized by remember { mutableStateOf(false) }
    var userDetached by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            val nearBottom = last != null &&
                last.index >= layout.totalItemsCount - 2 &&
                last.offset + last.size <= layout.viewportEndOffset + 48
            Triple(nearBottom, listState.isScrollInProgress, layout.totalItemsCount)
        }.collect { (nearBottom, scrolling, _) ->
            val totalItems = listState.layoutInfo.totalItemsCount
            if (!viewportInitialized && totalItems > 0) {
                followState = if (nearBottom) {
                    TaskTimelineFollowState()
                } else {
                    TaskTimelineFollowState(TaskTimelineFollowMode.DETACHED)
                }
                viewportInitialized = true
            } else {
                followState = followState.withViewport(nearBottom, scrolling)
            }
            if (nearBottom) userDetached = false else if (scrolling) userDetached = true
            if (
                viewportInitialized &&
                followState.mode == TaskTimelineFollowMode.FOLLOWING &&
                !scrolling &&
                !nearBottom
            ) {
                val lastIndex = totalItems - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
            }
        }
    }
    LaunchedEffect(contentVersion) {
        if (priorContentVersion == null) {
            priorContentVersion = contentVersion
            return@LaunchedEffect
        }
        if (priorContentVersion == contentVersion) return@LaunchedEffect
        priorContentVersion = contentVersion
        val shouldFollow = followState.mode == TaskTimelineFollowMode.FOLLOWING
        followState = followState.withNewContent()
        if (shouldFollow) {
            withFrameNanos { }
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("task-detail-timeline"),
            contentPadding = PaddingValues(start = 15.dp, top = 14.dp, end = 15.dp, bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            connectionBanner(state, onAction, policy)
            commandBanner(state.command, state.phoneLocal)
            state.planError?.let { error ->
                item(key = "plan-error:$error") {
                    val colors = LocalMomodingStatusColors.current
                    MomodingStatusBanner(
                        title = "Plan action failed",
                        body = error,
                        icon = Icons.Outlined.ErrorOutline,
                        iconColor = colors.danger,
                        containerColor = colors.dangerContainer,
                        tone = MomodingBannerTone.DANGER,
                    )
                }
            }
            providerRecoveryBanner(state, onAction, policy)
            state.recovery?.let { recovery ->
                item(key = "recovery-banner:${recovery.kind}") { RecoveryBanner(recovery) }
            }
            if (state.attentionNavigationNotice == TaskAttentionNavigationNotice.UNAVAILABLE) {
                item(key = "attention-navigation-unavailable") {
                    val colors = LocalMomodingStatusColors.current
                    MomodingStatusBanner(
                        title = "Attention unavailable",
                        body = ATTENTION_UNAVAILABLE_NOTICE,
                        icon = Icons.Outlined.WarningAmber,
                        iconColor = colors.warning,
                        containerColor = colors.warningContainer,
                        tone = MomodingBannerTone.WARNING,
                    )
                }
            }
            state.goal?.let { goal ->
                item(key = "goal:${goal.goalId}:${goal.generation}") {
                    GoalCard(state, goal, onAction, policy)
                }
            }
            if (state.childAgents.isNotEmpty()) {
                item(key = "child-agents") {
                    ChildAgentsSection(state.childAgents, onAction, policy)
                }
            }
            if (state.loadState == TaskDetailLoadState.LOADING && state.timeline.settledItems.isEmpty()) {
                item(key = "loading") { CenterState("Loading task history", showProgress = true) }
            }
            if (state.loadState == TaskDetailLoadState.MISSING) {
                item(key = "missing") { CenterState("This task is not available on the paired Host.") }
            }
            if (state.loadState == TaskDetailLoadState.ERROR && state.timeline.settledItems.isEmpty()) {
                item(key = "load-error") { CenterState("The task could not be loaded safely.") }
            }
            items(beforeLiveStatus, key = TimelineItem::stableKey) { item ->
                TimelineItemView(
                    item,
                    active = false,
                    canImplementPlan = state.canImplementPlan && state.latestPlanDigest == (item as? TimelineItem.Plan)?.planDigest,
                    onAction = onAction,
                    policy = policy,
                    attention = state.attention,
                    modifier = attentionFocusModifier,
                )
            }
            items(afterLiveStatus, key = TimelineItem::stableKey) { item ->
                TimelineItemView(
                    item,
                    active = false,
                    canImplementPlan = state.canImplementPlan && state.latestPlanDigest == (item as? TimelineItem.Plan)?.planDigest,
                    onAction = onAction,
                    policy = policy,
                    attention = state.attention,
                    modifier = attentionFocusModifier,
                )
            }
            state.timeline.activeItem
                ?.takeUnless { state.failureRecoveryAvailable && it.stableKey == state.latestError?.stableKey }
                ?.takeUnless { it.isPendingQuestion(state.attention) }
                ?.let { active ->
                item(key = active.stableKey) {
                    TimelineItemView(
                        active,
                        active = true,
                        canImplementPlan = state.canImplementPlan && state.latestPlanDigest == (active as? TimelineItem.Plan)?.planDigest,
                        onAction = onAction,
                        policy = policy,
                        attention = state.attention,
                        modifier = attentionFocusModifier,
                    )
                }
            }
            if (
                state.attention != null &&
                state.attention.kind != TaskAttentionKind.QUESTION &&
                !state.timeline.containsAttentionCall(state.attention.callId)
            ) {
                item(key = "attention-fallback:${state.attention.callId}") {
                    AttentionCard(state.attention, onAction, policy, attentionFocusModifier)
                }
            }
            if (state.timeline.activeItem != null) {
                item(key = "timeline-live-edge") {
                    Box(Modifier.size(1.dp).testTag("task-detail-live-edge"))
                }
            }
        }
        if (
            followState.mode == TaskTimelineFollowMode.DETACHED &&
            (userDetached || followState.unseenCount > 0)
        ) {
            IconButton(
                onClick = {
                    followState = TaskTimelineFollowState()
                    userDetached = false
                    scope.launch {
                        val lastIndex = listState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(48.dp)
                    .testTag("action-JumpToLatest")
                    .taskDetailContractAction(policy, TaskDetailInteraction.JUMP_TO_LATEST),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 3.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (followState.unseenCount > 0) {
                                "Jump to latest, ${followState.unseenCount} new updates"
                            } else {
                                "Jump to latest"
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun List<TimelineItem>.withoutProviderRecoveryError(state: TaskDetailUiState): List<TimelineItem> {
    val hiddenErrorKey = state.latestError?.stableKey?.takeIf { state.failureRecoveryAvailable }
        ?: return this
    return filterNot { it.stableKey == hiddenErrorKey }
}

private fun List<TimelineItem>.withoutPendingQuestion(
    attention: TaskAttentionUiModel?,
): List<TimelineItem> = filterNot { it.isPendingQuestion(attention) }

private fun TimelineItem.isPendingQuestion(attention: TaskAttentionUiModel?): Boolean =
    attention?.kind == TaskAttentionKind.QUESTION &&
        this is TimelineItem.ToolActivity &&
        toolCallId == attention.callId

private fun androidx.compose.foundation.lazy.LazyListScope.providerRecoveryBanner(
    state: TaskDetailUiState,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    if (!state.failureRecoveryAvailable) return
    item(key = "provider-recovery") {
        val colors = LocalMomodingStatusColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.dangerContainer, RoundedCornerShape(12.dp))
                .border(1.dp, colors.danger.copy(alpha = .28f), RoundedCornerShape(12.dp))
                .padding(start = 11.dp, top = 10.dp, end = 7.dp, bottom = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.failureTitle, style = MaterialTheme.typography.labelLarge, color = colors.danger)
                    Text(
                        requireNotNull(state.latestError).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.providerRecoveryAvailable) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.FixProvider) },
                        enabled = policy.allows(TaskDetailInteraction.FIX_PROVIDER),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("action-FixProvider")
                            .taskDetailContractAction(policy, TaskDetailInteraction.FIX_PROVIDER),
                    ) {
                        Text("Fix Provider", color = colors.danger)
                    }
                }
                if (state.failure?.recovery == TaskFailureRecovery.RETRY) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.RetryOriginal) },
                        enabled = state.canRetryOriginal &&
                            policy.allows(TaskDetailInteraction.RETRY_ORIGINAL),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("action-RetryOriginal")
                            .taskDetailContractAction(policy, TaskDetailInteraction.RETRY_ORIGINAL),
                    ) {
                        Text("Retry original", color = colors.danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryBanner(recovery: TaskRecoveryUiModel) {
    val colors = LocalMomodingStatusColors.current
    MomodingStatusBanner(
        title = if (recovery.kind == TaskRecoveryKind.WIRE_REPLAY) {
            "Restoring task history"
        } else {
            "Checking interrupted phone actions"
        },
        body = if (recovery.kind == TaskRecoveryKind.WIRE_REPLAY) {
            val count = recovery.replayedEventCount ?: 0
            "Replayed $count native Pi ${if (count == 1L) "event" else "events"} from the Host."
        } else {
            "Verifying the last known result before Momoding continues."
        },
        icon = Icons.Outlined.Refresh,
        iconColor = colors.info,
        containerColor = colors.infoContainer,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.connectionBanner(
    state: TaskDetailUiState,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    if (state.connection == TaskDetailConnectionState.CONNECTED) return
    item(key = "connection:${state.connection}") {
        val colors = LocalMomodingStatusColors.current
        val canRetry = state.connection in setOf(
            TaskDetailConnectionState.RECONNECTING,
            TaskDetailConnectionState.OFFLINE,
            TaskDetailConnectionState.ERROR,
        ) && policy.allows(TaskDetailInteraction.RETRY_CONNECTION)
        MomodingStatusBanner(
            title = when (state.connection) {
                TaskDetailConnectionState.RECONNECTING -> "Reconnecting to Host"
                TaskDetailConnectionState.OFFLINE -> "Host offline"
                TaskDetailConnectionState.UNPAIRED -> "Host unavailable"
                TaskDetailConnectionState.ERROR -> "Host connection error"
                TaskDetailConnectionState.CONNECTED -> return@item
            },
            body = if (state.canStop) {
                "History is cached. Stop can still be saved on this phone."
            } else {
                "Showing the latest task history saved on this phone."
            },
            icon = Icons.Outlined.WarningAmber,
            iconColor = colors.warning,
            containerColor = colors.warningContainer,
            tone = MomodingBannerTone.WARNING,
            actionLabel = if (canRetry) "Retry" else null,
            onAction = if (canRetry) ({ onAction(TaskDetailAction.RetryConnection) }) else null,
            actionModifier = Modifier
                .testTag("action-RetryConnection")
                .taskDetailContractAction(policy, TaskDetailInteraction.RETRY_CONNECTION),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.commandBanner(
    command: TaskCommandUiState,
    phoneLocal: Boolean,
) {
    if (command !is TaskCommandUiState.Failed) return
    item(key = "command-error:${command.kind}:${command.code}") {
        val colors = LocalMomodingStatusColors.current
        val message = command.safeMessage ?: if (phoneLocal) {
            when (command.code) {
                WireErrorCode.INVALID_SESSION_STATE.name ->
                    "Saved task context is damaged. Task history is still readable, but this task cannot continue."
                WireErrorCode.RECOVERY_REQUIRED.name ->
                    "Saved task context is unavailable. Task history is still readable; start a new task to continue."
                else -> "Momoding could not safely complete this command."
            }
        } else {
            runCatching { WireErrorCode.valueOf(command.code).taskDetailMessage() }
                .getOrDefault("The Host could not safely complete this command.")
        }
        MomodingStatusBanner(
            title = if (command.kind == TaskCommandKind.STOP) "Stop needs recovery" else "Message not accepted",
            body = message,
            icon = Icons.Outlined.ErrorOutline,
            iconColor = colors.danger,
            containerColor = colors.dangerContainer,
            tone = MomodingBannerTone.DANGER,
        )
    }
}

@Composable
private fun AttentionCard(
    attention: TaskAttentionUiModel,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
    modifier: Modifier,
) {
    val colors = LocalMomodingStatusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.warningContainer, RoundedCornerShape(12.dp))
            .border(1.dp, colors.warning.copy(alpha = .28f), RoundedCornerShape(12.dp))
            .clickable(enabled = policy.allows(TaskDetailInteraction.OPEN_ATTENTION)) {
                onAction(TaskDetailAction.OpenAttention(attention.callId))
            }
            .testTag("action-OpenAttention")
            .taskDetailContractAction(policy, TaskDetailInteraction.OPEN_ATTENTION)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.warning, modifier = Modifier.size(19.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Needs your attention", style = MaterialTheme.typography.labelLarge, color = colors.warning)
            Text(attention.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun TimelineItemView(
    item: TimelineItem,
    active: Boolean,
    canImplementPlan: Boolean,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
    attention: TaskAttentionUiModel?,
    modifier: Modifier,
) {
    when (item) {
        is TimelineItem.UserMessage -> UserMessage(item)
        is TimelineItem.AssistantText -> AssistantMessage(item, active)
        is TimelineItem.ThinkingSummary -> ThinkingMessage(item)
        is TimelineItem.ToolActivity -> when {
            attention?.callId == item.toolCallId && attention.kind == TaskAttentionKind.QUESTION -> Unit
            item.kind == ToolActivityKind.USER_INPUT && item.state == ToolActivityState.RUNNING -> Unit
            item.kind == ToolActivityKind.USER_INPUT -> StatusRow(
                label = item.result?.text ?: "Question answered",
                danger = item.state == ToolActivityState.FAILURE,
            )
            attention?.callId == item.toolCallId && attention.kind != TaskAttentionKind.QUESTION ->
                AttentionCard(attention, onAction, policy, modifier)
            else -> ToolActivity(item, onAction, policy)
        }
        is TimelineItem.Plan -> PlanCard(item, canImplementPlan, onAction, policy)
        is TimelineItem.RunStatus -> StatusRow(item.label, active = active)
        is TimelineItem.Error -> StatusRow(item.message, danger = true)
        is TimelineItem.Completion -> StatusRow(item.label)
        is TimelineItem.UnsupportedActivity -> StatusRow(item.label)
    }
}

internal fun TimelineWindow.containsAttentionCall(callId: String): Boolean =
    settledItems.any { it is TimelineItem.ToolActivity && it.toolCallId == callId } ||
        (activeItem as? TimelineItem.ToolActivity)?.toolCallId == callId

@Composable
private fun UserMessage(item: TimelineItem.UserMessage) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        MessageGroup(
            role = MessageRole.USER,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            if (item.attachmentIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.attachmentIds.forEachIndexed { index, attachmentId ->
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .testTag("timeline-attachment-$attachmentId")
                                .semantics { contentDescription = "Attached image ${index + 1}" },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Image ${index + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (item.text.isNotBlank()) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(item: TimelineItem.AssistantText, active: Boolean) {
    var priorAnnouncementText by remember(item.stableKey) { mutableStateOf("") }
    var appendedAnnouncement by remember(item.stableKey) { mutableStateOf("") }
    LaunchedEffect(item.text, active, item.partial) {
        if (active || item.partial) {
            appendedAnnouncement = if (item.text.startsWith(priorAnnouncementText)) {
                item.text.removePrefix(priorAnnouncementText)
            } else {
                "Momoding response updated"
            }
            priorAnnouncementText = item.text
        } else {
            appendedAnnouncement = ""
        }
    }
    MessageGroup(
        role = MessageRole.MOMODING,
        presence = if (active || item.partial) MomodingPresence.WORKING else MomodingPresence.READY,
    ) {
        AgentMarkdown(item.text, streaming = active || item.partial)
        if (appendedAnnouncement.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = appendedAnnouncement
                    },
            )
        }
    }
}

@Composable
private fun AgentMarkdown(
    text: String,
    streaming: Boolean,
) {
    val platformUriHandler = LocalUriHandler.current
    val safeUriHandler = remember(platformUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (isAllowedAgentLink(uri)) platformUriHandler.openUri(uri)
            }
        }
    }
    val blocks = remember(text, streaming) {
        if (streaming) {
            streamingMarkdownPresentationBlocks(text)
        } else {
            markdownPresentationBlocks(text)
        }
    }
    CompositionLocalProvider(LocalUriHandler provides safeUriHandler) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("assistant-markdown"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                blocks.forEachIndexed { index, block ->
                    key(index, block.presentationKind) {
                        when (block) {
                            is MarkdownPresentationBlock.Prose -> AgentMarkdownProse(block.text)
                            is MarkdownPresentationBlock.Table -> AgentMarkdownTable(block)
                            is MarkdownPresentationBlock.Code -> AgentMarkdownCode(block)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentMarkdownProse(text: String) {
    if (text.isBlank()) return
    val body = MaterialTheme.typography.bodyMedium
    val markdownState = rememberMarkdownState(content = text, retainState = true)
    Markdown(
        markdownState = markdownState,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(
            text = MaterialTheme.colorScheme.onSurface,
            codeBackground = MaterialTheme.colorScheme.surfaceContainer,
            inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainer,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            h3 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            h4 = body.copy(fontWeight = FontWeight.Bold),
            h5 = body.copy(fontWeight = FontWeight.SemiBold),
            h6 = body.copy(fontWeight = FontWeight.SemiBold),
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            inlineCode = body.copy(fontFamily = FontFamily.Monospace),
            quote = body.copy(fontStyle = FontStyle.Italic),
            textLink = TextLinkStyles(
                style = body.copy(
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ).toSpanStyle(),
            ),
            table = MaterialTheme.typography.bodySmall,
        ),
        padding = markdownPadding(
            block = 2.dp,
            list = 2.dp,
            listItemTop = 1.dp,
            listItemBottom = 1.dp,
            listIndent = 12.dp,
        ),
    )
}

@Composable
private fun AgentMarkdownTable(table: MarkdownPresentationBlock.Table) {
    Surface(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            MarkdownTableRow(table.headers, header = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEach { row ->
                MarkdownTableRow(row, header = false)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, header: Boolean) {
    Row {
        cells.forEach { cell ->
            Text(
                text = cell,
                modifier = Modifier.widthIn(min = 120.dp, max = 260.dp).padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun AgentMarkdownCode(block: MarkdownPresentationBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    block.language.ifBlank { "Code" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(block.code)) },
                    modifier = Modifier
                        .testTag("copy-code")
                        .structuralAction("CopyCode"),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = block.code,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

internal sealed interface MarkdownPresentationBlock {
    data class Prose(val text: String) : MarkdownPresentationBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownPresentationBlock
    data class Code(val language: String, val code: String) : MarkdownPresentationBlock
}

private val MarkdownPresentationBlock.presentationKind: String
    get() = when (this) {
        is MarkdownPresentationBlock.Prose -> "prose"
        is MarkdownPresentationBlock.Table -> "table"
        is MarkdownPresentationBlock.Code -> "code"
    }

internal fun streamingMarkdownPresentationBlocks(
    text: String,
): List<MarkdownPresentationBlock> =
    markdownPresentationBlocks(text).flatMap { block ->
        if (block is MarkdownPresentationBlock.Prose) {
            splitStreamingProse(block.text)
        } else {
            listOf(block)
        }
    }

private fun splitStreamingProse(text: String): List<MarkdownPresentationBlock.Prose> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownPresentationBlock.Prose>()
    val activeFenceStart = unclosedMarkdownFenceStart(lines)
    val completedEnd = activeFenceStart ?: lines.size
    var start = 0
    var index = 0
    while (index < completedEnd) {
        if (lines[index].isNotBlank()) {
            index += 1
            continue
        }
        lines.subList(start, index).joinToString("\n").takeIf(String::isNotBlank)?.let {
            blocks += MarkdownPresentationBlock.Prose(it)
        }
        while (index < completedEnd && lines[index].isBlank()) index += 1
        start = index
    }
    lines.subList(start, completedEnd).joinToString("\n").takeIf(String::isNotBlank)?.let {
        blocks += MarkdownPresentationBlock.Prose(it)
    }
    activeFenceStart?.let { fenceStart ->
        blocks += MarkdownPresentationBlock.Prose(
            lines.subList(fenceStart, lines.size).joinToString("\n"),
        )
    }
    return blocks.ifEmpty { listOf(MarkdownPresentationBlock.Prose(text)) }
}

private fun unclosedMarkdownFenceStart(lines: List<String>): Int? {
    var openMarker: String? = null
    var openIndex: Int? = null
    lines.forEachIndexed { index, line ->
        val trimmed = line.trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        } ?: return@forEachIndexed
        if (openMarker == null) {
            openMarker = marker
            openIndex = index
        } else if (openMarker == marker) {
            openMarker = null
            openIndex = null
        }
    }
    return openIndex
}

internal fun markdownPresentationBlocks(text: String): List<MarkdownPresentationBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownPresentationBlock>()
    var proseStart = 0
    var index = 0
    while (index < lines.size) {
        val trimmed = lines[index].trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (marker != null) {
            val closing = ((index + 1) until lines.size).firstOrNull {
                lines[it].trimStart().startsWith(marker)
            }
            if (closing != null) {
                lines.subList(proseStart, index).joinToString("\n")
                    .takeIf(String::isNotBlank)
                    ?.let { blocks += MarkdownPresentationBlock.Prose(it) }
                blocks += MarkdownPresentationBlock.Code(
                    language = trimmed.removePrefix(marker).trim(),
                    code = lines.subList(index + 1, closing).joinToString("\n"),
                )
                index = closing + 1
                proseStart = index
                continue
            }
        }
        val isTable = index + 1 < lines.size &&
            markdownTableCells(lines[index]).size >= 2 &&
            isMarkdownTableDivider(lines[index + 1])
        if (!isTable) {
            index += 1
            continue
        }
        lines.subList(proseStart, index).joinToString("\n").takeIf(String::isNotBlank)?.let {
            blocks += MarkdownPresentationBlock.Prose(it)
        }
        val headers = markdownTableCells(lines[index])
        index += 2
        val rows = mutableListOf<List<String>>()
        while (index < lines.size) {
            val cells = markdownTableCells(lines[index])
            if (cells.size < 2) break
            rows += List(headers.size) { column -> cells.getOrElse(column) { "" } }
            index += 1
        }
        blocks += MarkdownPresentationBlock.Table(headers, rows)
        proseStart = index
    }
    lines.subList(proseStart, lines.size).joinToString("\n").takeIf(String::isNotBlank)?.let {
        blocks += MarkdownPresentationBlock.Prose(it)
    }
    return blocks.ifEmpty { listOf(MarkdownPresentationBlock.Prose(text)) }
}

private fun isMarkdownTableDivider(line: String): Boolean {
    val cells = markdownTableCells(line)
    return cells.size >= 2 && cells.all { it.matches(Regex(":?-{3,}:?")) }
}

private fun markdownTableCells(line: String): List<String> {
    if ('|' !in line) return emptyList()
    val placeholder = "\u0000PIPE\u0000"
    return line.trim().trim('|').replace("\\|", placeholder).split('|').map {
        it.trim().replace(placeholder, "|")
    }
}

internal fun isAllowedAgentLink(uri: String): Boolean = runCatching {
    val parsed = java.net.URI(uri)
    parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()
}.getOrDefault(false)

@Composable
private fun ThinkingMessage(item: TimelineItem.ThinkingSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text("Thinking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun PlanCard(
    item: TimelineItem.Plan,
    canImplement: Boolean,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    val completed = item.steps.count { it.state == TaskPlanStepState.COMPLETED }
    val tone = when {
        item.steps.isNotEmpty() && completed == item.steps.size -> WorkBlockTone.SUCCESS
        item.steps.any { it.state == TaskPlanStepState.IN_PROGRESS } -> WorkBlockTone.ACTIVE
        else -> WorkBlockTone.NEUTRAL
    }
    WorkBlock(
        title = "Plan",
        detail = item.explanation,
        icon = MomodingIcons.Tasks,
        tone = tone,
        statusLabel = "$completed/${item.steps.size}",
        modifier = Modifier.testTag("plan-card-${item.planDigest.take(12)}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (label, color) = when (step.state) {
                        TaskPlanStepState.COMPLETED -> "✓" to LocalMomodingStatusColors.current.success
                        TaskPlanStepState.IN_PROGRESS -> "●" to LocalMomodingStatusColors.current.info
                        TaskPlanStepState.PENDING -> "${index + 1}" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Text(step.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            TextButton(
                onClick = { onAction(TaskDetailAction.ImplementPlan(item.planDigest)) },
                enabled = canImplement && policy.allows(TaskDetailInteraction.IMPLEMENT_PLAN),
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp)
                    .testTag("action-ImplementPlan")
                    .taskDetailContractAction(policy, TaskDetailInteraction.IMPLEMENT_PLAN),
            ) {
                Text(if (canImplement) "Implement plan" else "Plan saved")
            }
        }
    }
}

@Composable
private fun ToolActivity(
    item: TimelineItem.ToolActivity,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    val contextualAction = item.action?.let { action ->
        when (action) {
            ToolActivityAction.REVIEW_CHANGES -> Triple(
                "Review changes",
                TaskDetailAction.OpenDiff,
                TaskDetailInteraction.OPEN_DIFF,
            )
            ToolActivityAction.VIEW_OUTPUTS -> Triple(
                "View outputs",
                TaskDetailAction.OpenOutputs,
                TaskDetailInteraction.OPEN_OUTPUTS,
            )
        }
    }
    val expandable = item.result != null
    val icon = when (item.state) {
        ToolActivityState.RUNNING -> MomodingIcons.Retry
        ToolActivityState.SUCCESS -> Icons.Outlined.CheckCircle
        ToolActivityState.FAILURE -> Icons.Outlined.ErrorOutline
        ToolActivityState.CANCELLED -> MomodingFilledIcons.Stop
        ToolActivityState.UNSUPPORTED -> MomodingIcons.Warning
    }
    val tone = when (item.state) {
        ToolActivityState.RUNNING -> WorkBlockTone.ACTIVE
        ToolActivityState.SUCCESS -> WorkBlockTone.SUCCESS
        ToolActivityState.FAILURE -> WorkBlockTone.DANGER
        ToolActivityState.CANCELLED, ToolActivityState.UNSUPPORTED -> WorkBlockTone.WARNING
    }
    val status = when (item.state) {
        ToolActivityState.RUNNING -> "Working"
        ToolActivityState.SUCCESS -> "Done"
        ToolActivityState.FAILURE -> "Failed"
        ToolActivityState.CANCELLED -> "Stopped"
        ToolActivityState.UNSUPPORTED -> "Unsupported"
    }
    WorkBlock(
        title = item.title,
        detail = item.detail.takeIf { contextualAction == null },
        icon = icon,
        tone = tone,
        statusLabel = status.takeIf { contextualAction == null },
        onClick = if (expandable && policy.allows(TaskDetailInteraction.OPEN_TOOL)) {
            { onAction(TaskDetailAction.ToggleTool(item.stableKey)) }
        } else null,
        trailing = if (expandable) {
            {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = if (item.expanded) "Collapse tool activity" else "Expand tool activity",
                    modifier = Modifier.size(19.dp),
                )
            }
        } else null,
        modifier = if (expandable) {
            Modifier
                .testTag("action-OpenTool")
                .taskDetailContractAction(policy, TaskDetailInteraction.OPEN_TOOL)
        } else {
            Modifier.testTag("tool-activity-${item.toolCallId}")
        },
        content = if ((item.expanded && item.result != null) || contextualAction != null) {
            {
                if (item.expanded && item.result != null) ToolResult(item)
                contextualAction?.let { (label, action, interaction) ->
                    TextButton(
                        onClick = { onAction(action) },
                        enabled = policy.allows(interaction),
                        modifier = Modifier
                            .align(Alignment.End)
                            .heightIn(min = 48.dp)
                            .testTag("action-${interaction.contractName}")
                            .taskDetailContractAction(policy, interaction),
                    ) {
                        Text(label)
                    }
                }
            }
        } else null,
    )
}

@Composable
private fun ChildAgentsSection(
    children: List<TaskChildAgentUiModel>,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    val completed = children.count { it.state == TaskChildAgentState.COMPLETED }
    val running = children.count { it.state == TaskChildAgentState.RUNNING }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag("child-agents-section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                "Child agents · ${children.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text(
                "$completed completed · $running running",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        children.forEach { child ->
            ChildAgentCard(child, onAction, policy)
        }
    }
}

@Composable
private fun ChildAgentCard(
    child: TaskChildAgentUiModel,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    val colors = LocalMomodingStatusColors.current
    val (label, tint) = when (child.state) {
        TaskChildAgentState.RUNNING -> "Running" to colors.info
        TaskChildAgentState.COMPLETED -> "Completed" to colors.success
        TaskChildAgentState.FAILED -> "Failed" to colors.danger
        TaskChildAgentState.CANCELLED -> "Cancelled" to colors.warning
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = policy.allows(TaskDetailInteraction.OPEN_CHILD_AGENT)) {
                onAction(TaskDetailAction.ToggleChildAgent(child.parentToolCallId))
            }
            .testTag("child-agent-${child.parentToolCallId}")
            .taskDetailContractAction(policy, TaskDetailInteraction.OPEN_CHILD_AGENT),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (child.state == TaskChildAgentState.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = tint,
                    )
                } else {
                    Box(
                        modifier = Modifier.size(8.dp).background(tint, CircleShape),
                    )
                }
                Column(modifier = Modifier.padding(start = 9.dp).weight(1f)) {
                    Text(child.name, style = MaterialTheme.typography.labelLarge)
                    Text(
                        when (child.state) {
                            TaskChildAgentState.FAILED,
                            TaskChildAgentState.CANCELLED,
                            -> child.terminalReason ?: child.summary ?: child.instruction
                            TaskChildAgentState.RUNNING,
                            TaskChildAgentState.COMPLETED,
                            -> child.summary ?: child.instruction
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (child.expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                Icon(
                    if (child.expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.ChevronRight,
                    contentDescription = if (child.expanded) "Collapse child agent" else "Expand child agent",
                    modifier = Modifier.size(19.dp),
                )
            }
            if (child.expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Instruction", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        child.instruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                child.result?.takeIf(String::isNotBlank)?.let { result ->
                    Text("Result", style = MaterialTheme.typography.labelMedium)
                    AgentMarkdown(result, streaming = false)
                    if (child.resultTruncated) {
                        Text(
                            "Result was truncated on device.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.warning,
                        )
                    }
                }
                childUsageLabel(child)?.let { usage ->
                    Text(
                        usage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (child.state == TaskChildAgentState.RUNNING) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.CancelChildAgent(child.parentToolCallId)) },
                        enabled = policy.allows(TaskDetailInteraction.CANCEL_CHILD_AGENT),
                        modifier = Modifier
                            .testTag("action-CancelChildAgent-${child.parentToolCallId}")
                            .taskDetailContractAction(policy, TaskDetailInteraction.CANCEL_CHILD_AGENT),
                    ) {
                        Text("Cancel child")
                    }
                }
            }
        }
    }
}

private fun childUsageLabel(child: TaskChildAgentUiModel): String? {
    if (
        child.model == null && child.turnCount == 0 && child.contextTokens == 0 &&
        child.inputTokens == 0 && child.outputTokens == 0 && child.costUsd == 0.0
    ) return null
    return buildList {
        child.model?.let { add(it) }
        if (child.turnCount > 0) add("${child.turnCount} turn")
        if (child.inputTokens > 0 || child.outputTokens > 0) {
            add("${child.inputTokens} in / ${child.outputTokens} out")
        }
        if (child.cacheReadTokens > 0 || child.cacheWriteTokens > 0) {
            add("${child.cacheReadTokens} cache read / ${child.cacheWriteTokens} write")
        }
        if (child.contextTokens > 0) add("${child.contextTokens} context")
        if (child.costUsd > 0.0) add("USD ${"%.4f".format(child.costUsd)}")
    }.joinToString(" · ")
}

@Composable
private fun ToolResult(item: TimelineItem.ToolActivity) {
    val result = requireNotNull(item.result)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .testTag("tool-result-${item.toolCallId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (result.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = result.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (result.sources.isNotEmpty()) {
            Text("Sources", style = MaterialTheme.typography.labelMedium)
            result.sources.forEach { source ->
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (result.truncated) {
            Text(
                "Output was truncated for display.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, active: Boolean = false, danger: Boolean = false) {
    val colors = LocalMomodingStatusColors.current
    val brand = LocalMomodingBrandColors.current
    val indicatorColor = if (danger) colors.danger else if (active) brand.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = if (danger) colors.danger else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().semantics { if (active) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(indicatorColor, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall, color = textColor)
        if (active) CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp, color = indicatorColor)
    }
}

@Composable
private fun QueueRow(item: QueueItemUiModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .heightIn(min = 48.dp)
            .testTag("queued-message-${item.position}")
            .semantics {
                contentDescription = "Queued message ${item.position}: ${item.text}"
            }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Queued",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GoalCard(
    state: TaskDetailUiState,
    goal: TaskGoalUiModel,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    val tone = when (goal.state) {
        TaskGoalState.ACTIVE, TaskGoalState.PAUSE_PENDING -> WorkBlockTone.ACTIVE
        TaskGoalState.ACHIEVED -> WorkBlockTone.SUCCESS
        TaskGoalState.BLOCKED, TaskGoalState.LIMITED -> WorkBlockTone.WARNING
        TaskGoalState.FAILED -> WorkBlockTone.DANGER
        TaskGoalState.PAUSED, TaskGoalState.CLEARED -> WorkBlockTone.NEUTRAL
    }
    WorkBlock(
        title = "Goal",
        detail = goal.instruction,
        icon = Icons.Outlined.AutoAwesome,
        tone = tone,
        statusLabel = goal.state.goalLabel(),
        modifier = Modifier
            .testTag("goal-card-${goal.goalId}")
            .semantics { stateDescription = goal.state.goalLabel() },
    ) {
        goal.progressSummary?.let { summary ->
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    goal.progressMarker?.let { append(it).append(" · ") }
                    append("Auto turns ${goal.automaticTurnCount}/20")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (state.canPauseGoal) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.PauseGoal) },
                        enabled = policy.allows(TaskDetailInteraction.PAUSE_GOAL),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("action-PauseGoal"),
                    ) { Text("Pause") }
                }
                if (state.canResumeGoal) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.ResumeGoal) },
                        enabled = policy.allows(TaskDetailInteraction.RESUME_GOAL),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("action-ResumeGoal"),
                    ) { Text("Resume") }
                }
                if (state.canEditGoal) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.OpenEditGoal) },
                        enabled = policy.allows(TaskDetailInteraction.EDIT_GOAL),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("action-OpenEditGoal"),
                    ) { Text("Edit") }
                }
                if (state.canClearGoal) {
                    TextButton(
                        onClick = { onAction(TaskDetailAction.ConfirmGoal(GoalConfirmation.CLEAR)) },
                        enabled = policy.allows(TaskDetailInteraction.CLEAR_GOAL),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("action-ClearGoal"),
                    ) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun GoalEditorDialog(
    state: TaskDetailUiState,
    onAction: (TaskDetailAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(TaskDetailAction.CloseGoalEditor) },
        title = { Text(if (state.goalEditorMode == GoalEditorMode.CREATE) "Create goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Momoding can continue across multiple turns. It pauses after 20 automatic turns or 60 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.goalDraft,
                    onValueChange = { onAction(TaskDetailAction.EditGoalDraft(it)) },
                    modifier = Modifier.fillMaxWidth().testTag("action-EditGoalDraft"),
                    label = { Text("Goal") },
                    minLines = 3,
                    maxLines = 8,
                    supportingText = { Text("${state.goalDraft.text.length}/4096") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(TaskDetailAction.SubmitGoal) },
                enabled = state.goalDraft.text.trim().isNotEmpty() && state.goalDraft.text.length <= 4096,
                modifier = Modifier.testTag("action-SubmitGoal"),
            ) { Text(if (state.goalEditorMode == GoalEditorMode.CREATE) "Start goal" else "Review edit") }
        },
        dismissButton = {
            TextButton(onClick = { onAction(TaskDetailAction.CloseGoalEditor) }) { Text("Cancel") }
        },
    )
}

@Composable
private fun GoalConfirmationDialog(
    confirmation: GoalConfirmation,
    onAction: (TaskDetailAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(TaskDetailAction.DismissGoalConfirmation) },
        title = { Text(if (confirmation == GoalConfirmation.EDIT) "Replace this goal?" else "Clear this goal?") },
        text = {
            Text(
                if (confirmation == GoalConfirmation.EDIT) {
                    "Editing starts a new goal generation and resets its automatic-turn budget."
                } else {
                    "The goal state will be cleared. Task messages and results stay in this task."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(TaskDetailAction.ApplyGoalConfirmation) },
                modifier = Modifier.testTag("action-ApplyGoalConfirmation"),
            ) { Text(if (confirmation == GoalConfirmation.EDIT) "Replace" else "Clear") }
        },
        dismissButton = {
            TextButton(onClick = { onAction(TaskDetailAction.DismissGoalConfirmation) }) { Text("Cancel") }
        },
    )
}

private fun TaskGoalState.goalLabel(): String = when (this) {
    TaskGoalState.ACTIVE -> "Active"
    TaskGoalState.PAUSE_PENDING -> "Pausing after this turn"
    TaskGoalState.PAUSED -> "Paused"
    TaskGoalState.BLOCKED -> "Blocked"
    TaskGoalState.LIMITED -> "Limit reached"
    TaskGoalState.FAILED -> "Failed"
    TaskGoalState.ACHIEVED -> "Achieved"
    TaskGoalState.CLEARED -> "Cleared"
}

@Composable
private fun CenterState(label: String, showProgress: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showProgress) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskComposerDock(
    state: TaskDetailUiState,
    focusManager: FocusManager,
    onAction: (TaskDetailAction) -> Unit,
    policy: TaskDetailInteractionPolicy,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
            .padding(start = 13.dp, top = 8.dp, end = 13.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.queue.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("queued-messages"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.queue.take(3).forEach { queued -> QueueRow(queued) }
                if (state.queue.size > 3) {
                    Text(
                        "+${state.queue.size - 3} more queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (state.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.attachments.forEach { attachment ->
                    TaskComposerAttachment(
                        attachment = attachment,
                        removalEnabled = state.canEditComposer && !state.attachmentImporting,
                        onRemove = { onAction(TaskDetailAction.RemoveAttachment(attachment.attachmentId)) },
                    )
                }
            }
        }
        state.attachmentError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = LocalMomodingStatusColors.current.danger,
            )
        }
        state.approvalModeError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = LocalMomodingStatusColors.current.danger,
            )
        }
        ComposerDock(
            editor = {
            BasicTextField(
                value = state.composer,
                onValueChange = { onAction(TaskDetailAction.EditComposer(it)) },
                enabled = state.canEditComposer && policy.allows(TaskDetailInteraction.EDIT_COMPOSER),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 150.dp)
                    .testTag("action-EditComposer")
                    .taskDetailContractAction(policy, TaskDetailInteraction.EDIT_COMPOSER),
                decorationBox = { inner ->
                    Box {
                        if (state.composer.text.isEmpty()) {
                            Text(
                                state.composerPlaceholder(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            },
            footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.attachmentInputEnabled || state.phoneLocal) {
                        IconButton(
                            onClick = { onAction(TaskDetailAction.OpenAttachmentMenu) },
                            enabled = state.canEditComposer && !state.attachmentImporting &&
                                policy.allows(TaskDetailInteraction.OPEN_ATTACHMENT_MENU),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("action-OpenAttachmentMenu")
                                .taskDetailContractAction(policy, TaskDetailInteraction.OPEN_ATTACHMENT_MENU),
                        ) {
                            if (state.attachmentImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(MomodingIcons.Add, contentDescription = "Add")
                            }
                        }
                    }
                    app.momoding.ui.components.ApprovalModeSelector(
                        mode = state.approvalMode,
                        saving = state.approvalModeSaving,
                        enabled = state.canSelectApprovalMode &&
                            policy.allows(TaskDetailInteraction.SELECT_APPROVAL_MODE),
                        onSelect = { onAction(TaskDetailAction.SelectApprovalMode(it)) },
                        errorMessage = state.approvalModeError,
                        modifier = Modifier.taskDetailContractAction(
                            policy,
                            TaskDetailInteraction.SELECT_APPROVAL_MODE,
                        ),
                    )
                    state.composerFooter()?.let { footer ->
                        Text(
                            footer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val submitInteraction = when (state.composerMode) {
                        TaskComposerMode.PROMPT -> TaskDetailInteraction.SUBMIT_PROMPT
                        TaskComposerMode.STEER -> TaskDetailInteraction.SUBMIT_STEER
                        TaskComposerMode.FOLLOW_UP -> TaskDetailInteraction.SUBMIT_FOLLOW_UP
                        TaskComposerMode.BLOCKED -> TaskDetailInteraction.SUBMIT_PROMPT
                    }
                    val submitAllowed = state.canSubmit && policy.allows(submitInteraction)
                    val showSubmit = state.composerMode == TaskComposerMode.PROMPT ||
                        (state.composerMode != TaskComposerMode.BLOCKED &&
                            (state.composer.text.isNotBlank() || state.attachments.isNotEmpty()))
                    if (showSubmit) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                onAction(TaskDetailAction.Submit)
                            },
                            enabled = submitAllowed,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("action-${submitInteraction.contractName}")
                                .taskDetailContractAction(policy, submitInteraction)
                                .semantics { state.composerBlockedReason?.let { stateDescription = it } },
                        ) {
                            ComposerActionBox(
                                icon = MomodingFilledIcons.Send,
                                contentDescription = state.submitDescription(),
                                enabled = submitAllowed,
                                loading = state.commandPending && state.command !is TaskCommandUiState.Persisting,
                            )
                        }
                    }
                    if (state.canStop || state.activeStopFence) {
                        val stopAllowed = state.canStop && policy.allows(TaskDetailInteraction.STOP)
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                onAction(TaskDetailAction.Stop)
                            },
                            enabled = stopAllowed,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("action-Stop")
                                .taskDetailContractAction(policy, TaskDetailInteraction.STOP),
                        ) {
                            ComposerActionBox(
                                icon = MomodingFilledIcons.Stop,
                                contentDescription = if (state.activeStopFence) "Waiting for task to stop" else "Stop task",
                                enabled = stopAllowed,
                                loading = state.activeStopFence,
                            )
                        }
                    }
                }
            }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TaskAttachmentPickerSheet(
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onTextFile: () -> Unit,
    onGoal: () -> Unit,
    onTogglePlanMode: () -> Unit,
    photosEnabled: Boolean,
    textFileEnabled: Boolean,
    goal: TaskGoalUiModel?,
    goalEnabled: Boolean,
    planAvailable: Boolean,
    planEnabled: Boolean,
    planSaving: Boolean,
    planToggleEnabled: Boolean,
    attachmentCapacityAvailable: Boolean,
    policy: TaskDetailInteractionPolicy,
) {
    val hasAttachmentOptions = photosEnabled || textFileEnabled
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add", style = MaterialTheme.typography.titleLarge)
            Text(
                if (hasAttachmentOptions) {
                    "Add context or choose how Momoding should continue this task."
                } else {
                    "Choose how Momoding should continue this task."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (photosEnabled) {
                ComposerMenuItem(
                    icon = Icons.Outlined.Image,
                    title = "Photos",
                    detail = "Choose up to five images with Android Photo Picker",
                    onClick = onPhotos,
                    enabled = attachmentCapacityAvailable &&
                        policy.allows(TaskDetailInteraction.IMPORT_PHOTOS),
                    modifier = Modifier
                        .testTag("action-ImportPhotos")
                        .taskDetailContractAction(policy, TaskDetailInteraction.IMPORT_PHOTOS),
                )
            }
            if (textFileEnabled) {
                ComposerMenuItem(
                    icon = Icons.Outlined.AttachFile,
                    title = "Text file",
                    detail = "Choose UTF-8 text, code, Markdown, JSON, or XML",
                    onClick = onTextFile,
                    enabled = attachmentCapacityAvailable &&
                        policy.allows(TaskDetailInteraction.IMPORT_TEXT_FILE),
                    modifier = Modifier
                        .testTag("action-ImportTextFile")
                        .taskDetailContractAction(policy, TaskDetailInteraction.IMPORT_TEXT_FILE),
                )
            }
            if (planAvailable) {
                val goalInteraction = if (goal == null) {
                    TaskDetailInteraction.CREATE_GOAL
                } else {
                    TaskDetailInteraction.EDIT_GOAL
                }
                ComposerMenuItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Goal",
                    detail = when {
                        goal != null -> goal.instruction
                        !goalEnabled && planEnabled -> "Turn off Plan mode before starting a Goal"
                        !goalEnabled -> "Available when the current task is ready"
                        else -> "Set an objective Momoding can keep working toward"
                    },
                    onClick = onGoal,
                    enabled = goalEnabled && policy.allows(goalInteraction),
                    selected = goal != null,
                    modifier = Modifier
                        .testTag(if (goal == null) "action-OpenCreateGoal" else "action-OpenEditGoal")
                        .taskDetailContractAction(policy, goalInteraction),
                )
                ComposerMenuItem(
                    icon = MomodingIcons.Tasks,
                    title = "Plan mode",
                    detail = when {
                        !planToggleEnabled && goal != null ->
                            "Pause or clear the current Goal before changing Plan mode"
                        !planToggleEnabled -> "Available when the current task is ready"
                        planEnabled -> "On · Momoding will plan before making changes"
                        else -> "Ask Momoding to make a plan before acting"
                    },
                    onClick = onTogglePlanMode,
                    enabled = planToggleEnabled &&
                        policy.allows(TaskDetailInteraction.TOGGLE_PLAN_MODE),
                    selected = planEnabled,
                    loading = planSaving,
                    modifier = Modifier
                        .testTag("action-TogglePlanMode")
                        .taskDetailContractAction(policy, TaskDetailInteraction.TOGGLE_PLAN_MODE),
                )
            }
        }
    }
}

@Composable
private fun TaskComposerAttachment(
    attachment: TaskDetailAttachmentUiModel,
    removalEnabled: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("attachment-${attachment.attachmentId}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 7.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val bitmap = remember(attachment.attachmentId, attachment.thumbnailPng) {
                attachment.thumbnailPng?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            } else {
                Icon(
                    if (attachment.kind == AttachmentKind.IMAGE) {
                        Icons.Outlined.Image
                    } else {
                        Icons.Outlined.AttachFile
                    },
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                Text(
                    attachment.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${if (attachment.kind == AttachmentKind.IMAGE) "Image" else "Text file"} · " +
                        "${(attachment.byteSize + 1023L) / 1024L} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRemove,
                enabled = removalEnabled,
                modifier = Modifier.size(40.dp).testTag("action-RemoveAttachment"),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove ${attachment.displayName}")
            }
        }
    }
}

@Composable
private fun ComposerActionBox(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    loading: Boolean,
) {
    val brand = LocalMomodingBrandColors.current
    Box(
        modifier = Modifier.size(36.dp).background(
            if (enabled) brand.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            RoundedCornerShape(10.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = if (enabled) brand.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) brand.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun TaskDetailUiState.statusSubtitle(): String = when {
    loadState == TaskDetailLoadState.LOADING -> "Loading"
    loadState == TaskDetailLoadState.MISSING -> "Task unavailable"
    loadState == TaskDetailLoadState.ERROR -> "Couldn't load task"
    connection == TaskDetailConnectionState.RECONNECTING -> "Reconnecting"
    connection == TaskDetailConnectionState.OFFLINE -> "Offline"
    connection == TaskDetailConnectionState.UNPAIRED -> "Unavailable"
    connection == TaskDetailConnectionState.ERROR -> "Connection problem"
    attention != null -> "Waiting for you"
    runState == TaskDetailRunState.FAILED -> "Needs attention"
    runState == TaskDetailRunState.INTERRUPTED -> "Interrupted"
    runState in setOf(
        TaskDetailRunState.STARTING,
        TaskDetailRunState.RUNNING,
        TaskDetailRunState.RETRYING,
        TaskDetailRunState.COMPACTING,
        TaskDetailRunState.STOPPING,
        TaskDetailRunState.RECOVERING,
    ) -> "Working"
    else -> "Ready"
}

private fun TaskDetailUiState.composerPlaceholder(): String = when (composerMode) {
    TaskComposerMode.PROMPT -> when {
        planMode -> "Describe what the plan should cover"
        runState == TaskDetailRunState.FAILED -> "Ask Momoding to retry"
        else -> "Ask a follow-up"
    }
    TaskComposerMode.STEER,
    TaskComposerMode.FOLLOW_UP,
    -> "Add a follow-up"
    TaskComposerMode.BLOCKED -> composerBlockedReason ?: "Waiting for a safe task state"
}

private fun TaskDetailUiState.composerFooter(): String? = when {
    activeStopFence -> "Stop saved"
    command is TaskCommandUiState.Persisting -> "Saving locally"
    command is TaskCommandUiState.Recovering -> "Waiting for Host"
    command is TaskCommandUiState.Sending -> "Sending to Host"
    else -> null
}

private fun TaskDetailUiState.submitDescription(): String = when (composerMode) {
    TaskComposerMode.PROMPT,
    TaskComposerMode.STEER,
    TaskComposerMode.FOLLOW_UP,
    -> "Send message"
    TaskComposerMode.BLOCKED -> composerBlockedReason ?: "Send unavailable"
}
