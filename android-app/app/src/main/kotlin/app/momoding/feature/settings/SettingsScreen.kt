package app.momoding.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.momoding.wire.CoreProtocol
import app.momoding.BuildConfig
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.transport.SecureTransportUiPhase
import kotlinx.coroutines.launch

private enum class VersionDetailsOrigin { BANNER, HOST_ROW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onAction: (SettingsAction) -> Unit,
    interactionPolicy: SettingsInteractionPolicy = SettingsInteractionPolicy.All,
    onOpenMobileFiles: (() -> Unit)? = null,
    onOpenDeviceCapabilities: (() -> Unit)? = null,
    onOpenExtensions: (() -> Unit)? = null,
    providerProfile: ProviderProfile? = null,
    onOpenProviderSetup: (() -> Unit)? = null,
    onOpenRemoteHost: (() -> Unit)? = null,
) {
    var versionDetailsOrigin by remember { mutableStateOf<VersionDetailsOrigin?>(null) }
    var bannerFocused by remember { mutableStateOf(false) }
    var hostRowFocused by remember { mutableStateOf(false) }
    val bannerFocus = remember { FocusRequester() }
    val hostRowFocus = remember { FocusRequester() }
    val versionTitleFocus = remember { FocusRequester() }
    val versionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val canOpenVersionDetails = interactionPolicy.allows(SettingsInteraction.OPEN_VERSION_DETAILS)

    fun dismissVersionDetails() {
        val origin = versionDetailsOrigin
        scope.launch {
            versionSheetState.hide()
            versionDetailsOrigin = null
            repeat(120) {
                when (origin) {
                    VersionDetailsOrigin.BANNER -> bannerFocus.requestFocus()
                    VersionDetailsOrigin.HOST_ROW -> hostRowFocus.requestFocus()
                    null -> return@launch
                }
                withFrameNanos { }
                if (
                    (origin == VersionDetailsOrigin.BANNER && bannerFocused) ||
                    (origin == VersionDetailsOrigin.HOST_ROW && hostRowFocused)
                ) return@launch
            }
        }
    }

    LazyColumn(
        modifier = Modifier.testTag("settings-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 18.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null)
                }
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        "Developer edition",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (providerProfile == null && state.transport.phase == SecureTransportUiPhase.VERSION_MISMATCH) {
            item {
                WarningBanner(
                    title = "Host update required",
                    body = "App uses Wire v${CoreProtocol.PROTOCOL_VERSION}. The Host reported an incompatible protocol.",
                    action = "Details",
                    actionModifier = Modifier
                        .testTag("action-OpenVersionDetails")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_VERSION_DETAILS)
                        .focusRequester(bannerFocus)
                        .onFocusChanged { bannerFocused = it.isFocused }
                        .focusable(),
                    onClick = if (canOpenVersionDetails) {
                        { versionDetailsOrigin = VersionDetailsOrigin.BANNER }
                    } else null,
                )
            }
        }
        if (providerProfile == null) {
            item {
                SettingsSection("Host") {
                SettingsRow(
                    icon = Icons.Outlined.Computer,
                    title = state.transport.hostAlias ?: "Self-hosted Pi Host",
                    detail = connectionDetail(state.transport.phase),
                    value = connectionValue(state.transport.phase),
                    modifier = Modifier
                        .testTag("action-OpenVersionDetails")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_VERSION_DETAILS)
                        .focusRequester(hostRowFocus)
                        .onFocusChanged { hostRowFocused = it.isFocused }
                        .focusable(),
                    onClick = if (
                        state.transport.phase == SecureTransportUiPhase.VERSION_MISMATCH && canOpenVersionDetails
                    ) {
                        { versionDetailsOrigin = VersionDetailsOrigin.HOST_ROW }
                    } else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.transport.phase == SecureTransportUiPhase.OFFLINE) {
                    SettingsRow(
                        icon = Icons.Outlined.Refresh,
                        title = "Retry connection",
                        detail = "Keep the paired Host and cached tasks",
                        modifier = Modifier
                            .testTag("action-RetryConnection")
                            .contractAction(interactionPolicy, SettingsInteraction.RETRY_CONNECTION),
                        onClick = interactionPolicy.onAllowed(SettingsInteraction.RETRY_CONNECTION) {
                            onAction(SettingsAction.RetryConnection)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsRow(
                    icon = Icons.Outlined.WarningAmber,
                    title = "Unpair Host",
                    detail = "Removes this phone’s credential and local Host data",
                    modifier = Modifier
                        .testTag("action-UnpairHost")
                        .contractAction(interactionPolicy, SettingsInteraction.UNPAIR_HOST),
                    danger = true,
                    onClick = interactionPolicy.onAllowed(SettingsInteraction.UNPAIR_HOST) {
                        onAction(SettingsAction.UnpairHost)
                    },
                )
                }
            }
        } else {
            item {
                SettingsSection("Provider") {
                    SettingsRow(
                        icon = Icons.Outlined.BugReport,
                        title = providerProfile.displayName,
                        detail = "Encrypted with Android Keystore · runs Pi on this phone",
                        value = "Manage",
                        modifier = Modifier.testTag("action-OpenProvider"),
                        onClick = onOpenProviderSetup,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow(
                        icon = Icons.Outlined.Description,
                        title = "Model",
                        detail = "Used by the phone-local Pi runtime",
                        value = providerProfile.modelId,
                    )
                }
            }
        }
        if (providerProfile == null) {
            item {
                SettingsSection("Agent") {
                when (val profile = state.agentProfile) {
                    AgentProfileState.Loading -> SettingsRow(Icons.Outlined.BugReport, "Host profile", "Loading from Host", value = "Read-only")
                    is AgentProfileState.Error -> SettingsRow(
                        Icons.Outlined.Refresh,
                        "Host profile unavailable",
                        profile.message,
                        modifier = Modifier
                            .testTag("action-RetryAgentDefaults")
                            .contractAction(interactionPolicy, SettingsInteraction.RETRY_AGENT_DEFAULTS),
                        value = "Retry",
                        onClick = interactionPolicy.onAllowed(SettingsInteraction.RETRY_AGENT_DEFAULTS) {
                            onAction(SettingsAction.RetryAgentProfile)
                        },
                    )
                    is AgentProfileState.Ready -> {
                        SettingsRow(Icons.Outlined.BugReport, "Provider", "Configured on Host", value = profile.profile.provider)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(Icons.Outlined.Description, "Model", "Configured on Host", value = profile.profile.model)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(Icons.Outlined.Bolt, "Thinking", "Configured on Host · read-only", value = profile.profile.thinking)
                    }
                }
                }
            }
        }
        item {
            SettingsSection("Appearance") {
                AppearanceChoices(state.appearance, interactionPolicy) {
                    onAction(SettingsAction.SelectAppearance(it))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    Icons.Outlined.SettingsBrightness,
                    "Reduced motion",
                    "Follows Android system accessibility settings",
                    modifier = Modifier
                        .testTag("action-OpenSystemAccessibility")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_SYSTEM_ACCESSIBILITY),
                    value = "System",
                    onClick = interactionPolicy.onAllowed(SettingsInteraction.OPEN_SYSTEM_ACCESSIBILITY) {
                        onAction(SettingsAction.OpenSystemAccessibility)
                    },
                )
            }
        }
        item {
            SettingsSection("Mobile capabilities") {
                val extensionsEnabled = onOpenExtensions != null &&
                    interactionPolicy.allows(SettingsInteraction.OPEN_EXTENSIONS)
                SettingsRow(
                    icon = Icons.Outlined.Extension,
                    title = "Extensions",
                    detail = "Skills and built-in phone-local capabilities",
                    value = if (extensionsEnabled) "Manage" else "Unavailable",
                    modifier = Modifier
                        .testTag("action-OpenExtensions")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_EXTENSIONS),
                    onClick = if (extensionsEnabled) onOpenExtensions else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val filesEnabled = onOpenMobileFiles != null &&
                    interactionPolicy.allows(SettingsInteraction.OPEN_MOBILE_FILES)
                SettingsRow(
                    icon = if (filesEnabled) Icons.Outlined.Folder else Icons.Outlined.FolderOff,
                    title = "Mobile files",
                    detail = if (filesEnabled) {
                        "Choose and manage Android SAF folders"
                    } else {
                        "Available for folders you explicitly authorize"
                    },
                    value = if (filesEnabled) "Manage" else "Unavailable",
                    modifier = Modifier
                        .testTag("action-OpenMobileFiles")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_MOBILE_FILES),
                    onClick = if (filesEnabled) onOpenMobileFiles else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val capabilitiesEnabled = onOpenDeviceCapabilities != null &&
                    interactionPolicy.allows(SettingsInteraction.OPEN_DEVICE_CAPABILITIES)
                SettingsRow(
                    icon = Icons.Outlined.Security,
                    title = "Device capabilities",
                    detail = "Live Android access and availability",
                    value = if (capabilitiesEnabled) "Manage" else "Unavailable",
                    modifier = Modifier
                        .testTag("action-OpenDeviceCapabilities")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_DEVICE_CAPABILITIES),
                    onClick = if (capabilitiesEnabled) onOpenDeviceCapabilities else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(Icons.Outlined.PhotoLibrary, "Photos", "Not included in this phase", value = "Unavailable")
            }
        }
        item {
            SettingsSection("Diagnostics") {
                when (val diagnostics = state.diagnostics) {
                    DiagnosticsState.Idle -> SettingsRow(
                        Icons.Outlined.BugReport,
                        "Export diagnostics",
                        "Sanitized app, Host phase, and version metadata only",
                        modifier = Modifier
                            .testTag("action-ExportDiagnostics")
                            .contractAction(interactionPolicy, SettingsInteraction.EXPORT_DIAGNOSTICS),
                        onClick = interactionPolicy.onAllowed(SettingsInteraction.EXPORT_DIAGNOSTICS) {
                            onAction(SettingsAction.ExportDiagnostics)
                        },
                    )
                    DiagnosticsState.Exporting -> SettingsRow(
                        Icons.Outlined.BugReport,
                        "Cancel diagnostics export",
                        "Excludes tokens, URIs, and file contents",
                        modifier = Modifier
                            .testTag("action-CancelDiagnosticsExport")
                            .contractAction(interactionPolicy, SettingsInteraction.CANCEL_DIAGNOSTICS_EXPORT),
                        value = "Working",
                        onClick = interactionPolicy.onAllowed(SettingsInteraction.CANCEL_DIAGNOSTICS_EXPORT) {
                            onAction(SettingsAction.CancelDiagnosticsExport)
                        },
                    )
                    is DiagnosticsState.Error -> SettingsRow(
                        Icons.Outlined.Refresh,
                        "Diagnostics unavailable",
                        diagnostics.message,
                        modifier = Modifier
                            .testTag("action-ExportDiagnostics")
                            .contractAction(interactionPolicy, SettingsInteraction.EXPORT_DIAGNOSTICS),
                        value = "Retry",
                        onClick = interactionPolicy.onAllowed(SettingsInteraction.EXPORT_DIAGNOSTICS) {
                            onAction(SettingsAction.ExportDiagnostics)
                        },
                    )
                    is DiagnosticsState.Ready -> {
                        SettingsRow(
                            Icons.Outlined.BugReport,
                            "Share diagnostics",
                            "Expires after 15 minutes; excludes tokens, URIs, and file contents",
                            modifier = Modifier
                                .testTag("action-ShareDiagnostics")
                                .contractAction(interactionPolicy, SettingsInteraction.SHARE_DIAGNOSTICS),
                            value = "Share",
                            onClick = interactionPolicy.onAllowed(SettingsInteraction.SHARE_DIAGNOSTICS) {
                                onAction(SettingsAction.ShareDiagnostics)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            Icons.Outlined.WarningAmber,
                            "Delete diagnostics archive",
                            "Removes the temporary local export",
                            modifier = Modifier
                                .testTag("action-DeleteDiagnosticsArchive")
                                .contractAction(interactionPolicy, SettingsInteraction.DELETE_DIAGNOSTICS_ARCHIVE),
                            danger = true,
                            onClick = interactionPolicy.onAllowed(SettingsInteraction.DELETE_DIAGNOSTICS_ARCHIVE) {
                                onAction(SettingsAction.DeleteDiagnostics)
                            },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    Icons.Outlined.Info,
                    "Momoding",
                    "Independent open-source project; not an official OpenAI app",
                    value = BuildConfig.VERSION_NAME,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(Icons.Outlined.Description, "Source revision", "Current local build", value = BuildConfig.SOURCE_REVISION.take(12))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(Icons.Outlined.Description, "License", "Open-source license", value = "MIT")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    Icons.Outlined.Description,
                    "Source repository",
                    "Local checkout; public URL is assigned at OSS Alpha",
                    value = "Local",
                )
            }
        }
        item {
            SettingsSection("Experimental") {
                if (onOpenRemoteHost != null) {
                    SettingsRow(
                        Icons.Outlined.Computer,
                        "Remote Pi Host",
                        "Optional debug-only remote runtime",
                        value = "Open",
                        onClick = onOpenRemoteHost,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (versionDetailsOrigin != null) {
        ModalBottomSheet(
            sheetState = versionSheetState,
            onDismissRequest = ::dismissVersionDetails,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Wire version mismatch",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.focusRequester(versionTitleFocus).focusable().semantics { heading() },
                )
                LaunchedEffect(Unit) { versionTitleFocus.requestFocus() }
                Text(
                    "The App and Host must use the same Wire contract before tasks can run. Update one side, then reconnect.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VersionPair("App Wire", "v${CoreProtocol.PROTOCOL_VERSION}")
                VersionPair("Pi runtime", CoreProtocol.PI_VERSION)
                Button(
                    onClick = { onAction(SettingsAction.ExportDiagnostics) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("version-export")
                        .contractAction(interactionPolicy, SettingsInteraction.EXPORT_DIAGNOSTICS),
                    enabled = interactionPolicy.allows(SettingsInteraction.EXPORT_DIAGNOSTICS),
                ) { Text("Export diagnostics") }
                OutlinedButton(
                    onClick = { onAction(SettingsAction.UnpairHost) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("version-unpair")
                        .contractAction(interactionPolicy, SettingsInteraction.UNPAIR_HOST),
                    enabled = interactionPolicy.allows(SettingsInteraction.UNPAIR_HOST),
                ) { Text("Unpair Host") }
                OutlinedButton(
                    onClick = ::dismissVersionDetails,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("version-close"),
                ) { Text("Close") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) { content() }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val actionModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    val rowModifier = modifier
            .fillMaxWidth()
            .then(actionModifier)
            .heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    val iconTint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    if (largeFont) {
        Column(modifier = rowModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = iconTint)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onClick != null) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp),
                )
            }
        }
    } else {
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (onClick != null) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppearanceChoices(
    selectedMode: AppearanceMode,
    interactionPolicy: SettingsInteractionPolicy,
    onSelect: (AppearanceMode) -> Unit,
) {
    val rows = listOf(
        Triple(AppearanceMode.SYSTEM, Icons.Outlined.SettingsBrightness, "System"),
        Triple(AppearanceMode.LIGHT, Icons.Outlined.LightMode, "Light"),
        Triple(AppearanceMode.DARK, Icons.Outlined.DarkMode, "Dark"),
    )
    Column {
        rows.forEachIndexed { index, (mode, icon, label) ->
            val interaction = when (mode) {
                AppearanceMode.SYSTEM -> SettingsInteraction.SELECT_SYSTEM
                AppearanceMode.LIGHT -> SettingsInteraction.SELECT_LIGHT
                AppearanceMode.DARK -> SettingsInteraction.SELECT_DARK
            }
            SettingsRow(
                icon = icon,
                title = label,
                detail = if (mode == selectedMode) "Selected" else "Use $label appearance",
                value = if (mode == selectedMode) "Selected" else null,
                modifier = Modifier.semantics {
                    selected = mode == selectedMode
                    stateDescription = if (mode == selectedMode) "Selected" else "Not selected"
                }
                    .testTag("action-Select${label}")
                    .contractAction(interactionPolicy, interaction),
                onClick = interactionPolicy.onAllowed(interaction) { onSelect(mode) },
            )
            if (index != rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun WarningBanner(
    title: String,
    body: String,
    action: String,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val bannerModifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
            .padding(12.dp)
    if (largeFont) {
        Column(modifier = bannerModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(body, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onClick != null) {
                OutlinedButton(onClick = onClick, modifier = actionModifier.heightIn(min = 48.dp)) { Text(action) }
            }
        }
    } else {
        Row(
            modifier = bannerModifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (onClick != null) {
                OutlinedButton(onClick = onClick, modifier = actionModifier.heightIn(min = 48.dp)) { Text(action) }
            }
        }
    }
}

private inline fun SettingsInteractionPolicy.onAllowed(
    interaction: SettingsInteraction,
    crossinline action: () -> Unit,
): (() -> Unit)? = if (allows(interaction)) {
    { action() }
} else null

@Composable
private fun VersionPair(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun connectionDetail(phase: SecureTransportUiPhase): String = when (phase) {
    SecureTransportUiPhase.CONNECTING -> "Connecting to the paired Host"
    SecureTransportUiPhase.AUTHENTICATING -> "Authenticating this device"
    SecureTransportUiPhase.SYNCHRONIZING -> "Synchronizing task metadata"
    SecureTransportUiPhase.READY -> "Connected · Pi ${CoreProtocol.PI_VERSION}"
    SecureTransportUiPhase.OFFLINE -> "Paired cache available · Host offline"
    SecureTransportUiPhase.VERSION_MISMATCH -> "App and Host Wire contracts differ"
    SecureTransportUiPhase.ERROR -> "Connection blocked by a local or TLS error"
    else -> "Paired Host"
}

private fun connectionValue(phase: SecureTransportUiPhase): String = when (phase) {
    SecureTransportUiPhase.READY -> "Online"
    SecureTransportUiPhase.OFFLINE -> "Offline"
    SecureTransportUiPhase.VERSION_MISMATCH -> "Blocked"
    SecureTransportUiPhase.ERROR -> "Error"
    else -> "Connecting"
}
