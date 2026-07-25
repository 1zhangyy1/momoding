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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
            .padding(start = 13.dp, top = 8.dp, end = 13.dp, bottom = 9.dp)
            .testTag("question-composer"),
    ) {
        when (state) {
            is AttentionUiState.Visible -> QuestionComposerVisible(state, onIntent)
            is AttentionUiState.Loading -> QuestionComposerStatus(
                title = "Loading question…",
                detail = "Momoding is waiting for your answer.",
                busy = true,
            )
            is AttentionUiState.Unavailable,
            is AttentionUiState.Corrupt,
            is AttentionUiState.FailedClosedHidden,
            -> QuestionComposerStatus(
                title = "This question is no longer available",
                detail = "The task will update when its latest state is restored.",
            )
        }
    }
}

@Composable
private fun QuestionComposerVisible(
    state: AttentionUiState.Visible,
    onIntent: (AttentionIntent) -> Unit,
) {
    val prompt = state.prompt as? AttentionPrompt.Question
    val draft = state.draft
    if (prompt == null || draft == null) {
        val responding = state.state == AttentionVisibleState.Responding
        QuestionComposerStatus(
            title = if (responding) "Sending your answer…" else "Answer recorded",
            detail = if (responding) {
                "Your response is saved on this phone."
            } else {
                "Momoding is continuing the task."
            },
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
                    text = if (offline) "Waiting for connection" else "Momoding needs your answer",
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
                )
                if (offline) {
                    Text(
                        text = "Reconnect to send your answer.",
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
                    Text("Not now")
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
                    Text("Retry")
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
                    Text("Skip")
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
                    Text("Send answer")
                }
            }
        },
    )
}

@Composable
private fun QuestionComposerStatus(
    title: String,
    detail: String,
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
                text = "Waiting for Momoding",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
