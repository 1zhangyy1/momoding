package app.momoding.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.momoding.feature.hostgate.HostGateScreen
import app.momoding.feature.files.AuthorizedFoldersAction
import app.momoding.feature.files.AuthorizedFoldersOneShot
import app.momoding.feature.files.AuthorizedFoldersScreen
import app.momoding.feature.files.AuthorizedFoldersViewModel
import app.momoding.feature.extensions.ExtensionsAction
import app.momoding.feature.extensions.ExtensionsOneShot
import app.momoding.feature.extensions.ExtensionsScreen
import app.momoding.feature.extensions.ExtensionsViewModel
import app.momoding.feature.filechanges.FileChangeScreen
import app.momoding.feature.filechanges.FileChangeViewModel
import app.momoding.feature.attention.AttentionIntent
import app.momoding.feature.attention.AttentionOneShot
import app.momoding.feature.attention.AttentionIdentity
import app.momoding.feature.attention.AttentionReturnReason
import app.momoding.feature.attention.AttentionScreen
import app.momoding.feature.attention.AttentionUiState
import app.momoding.feature.attention.AttentionVisibleState
import app.momoding.feature.attention.AttentionViewModel
import app.momoding.core.data.TaskAttentionKind
import app.momoding.feature.newtask.NewTaskAction
import app.momoding.feature.newtask.NewTaskInteraction
import app.momoding.feature.newtask.NewTaskInteractionPolicy
import app.momoding.feature.newtask.NewTaskOneShot
import app.momoding.feature.newtask.NewTaskScreen
import app.momoding.feature.newtask.NewTaskViewModel
import app.momoding.core.attachments.AttachmentFeatureGate
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.photoLibraryPermissionRequest
import app.momoding.feature.outputs.TaskOutputsRoute
import app.momoding.feature.providersetup.ProviderSetupAction
import app.momoding.feature.providersetup.ProviderSetupLoadState
import app.momoding.feature.providersetup.ProviderSetupScreen
import app.momoding.feature.providersetup.ProviderSetupUiState
import app.momoding.feature.providersetup.ProviderSetupViewModel
import app.momoding.feature.settings.SettingsAction
import app.momoding.feature.settings.DeviceCapabilitiesScreen
import app.momoding.feature.settings.SettingsOneShot
import app.momoding.feature.settings.SettingsScreen
import app.momoding.feature.settings.SettingsUiState
import app.momoding.feature.settings.SettingsViewModel
import app.momoding.feature.share.EXTRA_SHARE_RECEIPT_ID
import app.momoding.feature.tasks.TaskHomeAction
import app.momoding.feature.tasks.TaskHomeConnectionState
import app.momoding.feature.tasks.TaskHomeLoadState
import app.momoding.feature.tasks.TaskHomeOneShot
import app.momoding.feature.tasks.TaskHomeScreen
import app.momoding.feature.tasks.TaskHomeUiState
import app.momoding.feature.tasks.TaskHomeViewModel
import app.momoding.feature.taskdetail.TaskDetailOneShot
import app.momoding.feature.taskdetail.TaskDetailScreen
import app.momoding.feature.taskdetail.TaskDetailViewModel
import app.momoding.feature.taskdetail.TASK_DETAIL_TITLE_FOCUS_KEY
import app.momoding.ui.components.MomodingScaffold
import app.momoding.ui.components.TopLevelDestination
import app.momoding.ui.navigation.AttentionRoute
import app.momoding.ui.navigation.AuthorizedFoldersRoute
import app.momoding.ui.navigation.MomodingBottomSheetDismissRegistry
import app.momoding.ui.navigation.MomodingBottomSheetSceneStrategy
import app.momoding.ui.navigation.LocalMomodingBottomSheetContentKey
import app.momoding.ui.navigation.DiffPlaceholderRoute
import app.momoding.ui.navigation.DeviceCapabilitiesRoute
import app.momoding.ui.navigation.ExtensionsRoute
import app.momoding.ui.navigation.FileChangeRoute
import app.momoding.ui.navigation.HostGateRoute
import app.momoding.ui.navigation.NewTaskRoute
import app.momoding.ui.navigation.OutputsPlaceholderRoute
import app.momoding.ui.navigation.ProviderSetupRoute
import app.momoding.ui.navigation.SettingsRoute
import app.momoding.ui.navigation.TaskDetailRoute
import app.momoding.ui.navigation.TaskHomeRoute
import app.momoding.ui.navigation.taskDetailAttentionFocusKey
import app.momoding.ui.theme.MomodingTheme
import app.momoding.ui.theme.resolvesToDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.runtime.withFrameNanos
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as MomodingApplication).container }
    private val externalNewTaskRoute = MutableStateFlow<NewTaskRoute?>(null)
    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            container.runtime,
            container.appearanceStore,
            container.diagnosticsExporter,
            false,
        )
    }
    private val providerSetupViewModel: ProviderSetupViewModel by viewModels {
        ProviderSetupViewModel.Factory(
            container.providerCredentialVault,
            container.openRouterClient,
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        acceptShareNavigation(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.oneShots.collect { oneShot ->
                    when (oneShot) {
                        is SettingsOneShot.ShareDiagnostics -> shareDiagnostics(oneShot)
                        SettingsOneShot.OpenSystemAccessibility -> openSystemAccessibility()
                    }
                }
            }
        }
        setContent {
            val state by settingsViewModel.state.collectAsStateWithLifecycle()
            val providerState by providerSetupViewModel.state.collectAsStateWithLifecycle()
            val sharedRoute by externalNewTaskRoute.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = resolvesToDark(state.appearance, systemDark)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MomodingTheme(
                appearance = state.appearance,
                systemDark = systemDark,
            ) {
                val attentionOwnerFactory: AttentionRouteViewModelFactory = remember(container) {
                    { identity -> container.attentionViewModelFactory(identity) }
                }
                MomodingApp(
                    state = state,
                    onAction = settingsViewModel::dispatch,
                    providerSetupState = providerState,
                    onProviderSetupAction = providerSetupViewModel::dispatch,
                    taskHomeEntry = { padding, onRouteAction, onOneShot ->
                        TaskHomeRouteContent(
                            container = container,
                            modelId = providerState.savedProfile?.modelId
                                ?: providerState.modelId,
                            padding = padding,
                            onRouteAction = onRouteAction,
                            onOneShot = onOneShot,
                        )
                    },
                    newTaskEntry = { route, onOpenTask, onBack, onOpenFullAccessSetup ->
                        NewTaskRouteContent(
                            route = route,
                            container = container,
                            modelId = providerState.savedProfile?.modelId
                                ?: providerState.modelId,
                            onOpenTask = onOpenTask,
                            onBack = onBack,
                            onOpenFullAccessSetup = onOpenFullAccessSetup,
                        )
                    },
                    taskDetailEntry = {
                            route,
                            restoreFocusKey,
                            attentionReturn,
                            onFocusRestored,
                            onAttentionReturnConsumed,
                            onOneShot,
                        ->
                        TaskDetailRouteContent(
                            route = route,
                            container = container,
                            restoreFocusKey = restoreFocusKey,
                            attentionReturn = attentionReturn,
                            onFocusRestored = onFocusRestored,
                            onAttentionReturnConsumed = onAttentionReturnConsumed,
                            onOneShot = onOneShot,
                            modelId = providerState.savedProfile?.modelId
                                ?: providerState.modelId,
                        )
                    },
                    attentionEntry = { route, dismissRegistry, onOneShot ->
                        AttentionRouteContent(
                            route = route,
                            ownerFactory = attentionOwnerFactory,
                            dismissRegistry = dismissRegistry,
                            onOneShot = onOneShot,
                        )
                    },
                    authorizedFoldersEntry = { route, onBack ->
                        AuthorizedFoldersRouteContent(
                            container = container,
                            startPicker = route.startPicker,
                            pickerRunId = route.pickerRunId,
                            onBack = onBack,
                        )
                    },
                    deviceCapabilitiesEntry = { route, onBack, onOpenFolders ->
                        DeviceCapabilitiesRouteContent(
                            container = container,
                            startFullAccessSetup = route.startFullAccessSetup,
                            setupRunId = route.setupRunId,
                            onBack = onBack,
                            onOpenFolders = onOpenFolders,
                        )
                    },
                    extensionsEntry = { onBack ->
                        ExtensionsRouteContent(container, onBack)
                    },
                    fileChangeEntry = { route, onBack ->
                        FileChangeRouteContent(route, container, onBack)
                    },
                    outputsEntry = { taskId, onBack ->
                        TaskOutputsRoute(
                            taskId = taskId,
                            repository = container.fileChangeRepository,
                            folders = container.authorizedFoldersRepository,
                            onBack = onBack,
                        )
                    },
                    externalNewTaskRoute = sharedRoute,
                    onExternalNewTaskConsumed = { externalNewTaskRoute.value = null },
                    initialBackStack = listOf(ProviderSetupRoute(onboarding = true)),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShareNavigation(intent)
    }

    override fun onResume() {
        super.onResume()
        container.androidCapabilityRegistry.refresh()
    }

    private fun acceptShareNavigation(intent: Intent?) {
        val result = container.shareImportCoordinator.completedResult(
            intent?.getStringExtra(EXTRA_SHARE_RECEIPT_ID),
        ) ?: return
        externalNewTaskRoute.value = NewTaskRoute(
            draftId = result.draftId,
            fromAndroidShare = true,
            importNotice = result.notice,
        )
    }

    private fun shareDiagnostics(oneShot: SettingsOneShot.ShareDiagnostics) {
        val intent = runCatching { container.diagnosticsExporter.shareIntent(oneShot.archive) }
            .getOrElse {
                settingsViewModel.dispatch(SettingsAction.OperationFailed("diagnostics", "Diagnostics archive is unavailable or expired."))
                return
            }
        try {
            startActivity(Intent.createChooser(intent, "Share sanitized diagnostics"))
        } catch (_: ActivityNotFoundException) {
            settingsViewModel.dispatch(SettingsAction.OperationFailed("diagnostics", "No compatible share target is available."))
        }
    }

    private fun openSystemAccessibility() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            settingsViewModel.dispatch(SettingsAction.OperationFailed("accessibility", "System accessibility settings are unavailable."))
        }
    }
}

