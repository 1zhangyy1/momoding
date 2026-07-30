package app.momoding.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.momoding.ui.components.ListGroup
import app.momoding.ui.components.ListRow
import app.momoding.ui.components.MomodingMark
import app.momoding.ui.components.PrimaryAction
import app.momoding.ui.components.ProductIconTone
import app.momoding.ui.components.ProductTopBar
import app.momoding.ui.components.StatusLine
import app.momoding.ui.components.StatusLineTone
import app.momoding.ui.icons.MomodingIcons
import app.momoding.ui.theme.LocalMomodingStatusColors
import app.momoding.ui.theme.LocalMomodingBrandColors

@Composable
fun TaskHomeScreen(
    state: TaskHomeUiState,
    contentPadding: PaddingValues,
    onAction: (TaskHomeAction) -> Unit,
    interactionPolicy: TaskHomeInteractionPolicy = TaskHomeInteractionPolicy.All,
    onBack: (() -> Unit)? = null,
) {
    val brand = LocalMomodingBrandColors.current
    state.managementDialog?.let { dialog ->
        TaskManagementDialog(dialog, onAction)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ProductTopBar(
            title = "All tasks",
            onBack = onBack,
            actions = {
                if (interactionPolicy.allows(TaskHomeInteraction.SEARCH)) {
                    IconButton(
                        onClick = { onAction(TaskHomeAction.Search) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("action-Search")
                            .taskHomeContractAction(interactionPolicy, TaskHomeInteraction.SEARCH),
                    ) { Icon(MomodingIcons.Search, contentDescription = "Search tasks") }
                }
                if (state.taskCount > 0 && interactionPolicy.allows(TaskHomeInteraction.NEW_TASK)) {
                    IconButton(
                        onClick = { onAction(TaskHomeAction.NewTask) },
                        enabled = state.connection == TaskHomeConnectionState.CONNECTED,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("action-NewTask")
                            .taskHomeContractAction(interactionPolicy, TaskHomeInteraction.NEW_TASK),
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).background(
                                if (state.connection == TaskHomeConnectionState.CONNECTED) {
                                    brand.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                RoundedCornerShape(10.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MomodingIcons.Add,
                                contentDescription = "New task",
                                tint = if (state.connection == TaskHomeConnectionState.CONNECTED) {
                                    brand.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("task-home-list"),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = 12.dp,
                end = 14.dp,
                bottom = contentPadding.calculateBottomPadding() + 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.connection) {
                TaskHomeConnectionState.CONNECTED -> Unit
                TaskHomeConnectionState.OFFLINE,
                TaskHomeConnectionState.RECONNECTING,
                -> item { OfflineBanner(state, onAction, interactionPolicy) }
                else -> item { ConnectionPill(state.hostAlias, state.connection) }
            }

            if (state.managementEnabled && state.taskCount > 0) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { onAction(TaskHomeAction.ToggleArchived) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("action-ToggleArchived")
                                .taskHomeContractAction(
                                    interactionPolicy,
                                    TaskHomeInteraction.TOGGLE_ARCHIVED,
                                ),
                        ) {
                            Icon(
                                if (state.archivedVisible) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(if (state.archivedVisible) "Show active" else "Show archived")
                        }
                    }
                }
            }

            if (state.searchVisible) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { onAction(TaskHomeAction.EditSearch(it)) },
                        enabled = interactionPolicy.allows(TaskHomeInteraction.EDIT_SEARCH),
                        singleLine = true,
                        label = { Text("Search tasks") },
                        leadingIcon = { Icon(MomodingIcons.Search, contentDescription = null) },
                        trailingIcon = if (state.query.isNotEmpty() && interactionPolicy.allows(TaskHomeInteraction.CLEAR_SEARCH)) {
                            {
                                IconButton(
                                    onClick = { onAction(TaskHomeAction.ClearSearch) },
                                    modifier = Modifier
                                        .testTag("action-ClearSearch")
                                        .taskHomeContractAction(interactionPolicy, TaskHomeInteraction.CLEAR_SEARCH),
                                ) {
                                    Icon(MomodingIcons.Close, contentDescription = "Clear search")
                                }
                            }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("action-EditSearch")
                            .taskHomeContractAction(interactionPolicy, TaskHomeInteraction.EDIT_SEARCH),
                    )
                }
            }

            state.notice?.let { notice ->
                item { Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            when {
                state.loadState == TaskHomeLoadState.LOADING -> item { LoadingTasks() }
                state.loadState == TaskHomeLoadState.FAILED && state.taskCount == 0 -> item { FailedTasks() }
                state.taskCount == 0 && state.query.isNotEmpty() -> item { SearchEmpty(state.query, onAction, interactionPolicy) }
                state.taskCount == 0 -> item {
                    EmptyTasks(
                        connection = state.connection,
                        showArchiveToggle = state.managementEnabled,
                        archivedVisible = state.archivedVisible,
                        onAction = onAction,
                        policy = interactionPolicy,
                    )
                }
                else -> items(state.sections, key = { it.title }) { section ->
                    TaskSection(
                        section,
                        onAction,
                        interactionPolicy,
                        state.managementEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(hostAlias: String, connection: TaskHomeConnectionState) {
    val connected = connection == TaskHomeConnectionState.CONNECTED
    val displayAlias = if (hostAlias.startsWith("On-device") && '/' in hostAlias) {
        "On-device · ${hostAlias.substringAfterLast('/')}"
    } else {
        hostAlias
    }
    val title = when (connection) {
        TaskHomeConnectionState.CONNECTED -> if (hostAlias.startsWith("On-device")) displayAlias else "$displayAlias · Connected"
        TaskHomeConnectionState.CONNECTING -> "$displayAlias · Connecting"
        TaskHomeConnectionState.RECONNECTING -> "$displayAlias · Reconnecting"
        TaskHomeConnectionState.OFFLINE -> "$displayAlias · Offline"
        TaskHomeConnectionState.UNPAIRED -> "No Host paired"
        TaskHomeConnectionState.ERROR -> "$displayAlias · Connection error"
    }
    val detail = when (connection) {
        TaskHomeConnectionState.CONNECTED -> "Ready for a new task"
        TaskHomeConnectionState.CONNECTING -> "Opening a secure connection"
        TaskHomeConnectionState.RECONNECTING -> "Cached tasks remain available"
        TaskHomeConnectionState.OFFLINE -> "Working from local history"
        TaskHomeConnectionState.UNPAIRED -> "Connect a Host to create tasks"
        TaskHomeConnectionState.ERROR -> "Check the connection and try again"
    }
    StatusLine(
        title = title,
        detail = detail,
        tone = if (connected) StatusLineTone.SUCCESS else StatusLineTone.WARNING,
        modifier = Modifier.semantics { stateDescription = title },
    )
}

@Composable
private fun OfflineBanner(
    state: TaskHomeUiState,
    onAction: (TaskHomeAction) -> Unit,
    policy: TaskHomeInteractionPolicy,
) {
    val reconnecting = state.connection == TaskHomeConnectionState.RECONNECTING
    StatusLine(
        title = if (reconnecting) "Reconnecting to the Host" else "Working from local history",
        detail = if (reconnecting) {
            "Cached tasks remain available while the secure connection is restored."
        } else {
            "Running tasks continue on the Host. New requests wait until it reconnects."
        },
        icon = if (reconnecting) MomodingIcons.Retry else Icons.Outlined.CloudOff,
        tone = StatusLineTone.WARNING,
        actionLabel = if (!reconnecting && policy.allows(TaskHomeInteraction.RETRY_CONNECTION)) "Retry" else null,
        onAction = if (!reconnecting && policy.allows(TaskHomeInteraction.RETRY_CONNECTION)) {
            { onAction(TaskHomeAction.RetryConnection) }
        } else null,
        actionModifier = Modifier
            .testTag("action-RetryConnection")
            .taskHomeContractAction(policy, TaskHomeInteraction.RETRY_CONNECTION),
    )
}

@Composable
private fun TaskSection(
    section: TaskSectionUiModel,
    onAction: (TaskHomeAction) -> Unit,
    policy: TaskHomeInteractionPolicy,
    managementEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp).semantics { heading() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (section.showCount) {
                Text(
                    section.rows.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        ListGroup {
            section.rows.forEachIndexed { index, row ->
                TaskRow(
                    row = row,
                    onAction = onAction,
                    policy = policy,
                    managementEnabled = managementEnabled,
                    showDivider = index < section.rows.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    row: TaskRowUiModel,
    onAction: (TaskHomeAction) -> Unit,
    policy: TaskHomeInteractionPolicy,
    managementEnabled: Boolean,
    showDivider: Boolean,
) {
    val interaction = when (row.openAction) {
        TaskRowOpenAction.OPEN_TASK -> TaskHomeInteraction.OPEN_TASK
        TaskRowOpenAction.OPEN_CACHED_TASK -> TaskHomeInteraction.OPEN_CACHED_TASK
        TaskRowOpenAction.OPEN_ATTENTION -> TaskHomeInteraction.OPEN_ATTENTION
    }
    val canOpen = policy.allows(interaction)
    val status = taskStatusVisual(row.status)
    val accessibleState = buildString {
        append(status.label)
        row.attentionKind?.let { append(", ").append(it) }
        if (row.unread) append(", unread")
        append(", ").append(row.ageLabel)
    }
    val open = if (canOpen) {
        {
            when (row.openAction) {
                TaskRowOpenAction.OPEN_TASK -> onAction(TaskHomeAction.OpenTask(row.taskId))
                TaskRowOpenAction.OPEN_CACHED_TASK -> onAction(TaskHomeAction.OpenCachedTask(row.taskId))
                TaskRowOpenAction.OPEN_ATTENTION -> onAction(TaskHomeAction.OpenAttention(row.taskId, row.attentionCallId))
            }
        }
    } else null
    ListRow(
        title = row.title,
        detail = row.detail,
        meta = row.ageLabel,
        icon = status.icon,
        iconTone = status.tone,
        onClick = open,
        showDivider = showDivider,
        trailing = when {
            managementEnabled -> {
                { TaskRowManagementMenu(row, onAction, policy) }
            }
            canOpen -> {
                {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            else -> null
        },
        modifier = Modifier
            .taskHomeContractAction(policy, interaction)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.title}. ${row.detail}"
                stateDescription = accessibleState
            }
            .testTag("task-${row.taskId}"),
    )
}

@Composable
private fun TaskRowManagementMenu(
    row: TaskRowUiModel,
    onAction: (TaskHomeAction) -> Unit,
    policy: TaskHomeInteractionPolicy,
) {
    var expanded by remember(row.taskId) { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp).testTag("task-actions-${row.taskId}"),
        ) {
            Icon(MomodingIcons.More, contentDescription = "Task actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (row.archived) {
                DropdownMenuItem(
                    text = { Text("Restore task") },
                    leadingIcon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(TaskHomeAction.Restore(row.taskId))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete permanently") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(TaskHomeAction.RequestDelete(row.taskId, row.title))
                    },
                )
            } else {
                if (row.recoveryAction == TaskRowRecoveryAction.FIX_PROVIDER) {
                    DropdownMenuItem(
                        text = { Text("Fix Provider") },
                        leadingIcon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
                        enabled = policy.allows(TaskHomeInteraction.FIX_PROVIDER),
                        onClick = {
                            expanded = false
                            onAction(TaskHomeAction.FixProvider(row.taskId))
                        },
                        modifier = Modifier.taskHomeContractAction(
                            policy,
                            TaskHomeInteraction.FIX_PROVIDER,
                        ),
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (row.pinned) "Unpin task" else "Pin task") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(TaskHomeAction.SetPinned(row.taskId, !row.pinned))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename task") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(TaskHomeAction.RequestRename(row.taskId, row.title))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Archive task") },
                    leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                    enabled = row.status !in setOf(TaskRowStatus.RUNNING, TaskRowStatus.ATTENTION),
                    onClick = {
                        expanded = false
                        onAction(TaskHomeAction.Archive(row.taskId))
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskManagementDialog(
    dialog: TaskManagementDialogUiModel,
    onAction: (TaskHomeAction) -> Unit,
) {
    when (dialog.kind) {
        TaskManagementDialogKind.RENAME -> AlertDialog(
            onDismissRequest = { onAction(TaskHomeAction.CancelManagementDialog) },
            title = { Text("Rename task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep it short and easy to recognize.")
                    OutlinedTextField(
                        value = dialog.draftTitle,
                        onValueChange = { onAction(TaskHomeAction.EditRename(it)) },
                        enabled = !dialog.busy,
                        singleLine = true,
                        isError = dialog.error != null,
                        supportingText = dialog.error?.let { error -> { Text(error) } },
                        modifier = Modifier.fillMaxWidth().testTag("rename-task-title"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(TaskHomeAction.ConfirmRename) },
                    enabled = !dialog.busy && dialog.draftTitle.isNotBlank(),
                    modifier = Modifier.testTag("confirm-rename-task"),
                ) { Text(if (dialog.busy) "Saving…" else "Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(TaskHomeAction.CancelManagementDialog) },
                    enabled = !dialog.busy,
                ) { Text("Cancel") }
            },
        )
        TaskManagementDialogKind.DELETE -> AlertDialog(
            onDismissRequest = { onAction(TaskHomeAction.CancelManagementDialog) },
            title = { Text("Delete this task permanently?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("“${dialog.taskTitle}” and its local messages, outputs, and approvals will be deleted from this phone.")
                    Text("This can’t be undone.", fontWeight = FontWeight.SemiBold)
                    dialog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(TaskHomeAction.ConfirmDelete) },
                    enabled = !dialog.busy,
                    modifier = Modifier.testTag("confirm-delete-task"),
                ) { Text(if (dialog.busy) "Deleting…" else "Delete permanently") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(TaskHomeAction.CancelManagementDialog) },
                    enabled = !dialog.busy,
                ) { Text("Cancel") }
            },
        )
    }
}

private data class TaskStatusVisual(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val container: Color,
    val tone: ProductIconTone,
)

@Composable
private fun taskStatusVisual(status: TaskRowStatus): TaskStatusVisual {
    val colors = LocalMomodingStatusColors.current
    return when (status) {
        TaskRowStatus.ATTENTION -> TaskStatusVisual(Icons.Outlined.WarningAmber, "Needs attention", colors.warning, colors.warningContainer, ProductIconTone.WARNING)
        TaskRowStatus.RUNNING -> TaskStatusVisual(MomodingIcons.Retry, "Running", colors.info, colors.infoContainer, ProductIconTone.BRAND)
        TaskRowStatus.COMPLETED -> TaskStatusVisual(Icons.Outlined.CheckCircle, "Completed", colors.success, colors.successContainer, ProductIconTone.SUCCESS)
        TaskRowStatus.FAILED -> TaskStatusVisual(Icons.Outlined.ErrorOutline, "Failed", colors.danger, colors.dangerContainer, ProductIconTone.DANGER)
    }
}

@Composable
private fun LoadingTasks() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text("Loading tasks…", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FailedTasks() {
    EmptyMessage(Icons.Outlined.ErrorOutline, "Tasks are unavailable", "The cached task list could not be loaded.")
}

@Composable
private fun SearchEmpty(query: String, onAction: (TaskHomeAction) -> Unit, policy: TaskHomeInteractionPolicy) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyMessage(MomodingIcons.Search, "No matching tasks", "Nothing matches “$query”.")
        if (policy.allows(TaskHomeInteraction.CLEAR_SEARCH)) {
            androidx.compose.material3.TextButton(
                onClick = { onAction(TaskHomeAction.ClearSearch) },
                modifier = Modifier
                    .testTag("action-ClearSearch")
                    .taskHomeContractAction(policy, TaskHomeInteraction.CLEAR_SEARCH),
            ) {
                Text("Clear search")
            }
        }
    }
}

@Composable
private fun EmptyTasks(
    connection: TaskHomeConnectionState,
    showArchiveToggle: Boolean,
    archivedVisible: Boolean,
    onAction: (TaskHomeAction) -> Unit,
    policy: TaskHomeInteractionPolicy,
) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MomodingMark(size = 72.dp)
        Text("Start your first task", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 17.dp).semantics { heading() })
        Text(
            if (connection == TaskHomeConnectionState.UNPAIRED) {
                "Connect your self-hosted Pi Host before creating a task."
            } else {
                "Ask Momoding to plan, write, review, or reason through a problem directly from this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        val canCreate = connection == TaskHomeConnectionState.CONNECTED && policy.allows(TaskHomeInteraction.NEW_TASK)
        val canPair = connection == TaskHomeConnectionState.UNPAIRED && policy.allows(TaskHomeInteraction.PAIR_HOST)
        if (canCreate || canPair) {
            PrimaryAction(
                label = if (canCreate) "New task" else "Connect Host",
                onClick = { onAction(if (canCreate) TaskHomeAction.NewTask else TaskHomeAction.PairHost) },
                icon = if (canCreate) MomodingIcons.Add else null,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag(if (canCreate) "action-NewTask" else "action-PairHost")
                    .taskHomeContractAction(
                        policy,
                        if (canCreate) TaskHomeInteraction.NEW_TASK else TaskHomeInteraction.PAIR_HOST,
                    ),
            )
        }
        if (showArchiveToggle && policy.allows(TaskHomeInteraction.TOGGLE_ARCHIVED)) {
            TextButton(
                onClick = { onAction(TaskHomeAction.ToggleArchived) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .testTag("action-ToggleArchived")
                    .taskHomeContractAction(policy, TaskHomeInteraction.TOGGLE_ARCHIVED),
            ) {
                Icon(
                    if (archivedVisible) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(7.dp))
                Text(if (archivedVisible) "Show active tasks" else "Browse archived tasks")
            }
        }
    }
}

@Composable
private fun EmptyMessage(icon: ImageVector, title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}
