package app.momoding.feature.localtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.momoding.ui.components.MomodingTopBar
import app.momoding.ui.components.MomodingHeadMark

@Composable
fun PhoneLocalTaskScreen(
    state: PhoneLocalTaskUiState,
    modelId: String,
    onAction: (PhoneLocalTaskAction) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MomodingTopBar(
                title = if (state.phase == PhoneLocalTaskPhase.COMPOSING) "New task" else "Task",
                subtitle = "On-device Pi · $modelId",
                onBack = { onAction(PhoneLocalTaskAction.Back) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (state.phase == PhoneLocalTaskPhase.COMPOSING) {
                Composer(state, onAction)
            } else {
                TaskConversation(state, onAction)
            }
        }
    }
}

@Composable
private fun Composer(
    state: PhoneLocalTaskUiState,
    onAction: (PhoneLocalTaskAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MomodingHeadMark(size = 54.dp)
                    Text(
                        "What should Momoding do?",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 14.dp).semantics { heading() },
                    )
                    Text(
                        "Pi runs on this phone. The model request goes directly from Android to your configured Provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    )
                }
            }
            item {
                Suggestion(
                    "Explain how this Android project is structured",
                    onAction,
                )
            }
            item {
                Suggestion(
                    "Draft a clear implementation plan for my next feature",
                    onAction,
                )
            }
            item {
                Suggestion(
                    "Review code or text that I paste here",
                    onAction,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(12.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = { onAction(PhoneLocalTaskAction.EditDraft(it)) },
                placeholder = { Text("Describe a task for Momoding") },
                minLines = 3,
                maxLines = 8,
                trailingIcon = {
                    IconButton(
                        onClick = { onAction(PhoneLocalTaskAction.Send) },
                        enabled = state.canSend,
                        modifier = Modifier.testTag("phone-local-send"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "Send task",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("phone-local-prompt"),
            )
        }
    }
}

@Composable
private fun Suggestion(
    value: String,
    onAction: (PhoneLocalTaskAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable { onAction(PhoneLocalTaskAction.UseSuggestion(value)) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TaskConversation(
    state: PhoneLocalTaskUiState,
    onAction: (PhoneLocalTaskAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            MessageCard(
                label = "You",
                text = state.submittedPrompt.orEmpty(),
                container = MaterialTheme.colorScheme.primaryContainer,
            )
        }
        item {
            MessageCard(
                label = "Momoding",
                text = state.answer.ifEmpty {
                    when (state.phase) {
                        PhoneLocalTaskPhase.RUNNING -> "Thinking…"
                        PhoneLocalTaskPhase.STOPPED -> "Stopped."
                        PhoneLocalTaskPhase.FAILED -> state.error ?: "Task failed."
                        else -> ""
                    }
                },
                container = MaterialTheme.colorScheme.surfaceContainerLow,
                loading = state.phase == PhoneLocalTaskPhase.RUNNING,
            )
        }
        state.error?.let { error ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            when (state.phase) {
                PhoneLocalTaskPhase.RUNNING -> OutlinedButton(
                    onClick = { onAction(PhoneLocalTaskAction.Stop) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("phone-local-stop"),
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Stop")
                }
                PhoneLocalTaskPhase.COMPLETED,
                PhoneLocalTaskPhase.FAILED,
                PhoneLocalTaskPhase.STOPPED,
                -> Button(
                    onClick = { onAction(PhoneLocalTaskAction.StartAnother) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Start another task")
                }
                PhoneLocalTaskPhase.COMPOSING -> Unit
            }
        }
    }
}

@Composable
private fun MessageCard(
    label: String,
    text: String,
    container: androidx.compose.ui.graphics.Color,
    loading: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
