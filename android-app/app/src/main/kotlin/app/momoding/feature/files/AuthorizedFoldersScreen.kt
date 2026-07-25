package app.momoding.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.core.files.AuthorizedDocumentMetadata
import app.momoding.core.files.AuthorizedFolderStatus
import app.momoding.core.files.AuthorizedFolderSummary
import app.momoding.ui.components.MomodingTopBar

@Composable
fun AuthorizedFoldersScreen(
    state: AuthorizedFoldersUiState,
    onAction: (AuthorizedFoldersAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            MomodingTopBar(
                title = "Mobile files",
                subtitle = "Android SAF folders",
                onBack = { onAction(AuthorizedFoldersAction.Back) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("authorized-folders-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    PrivacyIntro()
                }
                state.notice?.let { notice ->
                    item {
                        NoticeCard(notice) { onAction(AuthorizedFoldersAction.ClearNotice) }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { onAction(AuthorizedFoldersAction.AddFolder) },
                            enabled = state.busyAction == null,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("add-authorized-folder"),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Add folder")
                        }
                        OutlinedButton(
                            onClick = { onAction(AuthorizedFoldersAction.Refresh) },
                            enabled = state.busyAction == null,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
                if (state.loading && state.folders.isEmpty()) {
                    item { LoadingRow("Loading authorized folders") }
                } else if (state.folders.isEmpty()) {
                    item { EmptyFolders() }
                } else {
                    item {
                        Text(
                            "Authorized folders",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    items(state.folders, key = AuthorizedFolderSummary::grantId) { folder ->
                        FolderCard(
                            folder = folder,
                            selected = folder.grantId == state.selectedGrantId,
                            enabled = state.busyAction == null,
                            onOpen = { onAction(AuthorizedFoldersAction.OpenFolder(folder.grantId)) },
                            onRevoke = {
                                onAction(AuthorizedFoldersAction.RequestRevoke(folder.grantId))
                            },
                        )
                    }
                }
                if (state.selectedGrantId != null) {
                    item {
                        Text(
                            "Metadata scope",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                        )
                    }
                    item {
                        Text(
                            "The Agent may receive these names, types, sizes, timestamps, and opaque aliases. File contents still require a separate task-scoped approval.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.busyAction == "metadata") {
                        item { LoadingRow("Reading metadata") }
                    } else {
                        items(state.documents, key = AuthorizedDocumentMetadata::alias) { document ->
                            MetadataRow(document)
                        }
                        if (state.listingTruncated) {
                            item {
                                Text(
                                    "Metadata list stopped at the local safety limit: ${state.truncationReasons.sorted().joinToString()}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.pendingRevokeGrantId != null) {
        val folder = state.folders.firstOrNull {
            it.grantId == state.pendingRevokeGrantId
        }
        AlertDialog(
            onDismissRequest = { onAction(AuthorizedFoldersAction.CancelRevoke) },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Remove folder access?") },
            text = {
                Text(
                    "Momoding will release Android access to “${folder?.displayName ?: "this folder"}”. This does not delete or modify any files.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { onAction(AuthorizedFoldersAction.ConfirmRevoke) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove access") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(AuthorizedFoldersAction.CancelRevoke) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PrivacyIntro() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("You choose every folder", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android keeps the real folder address on this phone. After a task grant, Pi can receive only an opaque grant ID and approved metadata.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: AuthorizedFolderSummary,
    selected: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRevoke: () -> Unit,
) {
    val status = when (folder.status) {
        AuthorizedFolderStatus.ACTIVE -> "Read & write"
        AuthorizedFolderStatus.READ_ONLY -> "Read only"
        AuthorizedFolderStatus.REAUTHORIZATION_REQUIRED -> "Choose again"
        AuthorizedFolderStatus.PROVIDER_UNAVAILABLE -> "Provider unavailable"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(14.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled && folder.canRead, onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
            )
            Column(Modifier.weight(1f)) {
                Text(folder.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${folder.authorityLabel} · $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(
                onClick = onOpen,
                enabled = enabled && folder.canRead,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (selected) "Refresh metadata" else "View metadata") }
            TextButton(
                onClick = onRevoke,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Remove") }
        }
    }
}

@Composable
private fun MetadataRow(document: AuthorizedDocumentMetadata) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (document.depth.coerceAtMost(6) * 12).dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (document.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                document.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (document.isDirectory) {
                    "Folder"
                } else {
                    "${document.mimeType} · ${formatBytes(document.byteCount)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyFolders() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
        Text("No folders authorized", style = MaterialTheme.typography.titleSmall)
        Text(
            "Choose one real directory through Android’s system picker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoticeCard(
    notice: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Dismiss")
        }
    }
}

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "Size unavailable"
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> "${bytes / 1_048_576} MB"
}
