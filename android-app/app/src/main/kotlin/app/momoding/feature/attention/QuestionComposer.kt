package app.momoding.feature.attention

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.momoding.core.data.AttentionConfirmationPresentation
import app.momoding.core.data.AttentionPrompt
import app.momoding.feature.settings.contractAction
import app.momoding.ui.components.ComposerDock
import app.momoding.ui.components.MomodingMark
import app.momoding.ui.components.MomodingPresence
import app.momoding.ui.components.momodingPrimaryButtonColors

/** The bottom composer becomes the answer surface while the active turn is waiting for the user. */
@Composable
internal fun QuestionComposerDock(
    state: AttentionUiState,
    onIntent: (AttentionIntent) -> Unit,
    modifier: Modifier = Modifier,
    latestUserText: String? = null,
) {
    val visible = state as? AttentionUiState.Visible
    val question = visible?.prompt as? AttentionPrompt.Question
    val copy = attentionComposerCopy(
        attentionInteractionLanguage(
            latestUserText = latestUserText,
            promptFallback = question?.question,
            promptLanguageHint = visible?.promptLanguageHint,
        ),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
            .padding(start = 13.dp, top = 8.dp, end = 13.dp, bottom = 9.dp)
            .testTag("question-composer"),
    ) {
        when (state) {
            is AttentionUiState.Visible -> QuestionComposerVisible(state, onIntent, copy)
            is AttentionUiState.Loading -> QuestionComposerStatus(
                title = copy.loadingQuestion,
                detail = copy.waitingForAnswer,
                footer = copy.waitingForMomoding,
                busy = true,
            )
            is AttentionUiState.Unavailable,
            is AttentionUiState.Corrupt,
            is AttentionUiState.FailedClosedHidden,
            -> QuestionComposerStatus(
                title = copy.questionUnavailable,
                detail = copy.restoreLatestState,
                footer = copy.waitingForMomoding,
            )
        }
    }
}

@Composable
private fun QuestionComposerVisible(
    state: AttentionUiState.Visible,
    onIntent: (AttentionIntent) -> Unit,
    copy: AttentionComposerCopy,
) {
    val prompt = state.prompt as? AttentionPrompt.Question
    val draft = state.draft
    if (prompt == null || draft == null) {
        val responding = state.state == AttentionVisibleState.Responding
        QuestionComposerStatus(
            title = if (responding) copy.sendingAnswer else copy.answerRecorded,
            detail = if (responding) {
                copy.responseSaved
            } else {
                copy.continuingTask
            },
            footer = copy.waitingForMomoding,
            busy = responding,
        )
        return
    }

    val validation = (state.state as? AttentionVisibleState.ValidationError)?.code
        ?: draft.validationCode
    val hasAnswer = draft.selectedOptionIndex != null || draft.customAnswer.isNotBlank()
    val offline = state.state == AttentionVisibleState.OfflinePending

    ComposerDock(
        meta = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MomodingMark(size = 22.dp, presence = MomodingPresence.READY)
                Text(
                    text = if (offline) copy.waitingForConnection else copy.needsAnswer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        editor = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 330.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = prompt.question,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() }
                        .testTag("question-composer-heading"),
                )
                if (prompt.options.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                            .semantics {
                                collectionInfo = CollectionInfo(prompt.options.size, 1)
                            },
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        prompt.options.forEachIndexed { index, option ->
                            AttentionOptionRow(
                                index = index,
                                option = option,
                                selected = draft.selectedOptionIndex == index,
                                enabled = state.actions.canSelectOption,
                                onClick = { onIntent(AttentionIntent.SelectOption(index)) },
                                recommendedLabel = copy.recommended,
                            )
                        }
                    }
                }
                AttentionCustomAnswer(
                    draft = draft,
                    enabled = state.actions.canEditCustom,
                    validation = validation,
                    onIntent = onIntent,
                    compact = true,
                    label = copy.customAnswer,
                )
                if (offline) {
                    Text(
                        text = copy.reconnectToSend,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        footer = {
            if (offline) {
                TextButton(
                    onClick = { onIntent(AttentionIntent.Dismiss) },
                    enabled = state.actions.canDismiss,
                    modifier = Modifier.testTag("question-action-dismiss"),
                ) {
                    Text(copy.notNow)
                }
                Button(
                    onClick = { onIntent(AttentionIntent.RetryConnection) },
                    enabled = state.actions.canRetryConnection,
                    colors = momodingPrimaryButtonColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("question-action-retry"),
                ) {
                    Text(copy.retry)
                }
            } else {
                TextButton(
                    onClick = { onIntent(AttentionIntent.Skip) },
                    enabled = state.actions.canSkip,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("question-action-skip")
                        .semantics { if (state.actions.canSkip) contractAction = "Skip" },
                ) {
                    Text(copy.skip)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onIntent(AttentionIntent.SubmitAnswer) },
                    enabled = state.actions.canSubmitAnswer && hasAnswer && validation == null,
                    colors = momodingPrimaryButtonColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("question-action-submit")
                        .semantics {
                            if (state.actions.canSubmitAnswer && hasAnswer && validation == null) {
                                contractAction = "Answer"
                            }
                        },
                ) {
                    Text(copy.sendAnswer)
                }
            }
        },
    )
}

