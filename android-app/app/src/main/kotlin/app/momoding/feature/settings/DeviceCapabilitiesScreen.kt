package app.momoding.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.ui.components.MomodingTopBar

@Composable
fun DeviceCapabilitiesScreen(
    states: List<AndroidCapabilityState>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFolders: (() -> Unit)?,
    onManagePhotoAccess: (() -> Unit)? = null,
    onManageAccessibility: (() -> Unit)? = null,
    onManageScreenCapture: (() -> Unit)? = null,
    onManageAllFiles: (() -> Unit)? = null,
    onManageShizuku: (() -> Unit)? = null,
    onSetUpFullAccess: (() -> Unit)? = null,
) {
    val statesById = states.associateBy(AndroidCapabilityState::id)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            MomodingTopBar(
                title = "Device capabilities",
                subtitle = "Android access",
                onBack = onBack,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("device-capabilities-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { CapabilityBoundaryCard() }
                if (onSetUpFullAccess != null) {
                    item { FullAccessSetupCard(onSetUpFullAccess) }
                }
                item {
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("refresh-device-capabilities"),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Check again")
                    }
                }
                if (states.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                            Text("Checking Android access")
                        }
                    }
                }
                items(CapabilityPresentation.entries, key = { it.id.name }) { presentation ->
                    val action = when (presentation.id) {
                        AndroidCapabilityId.SAF_FOLDERS -> onOpenFolders
                        AndroidCapabilityId.PHOTO_LIBRARY -> onManagePhotoAccess
                        AndroidCapabilityId.ACCESSIBILITY_CONTROL -> onManageAccessibility
                        AndroidCapabilityId.SCREEN_CAPTURE -> onManageScreenCapture
                        AndroidCapabilityId.ALL_FILES -> onManageAllFiles
                        AndroidCapabilityId.SHIZUKU_SHELL_UID -> onManageShizuku
                    }
                    CapabilityCard(
                        presentation = presentation,
                        state = statesById[presentation.id],
                        onOpen = action,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullAccessSetupCard(onSetUp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Set up Full access",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Momoding guides you through photo, folder, and shared-storage access. Android still requires you to enable each system access once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onSetUp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("setup-full-access"),
        ) {
            Text("Continue setup")
        }
    }
}

@Composable
private fun CapabilityBoundaryCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Access is shared across task modes",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Approval modes control when Momoding asks. They never add Android permissions or make an unavailable capability ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun CapabilityCard(
    presentation: CapabilityPresentation,
    state: AndroidCapabilityState?,
    onOpen: (() -> Unit)?,
) {
    val status = state?.availability?.statusPresentation()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("capability-${presentation.id.name}")
            .semantics {
                stateDescription = status?.label ?: "Checking"
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(presentation.icon, contentDescription = null)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(presentation.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    presentation.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Checking", style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(
                    status.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = status.color(),
                )
                Text(
                    status.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = status.color(),
                )
            }
        }
        state?.let {
            Text(
                it.safeMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Source: ${it.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpen != null) {
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("capability-action-${presentation.id.name}"),
            ) {
                Text(
                    when {
                        presentation.id == AndroidCapabilityId.SCREEN_CAPTURE &&
                            state?.source == "Android MediaProjection" -> "Stop session"
                        presentation.id == AndroidCapabilityId.SCREEN_CAPTURE ->
                            "Start screen session"
                        else -> presentation.actionLabel
                    },
                )
            }
        }
    }
}

@Composable
private fun CapabilityStatusPresentation.color(): Color = when (availability) {
    CapabilityAvailability.READY -> MaterialTheme.colorScheme.primary
    CapabilityAvailability.PARTIAL -> MaterialTheme.colorScheme.tertiary
    CapabilityAvailability.ERROR -> MaterialTheme.colorScheme.error
    CapabilityAvailability.NOT_GRANTED,
    CapabilityAvailability.SESSION_REQUIRED -> MaterialTheme.colorScheme.tertiary
    CapabilityAvailability.UNSUPPORTED,
    CapabilityAvailability.MISSING_DEPENDENCY -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun CapabilityAvailability.statusPresentation(): CapabilityStatusPresentation = when (this) {
    CapabilityAvailability.READY -> CapabilityStatusPresentation(this, "Ready", Icons.Outlined.CheckCircle)
    CapabilityAvailability.PARTIAL -> CapabilityStatusPresentation(this, "Limited access", Icons.Outlined.Info)
    CapabilityAvailability.NOT_GRANTED -> CapabilityStatusPresentation(this, "Not granted", Icons.Outlined.WarningAmber)
    CapabilityAvailability.SESSION_REQUIRED -> CapabilityStatusPresentation(this, "Start session", Icons.Outlined.Info)
    CapabilityAvailability.MISSING_DEPENDENCY -> CapabilityStatusPresentation(this, "Not installed", Icons.Outlined.Block)
    CapabilityAvailability.UNSUPPORTED -> CapabilityStatusPresentation(this, "Unavailable", Icons.Outlined.Block)
    CapabilityAvailability.ERROR -> CapabilityStatusPresentation(this, "Check failed", Icons.Outlined.ErrorOutline)
}

private data class CapabilityStatusPresentation(
    val availability: CapabilityAvailability,
    val label: String,
    val icon: ImageVector,
)

private enum class CapabilityPresentation(
    val id: AndroidCapabilityId,
    val title: String,
    val purpose: String,
    val icon: ImageVector,
    val actionLabel: String,
) {
    SAF(
        AndroidCapabilityId.SAF_FOLDERS,
        "Authorized folders",
        "Read or change only folders you explicitly choose with Android SAF.",
        Icons.Outlined.Folder,
        "Manage folders",
    ),
    PHOTOS(
        AndroidCapabilityId.PHOTO_LIBRARY,
        "Photo library",
        "List bounded metadata for photos Android allows. Composer Photo Picker needs no permission.",
        Icons.Outlined.Image,
        "Manage photo access",
    ),
    ACCESSIBILITY(
        AndroidCapabilityId.ACCESSIBILITY_CONTROL,
        "Accessibility control",
        "Inspect and operate visible app interfaces after Android access is enabled.",
        Icons.Outlined.TouchApp,
        "Open",
    ),
    SCREEN_CAPTURE(
        AndroidCapabilityId.SCREEN_CAPTURE,
        "Screen capture",
        "Share the current screen with a time-limited Android capture session.",
        Icons.Outlined.Info,
        "Open",
    ),
    ALL_FILES(
        AndroidCapabilityId.ALL_FILES,
        "All files access",
        "Use bounded tools in Downloads, Documents, Pictures, Camera, Movies, and Music.",
        Icons.Outlined.Security,
        "Manage shared storage",
    ),
    SHIZUKU(
        AndroidCapabilityId.SHIZUKU_SHELL_UID,
        "Shizuku shell access",
        "Read selected Android package facts through a separately started shell-only service.",
        Icons.Outlined.Terminal,
        "Set up Shizuku",
    ),
}
