package app.momoding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.feature.tasks.TaskHomeAction
import app.momoding.feature.tasks.TaskHomeConnectionState
import app.momoding.feature.tasks.TaskHomeLoadState
import app.momoding.feature.tasks.TaskHomeUiState
import app.momoding.feature.tasks.TaskRowOpenAction
import app.momoding.feature.tasks.TaskRowStatus
import app.momoding.feature.tasks.TaskRowUiModel
import app.momoding.ui.icons.MomodingIcons
import app.momoding.ui.theme.LocalMomodingStatusColors
import app.momoding.ui.theme.LocalMomodingBrandColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomodingNavigationDrawer(
    state: TaskHomeUiState,
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    currentTaskId: String?,
    onTaskAction: (TaskHomeAction) -> Unit,
    onOpenAllTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 360.dp)
                    .fillMaxHeight(),
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp,
            ) {
                DrawerContent(
                    state = state,
                    currentTaskId = currentTaskId,
                    onTaskAction = onTaskAction,
                    onOpenAllTasks = onOpenAllTasks,
                    onOpenSettings = onOpenSettings,
                    onClose = onClose,
                )
            }
        },
        content = content,
    )
}

@Composable
private fun DrawerContent(
    state: TaskHomeUiState,
    currentTaskId: String?,
    onTaskAction: (TaskHomeAction) -> Unit,
    onOpenAllTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val rows = state.sections.flatMap { it.rows }
    val pinned = rows.filter(TaskRowUiModel::pinned)
    val needsYou = rows.filter {
        !it.pinned && it.status in setOf(TaskRowStatus.ATTENTION, TaskRowStatus.FAILED)
    }
    val running = rows.filter { !it.pinned && it.status == TaskRowStatus.RUNNING }
    val recent = rows.filter { !it.pinned && it.status == TaskRowStatus.COMPLETED }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MomodingMark(size = 34.dp)
            Text(
                text = "Momoding",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).testTag("drawer-close"),
            ) {
                Icon(MomodingIcons.Close, contentDescription = "Close navigation")
            }
        }

        DrawerNewTask(
            enabled = state.connection == TaskHomeConnectionState.CONNECTED,
            onClick = { onTaskAction(TaskHomeAction.NewTask) },
        )
        Spacer(Modifier.height(8.dp))
        DrawerSearch(state = state, onAction = onTaskAction)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (pinned.isNotEmpty()) {
                item("pinned") {
                    DrawerSection(
                        title = "Pinned",
                        rows = pinned.take(3),
                        currentTaskId = currentTaskId,
                        onTaskAction = onTaskAction,
                    )
                }
            }
            if (needsYou.isNotEmpty()) {
                item("needs-you") {
                    DrawerSection(
                        title = "Needs you",
                        rows = needsYou.take(3),
                        currentTaskId = currentTaskId,
                        onTaskAction = onTaskAction,
                    )
                }
            }
            if (running.isNotEmpty()) {
                item("running") {
                    DrawerSection(
                        title = "Running",
                        rows = running.take(3),
                        currentTaskId = currentTaskId,
                        onTaskAction = onTaskAction,
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item("recent") {
                    DrawerSection(
                        title = "Recent",
                        rows = recent.take(8),
                        currentTaskId = currentTaskId,
                        onTaskAction = onTaskAction,
                    )
                }
            }
            if (rows.isEmpty()) {
                item("empty") {
                    DrawerEmptyState(state)
                }
            }
            item("all-tasks") {
                DrawerUtilityRow(
                    icon = MomodingIcons.Tasks,
                    title = "All tasks",
                    detail = "Search, pin and manage",
                    onClick = onOpenAllTasks,
                    modifier = Modifier.testTag("drawer-all-tasks"),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DrawerFooter(state = state, onClick = onOpenSettings)
    }
}

@Composable
private fun DrawerNewTask(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val brand = LocalMomodingBrandColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("drawer-new-task"),
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) brand.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) brand.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MomodingIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("New task", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DrawerSearch(
    state: TaskHomeUiState,
    onAction: (TaskHomeAction) -> Unit,
) {
    if (state.searchVisible) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(TaskHomeAction.EditSearch(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("drawer-search-field"),
            placeholder = { Text("Search tasks") },
            leadingIcon = { Icon(MomodingIcons.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(
                    onClick = {
                        onAction(TaskHomeAction.ClearSearch)
                        onAction(TaskHomeAction.Search)
                    },
                ) {
                    Icon(MomodingIcons.Close, contentDescription = "Close search")
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
    } else {
        DrawerUtilityRow(
            icon = MomodingIcons.Search,
            title = "Search tasks",
            onClick = { onAction(TaskHomeAction.Search) },
            modifier = Modifier.testTag("drawer-search"),
        )
    }
}

@Composable
private fun DrawerSection(
    title: String,
    rows: List<TaskRowUiModel>,
    currentTaskId: String?,
    onTaskAction: (TaskHomeAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp).semantics { heading() },
        )
        rows.forEach { row ->
            DrawerTaskRow(
                row = row,
                selected = row.taskId == currentTaskId,
                onClick = { onTaskAction(row.openAction()) },
            )
        }
    }
}

@Composable
private fun DrawerTaskRow(
    row: TaskRowUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val brand = LocalMomodingBrandColors.current
    val status = LocalMomodingStatusColors.current
    val statusColor = when (row.status) {
        TaskRowStatus.ATTENTION -> status.warning
        TaskRowStatus.RUNNING -> status.info
        TaskRowStatus.FAILED -> status.danger
        TaskRowStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) brand.soft else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("drawer-task-${row.taskId}")
            .semantics {
                this.selected = selected
                stateDescription = row.accessibilityState()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(8.dp).background(statusColor, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.unread || selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = row.ageLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DrawerUtilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DrawerFooter(
    state: TaskHomeUiState,
    onClick: () -> Unit,
) {
    val status = LocalMomodingStatusColors.current
    val ready = state.connection == TaskHomeConnectionState.CONNECTED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag("drawer-settings"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MomodingMark(size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Momoding", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = if (ready) "Ready on this phone" else state.connection.footerLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) status.success else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            MomodingIcons.Settings,
            contentDescription = "Settings",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DrawerEmptyState(state: TaskHomeUiState) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.loadState == TaskHomeLoadState.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = when {
                state.loadState == TaskHomeLoadState.LOADING -> "Loading tasks…"
                state.loadState == TaskHomeLoadState.FAILED -> "Tasks couldn’t be loaded."
                state.query.isNotBlank() -> "No tasks match “${state.query}”."
                else -> "No tasks yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun TaskRowUiModel.openAction(): TaskHomeAction = when (openAction) {
    TaskRowOpenAction.OPEN_TASK -> TaskHomeAction.OpenTask(taskId)
    TaskRowOpenAction.OPEN_CACHED_TASK -> TaskHomeAction.OpenCachedTask(taskId)
    TaskRowOpenAction.OPEN_ATTENTION -> TaskHomeAction.OpenAttention(taskId, attentionCallId)
}

private fun TaskRowUiModel.accessibilityState(): String = buildList {
    add(
        when (status) {
            TaskRowStatus.ATTENTION -> "Needs attention"
            TaskRowStatus.RUNNING -> "Working"
            TaskRowStatus.COMPLETED -> "Completed"
            TaskRowStatus.FAILED -> "Failed"
        },
    )
    if (unread) add("Unread")
    if (pinned) add("Pinned")
}.joinToString()

private fun TaskHomeConnectionState.footerLabel(): String = when (this) {
    TaskHomeConnectionState.CONNECTED -> "Ready on this phone"
    TaskHomeConnectionState.CONNECTING -> "Connecting"
    TaskHomeConnectionState.RECONNECTING -> "Reconnecting"
    TaskHomeConnectionState.OFFLINE -> "Offline"
    TaskHomeConnectionState.UNPAIRED -> "Setup needed"
    TaskHomeConnectionState.ERROR -> "Needs attention"
}