@Composable
private fun QuestionComposerStatus(
    title: String,
    detail: String,
    footer: String,
    busy: Boolean = false,
) {
    ComposerDock(
        modifier = Modifier.semantics { if (busy) liveRegion = LiveRegionMode.Polite },
        editor = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        footer = {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** A normal one-shot confirmation uses the same task composer and authoritative Attention intents. */
@Composable
internal fun ConfirmationComposerDock(
    state: AttentionUiState,
    onIntent: (AttentionIntent) -> Unit,
    modifier: Modifier = Modifier,
    latestUserText: String? = null,
) {
    val visible = state as? AttentionUiState.Visible
    val confirmation = visible?.prompt as? AttentionPrompt.Confirmation
    val language = attentionInteractionLanguage(
        latestUserText = latestUserText,
        promptFallback = confirmation?.summary,
        promptLanguageHint = visible?.promptLanguageHint,
    )
    val copy = attentionComposerCopy(language)
    val presentedConfirmation = confirmation?.let {
        presentedConfirmationPrompt(
            prompt = it,
            presentation = visible.confirmationPresentation,
            language = language,
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
            .padding(start = 13.dp, top = 8.dp, end = 13.dp, bottom = 9.dp)
            .testTag("confirmation-composer"),
    ) {
        when (state) {
            is AttentionUiState.Visible -> ConfirmationComposerVisible(
                state = state,
                onIntent = onIntent,
                copy = copy,
                presentedPrompt = presentedConfirmation,
            )
            is AttentionUiState.Loading -> QuestionComposerStatus(
                title = copy.loadingApproval,
                detail = copy.waitingForDecision,
                footer = copy.waitingForMomoding,
                busy = true,
            )
            is AttentionUiState.Unavailable,
            is AttentionUiState.Corrupt,
            is AttentionUiState.FailedClosedHidden,
            -> QuestionComposerStatus(
                title = copy.approvalUnavailable,
                detail = copy.restoreLatestState,
                footer = copy.waitingForMomoding,
            )
        }
    }
}

@Composable
private fun ConfirmationComposerVisible(
    state: AttentionUiState.Visible,
    onIntent: (AttentionIntent) -> Unit,
    copy: AttentionComposerCopy,
    presentedPrompt: AttentionPrompt.Confirmation?,
) {
    val prompt = presentedPrompt
    if (prompt == null) {
        val responding = state.state == AttentionVisibleState.Responding
        QuestionComposerStatus(
            title = if (responding) copy.sendingDecision else copy.decisionRecorded,
            detail = if (responding) copy.decisionSaved else copy.continuingTask,
            footer = copy.waitingForMomoding,
            busy = responding,
        )
        return
    }
    val offline = state.state == AttentionVisibleState.OfflinePending
    ComposerDock(
        meta = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MomodingMark(size = 22.dp, presence = MomodingPresence.READY)
                Text(
                    text = if (offline) copy.waitingForConnection else copy.needsApproval,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        editor = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = prompt.summary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() }
                        .testTag("confirmation-composer-heading"),
                )
                prompt.details?.takeIf(String::isNotBlank)?.let { details ->
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("confirmation-composer-details"),
                    )
                }
                Text(
                    text = copy.approvalNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("confirmation-capability-note"),
                )
                if (offline) {
                    Text(
                        text = copy.reconnectToSend,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        footer = {
            if (offline) {
                TextButton(
                    onClick = { onIntent(AttentionIntent.Dismiss) },
                    enabled = state.actions.canDismiss,
                    modifier = Modifier.testTag("confirmation-action-dismiss"),
                ) {
                    Text(copy.notNow)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onIntent(AttentionIntent.RetryConnection) },
                    enabled = state.actions.canRetryConnection,
                    colors = momodingPrimaryButtonColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 44.dp).testTag("confirmation-action-retry"),
                ) {
                    Text(copy.retry)
                }
            } else {
                TextButton(
                    onClick = { onIntent(AttentionIntent.Decline) },
                    enabled = state.actions.canDecline,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("confirmation-action-decline")
                        .semantics { if (state.actions.canDecline) contractAction = "Reject" },
                ) {
                    Text(copy.decline)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onIntent(AttentionIntent.Confirm) },
                    enabled = state.actions.canConfirm,
                    colors = momodingPrimaryButtonColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("confirmation-action-confirm")
                        .semantics { if (state.actions.canConfirm) contractAction = "ConfirmOnce" },
                ) {
                    Text(copy.allowOnce)
                }
            }
        },
    )
}

internal fun presentedConfirmationPrompt(
    prompt: AttentionPrompt.Confirmation,
    presentation: AttentionConfirmationPresentation?,
    language: AttentionInteractionLanguage,
): AttentionPrompt.Confirmation {
    if (language != AttentionInteractionLanguage.ZH_CN) return prompt
    return when (presentation) {
        AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_CALENDARS ->
            AttentionPrompt.Confirmation(
                summary = "列出你的日历？",
                details = "本次工具调用最多返回 20 个日历的名称和访问状态。",
            )
        AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_EVENTS ->
            AttentionPrompt.Confirmation(
                summary = "读取所请求的日历事件？",
                details = "本次工具调用最多返回 10 条事件摘要。",
            )
        AttentionConfirmationPresentation.ANDROID_CALENDAR_CREATE_EVENT ->
            localizedCalendarCreatePrompt(prompt)
        AttentionConfirmationPresentation.ANDROID_CLIPBOARD_GET ->
            localizedClipboardGetPrompt(prompt)
        AttentionConfirmationPresentation.ANDROID_CLIPBOARD_SET ->
            localizedClipboardSetPrompt(prompt)
        AttentionConfirmationPresentation.ANDROID_CLIPBOARD_CLEAR ->
            localizedClipboardClearPrompt(prompt)
        null -> prompt
    }
}

private fun localizedCalendarCreatePrompt(
    prompt: AttentionPrompt.Confirmation,
): AttentionPrompt.Confirmation {
    val title = CALENDAR_CREATE_SUMMARY.matchEntire(prompt.summary)?.groupValues?.get(1)
        ?: return prompt
    val sourceDetails = prompt.details ?: return prompt
    val details = CALENDAR_CREATE_DETAILS.matchEntire(sourceDetails)?.groupValues
        ?: return prompt
    val schedule = when (details[1]) {
        "all-day" -> "全天"
        "timed" -> "定时"
        else -> return prompt
    }
    val calendar = details[2]
    return AttentionPrompt.Confirmation(
        summary = "创建“$title”？",
        details = "将在“$calendar”日历中创建一条${schedule}日程，并在执行后验证结果。",
    )
}

private fun localizedClipboardSetPrompt(
    prompt: AttentionPrompt.Confirmation,
): AttentionPrompt.Confirmation {
    val sourceDetails = prompt.details ?: return prompt
    val characterCount = CLIPBOARD_SET_DETAILS.matchEntire(sourceDetails)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it in 1..4_096 }
        ?: return prompt
    return AttentionPrompt.Confirmation(
        summary = "复制文字到 Android 剪贴板？",
        details = "将把 $characterCount 个字符写入 Android 剪贴板；审批记录不保存文字正文，写入后会验证当前剪贴板。",
    )
}

