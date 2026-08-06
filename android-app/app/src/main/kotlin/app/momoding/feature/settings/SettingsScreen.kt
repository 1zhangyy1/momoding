package app.momoding.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.wire.P1aProtocol
import app.momoding.BuildConfig
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.ui.components.MomodingMark
import app.momoding.ui.components.ProductTopBar
import app.momoding.ui.theme.LocalMomodingBrandColors
import kotlinx.coroutines.launch

private enum class VersionDetailsOrigin { BANNER, HOST_ROW }
private enum class SettingsPage(val title: String) {
    ROOT("Settings"),
    HELP("Help & diagnostics"),
    ABOUT("About Momoding"),
    ADVANCED("Advanced"),
}

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
    localProviderName: String? = providerProfile?.displayName,
    localProviderModelId: String? = providerProfile?.modelId,
    onOpenProviderSetup: (() -> Unit)? = null,
    onOpenRemoteHost: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    var versionDetailsOrigin by remember { mutableStateOf<VersionDetailsOrigin?>(null) }
    var bannerFocused by remember { mutableStateOf(false) }
    var hostRowFocused by remember { mutableStateOf(false) }
    val bannerFocus = remember { FocusRequester() }
    val hostRowFocus = remember { FocusRequester() }
    val versionTitleFocus = remember { FocusRequester() }
    val versionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val canOpenVersionDetails = interactionPolicy.allows(SettingsInteraction.OPEN_VERSION_DETAILS)
    val navigateBack: () -> Unit = {
        if (page == SettingsPage.ROOT) {
            onBack?.invoke()
        } else {
            page = SettingsPage.ROOT
        }
        Unit
    }

    BackHandler(enabled = page != SettingsPage.ROOT) {
        page = SettingsPage.ROOT
    }

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

    Column(modifier = Modifier.fillMaxSize()) {
        ProductTopBar(
            title = page.title,
            onBack = if (page == SettingsPage.ROOT) onBack else navigateBack,
            backModifier = Modifier.structuralAction("NavigateBack"),
        )
        when (page) {
            SettingsPage.ROOT -> SettingsRootPage(
                state = state,
                contentPadding = contentPadding,
                interactionPolicy = interactionPolicy,
                localProviderName = localProviderName,
                localProviderModelId = localProviderModelId,
                onOpenProviderSetup = onOpenProviderSetup,
                onOpenAppearance = { appearanceOpen = true },
                onOpenPhoneAccess = when {
                    onOpenDeviceCapabilities != null &&
                        interactionPolicy.allows(SettingsInteraction.OPEN_DEVICE_CAPABILITIES) -> {
                        SettingsNavigationAction(
                            modifier = Modifier
                                .testTag("action-OpenDeviceCapabilities")
                                .contractAction(
                                    interactionPolicy,
                                    SettingsInteraction.OPEN_DEVICE_CAPABILITIES,
                                ),
                            onClick = onOpenDeviceCapabilities,
                        )
                    }
                    onOpenMobileFiles != null &&
                        interactionPolicy.allows(SettingsInteraction.OPEN_MOBILE_FILES) -> {
                        SettingsNavigationAction(
                            modifier = Modifier
                                .testTag("action-OpenMobileFiles")
                                .contractAction(
                                    interactionPolicy,
                                    SettingsInteraction.OPEN_MOBILE_FILES,
                                ),
                            onClick = onOpenMobileFiles,
                        )
                    }
                    else -> null
                },
                onOpenExtensions = onOpenExtensions?.takeIf {
                    interactionPolicy.allows(SettingsInteraction.OPEN_EXTENSIONS)
                }?.let {
                    SettingsNavigationAction(
                        modifier = Modifier
                            .testTag("action-OpenExtensions")
                            .contractAction(interactionPolicy, SettingsInteraction.OPEN_EXTENSIONS),
                        onClick = it,
                    )
                },
                onOpenHelp = { page = SettingsPage.HELP },
                onOpenAbout = { page = SettingsPage.ABOUT },
                onOpenAdvanced = { page = SettingsPage.ADVANCED },
                bannerFocus = bannerFocus,
                onBannerFocusChanged = { bannerFocused = it },
                canOpenVersionDetails = canOpenVersionDetails,
                onOpenVersionDetails = { versionDetailsOrigin = VersionDetailsOrigin.BANNER },
            )
            SettingsPage.HELP -> SettingsHelpPage(
                state = state,
                contentPadding = contentPadding,
                interactionPolicy = interactionPolicy,
                onAction = onAction,
            )
            SettingsPage.ABOUT -> SettingsAboutPage(contentPadding)
            SettingsPage.ADVANCED -> SettingsAdvancedPage(
                state = state,
                contentPadding = contentPadding,
                interactionPolicy = interactionPolicy,
                localProviderName = localProviderName,
                localProviderModelId = localProviderModelId,
                onAction = onAction,
                onOpenRemoteHost = onOpenRemoteHost,
                hostRowFocus = hostRowFocus,
                onHostRowFocusChanged = { hostRowFocused = it },
                canOpenVersionDetails = canOpenVersionDetails,
                onOpenVersionDetails = { versionDetailsOrigin = VersionDetailsOrigin.HOST_ROW },
                onRequestUnpair = { confirmUnpair = true },
            )
        }
    }

    if (appearanceOpen) {
        ModalBottomSheet(
            onDismissRequest = { appearanceOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AppearanceSheet(
                selectedMode = state.appearance,
                interactionPolicy = interactionPolicy,
                onSelect = { mode ->
                    onAction(SettingsAction.SelectAppearance(mode))
                    appearanceOpen = false
                },
            )
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
                VersionPair("App Wire", "v${P1aProtocol.PROTOCOL_VERSION}")
                VersionPair("Pi runtime", P1aProtocol.PI_VERSION)
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
                    onClick = {
                        versionDetailsOrigin = null
                        confirmUnpair = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("version-unpair")
                        .structuralAction("RequestUnpairHost"),
                    enabled = interactionPolicy.allows(SettingsInteraction.UNPAIR_HOST),
                ) { Text("Unpair Host") }
                OutlinedButton(
                    onClick = ::dismissVersionDetails,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("version-close")
                        .structuralAction("CloseVersionDetails"),
                ) { Text("Close") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Unpair Remote Host?") },
            text = {
                Text(
                    "This removes the Host credential and cached Host details from this phone. You’ll need to pair again before using the Remote Host.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        onAction(SettingsAction.UnpairHost)
                    },
                    modifier = Modifier
                        .testTag("action-UnpairHost")
                        .contractAction(interactionPolicy, SettingsInteraction.UNPAIR_HOST),
                    enabled = interactionPolicy.allows(SettingsInteraction.UNPAIR_HOST),
                ) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmUnpair = false },
                    modifier = Modifier.structuralAction("CancelUnpairHost"),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

}

private data class SettingsNavigationAction(
    val modifier: Modifier,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsRootPage(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    interactionPolicy: SettingsInteractionPolicy,
    localProviderName: String?,
    localProviderModelId: String?,
    onOpenProviderSetup: (() -> Unit)?,
    onOpenAppearance: () -> Unit,
    onOpenPhoneAccess: SettingsNavigationAction?,
    onOpenExtensions: SettingsNavigationAction?,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAdvanced: () -> Unit,
    bannerFocus: FocusRequester,
    onBannerFocusChanged: (Boolean) -> Unit,
    canOpenVersionDetails: Boolean,
    onOpenVersionDetails: () -> Unit,
) {
    SettingsList(contentPadding) {
        if (
            localProviderName == null &&
            state.transport.phase == SecureTransportUiPhase.VERSION_MISMATCH
        ) {
            item("version-warning") {
                WarningBanner(
                    title = "Remote Host needs an update",
                    body = "Momoding can’t run remote tasks until the app and Host use compatible versions.",
                    action = "Details",
                    actionModifier = Modifier
                        .testTag("action-OpenVersionDetails")
                        .contractAction(interactionPolicy, SettingsInteraction.OPEN_VERSION_DETAILS)
                        .focusRequester(bannerFocus)
                        .onFocusChanged { onBannerFocusChanged(it.isFocused) }
                        .focusable(),
                    onClick = onOpenVersionDetails.takeIf { canOpenVersionDetails },
                )
            }
        }
        item("model-provider") {
            ModelProviderCard(
                state = state,
                localProviderName = localProviderName,
                localProviderModelId = localProviderModelId,
                onClick = when {
                    localProviderName != null && onOpenProviderSetup != null -> onOpenProviderSetup
                    localProviderName == null -> onOpenAdvanced
                    else -> null
                },
            )
        }
        item("experience") {
            SettingsSection("Experience") {
                SettingsRow(
                    icon = Icons.Outlined.SettingsBrightness,
                    title = "Appearance",
                    detail = "Choose how Momoding looks",
                    value = state.appearance.label(),
                    modifier = Modifier
                        .testTag("settings-open-appearance")
                        .structuralAction("OpenAppearance"),
                    onClick = onOpenAppearance,
                )
            }
        }
        item("access") {
            SettingsSection("Access") {
                SettingsRow(
                    icon = Icons.Outlined.Security,
                    title = "Phone access",
                    detail = if (onOpenPhoneAccess != null) {
                        "Files, photos and permissions"
                    } else {
                        "Not available in this build"
                    },
                    value = if (onOpenPhoneAccess != null) "Review" else null,
                    modifier = onOpenPhoneAccess?.modifier ?: when {
                        interactionPolicy.allows(
                            SettingsInteraction.OPEN_DEVICE_CAPABILITIES,
                        ) -> {
                            Modifier
                                .testTag("action-OpenDeviceCapabilities")
                                .contractAction(
                                    interactionPolicy,
                                    SettingsInteraction.OPEN_DEVICE_CAPABILITIES,
                                )
                        }
                        interactionPolicy.allows(SettingsInteraction.OPEN_MOBILE_FILES) -> {
                            Modifier
                                .testTag("action-OpenMobileFiles")
                                .contractAction(
                                    interactionPolicy,
                                    SettingsInteraction.OPEN_MOBILE_FILES,
                                )
                        }
                        else -> Modifier
                    },
                    onClick = onOpenPhoneAccess?.onClick,
                )
                onOpenExtensions?.let { action ->
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Extension,
                        title = "Skills & tools",
                        detail = "Manage what Momoding can use",
                        value = "Manage",
                        modifier = action.modifier,
                        onClick = action.onClick,
                    )
                }
            }
        }
        item("support") {
            SettingsSection("Support") {
                SettingsRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Help & diagnostics",
                    detail = state.diagnostics.rootSummary(),
                    modifier = Modifier
                        .testTag("settings-open-help")
                        .structuralAction("OpenHelp"),
                    onClick = onOpenHelp,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "About Momoding",
                    detail = "Version ${BuildConfig.VERSION_NAME}",
                    modifier = Modifier
                        .testTag("settings-open-about")
                        .structuralAction("OpenAbout"),
                    onClick = onOpenAbout,
                )
            }
        }
        item("advanced") {
            SettingsRow(
                icon = Icons.Outlined.Computer,
                title = "Advanced",
                detail = if (localProviderName == null) {
                    "Remote Host and runtime details"
                } else {
                    "Runtime and developer options"
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .testTag("settings-open-advanced")
                    .structuralAction("OpenAdvanced"),
                onClick = onOpenAdvanced,
            )
        }
    }
}

@Composable
private fun ModelProviderCard(
    state: SettingsUiState,
    localProviderName: String?,
    localProviderModelId: String?,
    onClick: (() -> Unit)?,
) {
    val brand = LocalMomodingBrandColors.current
    val detail = when {
        localProviderName != null && localProviderModelId != null -> {
            "$localProviderName · ${localProviderModelId.substringAfterLast('/')}"
        }
        state.agentProfile is AgentProfileState.Ready -> {
            val profile = state.agentProfile.profile
            "${profile.provider} · ${profile.model.substringAfterLast('/')}"
        }
        state.agentProfile is AgentProfileState.Error -> "Remote model unavailable"
        else -> "Loading remote model…"
    }
    val context = if (localProviderName != null) {
        "Tasks run on this phone"
    } else {
        "Managed by ${state.transport.hostAlias ?: "Remote Host"}"
    }
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings-model-provider")
            .structuralAction("OpenModelProvider"),
        shape = RoundedCornerShape(20.dp),
        color = brand.soft,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(brand.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = brand.primary,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Model & provider",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    context,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsHelpPage(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    interactionPolicy: SettingsInteractionPolicy,
    onAction: (SettingsAction) -> Unit,
) {
    SettingsList(contentPadding) {
        item("help-intro") {
            SettingsIntro(
                title = "Something not working?",
                body = "Create a private diagnostics file with app status and version details. It never includes tokens, file contents or folder locations.",
            )
        }
        item("diagnostics") {
            SettingsSection("Diagnostics") {
                DiagnosticsRow(state.diagnostics, interactionPolicy, onAction)
            }
        }
        item("accessibility") {
            SettingsSection("Android") {
                SettingsRow(
                    icon = Icons.Outlined.SettingsBrightness,
                    title = "System accessibility",
                    detail = "Text size, motion and TalkBack are managed by Android",
                    modifier = Modifier
                        .testTag("action-OpenSystemAccessibility")
                        .contractAction(
                            interactionPolicy,
                            SettingsInteraction.OPEN_SYSTEM_ACCESSIBILITY,
                        ),
                    onClick = interactionPolicy.onAllowed(
                        SettingsInteraction.OPEN_SYSTEM_ACCESSIBILITY,
                    ) {
                        onAction(SettingsAction.OpenSystemAccessibility)
                    },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsRow(
    diagnostics: DiagnosticsState,
    interactionPolicy: SettingsInteractionPolicy,
    onAction: (SettingsAction) -> Unit,
) {
    when (diagnostics) {
        DiagnosticsState.Idle -> SettingsRow(
            Icons.Outlined.BugReport,
            "Export diagnostics",
            "App status and version only—never tokens or file contents",
            modifier = Modifier
                .testTag("action-ExportDiagnostics")
                .contractAction(interactionPolicy, SettingsInteraction.EXPORT_DIAGNOSTICS),
            value = "Export",
            showChevron = false,
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
                .contractAction(
                    interactionPolicy,
                    SettingsInteraction.CANCEL_DIAGNOSTICS_EXPORT,
                ),
            value = "Working",
            showChevron = false,
            onClick = interactionPolicy.onAllowed(
                SettingsInteraction.CANCEL_DIAGNOSTICS_EXPORT,
            ) {
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
            showChevron = false,
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
                    .contractAction(
                        interactionPolicy,
                        SettingsInteraction.SHARE_DIAGNOSTICS,
                    ),
                value = "Share",
                showChevron = false,
                onClick = interactionPolicy.onAllowed(SettingsInteraction.SHARE_DIAGNOSTICS) {
                    onAction(SettingsAction.ShareDiagnostics)
                },
            )
            SettingsDivider()
            SettingsRow(
                Icons.Outlined.WarningAmber,
                "Delete diagnostics file",
                "Removes the temporary file from this phone",
                modifier = Modifier
                    .testTag("action-DeleteDiagnosticsArchive")
                    .contractAction(
                        interactionPolicy,
                        SettingsInteraction.DELETE_DIAGNOSTICS_ARCHIVE,
                    ),
                danger = true,
                value = "Delete",
                showChevron = false,
                onClick = interactionPolicy.onAllowed(
                    SettingsInteraction.DELETE_DIAGNOSTICS_ARCHIVE,
                ) {
                    onAction(SettingsAction.DeleteDiagnostics)
                },
            )
        }
    }
}

@Composable
private fun SettingsAboutPage(contentPadding: PaddingValues) {
    SettingsList(contentPadding) {
        item("about-identity") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MomodingMark(size = 58.dp)
                Text(
                    "Momoding",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "A bright little companion that gets things done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("about-details") {
            SettingsSection("About") {
                SettingsRow(
                    Icons.Outlined.Info,
                    "Version",
                    BuildConfig.VERSION_NAME,
                )
                SettingsDivider()
                SettingsRow(
                    Icons.Outlined.Description,
                    "Open-source license",
                    "MIT",
                )
            }
        }
        item("about-independence") {
            SettingsIntro(
                title = "Independent project",
                body = "Momoding is an independent open-source project and is not an official OpenAI app.",
            )
        }
    }
}

@Composable
private fun SettingsAdvancedPage(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    interactionPolicy: SettingsInteractionPolicy,
    localProviderName: String?,
    localProviderModelId: String?,
    onAction: (SettingsAction) -> Unit,
    onOpenRemoteHost: (() -> Unit)?,
    hostRowFocus: FocusRequester,
    onHostRowFocusChanged: (Boolean) -> Unit,
    canOpenVersionDetails: Boolean,
    onOpenVersionDetails: () -> Unit,
    onRequestUnpair: () -> Unit,
) {
    SettingsList(contentPadding) {
        if (localProviderName == null || localProviderModelId == null) {
            item("remote-runtime") {
                SettingsSection("Remote runtime") {
                    SettingsRow(
                        icon = Icons.Outlined.Computer,
                        title = state.transport.hostAlias ?: "Remote Host",
                        detail = connectionDetail(state.transport.phase),
                        value = connectionValue(state.transport.phase),
                        modifier = Modifier
                            .testTag("action-OpenVersionDetails")
                            .contractAction(
                                interactionPolicy,
                                SettingsInteraction.OPEN_VERSION_DETAILS,
                            )
                            .focusRequester(hostRowFocus)
                            .onFocusChanged { onHostRowFocusChanged(it.isFocused) }
                            .focusable(),
                        onClick = if (
                            state.transport.phase == SecureTransportUiPhase.VERSION_MISMATCH &&
                            canOpenVersionDetails
                        ) {
                            onOpenVersionDetails
                        } else {
                            null
                        },
                    )
                    if (state.transport.phase == SecureTransportUiPhase.OFFLINE) {
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Refresh,
                            title = "Retry connection",
                            detail = "Keep the paired Host and cached tasks",
                            modifier = Modifier
                                .testTag("action-RetryConnection")
                                .contractAction(
                                    interactionPolicy,
                                    SettingsInteraction.RETRY_CONNECTION,
                                ),
                            value = "Retry",
                            showChevron = false,
                            onClick = interactionPolicy.onAllowed(
                                SettingsInteraction.RETRY_CONNECTION,
                            ) {
                                onAction(SettingsAction.RetryConnection)
                            },
                        )
                    }
                }
            }
            item("remote-model") {
                SettingsSection("Host model") {
                    when (val profile = state.agentProfile) {
                        AgentProfileState.Loading -> SettingsRow(
                            Icons.Outlined.BugReport,
                            "Host profile",
                            "Loading from Remote Host",
                            value = "Read-only",
                        )
                        is AgentProfileState.Error -> SettingsRow(
                            Icons.Outlined.Refresh,
                            "Host profile unavailable",
                            profile.message,
                            modifier = Modifier
                                .testTag("action-RetryAgentDefaults")
                                .contractAction(
                                    interactionPolicy,
                                    SettingsInteraction.RETRY_AGENT_DEFAULTS,
                                ),
                            value = "Retry",
                            showChevron = false,
                            onClick = interactionPolicy.onAllowed(
                                SettingsInteraction.RETRY_AGENT_DEFAULTS,
                            ) {
                                onAction(SettingsAction.RetryAgentProfile)
                            },
                        )
                        is AgentProfileState.Ready -> SettingsRow(
                            Icons.Outlined.Bolt,
                            profile.profile.model,
                            "${profile.profile.provider} · Thinking ${profile.profile.thinking}",
                            value = "Host managed",
                        )
                    }
                }
            }
            item("remote-danger") {
                SettingsSection("Danger zone") {
                    SettingsRow(
                        icon = Icons.Outlined.WarningAmber,
                        title = "Unpair Remote Host",
                        detail = "Removes this phone’s credential and local Host data",
                        modifier = Modifier
                            .testTag("settings-request-unpair")
                            .structuralAction("RequestUnpairHost"),
                        danger = true,
                        value = "Unpair",
                        showChevron = false,
                        onClick = onRequestUnpair.takeIf {
                            interactionPolicy.allows(SettingsInteraction.UNPAIR_HOST)
                        },
                    )
                }
            }
        } else {
            item("local-runtime") {
                SettingsSection("Runtime") {
                    SettingsRow(
                        Icons.Outlined.Bolt,
                        "Runs on this phone",
                        "$localProviderName · $localProviderModelId",
                    )
                }
            }
        }
        onOpenRemoteHost?.let { openRemoteHost ->
            item("experimental") {
                SettingsSection("Experimental") {
                    SettingsRow(
                        Icons.Outlined.Computer,
                        "Remote Host",
                        "Run tasks on an optional paired computer",
                        value = "Open",
                        modifier = Modifier.structuralAction("OpenRemoteHost"),
                        onClick = openRemoteHost,
                    )
                }
            }
        }
        if (BuildConfig.DEBUG) {
            item("developer") {
                SettingsSection("Developer") {
                    SettingsRow(
                        Icons.Outlined.Description,
                        "Source revision",
                        if (BuildConfig.SOURCE_DIRTY) {
                            "Dirty local build"
                        } else {
                            "Clean local build"
                        },
                        value = BuildConfig.SOURCE_REVISION.take(12),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceSheet(
    selectedMode: AppearanceMode,
    interactionPolicy: SettingsInteractionPolicy,
    onSelect: (AppearanceMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Appearance",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        Text(
            "Choose how Momoding looks on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        AppearanceChoices(selectedMode, interactionPolicy, onSelect)
    }
}

@Composable
private fun SettingsList(
    contentPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 18.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun SettingsIntro(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(18.dp),
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
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
    showChevron: Boolean = onClick != null,
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
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = titleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showChevron) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showChevron) {
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
    val brand = LocalMomodingBrandColors.current
    val rows = listOf(
        Triple(AppearanceMode.SYSTEM, Icons.Outlined.SettingsBrightness, "System"),
        Triple(AppearanceMode.LIGHT, Icons.Outlined.LightMode, "Light"),
        Triple(AppearanceMode.DARK, Icons.Outlined.DarkMode, "Dark"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (mode, icon, label) ->
            val interaction = when (mode) {
                AppearanceMode.SYSTEM -> SettingsInteraction.SELECT_SYSTEM
                AppearanceMode.LIGHT -> SettingsInteraction.SELECT_LIGHT
                AppearanceMode.DARK -> SettingsInteraction.SELECT_DARK
            }
            val selected = mode == selectedMode
            val allowed = interactionPolicy.allows(interaction)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) brand.soft else MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                    .then(
                        if (allowed) {
                            Modifier.selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
                    .semantics {
                        this.selected = selected
                        stateDescription = if (selected) "Selected" else "Not selected"
                    }
                    .testTag("action-Select${label}")
                    .contractAction(interactionPolicy, interaction),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) brand.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(
                        "Selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = brand.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 50.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
    SecureTransportUiPhase.READY -> "Connected · Pi ${P1aProtocol.PI_VERSION}"
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

private fun AppearanceMode.label(): String = when (this) {
    AppearanceMode.SYSTEM -> "System"
    AppearanceMode.LIGHT -> "Light"
    AppearanceMode.DARK -> "Dark"
}

private fun DiagnosticsState.rootSummary(): String = when (this) {
    DiagnosticsState.Idle -> "Get help or export app diagnostics"
    DiagnosticsState.Exporting -> "Preparing diagnostics…"
    is DiagnosticsState.Ready -> "Diagnostics ready to share"
    is DiagnosticsState.Error -> "Diagnostics need attention"
}
