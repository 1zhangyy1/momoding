package app.momoding.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.ui.theme.LocalMomodingStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalModeSelector(
    mode: TaskApprovalMode,
    saving: Boolean,
    enabled: Boolean,
    onSelect: (TaskApprovalMode) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val presentation = mode.presentation()
    val warning = LocalMomodingStatusColors.current
    val triggerColor = if (mode == TaskApprovalMode.FULL_ACCESS) {
        warning.warning
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier) {
        Surface(
            onClick = { sheetOpen = true },
            enabled = enabled && !saving,
            modifier = Modifier
                .size(48.dp)
                .testTag("approval-mode-trigger")
                .semantics {
                    role = Role.Button
                    stateDescription = buildString {
                        append("Selected: ${presentation.label}")
                        errorMessage?.let { append(". ").append(it) }
                    }
                },
            shape = RoundedCornerShape(12.dp),
            color = if (mode == TaskApprovalMode.FULL_ACCESS) {
                warning.warningContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = triggerColor,
                    )
                } else {
                    Icon(
                        if (mode == TaskApprovalMode.FULL_ACCESS) {
                            Icons.Outlined.WarningAmber
                        } else {
                            Icons.Outlined.Security
                        },
                        contentDescription = "${presentation.label} approval mode",
                        modifier = Modifier.size(20.dp),
                        tint = triggerColor,
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            modifier = Modifier.testTag("approval-mode-sheet"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "How should Momoding handle actions?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Every mode uses the same device capabilities. This only changes when Momoding asks before acting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TaskApprovalMode.entries.forEach { option ->
                    ApprovalModeOption(
                        mode = option,
                        selected = option == mode,
                        onClick = {
                            sheetOpen = false
                            if (option != mode) onSelect(option)
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Device access is managed separately in Settings. Unsupported or ungranted capabilities stay unavailable in every mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApprovalModeOption(
    mode: TaskApprovalMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val presentation = mode.presentation()
    val warning = LocalMomodingStatusColors.current
    val fullAccess = mode == TaskApprovalMode.FULL_ACCESS
    val accent: Color = if (fullAccess) warning.warning else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("approval-mode-option-${mode.name}")
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        shape = RoundedCornerShape(14.dp),
        color = when {
            fullAccess -> warning.warningContainer
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = when {
                        fullAccess -> warning.warning
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (fullAccess) Icons.Outlined.WarningAmber else Icons.Outlined.Security,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(presentation.label, style = MaterialTheme.typography.titleSmall, color = accent)
                Text(
                    presentation.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fullAccess) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Selected",
                    tint = if (fullAccess) warning.warning else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Spacer(Modifier.size(22.dp))
            }
        }
    }
}

private data class ApprovalModePresentation(
    val label: String,
    val description: String,
)

private fun TaskApprovalMode.presentation(): ApprovalModePresentation = when (this) {
    TaskApprovalMode.REQUEST_APPROVAL -> ApprovalModePresentation(
        label = "Request approval",
        description = "Ask before Android actions such as changing device data, controlling apps, or sending. App-private workspace work runs directly.",
    )
    TaskApprovalMode.AUTO_APPROVE -> ApprovalModePresentation(
        label = "Approve for me",
        description = "Automatically allow low-risk actions; still ask before high-risk actions.",
    )
    TaskApprovalMode.FULL_ACCESS -> ApprovalModePresentation(
        label = "Full access",
        description = "Automatically allow supported actions within Android's hard safety boundaries.",
    )
}