private fun localizedClipboardGetPrompt(
    prompt: AttentionPrompt.Confirmation,
): AttentionPrompt.Confirmation {
    if (
        prompt.summary != CLIPBOARD_GET_SUMMARY ||
        prompt.details != CLIPBOARD_GET_DETAILS
    ) return prompt
    return AttentionPrompt.Confirmation(
        summary = "读取 Android 剪贴板文字？",
        details = "只把普通文字返回给本次工具调用；敏感内容会被隐藏。",
    )
}

private fun localizedClipboardClearPrompt(
    prompt: AttentionPrompt.Confirmation,
): AttentionPrompt.Confirmation {
    if (
        prompt.summary != CLIPBOARD_CLEAR_SUMMARY ||
        prompt.details != CLIPBOARD_CLEAR_DETAILS
    ) return prompt
    return AttentionPrompt.Confirmation(
        summary = "清空 Android 剪贴板？",
        details = "将清空当前剪贴板，并验证它已为空。",
    )
}

private val CALENDAR_CREATE_SUMMARY = Regex(
    pattern = "\\ACreate “(.{1,80})”\\?\\z",
    option = RegexOption.DOT_MATCHES_ALL,
)
private val CALENDAR_CREATE_DETAILS = Regex(
    pattern = "\\ACreate one (all-day|timed) event in (.{1,480})\\.\\z",
    option = RegexOption.DOT_MATCHES_ALL,
)
private val CLIPBOARD_SET_DETAILS = Regex(
    pattern = "\\AWrites ([0-9]{1,4}) characters and verifies the current clipboard without storing the text in approval history\\.\\z",
)
private const val CLIPBOARD_GET_SUMMARY = "Allow Momoding to read clipboard text?"
private const val CLIPBOARD_GET_DETAILS =
    "Returns ordinary plain text to this Tool call only. Sensitive content is withheld."
