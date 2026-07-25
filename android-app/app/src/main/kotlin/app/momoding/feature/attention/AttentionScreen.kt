package app.momoding.feature.attention

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionOption
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionValidationCode
import app.momoding.feature.settings.contractAction
import app.momoding.feature.settings.structuralAction
import app.momoding.ui.components.DecisionFact
import app.momoding.ui.components.DecisionFacts
import app.momoding.ui.components.DecisionSheet
import app.momoding.ui.components.MomodingMark
import app.momoding.ui.components.MomodingPresence
import app.momoding.ui.components.PrimaryAction
import app.momoding.ui.components.SecondaryAction
import app.momoding.ui.icons.MomodingIcons
import app.momoding.ui.theme.LocalMomodingStatusColors
import kotlinx.coroutines.launch

internal enum class AttentionVisualRole(
    val testTag: String,
) {
    CONFIRM_WARNING("confirm-warning"),
    CONTENT_READ_WARNING("content-read-warning"),
    QUESTION_WARNING("question-warning"),
    VALIDATION_ERROR("validation-error"),
    OFFLINE_INFO("offline-info"),
    RESPONDING_INFO("responding-info"),
    RESOLVED_SUCCESS("resolved-success"),
    REJECTED_DANGER("rejected-danger"),
    SKIPPED_NEUTRAL("skipped-neutral"),
    EXPIRED_NEUTRAL("expired-neutral"),
    CANCELLED_NEUTRAL("cancelled-neutral"),
    ALREADY_ANSWERED_INFO("already-answered-info"),
}

internal data class AttentionStateCopy(
    val eyebrow: String,
    val heading: String,
    val body: String? = null,
)

internal fun AttentionVisibleState.visualRole(): AttentionVisualRole = when (this) {
    AttentionVisibleState.ConfirmPending -> AttentionVisualRole.CONFIRM_WARNING
    AttentionVisibleState.ContentReadPending -> AttentionVisualRole.CONTENT_READ_WARNING
    AttentionVisibleState.FileChangesPending -> AttentionVisualRole.CONFIRM_WARNING
    AttentionVisibleState.QuestionPending -> AttentionVisualRole.QUESTION_WARNING
    is AttentionVisibleState.ValidationError -> AttentionVisualRole.VALIDATION_ERROR
    AttentionVisibleState.OfflinePending -> AttentionVisualRole.OFFLINE_INFO
    AttentionVisibleState.Responding -> AttentionVisualRole.RESPONDING_INFO
    AttentionVisibleState.Resolved -> AttentionVisualRole.RESOLVED_SUCCESS
    AttentionVisibleState.Rejected -> AttentionVisualRole.REJECTED_DANGER
    AttentionVisibleState.Skipped -> AttentionVisualRole.SKIPPED_NEUTRAL
    AttentionVisibleState.Expired -> AttentionVisualRole.EXPIRED_NEUTRAL
    AttentionVisibleState.Cancelled -> AttentionVisualRole.CANCELLED_NEUTRAL
    AttentionVisibleState.AlreadyAnswered -> AttentionVisualRole.ALREADY_ANSWERED_INFO
}