typealias TaskHomeRouteEntry = @Composable (
    PaddingValues,
    (TaskHomeAction) -> Unit,
    (TaskHomeOneShot) -> Unit,
) -> Unit

typealias TaskDetailRouteEntry = @Composable (
    TaskDetailRoute,
    String?,
    AttentionNavigationReturn?,
    () -> Unit,
    () -> Unit,
    (TaskDetailOneShot) -> Unit,
) -> Unit

internal typealias AttentionRouteEntry = @Composable (
    AttentionRoute,
    MomodingBottomSheetDismissRegistry,
    (AttentionOneShot) -> Unit,
) -> Unit

internal typealias AuthorizedFoldersRouteEntry = @Composable (AuthorizedFoldersRoute, () -> Unit) -> Unit
internal typealias DeviceCapabilitiesRouteEntry = @Composable (
    DeviceCapabilitiesRoute,
    () -> Unit,
    (Boolean) -> Unit,
) -> Unit
internal typealias ExtensionsRouteEntry = @Composable (() -> Unit) -> Unit
internal typealias FileChangeRouteEntry = @Composable (FileChangeRoute, () -> Unit) -> Unit
internal typealias OutputsRouteEntry = @Composable (String, () -> Unit) -> Unit

internal typealias AttentionRouteViewModelFactory =
    (AttentionIdentity) -> ViewModelProvider.Factory

private fun AppContainer.attentionViewModelFactory(
    identity: AttentionIdentity,
): ViewModelProvider.Factory = if (phoneLocalAttentionBridge.owns(identity.callId)) {
    AttentionViewModel.PhoneLocalFactory(
        taskId = identity.taskId,
        callId = identity.callId,
        repository = attentionRepository,
        bridge = phoneLocalAttentionBridge,
    )
} else {
    AttentionViewModel.Factory(
        taskId = identity.taskId,
        callId = identity.callId,
        repository = attentionRepository,
        runtime = runtime,
    )
}