private const val CLIPBOARD_CLEAR_SUMMARY = "Clear the Android clipboard?"
private const val CLIPBOARD_CLEAR_DETAILS = "Clears the current clipboard and verifies it is empty."

private data class AttentionComposerCopy(
    val loadingQuestion: String,
    val waitingForAnswer: String,
    val questionUnavailable: String,
    val restoreLatestState: String,
    val sendingAnswer: String,
    val answerRecorded: String,
    val responseSaved: String,
    val continuingTask: String,
    val waitingForConnection: String,
    val needsAnswer: String,
    val recommended: String,
    val customAnswer: String,
    val reconnectToSend: String,
    val notNow: String,
    val retry: String,
    val skip: String,
    val sendAnswer: String,
    val waitingForMomoding: String,
    val loadingApproval: String,
    val waitingForDecision: String,
    val approvalUnavailable: String,
    val sendingDecision: String,
    val decisionRecorded: String,
    val decisionSaved: String,
    val needsApproval: String,
    val approvalNote: String,
    val decline: String,
    val allowOnce: String,
)

private fun attentionComposerCopy(language: AttentionInteractionLanguage): AttentionComposerCopy =
    when (language) {
        AttentionInteractionLanguage.ZH_CN -> AttentionComposerCopy(
            loadingQuestion = "正在加载问题…",
            waitingForAnswer = "Momoding 正在等待你的回答。",
            questionUnavailable = "这个问题已不可用",
            restoreLatestState = "恢复最新状态后，任务会自动更新。",
            sendingAnswer = "正在提交回答…",
            answerRecorded = "已记录回答",
            responseSaved = "回答已安全保存在这台手机上。",
            continuingTask = "Momoding 正在继续原任务。",
            waitingForConnection = "等待网络连接",
            needsAnswer = "Momoding 需要你的回答",
            recommended = "推荐",
            customAnswer = "其他回答",
            reconnectToSend = "重新连接后即可继续。",
            notNow = "暂不处理",
            retry = "重试",
            skip = "跳过",
            sendAnswer = "提交回答",
            waitingForMomoding = "等待 Momoding",
            loadingApproval = "正在加载批准请求…",
            waitingForDecision = "Momoding 正在等待你的决定。",
            approvalUnavailable = "这个批准请求已不可用",
            sendingDecision = "正在提交决定…",
            decisionRecorded = "已记录决定",
            decisionSaved = "决定已安全保存在这台手机上。",
            needsApproval = "Momoding 需要你的批准",
            approvalNote = "这只批准当前操作；Android 系统权限需要单独授予。",
            decline = "拒绝",
            allowOnce = "仅允许本次",
        )
        AttentionInteractionLanguage.ENGLISH -> AttentionComposerCopy(
            loadingQuestion = "Loading question…",
            waitingForAnswer = "Momoding is waiting for your answer.",
            questionUnavailable = "This question is no longer available",
            restoreLatestState = "The task will update when its latest state is restored.",
            sendingAnswer = "Sending your answer…",
            answerRecorded = "Answer recorded",
            responseSaved = "Your response is saved on this phone.",
            continuingTask = "Momoding is continuing the original task.",
            waitingForConnection = "Waiting for connection",
            needsAnswer = "Momoding needs your answer",
            recommended = "Recommended",
            customAnswer = "Something else",
            reconnectToSend = "Reconnect to continue.",
            notNow = "Not now",
            retry = "Retry",
            skip = "Skip",
            sendAnswer = "Send answer",
            waitingForMomoding = "Waiting for Momoding",
            loadingApproval = "Loading approval request…",
            waitingForDecision = "Momoding is waiting for your decision.",
            approvalUnavailable = "This approval request is no longer available",
            sendingDecision = "Sending your decision…",
            decisionRecorded = "Decision recorded",
            decisionSaved = "Your decision is saved on this phone.",
            needsApproval = "Momoding needs your approval",
            approvalNote = "This approves only the current action; Android system permissions are separate.",
            decline = "Decline",
            allowOnce = "Allow once",
        )
    }