internal fun AttentionUiState.Visible.stateCopy(): AttentionStateCopy = when (val visible = state) {
    AttentionVisibleState.ConfirmPending -> {
        val confirmation = prompt as? AttentionPrompt.Confirmation
        AttentionStateCopy(
            eyebrow = "Review required",
            heading = confirmation?.summary ?: "Review this request",
            body = confirmation?.details,
        )
    }
    AttentionVisibleState.ContentReadPending -> {
        val read = prompt as? AttentionPrompt.ContentRead
        AttentionStateCopy(
            eyebrow = "File access request",
            heading = "Allow Momoding to read ${read?.documents?.size ?: 0} " +
                if (read?.documents?.size == 1) "file?" else "files?",
            body = read?.purpose,
        )
    }
    AttentionVisibleState.FileChangesPending -> AttentionStateCopy(
        eyebrow = "File changes",
        heading = "Review proposed changes",
        body = "Open the task's Review changes page to inspect and approve this change set.",
    )
    AttentionVisibleState.QuestionPending -> {
        val question = prompt as? AttentionPrompt.Question
        AttentionStateCopy(
            eyebrow = "Momoding question",
            heading = question?.question ?: "Answer Momoding",
        )
    }
    is AttentionVisibleState.ValidationError -> {
        val question = prompt as? AttentionPrompt.Question
        AttentionStateCopy(
            eyebrow = "Momoding question",
            heading = question?.question ?: "Answer Momoding",
        )
    }
    AttentionVisibleState.OfflinePending -> AttentionStateCopy(
        eyebrow = "Host offline",
        heading = "Your draft is saved on this device.",
        body = "Reconnect before sending a response.",
    )
    AttentionVisibleState.Responding -> AttentionStateCopy(
        eyebrow = "Response saved",
        heading = "Waiting for Momoding",
        body = "Your response is saved on this device and is waiting for Momoding.",
    )
    AttentionVisibleState.Resolved -> AttentionStateCopy(
        eyebrow = "Answer sent",
        heading = "Momoding received your response.",
    )
    AttentionVisibleState.Rejected -> AttentionStateCopy(
        eyebrow = "Step declined",
        heading = "Momoding received your decision.",
        body = "This step will not continue.",
    )
    AttentionVisibleState.Skipped -> AttentionStateCopy(
        eyebrow = "Question skipped",
        heading = "The task can continue without an answer.",
    )
    AttentionVisibleState.Expired -> AttentionStateCopy(
        eyebrow = "Request expired",
        heading = "This request is no longer active.",
    )
    AttentionVisibleState.Cancelled -> AttentionStateCopy(
        eyebrow = "Request cancelled",
        heading = "This request was cancelled.",
    )
    AttentionVisibleState.AlreadyAnswered -> AttentionStateCopy(
        eyebrow = "Already handled",
        heading = "This request was already handled.",
    )
}

internal fun AttentionTransientNotice.fixedCopy(): String = when (this) {
    AttentionTransientNotice.DECISION_NOT_SENT ->
        "Your response wasn't sent. Try the action again or dismiss this request."
    AttentionTransientNotice.DRAFT_NOT_SAVED ->
        "Your latest edit wasn't saved. Edit the answer or choose an option to try again. Your last saved draft is still available."
    AttentionTransientNotice.DISMISS_NOT_SAVED ->
        "Couldn't save the dismissal. Try closing again, or return to the task without dismissing."
    AttentionTransientNotice.EDITOR_LIMIT_REACHED ->
        "This answer is too long to save. Shorten it to 8,192 characters or fewer. Your last saved draft is unchanged."
    AttentionTransientNotice.CONNECTION_RETRY_FAILED ->
        "Couldn't reconnect to the Host. Try again."
}

@Composable
fun AttentionScreen(
    state: AttentionUiState,
    onIntent: (AttentionIntent) -> Unit,
    modifier: Modifier = Modifier,
    embeddedInBottomSheet: Boolean = false,
) {
    when (state) {
        is AttentionUiState.Loading -> AttentionLoading(modifier)
        is AttentionUiState.Unavailable,
        is AttentionUiState.Corrupt,
        is AttentionUiState.FailedClosedHidden,
        -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(1.dp)
                .testTag("attention-failed-closed"),
        )
        is AttentionUiState.Visible -> AttentionVisibleScreen(
            state = state,
            onIntent = onIntent,
            modifier = modifier,
            embeddedInBottomSheet = embeddedInBottomSheet,
        )
    }
}

