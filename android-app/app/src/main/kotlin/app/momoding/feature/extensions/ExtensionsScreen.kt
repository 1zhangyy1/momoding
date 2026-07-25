package app.momoding.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillSource
import app.momoding.ui.components.MomodingTopBar

@Composable
fun ExtensionsScreen(
    state: ExtensionsUiState,
    onAction: (ExtensionsAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            MomodingTopBar(
                title = "Extensions",
                subtitle = "Skills and phone-local capabilities",
                onBack = { onAction(ExtensionsAction.Back) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("extensions-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { ExtensionsIntro() }
                state.notice?.let { notice ->
                    item { NoticeCard(notice) { onAction(ExtensionsAction.ClearNotice) } }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeading("Skills", "Pi-native instructions")
                        OutlinedButton(
                            onClick = { onAction(ExtensionsAction.ImportSkill) },
                            enabled = !state.importing && state.busySkillName == null,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("import-skill"),
                        ) {
                            if (state.importing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(if (state.importing) "Importing" else "Import")
                        }
                    }
                }
                if (state.loading && state.skills.isEmpty()) {
                    item { LoadingCard("Loading Skills") }
                } else if (state.skills.isEmpty()) {
                    item { EmptySkillsCard() }
                } else {
                    items(state.skills, key = ExtensionSkillUiState::name) { skill ->
                        SkillCard(
                            skill = skill,
                            busy = state.busySkillName == skill.name,
                            controlsEnabled = !state.importing && state.busySkillName == null,
                            onEnabledChange = { enabled ->
                                onAction(ExtensionsAction.SetSkillEnabled(skill.name, enabled))
                            },
                            onDelete = {
                                onAction(ExtensionsAction.RequestDeleteSkill(skill.name))
                            },
                        )
                    }
                }
                item { SectionHeading("Mobile extensions", "Built into this app") }
                item {
                    SectionCard {
                        BuiltInRow(Icons.Outlined.Route, "Plan", "Create an implementation plan")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BuiltInRow(Icons.Outlined.Flag, "Goal", "Continue toward a durable objective")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BuiltInRow(Icons.AutoMirrored.Outlined.CallSplit, "Child Agents", "Delegate bounded sub-tasks")
                    }
                }
                item { SectionHeading("MCP", "External tool servers") }
                item {
                    SectionCard {
                        CapabilityRow(
                            icon = Icons.Outlined.Hub,
                            title = "Model Context Protocol",
                            detail = "Not supported by Pi 0.80.6 in Emulator Alpha",
                            value = "Unavailable",
                            modifier = Modifier.testTag("mcp-unsupported"),
                        )
                    }
                }
            }
        }
    }

    val pendingDelete = state.pendingDeleteSkillName
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { onAction(ExtensionsAction.CancelDeleteSkill) },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Remove imported Skill?") },
            text = {
                Text(
                    "“$pendingDelete” will be removed from this phone. Its original document is not changed.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { onAction(ExtensionsAction.ConfirmDeleteSkill) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(ExtensionsAction.CancelDeleteSkill) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ExtensionsIntro() {
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
                Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Extend phone-local Pi", style = MaterialTheme.typography.titleSmall)
            Text(
                "Single-file import; external references are unavailable. Select one SKILL.md. The document is read once; Android access and its URI are not retained.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.semantics { heading() }) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillCard(
    skill: ExtensionSkillUiState,
    busy: Boolean,
    controlsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val available = skill.availability == SkillAvailability.AVAILABLE
    val status = when {
        busy -> "Updating"
        skill.availability == SkillAvailability.UNAVAILABLE -> "Unavailable"
        skill.availability == SkillAvailability.ERROR -> "Error"
        skill.enabled -> "Enabled"
        else -> "Disabled"
    }
    SectionCard(modifier = Modifier.testTag("skill-${skill.name}")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Extension, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${if (skill.source == SkillSource.BUNDLED) "Bundled" else "Imported"} · $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Switch(
                checked = skill.enabled && available,
                onCheckedChange = onEnabledChange,
                enabled = available && controlsEnabled,
                modifier = Modifier
                    .testTag("skill-toggle-${skill.name}")
                    .semantics {
                        role = Role.Switch
                        stateDescription = status
                    },
            )
        }
        if (!available && !skill.diagnosticMessage.isNullOrBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    skill.diagnosticMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (skill.source == SkillSource.IMPORTED) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = onDelete,
                enabled = controlsEnabled,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp)
                    .testTag("delete-skill-${skill.name}"),
            ) { Text("Remove") }
        }
    }
}

@Composable
private fun BuiltInRow(icon: ImageVector, title: String, detail: String) {
    CapabilityRow(
        icon = icon,
        title = title,
        detail = detail,
        value = "Enabled",
        valueIcon = Icons.Outlined.CheckCircle,
    )
}

@Composable
private fun CapabilityRow(
    icon: ImageVector,
    title: String,
    detail: String,
    value: String,
    modifier: Modifier = Modifier,
    valueIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        valueIcon?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun LoadingCard(label: String) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptySkillsCard() {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("No Skills installed", style = MaterialTheme.typography.titleSmall)
            Text(
                "Import a SKILL.md document to add one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeCard(notice: String, onDismiss: () -> Unit) {
    SectionCard(modifier = Modifier.testTag("extensions-notice")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Dismiss")
            }
        }
    }
}
