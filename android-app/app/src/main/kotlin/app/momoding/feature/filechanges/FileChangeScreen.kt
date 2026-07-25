package app.momoding.feature.filechanges

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.core.data.FileChangeItem
import app.momoding.core.files.FileChangeSetState
import app.momoding.ui.components.MomodingTopBar

@Composable
fun FileChangeScreen(
    state: FileChangeUiState,
    onBack: () -> Unit,
    onAction: (FileChangeAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MomodingTopBar(
                title = "Review changes",
                subtitle = state.record?.let { "${it.items.size} proposed changes" },
                onBack = onBack,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (state.loadState) {
                FileChangeLoadState.LOADING -> CenterState(
                    title = "Loading changes",
                    loading = true,
                )
                FileChangeLoadState.MISSING -> CenterState(
                    title = "No prepared changes",
                    body = "This task has not prepared a file change set yet.",
                )
                FileChangeLoadState.CORRUPT -> CenterState(
                    title = "Changes unavailable",
                    body = "Android could not verify the local change record.",
                    error = true,
                )
                FileChangeLoadState.READY -> {
                    val record = requireNotNull(state.record)
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("file-change-list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 20.dp,
                            top = 20.dp,
                            end = 20.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(key = "summary") {
                            ChangeSummary(
                                purpose = record.purpose,
                                state = record.state,
                                count = record.items.size,
                            )
                        }
                        items(
                            count = record.items.size,
                            key = { record.items[it].operationId },
                        ) { index ->
                            ChangeCard(index + 1, record.items[index])
                        }
                        record.approvalReceiptId?.let { receipt ->
                            item(key = "receipt") {
                                Text(
                                    text = "Approval receipt ${receipt.take(8)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    ActionDock(state, onAction)
                }
            }
        }
    }
}

@Composable
private fun ChangeSummary(
    purpose: String,
    state: FileChangeSetState,
    count: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = purpose,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryChip("$count operations")
            SummaryChip(state.label())
        }
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    "These changes write to the Android folder you authorized. Review every item before applying.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChangeCard(index: Int, item: FileChangeItem) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        item.kind.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index}. ${item.kind.label()}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    item.mimeType?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item.resultState?.let { ResultBadge(it) }
            }
            if (item.beforeName != null) {
                PathRow("Before", item.beforeName, item.beforeParentDisplayPath)
            }
            if (item.afterName != null) {
                PathRow("After", item.afterName, item.afterParentDisplayPath)
            }
            if (item.content != null) {
                Text(
                    text = if (item.kind == "write_file") {
                        "Replacement content · ${item.contentByteCount ?: 0} bytes"
                    } else {
                        "New file content · ${item.contentByteCount ?: 0} bytes"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = item.content,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            item.errorCode?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PathRow(label: String, name: String, parentDisplayPath: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            modifier = Modifier.width(58.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            parentDisplayPath?.let {
                Text(
                    text = "Folder · $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultBadge(state: String) {
    val (label, icon) = when (state) {
        "succeeded" -> "Applied" to Icons.Outlined.CheckCircle
        "failed" -> "Failed" to Icons.Outlined.ErrorOutline
        "unknown" -> "Check file" to Icons.Outlined.WarningAmber
        else -> "Not run" to Icons.Outlined.HourglassTop
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.Icon(icon, null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActionDock(
    state: FileChangeUiState,
    onAction: (FileChangeAction) -> Unit,
) {
    val record = requireNotNull(state.record)
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!state.connected && record.state == FileChangeSetState.AWAITING_APPROVAL) {
                Text(
                    "Reconnect to the Pi Host before deciding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (record.state) {
                FileChangeSetState.PREPARED -> Text(
                    "Prepared locally. Waiting for the agent to request commit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FileChangeSetState.AWAITING_APPROVAL -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onAction(FileChangeAction.Reject) },
                        enabled = state.canReject,
                        modifier = Modifier.weight(1f).testTag("file-change-reject"),
                    ) {
                        Text("Reject")
                    }
                    Button(
                        onClick = { onAction(FileChangeAction.Approve) },
                        enabled = state.canApprove,
                        modifier = Modifier.weight(1f).testTag("file-change-approve"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Apply changes")
                    }
                }
                FileChangeSetState.APPROVED,
                FileChangeSetState.COMMITTING,
                -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Applying changes on this device…")
                }
                else -> Text(
                    record.state.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CenterState(
    title: String,
    body: String? = null,
    loading: Boolean = false,
    error: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(28.dp),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            if (error) androidx.compose.material3.Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun String.label(): String = when (this) {
    "create_file" -> "Create file"
    "create_directory" -> "Create folder"
    "rename" -> "Rename"
    "move" -> "Move"
    "write_file" -> "Modify file"
    "delete_file" -> "Delete file"
    else -> "File change"
}

private fun String.icon(): ImageVector = when (this) {
    "create_file" -> Icons.Outlined.Add
    "create_directory" -> Icons.Outlined.CreateNewFolder
    "rename" -> Icons.Outlined.Edit
    "move" -> Icons.AutoMirrored.Outlined.DriveFileMove
    "write_file" -> Icons.Outlined.Edit
    "delete_file" -> Icons.Outlined.DeleteOutline
    else -> Icons.Outlined.WarningAmber
}

private fun FileChangeSetState.label(): String = when (this) {
    FileChangeSetState.PREPARED -> "Prepared"
    FileChangeSetState.AWAITING_APPROVAL -> "Needs approval"
    FileChangeSetState.APPROVED -> "Approved"
    FileChangeSetState.COMMITTING -> "Applying"
    FileChangeSetState.COMPLETED -> "Applied"
    FileChangeSetState.PARTIALLY_FAILED -> "Partially applied"
    FileChangeSetState.FAILED -> "Failed"
    FileChangeSetState.REJECTED -> "Rejected"
    FileChangeSetState.EXPIRED -> "Expired"
    FileChangeSetState.CANCELLED -> "Cancelled"
    FileChangeSetState.UNKNOWN -> "Needs inspection"
}