@Composable
private fun AttentionLoading(modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .testTag("attention-loading"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = "Loading request",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttentionVisibleScreen(
    state: AttentionUiState.Visible,
    onIntent: (AttentionIntent) -> Unit,
    modifier: Modifier,
    embeddedInBottomSheet: Boolean,
) {
    val copy = state.stateCopy()
    val dismissIntent = state.closeIntent()
    val titleFocus = remember(state.identity.stableKey) { FocusRequester() }
    val density = LocalDensity.current
    val maxSheetHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() - 44.dp
    }.coerceAtLeast(280.dp)
    LaunchedEffect(state.identity.stableKey) {
        withFrameNanos { }
        titleFocus.requestFocus()
    }

    DecisionSheet(
        maxHeight = maxSheetHeight,
        paneTitle = copy.eyebrow,
        showSheetChrome = !embeddedInBottomSheet,
        modifier = modifier
            .testTag("attention-sheet"),
        content = {
            AttentionHeader(
                state = state,
                copy = copy,
                titleFocus = titleFocus,
            )
            when (state.state) {
                AttentionVisibleState.ConfirmPending ->
                    ConfirmationContext(state.prompt)
                AttentionVisibleState.ContentReadPending ->
                    ContentReadContext(state.prompt)
                AttentionVisibleState.FileChangesPending ->
                    DecisionFacts(
                        facts = listOf(
                            DecisionFact("Scope", "This task"),
                            DecisionFact("Action", "Open Review changes"),
                            DecisionFact("Impact", "Inspect every file operation before applying"),
                            DecisionFact("Duration", "No changes run from this sheet"),
                        ),
                    )
                AttentionVisibleState.QuestionPending,
                is AttentionVisibleState.ValidationError,
                -> QuestionContext(state, onIntent)
                AttentionVisibleState.OfflinePending -> OfflineRequestContext(state)
                AttentionVisibleState.Responding,
                AttentionVisibleState.Resolved,
                AttentionVisibleState.Rejected,
                AttentionVisibleState.Skipped,
                AttentionVisibleState.Expired,
                AttentionVisibleState.Cancelled,
                AttentionVisibleState.AlreadyAnswered,
                -> Unit
            }
            if (state.blockingReason == AttentionBlockingReason.TASK_STOPPING) {
                AttentionInlineMessage(
                    text = "The task is stopping. This request can no longer be answered.",
                    danger = true,
                    live = true,
                    testTag = "attention-stop-fence",
                )
            }
            state.notice?.let { notice ->
                AttentionInlineMessage(
                    text = notice.fixedCopy(),
                    danger = notice != AttentionTransientNotice.CONNECTION_RETRY_FAILED,
                    live = true,
                    testTag = "attention-notice-${notice.name.lowercase()}",
                )
            }
        },
        footer = {
            AttentionFooter(
                state = state,
                onIntent = onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        overlay = {
            if (dismissIntent != null) {
            IconButton(
                onClick = { onIntent(dismissIntent) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 18.dp)
                    .size(48.dp)
                    .attentionContractAction(
                        if (dismissIntent == AttentionIntent.Dismiss) {
                            "Dismiss"
                        } else {
                            "ReturnToTask"
                        },
                    )
                    .testTag("attention-close"),
            ) {
                Icon(
                    imageVector = MomodingIcons.Close,
                    contentDescription = if (dismissIntent == AttentionIntent.Dismiss) {
                        "Dismiss request"
                    } else {
                        "Return to task"
                    },
                )
            }
            }
        },
    )
}

@Composable
private fun AttentionHeader(
    state: AttentionUiState.Visible,
    copy: AttentionStateCopy,
    titleFocus: FocusRequester,
) {
    val role = state.state.visualRole()
    val visual = role.visual()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (state.state.hasPermissionPresence()) {
            MomodingMark(
                size = 44.dp,
                presence = MomodingPresence.PERMISSION,
                modifier = Modifier.testTag("attention-visual-${role.testTag}"),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(visual.container, RoundedCornerShape(13.dp))
                    .testTag("attention-visual-${role.testTag}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.foreground,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(titleFocus)
                .focusable()
                .semantics(mergeDescendants = true) { heading() }
                .testTag("attention-heading"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = copy.eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.3.sp,
                modifier = Modifier.testTag("attention-eyebrow"),
            )
            Text(
                text = copy.heading,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        copy.body?.let { body ->
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.state == AttentionVisibleState.Responding) {
            Text(
                text = "Response pending",
                style = MaterialTheme.typography.labelMedium,
                color = visual.foreground,
                modifier = Modifier.testTag("attention-responding-status"),
            )
        }
    }
}

@Composable
private fun ConfirmationContext(prompt: AttentionPrompt?) {
    val confirmation = prompt as? AttentionPrompt.Confirmation ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DecisionFacts(
            facts = listOf(
                DecisionFact("Scope", "This request only"),
                DecisionFact("Action", confirmation.summary),
                DecisionFact("Impact", confirmation.details ?: "Lets Momoding continue this step"),
                DecisionFact("Duration", "One time"),
            ),
        )
        Text(
            text = "This approval does not grant an Android capability.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("attention-capability-note"),
        )
    }
}

@Composable
private fun ContentReadContext(prompt: AttentionPrompt?) {
    val read = prompt as? AttentionPrompt.ContentRead ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp)
            .testTag("content-read-context"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DecisionFacts(
            facts = listOf(
                DecisionFact("Scope", "Android SAF · this request only"),
                DecisionFact("Action", "Read ${read.documents.size} verified file" + if (read.documents.size == 1) "" else "s"),
                DecisionFact("Impact", "Up to ${formatByteBudget(read.totalMaxBytes)} · files unchanged"),
                DecisionFact("Duration", "Ends with this request or earlier revocation"),
            ),
        )
        if (!read.filesVerified) {
            Text(
                text = "Android couldn't verify the exact files. Don't allow this request; retry after the folder is available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("content-read-unverified"),
            )
        }
        read.documents.forEach { document ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("content-read-file-${document.alias}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = document.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${document.expectedMimeType} · up to ${formatByteBudget(document.maxBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Total limit ${formatByteBudget(read.totalMaxBytes)}. Access ends when this request finishes, the task stops, the folder is revoked, or the approval expires.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("content-read-scope"),
        )
    }
}

@Composable
private fun QuestionContext(
    state: AttentionUiState.Visible,
    onIntent: (AttentionIntent) -> Unit,
) {
    val prompt = state.prompt as? AttentionPrompt.Question ?: return
    val draft = state.draft ?: emptyDraft()
    val validation = (state.state as? AttentionVisibleState.ValidationError)?.code

    if (prompt.options.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .semantics {
                    collectionInfo = CollectionInfo(
                        rowCount = prompt.options.size,
                        columnCount = 1,
                    )
                }
                .testTag("attention-option-group"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            prompt.options.forEachIndexed { index, option ->
                AttentionOptionRow(
                    index = index,
                    option = option,
                    selected = draft.selectedOptionIndex == index,
                    enabled = state.actions.canSelectOption,
                    onClick = { onIntent(AttentionIntent.SelectOption(index)) },
                )
            }
        }
    }
    AttentionCustomAnswer(
        draft = draft,
        enabled = state.actions.canEditCustom,
        validation = validation,
        onIntent = onIntent,
    )
}

@Composable
private fun OfflineRequestContext(state: AttentionUiState.Visible) {
    when (val prompt = state.prompt) {
        is AttentionPrompt.Confirmation -> {
            prompt.details?.let {
                AttentionReadOnlyContext(
                    title = prompt.summary,
                    body = it,
                )
            } ?: AttentionReadOnlyContext(title = prompt.summary)
        }
        is AttentionPrompt.Question -> {
            AttentionReadOnlyContext(
                title = prompt.question,
                body = "Your saved draft will be available when the Host reconnects.",
            )
        }
        is AttentionPrompt.ContentRead -> {
            AttentionReadOnlyContext(
                title = "Read ${prompt.documents.size} approved file" +
                    if (prompt.documents.size == 1) "" else "s",
                body = "Reconnect to review the exact files and byte limits.",
            )
        }
        is AttentionPrompt.FileChanges -> {
            AttentionReadOnlyContext(
                title = "Review proposed file changes",
                body = "Return to the task and open Review changes for the full impact.",
            )
        }
        null -> Unit
    }
}

@Composable
private fun AttentionReadOnlyContext(
    title: String,
    body: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("attention-read-only-context"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AttentionOptionRow(
    index: Int,
    option: AttentionOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMomodingStatusColors.current
    val shape = RoundedCornerShape(12.dp)
    val container = if (selected) colors.successContainer else MaterialTheme.colorScheme.surface
    val border = if (selected) colors.success else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(container, shape)
            .border(1.dp, border, shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                collectionItemInfo = CollectionItemInfo(
                    rowIndex = index,
                    rowSpan = 1,
                    columnIndex = 0,
                    columnSpan = 1,
                )
                if (enabled) contractAction = "SelectChoice"
            }
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag("attention-option-$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .border(1.dp, border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (option.recommended) {
                    Text(
                        text = "Recommended",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.success,
                        modifier = Modifier
                            .background(colors.successContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            option.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
internal fun AttentionCustomAnswer(
    draft: AttentionDraft,
    enabled: Boolean,
    validation: AttentionValidationCode?,
    onIntent: (AttentionIntent) -> Unit,
    compact: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var fieldFocused by remember { mutableStateOf(false) }
    val value = TextFieldValue(
        text = draft.customAnswer,
        selection = TextRange(
            start = draft.selectionStart.coerceIn(0, draft.customAnswer.length),
            end = draft.selectionEnd.coerceIn(0, draft.customAnswer.length),
        ),
    )
    val errorCopy = validation.errorCopy()

    LaunchedEffect(fieldFocused, errorCopy) {
        if (fieldFocused) bringIntoViewRequester.bringIntoView()
    }

    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            onIntent(
                AttentionIntent.EditCustom(
                    text = next.text,
                    selectionStart = next.selection.start,
                    selectionEnd = next.selection.end,
                ),
            )
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged {
                fieldFocused = it.isFocused
                if (it.isFocused) {
                    coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                }
            }
            .then(
                if (validation != null) {
                    Modifier.attentionContractAction("EditAnswer")
                } else {
                    Modifier.structuralAction("EditAnswer")
                },
            )
            .semantics {
                if (errorCopy != null) error(errorCopy)
            }
            .testTag("attention-custom-answer"),
        label = { Text(if (compact) "Something else" else "Write another answer") },
        supportingText = errorCopy?.let { copy ->
            {
                Text(
                    text = copy,
                    modifier = Modifier.testTag("attention-answer-error"),
                )
            }
        },
        isError = errorCopy != null,
        minLines = if (compact) 1 else 2,
        maxLines = if (compact) 3 else 5,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
    )
}

@Composable
private fun AttentionInlineMessage(
    text: String,
    danger: Boolean,
    live: Boolean,
    testTag: String,
) {
    val colors = LocalMomodingStatusColors.current
    val foreground = if (danger) MaterialTheme.colorScheme.error else colors.info
    val container = if (danger) MaterialTheme.colorScheme.errorContainer else colors.infoContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .border(1.dp, foreground, RoundedCornerShape(12.dp))
            .semantics {
                if (live) liveRegion = LiveRegionMode.Polite
            }
            .padding(11.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (danger) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AttentionFooter(
    state: AttentionUiState.Visible,
    onIntent: (AttentionIntent) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        when (state.state) {
            AttentionVisibleState.ConfirmPending -> AttentionActionPair(
                secondaryLabel = "Decline",
                secondaryEnabled = state.actions.canDecline,
                onSecondary = { onIntent(AttentionIntent.Decline) },
                secondaryTag = "attention-action-decline",
                secondaryContractAction = "Reject",
                primaryLabel = "Allow once",
                primaryEnabled = state.actions.canConfirm,
                onPrimary = { onIntent(AttentionIntent.Confirm) },
                primaryTag = "attention-action-confirm",
                primaryContractAction = "ConfirmOnce",
            )
            AttentionVisibleState.ContentReadPending -> AttentionActionPair(
                secondaryLabel = "Don't allow",
                secondaryEnabled = state.actions.canDenyContentRead,
                onSecondary = { onIntent(AttentionIntent.DenyContentRead) },
                secondaryTag = "attention-action-deny-content",
                secondaryContractAction = "DenyContentRead",
                primaryLabel = "Allow this read",
                primaryEnabled = state.actions.canAllowContentRead,
                onPrimary = { onIntent(AttentionIntent.AllowContentRead) },
                primaryTag = "attention-action-allow-content",
                primaryContractAction = "AllowContentRead",
            )
            AttentionVisibleState.FileChangesPending -> AttentionPrimaryButton(
                label = "Return to task",
                enabled = state.actions.canDismiss,
                onClick = { onIntent(AttentionIntent.Dismiss) },
                testTag = "attention-action-dismiss",
                contractAction = "Dismiss",
            )
            AttentionVisibleState.QuestionPending,
            is AttentionVisibleState.ValidationError,
            -> AttentionActionPair(
                secondaryLabel = "Skip",
                secondaryEnabled = state.actions.canSkip,
                onSecondary = { onIntent(AttentionIntent.Skip) },
                secondaryTag = "attention-action-skip",
                secondaryContractAction = "Skip",
                primaryLabel = "Send answer",
                primaryEnabled = state.actions.canSubmitAnswer,
                onPrimary = { onIntent(AttentionIntent.SubmitAnswer) },
                primaryTag = "attention-action-submit",
                primaryContractAction = "Answer",
            )
            AttentionVisibleState.OfflinePending -> AttentionActionPair(
                secondaryLabel = "Dismiss",
                secondaryEnabled = state.actions.canDismiss,
                onSecondary = { onIntent(AttentionIntent.Dismiss) },
                secondaryTag = "attention-action-dismiss",
                secondaryContractAction = "Dismiss",
                primaryLabel = "Retry connection",
                primaryEnabled = state.actions.canRetryConnection,
                onPrimary = { onIntent(AttentionIntent.RetryConnection) },
                primaryTag = "attention-action-retry",
                primaryContractAction = "RetryConnection",
            )
            AttentionVisibleState.Responding -> Unit
            AttentionVisibleState.Expired -> AttentionPrimaryButton(
                label = "Dismiss",
                enabled = state.actions.canDismiss,
                onClick = { onIntent(AttentionIntent.Dismiss) },
                testTag = "attention-action-dismiss",
                contractAction = "Dismiss",
            )
            AttentionVisibleState.Resolved,
            AttentionVisibleState.Rejected,
            AttentionVisibleState.Skipped,
            AttentionVisibleState.Cancelled,
            AttentionVisibleState.AlreadyAnswered,
            -> AttentionPrimaryButton(
                label = "Return to task",
                enabled = state.actions.canReturnToTask,
                onClick = { onIntent(AttentionIntent.ReturnToTask) },
                testTag = "attention-action-return",
                contractAction = "ReturnToTask",
            )
        }
        if (state.actions.canReturnToTask && state.state.isPending()) {
            AttentionSecondaryButton(
                label = "Return to task",
                enabled = true,
                onClick = { onIntent(AttentionIntent.ReturnToTask) },
                testTag = "attention-action-return",
                contractAction = "ReturnToTask",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AttentionActionPair(
    secondaryLabel: String,
    secondaryEnabled: Boolean,
    onSecondary: () -> Unit,
    secondaryTag: String,
    secondaryContractAction: String,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    primaryTag: String,
    primaryContractAction: String,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val buttonTextStyle = MaterialTheme.typography.labelLarge
    val secondaryRequired = with(density) {
        textMeasurer.measure(
            text = secondaryLabel,
            style = buttonTextStyle,
            maxLines = 1,
        ).size.width.toDp()
    } + 48.dp
    val primaryRequired = with(density) {
        textMeasurer.measure(
            text = primaryLabel,
            style = buttonTextStyle,
            maxLines = 1,
        ).size.width.toDp()
    } + 48.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableForButtons = (maxWidth - 9.dp).coerceAtLeast(0.dp)
        val secondaryAllocation = availableForButtons / 2.6f
        val primaryAllocation = availableForButtons - secondaryAllocation
        val horizontal = secondaryRequired <= secondaryAllocation &&
            primaryRequired <= primaryAllocation
        if (horizontal) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attention-action-layout-row"),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttentionSecondaryButton(
                    label = secondaryLabel,
                    enabled = secondaryEnabled,
                    onClick = onSecondary,
                    testTag = secondaryTag,
                    contractAction = secondaryContractAction,
                    modifier = Modifier.weight(1f),
                )
                AttentionPairPrimaryButton(
                    label = primaryLabel,
                    enabled = primaryEnabled,
                    onClick = onPrimary,
                    testTag = primaryTag,
                    contractAction = primaryContractAction,
                    modifier = Modifier.weight(1.6f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attention-action-layout-column"),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                AttentionSecondaryButton(
                    label = secondaryLabel,
                    enabled = secondaryEnabled,
                    onClick = onSecondary,
                    testTag = secondaryTag,
                    contractAction = secondaryContractAction,
                    modifier = Modifier.fillMaxWidth(),
                )
                AttentionPairPrimaryButton(
                    label = primaryLabel,
                    enabled = primaryEnabled,
                    onClick = onPrimary,
                    testTag = primaryTag,
                    contractAction = primaryContractAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AttentionSecondaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    contractAction: String,
    modifier: Modifier,
) {
    SecondaryAction(
        label = label,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(
                if (enabled) Modifier.attentionContractAction(contractAction) else Modifier,
            )
            .testTag(testTag),
    )
}

@Composable
private fun AttentionPairPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    contractAction: String,
    modifier: Modifier,
) {
    PrimaryAction(
        label = label,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(
                if (enabled) Modifier.attentionContractAction(contractAction) else Modifier,
            )
            .testTag(testTag),
    )
}

@Composable
private fun AttentionPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    contractAction: String,
) {
    PrimaryAction(
        label = label,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .then(
                if (enabled) Modifier.attentionContractAction(contractAction) else Modifier,
            )
            .testTag(testTag),
    )
}

private fun Modifier.attentionContractAction(action: String): Modifier =
    semantics { contractAction = action }

private fun formatByteBudget(bytes: Int): String = when {
    bytes >= 1_024 * 1_024 -> "${bytes / (1_024 * 1_024)} MB"
    bytes >= 1_024 -> "${bytes / 1_024} KB"
    else -> "$bytes B"
}

@Composable
private fun AttentionVisualRole.visual(): AttentionVisual {
    val status = LocalMomodingStatusColors.current
    return when (this) {
        AttentionVisualRole.CONFIRM_WARNING -> AttentionVisual(
            icon = Icons.Outlined.GppGood,
            foreground = status.warning,
            container = status.warningContainer,
        )
        AttentionVisualRole.CONTENT_READ_WARNING -> AttentionVisual(
            icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
            foreground = status.warning,
            container = status.warningContainer,
        )
        AttentionVisualRole.QUESTION_WARNING -> AttentionVisual(
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            foreground = status.warning,
            container = status.warningContainer,
        )
        AttentionVisualRole.VALIDATION_ERROR -> AttentionVisual(
            icon = Icons.Outlined.ErrorOutline,
            foreground = MaterialTheme.colorScheme.error,
            container = MaterialTheme.colorScheme.errorContainer,
        )
        AttentionVisualRole.OFFLINE_INFO -> AttentionVisual(
            icon = Icons.Outlined.CloudOff,
            foreground = status.info,
            container = status.infoContainer,
        )
        AttentionVisualRole.RESPONDING_INFO -> AttentionVisual(
            icon = Icons.Outlined.HourglassTop,
            foreground = status.info,
            container = status.infoContainer,
        )
        AttentionVisualRole.RESOLVED_SUCCESS -> AttentionVisual(
            icon = Icons.Outlined.CheckCircle,
            foreground = status.success,
            container = status.successContainer,
        )
        AttentionVisualRole.REJECTED_DANGER -> AttentionVisual(
            icon = Icons.Outlined.Block,
            foreground = status.danger,
            container = status.dangerContainer,
        )
        AttentionVisualRole.SKIPPED_NEUTRAL -> AttentionVisual(
            icon = Icons.Outlined.SkipNext,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        AttentionVisualRole.EXPIRED_NEUTRAL -> AttentionVisual(
            icon = Icons.Outlined.Schedule,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        AttentionVisualRole.CANCELLED_NEUTRAL -> AttentionVisual(
            icon = Icons.Outlined.Cancel,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        AttentionVisualRole.ALREADY_ANSWERED_INFO -> AttentionVisual(
            icon = Icons.Outlined.DoneAll,
            foreground = status.info,
            container = status.infoContainer,
        )
    }
}

private data class AttentionVisual(
    val icon: ImageVector,
    val foreground: Color,
    val container: Color,
)

private fun AttentionUiState.Visible.closeIntent(): AttentionIntent? = when {
    actions.canDismiss -> AttentionIntent.Dismiss
    actions.canReturnToTask && !state.isPending() -> AttentionIntent.ReturnToTask
    else -> null
}

private fun AttentionVisibleState.isPending(): Boolean =
    this == AttentionVisibleState.ConfirmPending ||
        this == AttentionVisibleState.ContentReadPending ||
        this == AttentionVisibleState.FileChangesPending ||
        this == AttentionVisibleState.QuestionPending ||
        this == AttentionVisibleState.OfflinePending ||
        this is AttentionVisibleState.ValidationError

private fun AttentionVisibleState.hasPermissionPresence(): Boolean =
    this == AttentionVisibleState.ConfirmPending ||
        this == AttentionVisibleState.ContentReadPending ||
        this == AttentionVisibleState.FileChangesPending ||
        this == AttentionVisibleState.QuestionPending ||
        this is AttentionVisibleState.ValidationError

private fun AttentionValidationCode?.errorCopy(): String? = when (this) {
    AttentionValidationCode.ANSWER_REQUIRED -> "Enter an answer."
    AttentionValidationCode.ANSWER_TOO_LONG ->
        "Answer is too long. Use 4,096 characters or fewer."
    null -> null
}

private fun emptyDraft(): AttentionDraft = AttentionDraft(
    selectedOptionIndex = null,
    customAnswer = "",
    selectionStart = 0,
    selectionEnd = 0,
    validationCode = null,
)