data class AttentionNavigationReturn(
    val effectId: String,
    val taskId: String,
    val callId: String,
    val originFocusKey: String,
    val reason: AttentionReturnReason,
) {
    init {
        require(effectId.isNotBlank()) { "Attention return effectId is blank" }
        require(taskId.isNotBlank()) { "Attention return taskId is blank" }
        require(callId.isNotBlank()) { "Attention return callId is blank" }
        require(originFocusKey.isNotBlank()) { "Attention return origin focus key is blank" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomodingApp(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    providerSetupState: ProviderSetupUiState? = null,
    onProviderSetupAction: (ProviderSetupAction) -> Unit = {},
    taskHomeState: TaskHomeUiState = TaskHomeUiState(
        hostAlias = state.transport.hostAlias ?: "Self-hosted Pi Host",
        connection = if (state.transport.phase == app.momoding.core.transport.SecureTransportUiPhase.READY) {
            TaskHomeConnectionState.CONNECTED
        } else {
            TaskHomeConnectionState.UNPAIRED
        },
        loadState = TaskHomeLoadState.READY,
    ),
    onTaskHomeAction: (TaskHomeAction) -> Unit = {},
    taskHomeOneShots: Flow<TaskHomeOneShot> = emptyFlow(),
    taskHomeEntry: TaskHomeRouteEntry? = null,
    newTaskEntry: @Composable (NewTaskRoute, (String) -> Unit, () -> Unit, () -> Unit) -> Unit = { _, _, _, _ ->
        StagePlaceholder("New task", "Task creation host is unavailable.", PaddingValues(24.dp))
    },
    taskDetailEntry: TaskDetailRouteEntry? = null,
    attentionEntry: AttentionRouteEntry? = null,
    authorizedFoldersEntry: AuthorizedFoldersRouteEntry? = null,
    deviceCapabilitiesEntry: DeviceCapabilitiesRouteEntry? = null,
    extensionsEntry: ExtensionsRouteEntry? = null,
    fileChangeEntry: FileChangeRouteEntry? = null,
    outputsEntry: OutputsRouteEntry? = null,
    externalNewTaskRoute: NewTaskRoute? = null,
    onExternalNewTaskConsumed: () -> Unit = {},
    initialBackStack: List<NavKey> = listOf(HostGateRoute),
    onAccessibilityAnnouncement: ((String) -> Unit)? = null,
) {
    val accessibilityView = LocalView.current
    val announceAccessibility = onAccessibilityAnnouncement ?: remember(accessibilityView) {
        { message: String -> accessibilityView.announceCompat(message) }
    }
    val announcedAttentionEffects = remember { LinkedHashSet<String>() }
    val backStack = rememberNavBackStack(*initialBackStack.toTypedArray())
    val bottomSheetDismissRegistry = remember {
        MomodingBottomSheetDismissRegistry()
    }
    val bottomSheetStrategy = remember(bottomSheetDismissRegistry) {
        MomodingBottomSheetSceneStrategy<NavKey>(bottomSheetDismissRegistry)
    }
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val taskHomeSettingsFocus = remember { FocusRequester() }
    val taskHomeSettingsFocused = remember { mutableStateOf(false) }
    val pendingFocusKey = remember { mutableStateOf<String?>(null) }
    val pendingAttentionReturn = remember { mutableStateOf<AttentionNavigationReturn?>(null) }
    val attentionDismissGuard = remember { mutableStateOf<AttentionRoute?>(null) }

    LaunchedEffect(externalNewTaskRoute) {
        val route = externalNewTaskRoute ?: return@LaunchedEffect
        backStack.clear()
        backStack.add(TaskHomeRoute)
        backStack.add(route)
        pendingFocusKey.value = null
        pendingAttentionReturn.value = null
        onExternalNewTaskConsumed()
    }

    LaunchedEffect(attentionDismissGuard.value) {
        if (attentionDismissGuard.value == null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        attentionDismissGuard.value = null
    }

    LaunchedEffect(state.transport.phase, providerSetupState?.loadState) {
        val providerState = providerSetupState
        if (providerState != null) {
            if (providerState.loadState == ProviderSetupLoadState.LOADING) {
                return@LaunchedEffect
            }
            val top = backStack.lastOrNull()
            if (!providerState.configured) {
                if (top is NewTaskRoute && top.fromAndroidShare) {
                    return@LaunchedEffect
                }
                if (top !is ProviderSetupRoute || !top.onboarding) {
                    backStack.clear()
                    backStack.add(ProviderSetupRoute(onboarding = true))
                }
            } else if (top is ProviderSetupRoute && top.onboarding) {
                backStack.clear()
                backStack.add(TaskHomeRoute)
            }
        } else {
            if (state.transport.phase == app.momoding.core.transport.SecureTransportUiPhase.STARTING) {
                return@LaunchedEffect
            }
            if (state.showHostGate) {
                if (backStack.lastOrNull() != HostGateRoute) {
                    backStack.clear()
                    backStack.add(HostGateRoute)
                }
            } else if (backStack.lastOrNull() == HostGateRoute) {
                backStack.clear()
                backStack.add(TaskHomeRoute)
            }
        }
    }

    fun openTasks() {
        if (backStack.lastOrNull() != TaskHomeRoute) {
            backStack.clear()
            backStack.add(TaskHomeRoute)
        }
        pendingFocusKey.value = null
        pendingAttentionReturn.value = null
    }

    fun openSettings() {
        if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute)
    }

    fun openProviderSetup(returnTaskId: String? = null) {
        val route = ProviderSetupRoute(
            onboarding = false,
            returnTaskId = returnTaskId,
        )
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun openRemoteHost() {
        onAction(SettingsAction.EnableRemoteHost)
        if (backStack.lastOrNull() != HostGateRoute) backStack.add(HostGateRoute)
    }

    fun openMobileFiles(startPicker: Boolean = false) {
        val current = backStack.lastOrNull()
        if (
            startPicker &&
            current is DeviceCapabilitiesRoute &&
            current.startFullAccessSetup
        ) {
            backStack.removeLastOrNull()
            backStack.add(DeviceCapabilitiesRoute())
        }
        val route = AuthorizedFoldersRoute(
            startPicker = startPicker,
            pickerRunId = UUID.randomUUID().toString().takeIf { startPicker },
        )
        if (authorizedFoldersEntry != null && backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun openExtensions() {
        if (
            extensionsEntry != null &&
            backStack.lastOrNull() != ExtensionsRoute
        ) {
            backStack.add(ExtensionsRoute)
        }
    }

    fun openDeviceCapabilities(startFullAccessSetup: Boolean = false) {
        val route = DeviceCapabilitiesRoute(
            startFullAccessSetup = startFullAccessSetup,
            setupRunId = UUID.randomUUID().toString().takeIf { startFullAccessSetup },
        )
        if (deviceCapabilitiesEntry != null && backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun handleTaskHomeAction(action: TaskHomeAction) {
        onTaskHomeAction(action)
        when (action) {
            TaskHomeAction.NewTask -> backStack.add(NewTaskRoute(UUID.randomUUID().toString()))
            TaskHomeAction.PairHost -> {
                if (providerSetupState == null) {
                    backStack.clear()
                    backStack.add(HostGateRoute)
                } else {
                    openProviderSetup()
                }
            }
            TaskHomeAction.OpenSettings -> openSettings()
            TaskHomeAction.Search,
            is TaskHomeAction.EditSearch,
            TaskHomeAction.ClearSearch,
            TaskHomeAction.RetryConnection,
            is TaskHomeAction.OpenTask,
            is TaskHomeAction.OpenCachedTask,
            is TaskHomeAction.OpenAttention,
            is TaskHomeAction.FixProvider,
            TaskHomeAction.ToggleArchived,
            is TaskHomeAction.RequestRename,
            is TaskHomeAction.EditRename,
            TaskHomeAction.CancelManagementDialog,
            TaskHomeAction.ConfirmRename,
            is TaskHomeAction.SetPinned,
            is TaskHomeAction.Archive,
            is TaskHomeAction.Restore,
            is TaskHomeAction.RequestDelete,
            TaskHomeAction.ConfirmDelete,
            -> Unit
        }
    }

    fun handleTaskHomeOneShot(oneShot: TaskHomeOneShot) {
        if (backStack.lastOrNull() != TaskHomeRoute) return
        when (oneShot) {
            is TaskHomeOneShot.OpenTask -> if (oneShot.taskId.isNotBlank()) {
                backStack.add(TaskDetailRoute(oneShot.taskId))
            }
            is TaskHomeOneShot.OpenAttention -> if (
                oneShot.taskId.isNotBlank() && oneShot.callId.isNotBlank()
            ) {
                val detail = TaskDetailRoute(oneShot.taskId)
                backStack.add(detail)
                backStack.add(
                    AttentionRoute(
                        taskId = oneShot.taskId,
                        callId = oneShot.callId,
                        originFocusKey = taskDetailAttentionFocusKey(oneShot.taskId, oneShot.callId),
                    ),
                )
            }
            is TaskHomeOneShot.OpenProvider -> if (oneShot.taskId.isNotBlank()) {
                openProviderSetup(returnTaskId = oneShot.taskId)
            }
        }
    }

    if (taskHomeEntry == null) {
        LaunchedEffect(taskHomeOneShots) {
            taskHomeOneShots.collect(::handleTaskHomeOneShot)
        }
    }

    fun popRoute() {
        if (backStack.size <= 1) {
            val soleRoute = backStack.lastOrNull()
            if (
                soleRoute != null &&
                soleRoute != HostGateRoute &&
                soleRoute != TaskHomeRoute &&
                soleRoute != ProviderSetupRoute(onboarding = true)
            ) {
                backStack.clear()
                backStack.add(TaskHomeRoute)
            }
            return
        }
        val top = backStack.lastOrNull()
        val guardedAttention = attentionDismissGuard.value
        if (top is TaskDetailRoute && guardedAttention?.taskId == top.taskId) {
            attentionDismissGuard.value = null
            return
        }
        if (top is AttentionRoute) {
            val routeReturn = pendingAttentionReturn.value?.takeIf {
                it.taskId == top.taskId &&
                    it.callId == top.callId &&
                    it.originFocusKey == top.originFocusKey
            }
            pendingFocusKey.value = when (routeReturn?.reason) {
                AttentionReturnReason.COMPLETED,
                AttentionReturnReason.CANCELLED,
                AttentionReturnReason.UNAVAILABLE,
                -> TASK_DETAIL_TITLE_FOCUS_KEY
                AttentionReturnReason.DISMISSED,
                AttentionReturnReason.USER_RETURN,
                null,
                -> top.originFocusKey
            }
            attentionDismissGuard.value = top
        }
        backStack.removeLastOrNull()
    }

    fun handleTaskDetailOneShot(route: TaskDetailRoute, oneShot: TaskDetailOneShot) {
        when (oneShot) {
            TaskDetailOneShot.Back -> if (backStack.lastOrNull() == route) popRoute()
            TaskDetailOneShot.OpenOutputs -> if (backStack.lastOrNull() == route) {
                backStack.add(OutputsPlaceholderRoute(route.taskId))
            }
            TaskDetailOneShot.OpenDiff -> if (backStack.lastOrNull() == route) {
                backStack.add(FileChangeRoute(route.taskId))
            }
            TaskDetailOneShot.OpenProvider -> if (backStack.lastOrNull() == route) {
                openProviderSetup(returnTaskId = route.taskId)
            }
            TaskDetailOneShot.OpenFullAccessSetup -> if (backStack.lastOrNull() == route) {
                openDeviceCapabilities(startFullAccessSetup = true)
            }
            is TaskDetailOneShot.OpenAttention -> if (
                backStack.lastOrNull() == route && oneShot.callId.isNotBlank()
            ) {
                if (oneShot.fileChanges) {
                    backStack.add(FileChangeRoute(route.taskId, oneShot.callId))
                } else {
                    backStack.add(
                        AttentionRoute(
                            taskId = route.taskId,
                            callId = oneShot.callId,
                            originFocusKey = taskDetailAttentionFocusKey(
                                route.taskId,
                                oneShot.callId,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun handleAttentionOneShot(route: AttentionRoute, oneShot: AttentionOneShot) {
        when (oneShot) {
            is AttentionOneShot.AnnounceResponding -> if (
                backStack.lastOrNull() == route &&
                announcedAttentionEffects.add(oneShot.effectId)
            ) {
                announceAccessibility("Response pending")
                if (announcedAttentionEffects.size > 128) {
                    announcedAttentionEffects.remove(announcedAttentionEffects.first())
                }
            }
            is AttentionOneShot.ReturnToTask -> if (backStack.lastOrNull() == route) {
                pendingAttentionReturn.value = AttentionNavigationReturn(
                    effectId = oneShot.effectId,
                    taskId = route.taskId,
                    callId = route.callId,
                    originFocusKey = route.originFocusKey,
                    reason = oneShot.reason,
                )
                popRoute()
            }
        }
    }

    val topRoute = backStack.lastOrNull()
    val handlesSystemBack = backStack.size > 1 ||
        (
            topRoute != null &&
                topRoute != HostGateRoute &&
                topRoute != TaskHomeRoute &&
                topRoute != ProviderSetupRoute(onboarding = true)
        )
    BackHandler(
        enabled = handlesSystemBack && topRoute !is AttentionRoute,
        onBack = ::popRoute,
    )

    NavDisplay(
        backStack = backStack,
        onBack = {
            val route = backStack.lastOrNull()
            if (route is AttentionRoute) {
                bottomSheetDismissRegistry.requestDismissForRoute(route)
            } else {
                popRoute()
            }
        },
        entryDecorators = listOf(viewModelStoreDecorator),
        sceneStrategies = listOf(bottomSheetStrategy),
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        entryProvider = entryProvider {
            entry<HostGateRoute> {
                HostGateScreen(
                    state = state,
                    onAction = onAction,
                    onOpenMobileFiles = if (authorizedFoldersEntry != null) {
                        { openMobileFiles() }
                    } else {
                        null
                    },
                )
            }
            entry<ProviderSetupRoute> { route ->
                ProviderSetupScreen(
                    state = providerSetupState ?: ProviderSetupUiState(
                        loadState = ProviderSetupLoadState.ERROR,
                        notice = "Provider settings are unavailable in this build.",
                    ),
                    onAction = onProviderSetupAction,
                    onBack = if (route.onboarding) null else ::popRoute,
                    onReturnToTask = route.returnTaskId?.let { taskId ->
                        {
                            if (
                                providerSetupState?.canReturnToTask == true &&
                                backStack.lastOrNull() == route
                            ) {
                                backStack.removeLastOrNull()
                                val taskRoute = TaskDetailRoute(taskId)
                                if (backStack.lastOrNull() != taskRoute) {
                                    backStack.add(taskRoute)
                                }
                            }
                        }
                    },
                )
            }
            entry<TaskHomeRoute> {
                RestoreFocusWhenResumed(
                    pendingFocusKey = pendingFocusKey.value,
                    expectedFocusKey = TASK_HOME_SETTINGS_FOCUS_KEY,
                    requester = taskHomeSettingsFocus,
                    isFocused = { taskHomeSettingsFocused.value },
                    onRestored = { pendingFocusKey.value = null },
                )
                MomodingScaffold(
                    selected = TopLevelDestination.TASKS,
                    onTasks = ::openTasks,
                    onSettings = ::openSettings,
                    settingsItemDecoration = { modifier ->
                        modifier
                            .focusRequester(taskHomeSettingsFocus)
                            .onFocusChanged { taskHomeSettingsFocused.value = it.isFocused }
                            .focusable()
                    },
                ) { padding ->
                    if (taskHomeEntry == null) {
                        TaskHomeScreen(taskHomeState, padding, ::handleTaskHomeAction)
                    } else {
                        taskHomeEntry(padding, ::handleTaskHomeAction, ::handleTaskHomeOneShot)
                    }
                }
            }
            entry<SettingsRoute> {
                MomodingScaffold(TopLevelDestination.SETTINGS, ::openTasks, ::openSettings) { padding ->
                    SettingsScreen(
                        state = state,
                        contentPadding = padding,
                        onAction = onAction,
                        onOpenMobileFiles = if (authorizedFoldersEntry != null) {
                            { openMobileFiles() }
                        } else {
                            null
                        },
                        onOpenExtensions = if (extensionsEntry != null) {
                            ::openExtensions
                        } else {
                            null
                        },
                        onOpenDeviceCapabilities = if (deviceCapabilitiesEntry != null) {
                            { openDeviceCapabilities() }
                        } else {
                            null
                        },
                        providerProfile = providerSetupState?.savedProfile,
                        onOpenProviderSetup = if (providerSetupState != null) {
                            { openProviderSetup() }
                        } else {
                            null
                        },
                        onOpenRemoteHost = if (
                            providerSetupState != null &&
                            app.momoding.BuildConfig.DEBUG
                        ) {
                            ::openRemoteHost
                        } else {
                            null
                        },
                    )
                }
            }
            entry<AuthorizedFoldersRoute> { key ->
                authorizedFoldersEntry?.invoke(key, ::popRoute)
                    ?: StagePlaceholder(
                        "Mobile files unavailable",
                        "Folder authorization is not installed in this build.",
                        PaddingValues(24.dp),
                    )
            }
            entry<DeviceCapabilitiesRoute> { key ->
                deviceCapabilitiesEntry?.invoke(key, ::popRoute, ::openMobileFiles)
                    ?: StagePlaceholder(
                        "Device capabilities unavailable",
                        "Android capability checks are not installed in this build.",
                        PaddingValues(24.dp),
                    )
            }
            entry<ExtensionsRoute> {
                extensionsEntry?.invoke(::popRoute)
                    ?: StagePlaceholder(
                        "Extensions unavailable",
                        "Phone-local extensions are not installed in this build.",
                        PaddingValues(24.dp),
                    )
            }
            entry<NewTaskRoute> { key ->
                newTaskEntry(
                    key,
                    { taskId ->
                        if (backStack.lastOrNull() == key) {
                            backStack.removeLastOrNull()
                            backStack.add(TaskDetailRoute(taskId))
                        }
                    },
                    ::popRoute,
                    { openDeviceCapabilities(startFullAccessSetup = true) },
                )
            }
            entry<TaskDetailRoute> { key ->
                if (taskDetailEntry == null) {
                    StagePlaceholder("Task ${key.taskId}", "Task timeline host is unavailable.", PaddingValues(24.dp))
                } else {
                    taskDetailEntry(
                        key,
                        pendingFocusKey.value,
                        pendingAttentionReturn.value?.takeIf { it.taskId == key.taskId },
                        { pendingFocusKey.value = null },
                        { pendingAttentionReturn.value = null },
                    ) { oneShot -> handleTaskDetailOneShot(key, oneShot) }
                }
            }
            entry<OutputsPlaceholderRoute> { key ->
                outputsEntry?.invoke(key.taskId, ::popRoute)
                    ?: StagePlaceholder(
                        "Outputs unavailable",
                        "This task has no local output reader.",
                        PaddingValues(24.dp),
                    )
            }
            entry<DiffPlaceholderRoute> {
                StagePlaceholder(
                    "Diff unavailable",
                    "No reviewed file change is available for this task.",
                    PaddingValues(24.dp),
                )
            }
            entry<FileChangeRoute> { key ->
                fileChangeEntry?.invoke(key, ::popRoute)
                    ?: StagePlaceholder(
                        "Review changes",
                        "File change review is unavailable in this build.",
                        PaddingValues(24.dp),
                    )
            }
            entry<AttentionRoute>(metadata = MomodingBottomSheetSceneStrategy.bottomSheet()) { key ->
                if (attentionEntry == null) {
                    AttentionFallbackContent(
                        route = key,
                        dismissRegistry = bottomSheetDismissRegistry,
                        onOneShot = { oneShot -> handleAttentionOneShot(key, oneShot) },
                    )
                } else {
                    attentionEntry(
                        key,
                        bottomSheetDismissRegistry,
                    ) { oneShot -> handleAttentionOneShot(key, oneShot) }
                }
            }
        },
    )
}

/**
 * A direct announcement is used for this one-shot event because it has no persistent visual
 * status whose text change could act as an accessibility live region.
 */
@Suppress("DEPRECATION")
private fun android.view.View.announceCompat(message: String) {
    announceForAccessibility(message)
}

@Composable
private fun AuthorizedFoldersRouteContent(
    container: AppContainer,
    startPicker: Boolean,
    pickerRunId: String?,
    onBack: () -> Unit,
) {
    val viewModel: AuthorizedFoldersViewModel = viewModel(
        key = "authorized-folders",
        factory = AuthorizedFoldersViewModel.Factory(container.authorizedFoldersRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        viewModel.dispatch(
            AuthorizedFoldersAction.PickerFinished(
                treeUri = if (result.resultCode == Activity.RESULT_OK) {
                    data?.data?.toString()
                } else {
                    null
                },
                resultFlags = data?.flags ?: 0,
            ),
        )
    }
    var pickerAutoStarted by rememberSaveable(pickerRunId) { mutableStateOf(false) }
    LaunchedEffect(pickerRunId, startPicker) {
        if (startPicker && !pickerAutoStarted) {
            pickerAutoStarted = true
            withFrameNanos { }
            withFrameNanos { }
            delay(300)
            treePicker.launch(folderTreePickerIntent())
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.oneShots.collect { oneShot ->
            when (oneShot) {
                AuthorizedFoldersOneShot.Back -> onBack()
                AuthorizedFoldersOneShot.LaunchSystemTreePicker ->
                    treePicker.launch(folderTreePickerIntent())
            }
        }
    }
    BackHandler { viewModel.dispatch(AuthorizedFoldersAction.Back) }
    AuthorizedFoldersScreen(state, viewModel::dispatch)
}

private fun folderTreePickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
}

@Composable
private fun DeviceCapabilitiesRouteContent(
    container: AppContainer,
    startFullAccessSetup: Boolean,
    setupRunId: String?,
    onBack: () -> Unit,
    onOpenFolders: (Boolean) -> Unit,
) {
    val states by container.androidCapabilityRegistry.states.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var continueToFoldersAfterPhoto by rememberSaveable { mutableStateOf(false) }
    val foldersNeedSetup = states.firstOrNull {
        it.id == AndroidCapabilityId.SAF_FOLDERS
    }?.availability != CapabilityAvailability.READY
    val photoAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        container.androidCapabilityRegistry.refresh()
        if (continueToFoldersAfterPhoto) {
            continueToFoldersAfterPhoto = false
            if (foldersNeedSetup) onOpenFolders(true)
        }
    }
    val startAvailableAccessSetup = {
        val photoAccess = states.firstOrNull { it.id == AndroidCapabilityId.PHOTO_LIBRARY }
        val photoReady = photoAccess?.availability in setOf(
            CapabilityAvailability.READY,
            CapabilityAvailability.PARTIAL,
        )
        if (!photoReady) {
            continueToFoldersAfterPhoto = true
            photoAccessLauncher.launch(
                photoLibraryPermissionRequest(Build.VERSION.SDK_INT).toTypedArray(),
            )
        } else if (foldersNeedSetup) {
            onOpenFolders(true)
        }
    }
    val latestStartAvailableAccessSetup by rememberUpdatedState(startAvailableAccessSetup)
    var setupAutoStarted by rememberSaveable(setupRunId) { mutableStateOf(false) }
    LaunchedEffect(setupRunId, startFullAccessSetup, states.isNotEmpty()) {
        if (startFullAccessSetup && states.isNotEmpty() && !setupAutoStarted) {
            withFrameNanos { }
            withFrameNanos { }
            delay(300)
            setupAutoStarted = true
            latestStartAvailableAccessSetup()
        }
    }
    DisposableEffect(lifecycleOwner, container.androidCapabilityRegistry) {
        container.androidCapabilityRegistry.refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) container.androidCapabilityRegistry.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onBack)
    DeviceCapabilitiesScreen(
        states = states,
        onBack = onBack,
        onRefresh = container.androidCapabilityRegistry::refresh,
        onSetUpFullAccess = startAvailableAccessSetup,
        onOpenFolders = { onOpenFolders(false) },
        onManagePhotoAccess = {
            val photoAccess = states.firstOrNull { it.id == AndroidCapabilityId.PHOTO_LIBRARY }
            if (photoAccess?.availability in setOf(
                    CapabilityAvailability.READY,
                    CapabilityAvailability.PARTIAL,
                )
            ) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            } else {
                photoAccessLauncher.launch(
                    photoLibraryPermissionRequest(Build.VERSION.SDK_INT).toTypedArray(),
                )
            }
        },
    )
}

@Composable
private fun ExtensionsRouteContent(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: ExtensionsViewModel = viewModel(
        key = "extensions",
        factory = ExtensionsViewModel.Factory(
            repository = container.skillRepository,
            catalog = container.skillCatalogService,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        viewModel.dispatch(ExtensionsAction.ImportFinished(uri))
    }
    LaunchedEffect(viewModel) {
        viewModel.oneShots.collect { oneShot ->
            when (oneShot) {
                ExtensionsOneShot.Back -> onBack()
                ExtensionsOneShot.LaunchSkillPicker -> documentPicker.launch(
                    arrayOf("text/*", "application/octet-stream"),
                )
            }
        }
    }
    BackHandler { viewModel.dispatch(ExtensionsAction.Back) }
    ExtensionsScreen(state, viewModel::dispatch)
}

@Composable
private fun FileChangeRouteContent(
    route: FileChangeRoute,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: FileChangeViewModel = viewModel(
        key = "file-change:${route.taskId}:${route.commitCallId.orEmpty()}",
        factory = if (
            route.commitCallId?.let(container.phoneLocalAttentionBridge::owns) == true
        ) {
            FileChangeViewModel.PhoneLocalFactory(
                taskId = route.taskId,
                commitCallId = route.commitCallId,
                repository = container.fileChangeRepository,
                bridge = container.phoneLocalAttentionBridge,
            )
        } else {
            FileChangeViewModel.Factory(
                taskId = route.taskId,
                commitCallId = route.commitCallId,
                repository = container.fileChangeRepository,
                runtime = container.runtime,
            )
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    FileChangeScreen(
        state = state,
        onBack = onBack,
        onAction = viewModel::dispatch,
    )
}

@Composable
private fun TaskHomeRouteContent(
    container: AppContainer,
    modelId: String,
    padding: PaddingValues,
    onRouteAction: (TaskHomeAction) -> Unit,
    onOneShot: (TaskHomeOneShot) -> Unit,
) {
    val viewModel: TaskHomeViewModel = viewModel(
        key = "task-home-phone-local:$modelId",
        factory = TaskHomeViewModel.PhoneLocalFactory(container.taskRepository, modelId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.oneShots.collect(onOneShot)
    }
    TaskHomeScreen(
        state = state,
        contentPadding = padding,
        onAction = { action ->
            viewModel.dispatch(action)
            onRouteAction(action)
        },
    )
}

@Composable
private fun NewTaskRouteContent(
    route: NewTaskRoute,
    container: AppContainer,
    modelId: String,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    onOpenFullAccessSetup: () -> Unit,
) {
    val viewModel: NewTaskViewModel = viewModel(
        key = "new-task-phone-local-${route.draftId}",
        factory = NewTaskViewModel.PhoneLocalFactory(
            draftId = route.draftId,
            drafts = container.draftRepository,
            coordinator = container.phoneLocalTaskCoordinator,
            modelId = modelId,
            authorizedFolders = container.authorizedFoldersRepository,
            attachments = container.attachmentRepository,
            photoAttachmentInputEnabled = AttachmentFeatureGate.PHOTO_PRODUCT_INPUT_ENABLED,
            textFileAttachmentInputEnabled = AttachmentFeatureGate.TEXT_FILE_PRODUCT_INPUT_ENABLED,
            cameraAttachmentInputEnabled = AttachmentFeatureGate.CAMERA_PRODUCT_INPUT_ENABLED,
            imageAttachmentRuntimeReady = AttachmentFeatureGate.PHOTO_PRODUCT_INPUT_ENABLED,
            textFileAttachmentRuntimeReady = AttachmentFeatureGate.TEXT_FILE_PRODUCT_INPUT_ENABLED,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingCameraCaptureId by rememberSaveable(route.draftId) { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val captureId = pendingCameraCaptureId ?: return@rememberLauncherForActivityResult
        pendingCameraCaptureId = null
        viewModel.dispatch(
            NewTaskAction.CameraCaptureFinished(
                captureId = captureId,
                captured = captured,
            ),
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.oneShots.collect { oneShot ->
            when (oneShot) {
                is NewTaskOneShot.OpenTask -> onOpenTask(oneShot.taskId)
                NewTaskOneShot.Back -> onBack()
                NewTaskOneShot.OpenFullAccessSetup -> onOpenFullAccessSetup()
                is NewTaskOneShot.LaunchCamera -> {
                    pendingCameraCaptureId = oneShot.captureId
                    try {
                        cameraLauncher.launch(oneShot.outputUri)
                    } catch (_: ActivityNotFoundException) {
                        pendingCameraCaptureId = null
                        viewModel.dispatch(
                            NewTaskAction.CameraCaptureFinished(
                                captureId = oneShot.captureId,
                                captured = false,
                                launchFailed = true,
                            ),
                        )
                    } catch (_: SecurityException) {
                        pendingCameraCaptureId = null
                        viewModel.dispatch(
                            NewTaskAction.CameraCaptureFinished(
                                captureId = oneShot.captureId,
                                captured = false,
                                launchFailed = true,
                            ),
                        )
                    }
                }
            }
        }
    }
    BackHandler { viewModel.dispatch(NewTaskAction.Back) }
    NewTaskScreen(
        state = state,
        onAction = viewModel::dispatch,
        interactionPolicy = NewTaskInteractionPolicy.All,
        importNotice = route.importNotice,
    )
}

@Composable
private fun TaskDetailRouteContent(
    route: TaskDetailRoute,
    container: AppContainer,
    restoreFocusKey: String?,
    attentionReturn: AttentionNavigationReturn?,
    onFocusRestored: () -> Unit,
    onAttentionReturnConsumed: () -> Unit,
    onOneShot: (TaskDetailOneShot) -> Unit,
    modelId: String,
) {
    val viewModel: TaskDetailViewModel = viewModel(
        key = "task-detail-${route.taskId}",
        factory = TaskDetailViewModel.PhoneLocalFactory(
            taskId = route.taskId,
            repository = container.taskDetailRepository,
            coordinator = container.phoneLocalTaskCoordinator,
            modelId = modelId,
            taskAttachments = container.attachmentRepository,
            imageAttachmentInputEnabled = AttachmentFeatureGate.PHOTO_PRODUCT_INPUT_ENABLED,
            textFileAttachmentInputEnabled = AttachmentFeatureGate.TEXT_FILE_PRODUCT_INPUT_ENABLED,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeQuestion = state.attention?.takeIf { it.kind == TaskAttentionKind.QUESTION }
    val questionViewModel = activeQuestion?.let { question ->
        viewModel<AttentionViewModel>(
            key = "task-question-${route.taskId}-${question.callId}",
            factory = container.attentionViewModelFactory(
                AttentionIdentity(route.taskId, question.callId),
            ),
        )
    }
    val questionState = questionViewModel?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(attentionReturn?.effectId, route.taskId) {
        val effect = attentionReturn ?: return@LaunchedEffect
        if (effect.taskId != route.taskId || effect.callId.isBlank()) return@LaunchedEffect
        viewModel.acceptAttentionReturn(
            effectId = effect.effectId,
            callId = effect.callId,
            unavailable = effect.reason == AttentionReturnReason.UNAVAILABLE,
        )
        onAttentionReturnConsumed()
    }
    LaunchedEffect(viewModel) { viewModel.oneShots.collect(onOneShot) }
    LaunchedEffect(questionViewModel) {
        questionViewModel?.oneShots?.collect { effect ->
            if (effect is AttentionOneShot.ReturnToTask) {
                viewModel.acceptAttentionReturn(
                    effectId = effect.effectId,
                    callId = activeQuestion?.callId ?: return@collect,
                    unavailable = effect.reason == AttentionReturnReason.UNAVAILABLE,
                )
            }
        }
    }
    BackHandler { viewModel.dispatch(app.momoding.feature.taskdetail.TaskDetailAction.Back) }
    TaskDetailScreen(
        state = state,
        onAction = viewModel::dispatch,
        questionState = questionState,
        onQuestionIntent = { intent -> questionViewModel?.dispatch(intent) },
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
    )
}

@Composable
internal fun AttentionRouteContent(
    route: AttentionRoute,
    ownerFactory: AttentionRouteViewModelFactory,
    dismissRegistry: MomodingBottomSheetDismissRegistry,
    onOneShot: (AttentionOneShot) -> Unit,
) {
    val identity = AttentionIdentity(route.taskId, route.callId)
    val viewModel: AttentionViewModel = viewModel(
        key = taskDetailAttentionFocusKey(route.taskId, route.callId),
        factory = ownerFactory(identity),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.oneShots.collect(onOneShot) }
    val dismissIntent = state.systemDismissIntent()
    val contentKey = LocalMomodingBottomSheetContentKey.current ?: route
    DisposableEffect(route, contentKey, viewModel, dismissIntent, dismissRegistry) {
        dismissRegistry.register(
            key = contentKey,
            routeIdentity = route,
            owner = viewModel,
            enabled = dismissIntent != null,
            request = { dismissIntent?.let(viewModel::dispatch) },
        )
        onDispose { dismissRegistry.unregister(contentKey, viewModel) }
    }
    AttentionScreen(
        state = state,
        onIntent = viewModel::dispatch,
        embeddedInBottomSheet = true,
    )
}

@Composable
private fun RestoreFocusWhenResumed(
    pendingFocusKey: String?,
    expectedFocusKey: String,
    requester: FocusRequester,
    isFocused: () -> Boolean,
    onRestored: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pendingFocusKey, lifecycleOwner) {
        if (pendingFocusKey != expectedFocusKey) return@LaunchedEffect
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        repeat(120) {
            requester.requestFocus()
            withFrameNanos { }
            if (isFocused()) {
                onRestored()
                return@LaunchedEffect
            }
        }
    }
}

@Composable
private fun StagePlaceholder(
    title: String,
    body: String,
    padding: PaddingValues,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionFocusRequester: FocusRequester? = null,
    onActionFocusChanged: ((Boolean) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (action != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(top = 20.dp)
                    .then(actionFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .then(
                        onActionFocusChanged?.let { callback ->
                            Modifier.onFocusChanged { callback(it.isFocused) }
                        } ?: Modifier,
                    )
                    .focusable(),
            ) {
                Text(action)
            }
        }
    }
}

@Composable
private fun AttentionFoundation(
    dismissIntent: AttentionIntent?,
    onIntent: (AttentionIntent) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Action needs attention",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() },
        )
        LaunchedEffect(Unit) { titleFocus.requestFocus() }
        Text("Dismiss closes this view without approving or rejecting the request.")
        if (dismissIntent != null) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onIntent(dismissIntent)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    if (dismissIntent == AttentionIntent.ReturnToTask) {
                        "Return to task"
                    } else {
                        "Dismiss"
                    },
                )
            }
        }
    }
}

@Composable
private fun AttentionFallbackContent(
    route: AttentionRoute,
    dismissRegistry: MomodingBottomSheetDismissRegistry,
    onOneShot: (AttentionOneShot) -> Unit,
) {
    val owner = remember(route) { Any() }
    val returnEffect = remember(route) {
        AttentionOneShot.ReturnToTask(
            effectId = "${route.taskId}:${route.callId}:fallback-return",
            reason = AttentionReturnReason.USER_RETURN,
        )
    }
    val contentKey = LocalMomodingBottomSheetContentKey.current ?: route
    DisposableEffect(route, contentKey, owner, dismissRegistry) {
        dismissRegistry.register(
            key = contentKey,
            routeIdentity = route,
            owner = owner,
            enabled = true,
            request = { onOneShot(returnEffect) },
        )
        onDispose { dismissRegistry.unregister(contentKey, owner) }
    }
    AttentionFoundation(
        dismissIntent = AttentionIntent.Dismiss,
        onIntent = { onOneShot(returnEffect) },
    )
}

internal fun AttentionUiState.systemDismissIntent(): AttentionIntent? {
    val visible = this as? AttentionUiState.Visible ?: return null
    return when {
        visible.actions.canDismiss -> AttentionIntent.Dismiss
        visible.actions.canReturnToTask && visible.state in setOf(
            AttentionVisibleState.Resolved,
            AttentionVisibleState.Rejected,
            AttentionVisibleState.Skipped,
            AttentionVisibleState.Cancelled,
            AttentionVisibleState.AlreadyAnswered,
        ) -> AttentionIntent.ReturnToTask
        else -> null
    }
}

const val TASK_HOME_SETTINGS_FOCUS_KEY = "task-home-settings"
