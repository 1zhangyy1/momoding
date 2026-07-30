package app.momoding.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.testTag
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import app.momoding.core.capabilities.AndroidCapabilityRequirement
import app.momoding.core.capabilities.AndroidCapabilityRequest
import app.momoding.core.capabilities.AndroidCapabilityRequestResult
import app.momoding.core.capabilities.AndroidPermissionRequest
import app.momoding.core.capabilities.AndroidPermissionRequestResult
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.CalendarCapabilityAccess
import app.momoding.core.capabilities.ContactsCapabilityAccess
import app.momoding.core.capabilities.LocationCapabilityAccess
import app.momoding.core.capabilities.calendarPermissionRequest
import app.momoding.core.capabilities.contactsPermissionRequest
import app.momoding.core.capabilities.locationPermissionRequest
import app.momoding.core.capabilities.photoLibraryPermissionRequest
import app.momoding.core.capabilities.shouldOpenAppSettingsForPermissions
import app.momoding.core.accessibility.MomodingScreenCaptureRuntime
import app.momoding.core.accessibility.ScreenCaptureSessionService
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
import app.momoding.ui.components.MomodingNavigationDrawer
import app.momoding.ui.components.NavigationDrawerButton
import app.momoding.ui.components.ProductTopBar
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
import kotlinx.coroutines.flow.filterNotNull
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
    private lateinit var toolPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var toolPermissionSettingsLauncher: ActivityResultLauncher<Intent>
    private var activeToolPermissionRequestId: String? = null
    private var activeToolPermissions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        registerToolPermissionLaunchers()
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.androidPermissionRequestCoordinator.pending
                    .filterNotNull()
                    .collect(::handleToolPermissionRequest)
            }
        }
        setContent {
            val state by settingsViewModel.state.collectAsStateWithLifecycle()
            val providerState by providerSetupViewModel.state.collectAsStateWithLifecycle()
            val sharedRoute by externalNewTaskRoute.collectAsStateWithLifecycle()
            val capabilityRequest by container.androidCapabilityRequestCoordinator.pending
                .collectAsStateWithLifecycle()
            val phoneLocalModelId = providerState.savedProfile?.modelId ?: providerState.modelId
            val taskHomeViewModel: TaskHomeViewModel = viewModel(
                key = "task-home-phone-local",
                factory = TaskHomeViewModel.PhoneLocalFactory(
                    container.taskRepository,
                    phoneLocalModelId,
                ),
            )
            val collectedTaskHomeState by taskHomeViewModel.state.collectAsStateWithLifecycle()
            val taskHomeState = collectedTaskHomeState.copy(
                hostAlias = "On-device · $phoneLocalModelId",
            )
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
                    taskHomeState = taskHomeState,
                    onTaskHomeAction = taskHomeViewModel::dispatch,
                    taskHomeOneShots = taskHomeViewModel.oneShots,
                    newTaskEntry = {
                        route,
                        onOpenTask,
                        onBack,
                        onOpenFullAccessSetup,
                        onOpenNavigation,
                    ->
                        NewTaskRouteContent(
                            route = route,
                            container = container,
                            modelId = phoneLocalModelId,
                            onOpenTask = onOpenTask,
                            onBack = onBack,
                            onOpenFullAccessSetup = onOpenFullAccessSetup,
                            onOpenNavigation = onOpenNavigation,
                        )
                    },
                    taskDetailEntry = {
                            route,
                            restoreFocusKey,
                            attentionReturn,
                            onFocusRestored,
                            onAttentionReturnConsumed,
                            onOneShot,
                            onOpenNavigation,
                            onNewTask,
                        ->
                        TaskDetailRouteContent(
                            route = route,
                            container = container,
                            restoreFocusKey = restoreFocusKey,
                            attentionReturn = attentionReturn,
                            onFocusRestored = onFocusRestored,
                            onAttentionReturnConsumed = onAttentionReturnConsumed,
                            onOneShot = onOneShot,
                            modelId = phoneLocalModelId,
                            onOpenNavigation = onOpenNavigation,
                            onNewTask = onNewTask,
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
                            capabilityRequestId = route.capabilityRequestId,
                            onBack = onBack,
                        )
                    },
                    deviceCapabilitiesEntry = { route, onBack, onOpenFolders ->
                        DeviceCapabilitiesRouteContent(
                            container = container,
                            startFullAccessSetup = route.startFullAccessSetup,
                            setupRunId = route.setupRunId,
                            capabilityRequestId = route.capabilityRequestId,
                            requestedCapability = route.requestedCapability,
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
                    capabilityRequest = capabilityRequest,
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

    private fun registerToolPermissionLaunchers() {
        toolPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            if (activeToolPermissions.any(::isPermissionGranted)) {
                completeToolPermissionRequest(AndroidPermissionRequestResult.GRANTED)
            } else {
                showToolPermissionSettingsFallback()
            }
        }
        toolPermissionSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            completeToolPermissionRequest(
                if (activeToolPermissions.any(::isPermissionGranted)) {
                    AndroidPermissionRequestResult.GRANTED
                } else {
                    AndroidPermissionRequestResult.DENIED
                },
            )
        }
    }

    private fun handleToolPermissionRequest(request: AndroidPermissionRequest) {
        if (request.requestId == activeToolPermissionRequestId) return
        activeToolPermissionRequestId = request.requestId
        activeToolPermissions = request.permissions
        if (request.permissions.any(::isPermissionGranted)) {
            completeToolPermissionRequest(AndroidPermissionRequestResult.GRANTED)
            return
        }
        val permanentlyDenied = request.permissions.any { permission ->
            wasPermissionAsked(permission) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }
        if (permanentlyDenied) {
            showToolPermissionSettingsFallback()
        } else {
            request.permissions.forEach(::markPermissionAsked)
            toolPermissionLauncher.launch(request.permissions.toTypedArray())
        }
    }

    private fun showToolPermissionSettingsFallback() {
        val requestId = activeToolPermissionRequestId ?: return
        AlertDialog.Builder(this)
            .setTitle("Photo access needed")
            .setMessage(
                "Momoding needs Android photo access to complete this request. " +
                    "Open this app’s settings and allow Photos and videos.",
            )
            .setPositiveButton("Open settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
                try {
                    toolPermissionSettingsLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    if (activeToolPermissionRequestId == requestId) {
                        completeToolPermissionRequest(AndroidPermissionRequestResult.DENIED)
                    }
                }
            }
            .setNegativeButton("Not now") { _, _ ->
                if (activeToolPermissionRequestId == requestId) {
                    completeToolPermissionRequest(AndroidPermissionRequestResult.DENIED)
                }
            }
            .setOnCancelListener {
                if (activeToolPermissionRequestId == requestId) {
                    completeToolPermissionRequest(AndroidPermissionRequestResult.DENIED)
                }
            }
            .show()
    }

    private fun completeToolPermissionRequest(result: AndroidPermissionRequestResult) {
        val requestId = activeToolPermissionRequestId ?: return
        activeToolPermissionRequestId = null
        activeToolPermissions = emptyList()
        container.androidPermissionRequestCoordinator.respond(requestId, result)
        container.androidCapabilityRegistry.refresh()
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun wasPermissionAsked(permission: String): Boolean =
        getSharedPreferences(TOOL_PERMISSION_PREFS, MODE_PRIVATE)
            .getBoolean(permission, false)

    private fun markPermissionAsked(permission: String) {
        getSharedPreferences(TOOL_PERMISSION_PREFS, MODE_PRIVATE).edit {
            putBoolean(permission, true)
        }
    }

    private companion object {
        const val TOOL_PERMISSION_PREFS = "tool_permission_requests"
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
    () -> Unit,
    () -> Unit,
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
    newTaskEntry: @Composable (NewTaskRoute, (String) -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit = { _, _, _, _, onOpenNavigation ->
        TaskSurfacePlaceholder(
            title = "New task",
            body = "Task creation host is unavailable.",
            onOpenNavigation = onOpenNavigation,
        )
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
    capabilityRequest: AndroidCapabilityRequest? = null,
    initialBackStack: List<NavKey> = listOf(HostGateRoute),
    onAccessibilityAnnouncement: ((String) -> Unit)? = null,
) {
    val accessibilityView = LocalView.current
    val announceAccessibility = onAccessibilityAnnouncement ?: remember(accessibilityView) {
        { message: String -> accessibilityView.announceForAccessibility(message) }
    }
    val announcedAttentionEffects = remember { LinkedHashSet<String>() }
    val backStack = rememberNavBackStack(*initialBackStack.toTypedArray())
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val navigationScope = rememberCoroutineScope()
    val bottomSheetDismissRegistry = remember {
        MomodingBottomSheetDismissRegistry()
    }
    val bottomSheetStrategy = remember(bottomSheetDismissRegistry) {
        MomodingBottomSheetSceneStrategy<NavKey>(bottomSheetDismissRegistry)
    }
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val pendingFocusKey = remember { mutableStateOf<String?>(null) }
    val pendingAttentionReturn = remember { mutableStateOf<AttentionNavigationReturn?>(null) }
    val pendingTaskHomeNavigation = remember { mutableStateOf<TaskHomeAction?>(null) }
    val attentionDismissGuard = remember { mutableStateOf<AttentionRoute?>(null) }

    fun clearPrimaryNavigationState() {
        pendingFocusKey.value = null
        pendingAttentionReturn.value = null
        pendingTaskHomeNavigation.value = null
    }

    LaunchedEffect(externalNewTaskRoute) {
        val route = externalNewTaskRoute ?: return@LaunchedEffect
        backStack.clear()
        backStack.add(route)
        clearPrimaryNavigationState()
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
                    clearPrimaryNavigationState()
                }
            } else if (top is ProviderSetupRoute && top.onboarding) {
                backStack.clear()
                backStack.add(NewTaskRoute(UUID.randomUUID().toString()))
                clearPrimaryNavigationState()
            }
        } else {
            if (state.transport.phase == app.momoding.core.transport.SecureTransportUiPhase.STARTING) {
                return@LaunchedEffect
            }
            if (state.showHostGate) {
                if (backStack.lastOrNull() != HostGateRoute) {
                    backStack.clear()
                    backStack.add(HostGateRoute)
                    clearPrimaryNavigationState()
                }
            } else if (backStack.lastOrNull() == HostGateRoute) {
                backStack.clear()
                backStack.add(NewTaskRoute(UUID.randomUUID().toString()))
                clearPrimaryNavigationState()
            }
        }
    }

    fun openTasks() {
        if (backStack.lastOrNull() != TaskHomeRoute) backStack.add(TaskHomeRoute)
        clearPrimaryNavigationState()
    }

    fun replacePrimaryRoute(route: NavKey) {
        backStack.clear()
        backStack.add(route)
        clearPrimaryNavigationState()
    }

    fun openNewTask() {
        replacePrimaryRoute(NewTaskRoute(UUID.randomUUID().toString()))
    }

    fun openDrawer() {
        navigationScope.launch { drawerState.open() }
    }

    fun closeDrawerThen(action: () -> Unit) {
        navigationScope.launch {
            drawerState.close()
            action()
        }
    }

    fun openSettings() {
        pendingTaskHomeNavigation.value = null
        if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute)
    }

    fun openProviderSetup(returnTaskId: String? = null) {
        pendingTaskHomeNavigation.value = null
        if (returnTaskId != null) {
            replacePrimaryRoute(TaskDetailRoute(returnTaskId))
        }
        val route = ProviderSetupRoute(
            onboarding = false,
            returnTaskId = returnTaskId,
        )
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun openRemoteHost() {
        pendingTaskHomeNavigation.value = null
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
            capabilityRequestId = (current as? DeviceCapabilitiesRoute)
                ?.capabilityRequestId,
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

    fun openDeviceCapabilities(
        startFullAccessSetup: Boolean = false,
        request: AndroidCapabilityRequest? = null,
    ) {
        val route = DeviceCapabilitiesRoute(
            startFullAccessSetup = startFullAccessSetup,
            setupRunId = UUID.randomUUID().toString().takeIf { startFullAccessSetup },
            capabilityRequestId = request?.requestId,
            requestedCapability = request?.capability?.name,
        )
        if (deviceCapabilitiesEntry != null && backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    LaunchedEffect(capabilityRequest?.requestId) {
        val request = capabilityRequest ?: return@LaunchedEffect
        openDeviceCapabilities(request = request)
    }

    fun handleTaskHomeAction(action: TaskHomeAction) {
        pendingTaskHomeNavigation.value = when (action) {
            is TaskHomeAction.OpenTask,
            is TaskHomeAction.OpenCachedTask,
            is TaskHomeAction.OpenAttention,
            is TaskHomeAction.FixProvider,
            -> action
            TaskHomeAction.NewTask,
            TaskHomeAction.PairHost,
            TaskHomeAction.OpenSettings,
            -> null
            else -> pendingTaskHomeNavigation.value
        }
        onTaskHomeAction(action)
        when (action) {
            TaskHomeAction.NewTask -> openNewTask()
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
        val expected = pendingTaskHomeNavigation.value
        val matches = when (oneShot) {
            is TaskHomeOneShot.OpenTask -> when (expected) {
                is TaskHomeAction.OpenTask -> expected.taskId == oneShot.taskId
                is TaskHomeAction.OpenCachedTask -> expected.taskId == oneShot.taskId
                else -> false
            }
            is TaskHomeOneShot.OpenAttention -> expected is TaskHomeAction.OpenAttention &&
                expected.taskId == oneShot.taskId &&
                (expected.callId == null || expected.callId == oneShot.callId)
            is TaskHomeOneShot.OpenProvider -> expected is TaskHomeAction.FixProvider &&
                expected.taskId == oneShot.taskId
        }
        if (!matches) return
        pendingTaskHomeNavigation.value = null
        when (oneShot) {
            is TaskHomeOneShot.OpenTask -> if (oneShot.taskId.isNotBlank()) {
                replacePrimaryRoute(TaskDetailRoute(oneShot.taskId))
            }
            is TaskHomeOneShot.OpenAttention -> if (
                oneShot.taskId.isNotBlank() && oneShot.callId.isNotBlank()
            ) {
                val detail = TaskDetailRoute(oneShot.taskId)
                replacePrimaryRoute(detail)
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
                soleRoute !is NewTaskRoute &&
                soleRoute !is TaskDetailRoute &&
                soleRoute != ProviderSetupRoute(onboarding = true)
            ) {
                openNewTask()
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
    val topAttentionRoute = topRoute as? AttentionRoute
    val drawerEnabled = topRoute is NewTaskRoute || topRoute is TaskDetailRoute
    val drawerActive =
        drawerState.currentValue == DrawerValue.Open ||
            drawerState.targetValue == DrawerValue.Open
    LaunchedEffect(drawerEnabled) {
        if (!drawerEnabled && drawerActive) drawerState.close()
    }
    val handlesSystemBack = backStack.size > 1 ||
        (
            topRoute != null &&
                topRoute != HostGateRoute &&
                topRoute !is NewTaskRoute &&
                topRoute !is TaskDetailRoute &&
                topRoute != ProviderSetupRoute(onboarding = true)
        )
    BackHandler(
        enabled = handlesSystemBack && topRoute !is AttentionRoute && !drawerActive,
        onBack = ::popRoute,
    )
    BackHandler(enabled = topAttentionRoute != null && !drawerActive) {
        bottomSheetDismissRegistry.requestDismissForRoute(requireNotNull(topAttentionRoute))
    }
    BackHandler(enabled = drawerActive) {
        navigationScope.launch { drawerState.close() }
    }

    MomodingNavigationDrawer(
        state = taskHomeState,
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        currentTaskId = (topRoute as? TaskDetailRoute)?.taskId,
        onTaskAction = { action ->
            when (action) {
                TaskHomeAction.Search,
                is TaskHomeAction.EditSearch,
                TaskHomeAction.ClearSearch,
                -> handleTaskHomeAction(action)
                else -> closeDrawerThen { handleTaskHomeAction(action) }
            }
        },
        onOpenAllTasks = { closeDrawerThen(::openTasks) },
        onOpenSettings = { closeDrawerThen(::openSettings) },
        onClose = { navigationScope.launch { drawerState.close() } },
    ) {
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
                if (taskHomeEntry == null) {
                    TaskHomeScreen(
                        taskHomeState,
                        PaddingValues(),
                        ::handleTaskHomeAction,
                        onBack = ::popRoute,
                    )
                } else {
                    taskHomeEntry(
                        PaddingValues(),
                        ::handleTaskHomeAction,
                        ::handleTaskHomeOneShot,
                    )
                }
            }
            entry<SettingsRoute> {
                SettingsScreen(
                    state = state,
                    contentPadding = PaddingValues(),
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
                    onBack = ::popRoute,
                )
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
                    ::openDrawer,
                )
            }
            entry<TaskDetailRoute> { key ->
                if (taskDetailEntry == null) {
                    TaskSurfacePlaceholder(
                        title = "Task ${key.taskId}",
                        body = "Task timeline host is unavailable.",
                        onOpenNavigation = ::openDrawer,
                        onNewTask = ::openNewTask,
                    )
                } else {
                    taskDetailEntry(
                        key,
                        pendingFocusKey.value,
                        pendingAttentionReturn.value?.takeIf { it.taskId == key.taskId },
                        { pendingFocusKey.value = null },
                        { pendingAttentionReturn.value = null },
                        { oneShot -> handleTaskDetailOneShot(key, oneShot) },
                        ::openDrawer,
                        ::openNewTask,
                    )
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
                    "Diff content is not available for this task.",
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
}

@Composable
private fun TaskSurfacePlaceholder(
    title: String,
    body: String,
    onOpenNavigation: () -> Unit,
    onNewTask: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize().testTag("task-surface")) {
        ProductTopBar(
            title = title,
            navigation = { NavigationDrawerButton(onClick = onOpenNavigation) },
            actions = {
                onNewTask?.let { createTask ->
                    androidx.compose.material3.IconButton(onClick = createTask) {
                        androidx.compose.material3.Icon(
                            app.momoding.ui.icons.MomodingIcons.Add,
                            contentDescription = "New task",
                        )
                    }
                }
            },
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuthorizedFoldersRouteContent(
    container: AppContainer,
    startPicker: Boolean,
    pickerRunId: String?,
    capabilityRequestId: String?,
    onBack: () -> Unit,
) {
    val viewModel: AuthorizedFoldersViewModel = viewModel(
        key = "authorized-folders",
        factory = AuthorizedFoldersViewModel.Factory(container.authorizedFoldersRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var capabilityPickerAccepted by rememberSaveable(capabilityRequestId) {
        mutableStateOf(false)
    }
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val selectedTree = result.resultCode == Activity.RESULT_OK && data?.data != null
        if (capabilityRequestId != null && !selectedTree) {
            container.androidCapabilityRequestCoordinator.respond(
                capabilityRequestId,
                AndroidCapabilityRequestResult.DENIED,
            )
            onBack()
            return@rememberLauncherForActivityResult
        }
        capabilityPickerAccepted = capabilityRequestId != null && selectedTree
        viewModel.dispatch(
            AuthorizedFoldersAction.PickerFinished(
                treeUri = if (selectedTree) {
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
            if (capabilityRequestId != null) {
                viewModel.dispatch(AuthorizedFoldersAction.ClearNotice)
            }
            withFrameNanos { }
            withFrameNanos { }
            delay(300)
            treePicker.launch(folderTreePickerIntent())
        }
    }
    LaunchedEffect(
        capabilityRequestId,
        capabilityPickerAccepted,
        state.busyAction,
        state.selectedGrantId,
    ) {
        val requestId = capabilityRequestId ?: return@LaunchedEffect
        if (!capabilityPickerAccepted || state.busyAction != null) {
            return@LaunchedEffect
        }
        val grantId = state.selectedGrantId
        val authorized = grantId != null &&
            container.androidCapabilityRegistry.refreshNow()
                .first { it.id == AndroidCapabilityId.SAF_FOLDERS }
                .availability == CapabilityAvailability.READY
        if (
            container.androidCapabilityRequestCoordinator.respond(
                requestId = requestId,
                result = if (authorized) {
                    AndroidCapabilityRequestResult.READY
                } else {
                    AndroidCapabilityRequestResult.UNAVAILABLE
                },
                grantId = grantId.takeIf { authorized },
            )
        ) {
            onBack()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.oneShots.collect { oneShot ->
            when (oneShot) {
                AuthorizedFoldersOneShot.Back -> {
                    capabilityRequestId?.let { requestId ->
                        container.androidCapabilityRequestCoordinator.respond(
                            requestId,
                            AndroidCapabilityRequestResult.DENIED,
                        )
                    }
                    onBack()
                }
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
    capabilityRequestId: String?,
    requestedCapability: String?,
    onBack: () -> Unit,
    onOpenFolders: (Boolean) -> Unit,
) {
    val states by container.androidCapabilityRegistry.states.collectAsStateWithLifecycle()
    val pendingCapabilityRequest by container.androidCapabilityRequestCoordinator.pending
        .collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestedCapabilityId = remember(requestedCapability) {
        requestedCapability?.let { wireValue ->
            AndroidCapabilityId.entries.firstOrNull { it.name == wireValue }
        }
    }
    val calendarRequiredAccess =
        (
            pendingCapabilityRequest
                ?.takeIf { it.requestId == capabilityRequestId }
                ?.requirement as? AndroidCapabilityRequirement.Calendar
            )?.access
    val contactsRequiredAccess =
        (
            pendingCapabilityRequest
                ?.takeIf { it.requestId == capabilityRequestId }
                ?.requirement as? AndroidCapabilityRequirement.Contacts
            )?.access
    val locationRequiredAccess =
        (
            pendingCapabilityRequest
                ?.takeIf { it.requestId == capabilityRequestId }
                ?.requirement as? AndroidCapabilityRequirement.Location
            )?.access
    var continueToFoldersAfterPhoto by rememberSaveable { mutableStateOf(false) }
    var continueToFoldersAfterAllFiles by rememberSaveable { mutableStateOf(false) }
    val foldersNeedSetup = states.firstOrNull {
        it.id == AndroidCapabilityId.SAF_FOLDERS
    }?.availability != CapabilityAvailability.READY
    val allFilesNeedSetup = states.firstOrNull {
        it.id == AndroidCapabilityId.ALL_FILES
    }?.availability != CapabilityAvailability.READY
    val allFilesSettingsIntents = remember(context.packageName) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
    }
    val openAllFilesSettings: () -> Unit = openSettings@{
        allFilesSettingsIntents.forEach { intent ->
            try {
                context.startActivity(intent)
                return@openSettings
            } catch (_: ActivityNotFoundException) {
                // Try the next Android/OEM fallback.
            }
        }
    }
    fun isCapabilityReady(capability: AndroidCapabilityId): Boolean {
        val availability = states.firstOrNull { it.id == capability }?.availability
        return availability == CapabilityAvailability.READY ||
            (
                capability == AndroidCapabilityId.PHOTO_LIBRARY &&
                    availability == CapabilityAvailability.PARTIAL
                ) ||
            (
                capability == AndroidCapabilityId.CALENDAR &&
                    calendarRequiredAccess == CalendarCapabilityAccess.READ &&
                    availability == CapabilityAvailability.PARTIAL
                ) ||
            (
                capability == AndroidCapabilityId.CONTACTS &&
                    contactsRequiredAccess == ContactsCapabilityAccess.READ &&
                    availability == CapabilityAvailability.PARTIAL
                ) ||
            (
                capability == AndroidCapabilityId.LOCATION &&
                    locationRequiredAccess == LocationCapabilityAccess.APPROXIMATE &&
                    availability == CapabilityAvailability.PARTIAL
                )
    }
    val finishCapabilityRequest: (AndroidCapabilityRequestResult) -> Unit = { result ->
        capabilityRequestId?.let { requestId ->
            if (container.androidCapabilityRequestCoordinator.respond(requestId, result)) {
                onBack()
            }
        }
    }
    val latestFinishCapabilityRequest by rememberUpdatedState(finishCapabilityRequest)
    fun refreshTargetAndFinish(
        capability: AndroidCapabilityId,
        fallback: AndroidCapabilityRequestResult,
    ) {
        scope.launch {
            var availability = CapabilityAvailability.ERROR
            repeat(CAPABILITY_RESULT_REFRESH_ATTEMPTS) { attempt ->
                availability = container.androidCapabilityRegistry.refreshNow()
                    .first { it.id == capability }
                    .availability
                val ready = availability == CapabilityAvailability.READY ||
                    (
                        capability == AndroidCapabilityId.PHOTO_LIBRARY &&
                            availability == CapabilityAvailability.PARTIAL
                        ) ||
                    (
                        capability == AndroidCapabilityId.CALENDAR &&
                            calendarRequiredAccess == CalendarCapabilityAccess.READ &&
                            availability == CapabilityAvailability.PARTIAL
                        ) ||
                    (
                        capability == AndroidCapabilityId.CONTACTS &&
                            contactsRequiredAccess == ContactsCapabilityAccess.READ &&
                            availability == CapabilityAvailability.PARTIAL
                        ) ||
                    (
                        capability == AndroidCapabilityId.LOCATION &&
                            locationRequiredAccess == LocationCapabilityAccess.APPROXIMATE &&
                            availability == CapabilityAvailability.PARTIAL
                        )
                if (ready) {
                    latestFinishCapabilityRequest(AndroidCapabilityRequestResult.READY)
                    return@launch
                }
                if (
                    availability != CapabilityAvailability.SESSION_REQUIRED ||
                    attempt == CAPABILITY_RESULT_REFRESH_ATTEMPTS - 1
                ) {
                    latestFinishCapabilityRequest(fallback)
                    return@launch
                }
                delay(CAPABILITY_RESULT_REFRESH_DELAY_MILLIS)
            }
        }
    }
    val specialAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        container.androidCapabilityRegistry.refresh()
        requestedCapabilityId?.let { capability ->
            refreshTargetAndFinish(capability, AndroidCapabilityRequestResult.DENIED)
        }
    }
    fun launchTargetAllFilesSettings(): Boolean {
        allFilesSettingsIntents.forEach { intent ->
            try {
                specialAccessLauncher.launch(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next Android/OEM fallback.
            }
        }
        return false
    }
    val photoAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        container.androidCapabilityRegistry.refresh()
        if (
            capabilityRequestId != null &&
            requestedCapabilityId == AndroidCapabilityId.PHOTO_LIBRARY
        ) {
            refreshTargetAndFinish(
                AndroidCapabilityId.PHOTO_LIBRARY,
                AndroidCapabilityRequestResult.DENIED,
            )
            return@rememberLauncherForActivityResult
        }
        if (continueToFoldersAfterPhoto) {
            continueToFoldersAfterPhoto = false
            if (allFilesNeedSetup) {
                continueToFoldersAfterAllFiles = true
                openAllFilesSettings()
            } else if (foldersNeedSetup) {
                onOpenFolders(true)
            }
        }
    }
    val calendarAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        container.androidCapabilityRegistry.refresh()
        if (
            capabilityRequestId != null &&
            requestedCapabilityId == AndroidCapabilityId.CALENDAR
        ) {
            refreshTargetAndFinish(
                AndroidCapabilityId.CALENDAR,
                AndroidCapabilityRequestResult.DENIED,
            )
        }
    }
    fun launchCalendarPermissions(permissions: List<String>) {
        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            refreshTargetAndFinish(
                AndroidCapabilityId.CALENDAR,
                AndroidCapabilityRequestResult.DENIED,
            )
            return
        }
        val activity = context as? Activity
        val permanentlyDenied = activity != null && shouldOpenAppSettingsForPermissions(
            missingPermissions = missing,
            wasAsked = { permission -> wasCapabilityPermissionAsked(context, permission) },
            shouldShowRationale = { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            },
        )
        if (permanentlyDenied) {
            specialAccessLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        } else {
            markCapabilityPermissionsAsked(context, missing)
            calendarAccessLauncher.launch(missing.toTypedArray())
        }
    }
    val contactsAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        container.androidCapabilityRegistry.refresh()
        if (
            capabilityRequestId != null &&
            requestedCapabilityId == AndroidCapabilityId.CONTACTS
        ) {
            refreshTargetAndFinish(
                AndroidCapabilityId.CONTACTS,
                AndroidCapabilityRequestResult.DENIED,
            )
        }
    }
    fun launchContactsPermissions(permissions: List<String>) {
        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            refreshTargetAndFinish(
                AndroidCapabilityId.CONTACTS,
                AndroidCapabilityRequestResult.DENIED,
            )
            return
        }
        val activity = context as? Activity
        val permanentlyDenied = activity != null && shouldOpenAppSettingsForPermissions(
            missingPermissions = missing,
            wasAsked = { permission -> wasCapabilityPermissionAsked(context, permission) },
            shouldShowRationale = { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            },
        )
        if (permanentlyDenied) {
            specialAccessLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        } else {
            markCapabilityPermissionsAsked(context, missing)
            contactsAccessLauncher.launch(missing.toTypedArray())
        }
    }
    val locationAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        container.androidCapabilityRegistry.refresh()
        if (
            capabilityRequestId != null &&
            requestedCapabilityId == AndroidCapabilityId.LOCATION
        ) {
            refreshTargetAndFinish(
                AndroidCapabilityId.LOCATION,
                AndroidCapabilityRequestResult.DENIED,
            )
        }
    }
    fun launchLocationPermissions(permissions: List<String>) {
        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            refreshTargetAndFinish(
                AndroidCapabilityId.LOCATION,
                AndroidCapabilityRequestResult.DENIED,
            )
            return
        }
        val activity = context as? Activity
        val permanentlyDenied = activity != null && shouldOpenAppSettingsForPermissions(
            missingPermissions = missing,
            wasAsked = { permission -> wasCapabilityPermissionAsked(context, permission) },
            shouldShowRationale = { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            },
        )
        if (permanentlyDenied) {
            specialAccessLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        } else {
            markCapabilityPermissionsAsked(context, missing)
            // Keep coarse + fine together for Android 12+ precise-location requests.
            locationAccessLauncher.launch(permissions.distinct().toTypedArray())
        }
    }
    val notificationAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        container.androidCapabilityRegistry.refresh()
        if (
            capabilityRequestId != null &&
            requestedCapabilityId == AndroidCapabilityId.NOTIFICATIONS
        ) {
            refreshTargetAndFinish(
                AndroidCapabilityId.NOTIFICATIONS,
                AndroidCapabilityRequestResult.DENIED,
            )
        }
    }
    fun launchNotificationPermission() {
        val permission = POST_NOTIFICATIONS_PERMISSION
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (runtimePermissionGranted) {
            specialAccessLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            return
        }
        val activity = context as? Activity
        val permanentlyDenied = activity != null && shouldOpenAppSettingsForPermissions(
            missingPermissions = listOf(permission),
            wasAsked = { wasCapabilityPermissionAsked(context, permission) },
            shouldShowRationale = {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            },
        )
        if (permanentlyDenied) {
            specialAccessLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        } else {
            markCapabilityPermissionsAsked(context, listOf(permission))
            notificationAccessLauncher.launch(permission)
        }
    }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                context,
                ScreenCaptureSessionService.startIntent(context, result.resultCode, data),
            )
        }
        container.androidCapabilityRegistry.refresh()
        if (
            capabilityRequestId != null &&
            requestedCapabilityId == AndroidCapabilityId.SCREEN_CAPTURE
        ) {
            refreshTargetAndFinish(
                AndroidCapabilityId.SCREEN_CAPTURE,
                AndroidCapabilityRequestResult.DENIED,
            )
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
        } else if (allFilesNeedSetup) {
            continueToFoldersAfterAllFiles = true
            openAllFilesSettings()
        } else if (foldersNeedSetup) {
            onOpenFolders(true)
        }
    }
    LaunchedEffect(allFilesNeedSetup, continueToFoldersAfterAllFiles) {
        if (continueToFoldersAfterAllFiles && !allFilesNeedSetup) {
            continueToFoldersAfterAllFiles = false
            if (foldersNeedSetup) onOpenFolders(true)
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
    var shizukuPermissionAutoRequested by rememberSaveable(capabilityRequestId) {
        mutableStateOf(false)
    }
    val launchRequestedCapability = {
        val capability = requestedCapabilityId
        when {
            capabilityRequestId == null -> Unit
            capability == null -> latestFinishCapabilityRequest(
                AndroidCapabilityRequestResult.UNAVAILABLE,
            )
            isCapabilityReady(capability) &&
                capability != AndroidCapabilityId.SAF_FOLDERS ->
                latestFinishCapabilityRequest(AndroidCapabilityRequestResult.READY)
            states.firstOrNull { it.id == capability }?.availability ==
                CapabilityAvailability.UNSUPPORTED ->
                latestFinishCapabilityRequest(AndroidCapabilityRequestResult.UNAVAILABLE)
            capability == AndroidCapabilityId.SAF_FOLDERS -> onOpenFolders(true)
            capability == AndroidCapabilityId.PHOTO_LIBRARY -> {
                val permissions = photoLibraryPermissionRequest(Build.VERSION.SDK_INT)
                if (permissions.isEmpty()) {
                    latestFinishCapabilityRequest(AndroidCapabilityRequestResult.UNAVAILABLE)
                } else {
                    photoAccessLauncher.launch(permissions.toTypedArray())
                }
            }
            capability == AndroidCapabilityId.CALENDAR -> {
                val access = calendarRequiredAccess
                if (access == null) {
                    latestFinishCapabilityRequest(AndroidCapabilityRequestResult.UNAVAILABLE)
                } else {
                    val permissions = calendarPermissionRequest(access) { permission ->
                        ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (permissions.isEmpty()) {
                        refreshTargetAndFinish(
                            AndroidCapabilityId.CALENDAR,
                            AndroidCapabilityRequestResult.DENIED,
                        )
                    } else {
                        launchCalendarPermissions(permissions)
                    }
                }
            }
            capability == AndroidCapabilityId.CONTACTS -> {
                val access = contactsRequiredAccess
                if (access == null) {
                    latestFinishCapabilityRequest(AndroidCapabilityRequestResult.UNAVAILABLE)
                } else {
                    val permissions = contactsPermissionRequest(access) { permission ->
                        ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (permissions.isEmpty()) {
                        refreshTargetAndFinish(
                            AndroidCapabilityId.CONTACTS,
                            AndroidCapabilityRequestResult.DENIED,
                        )
                    } else {
                        launchContactsPermissions(permissions)
                    }
                }
            }
            capability == AndroidCapabilityId.LOCATION -> {
                val access = locationRequiredAccess
                if (access == null) {
                    latestFinishCapabilityRequest(AndroidCapabilityRequestResult.UNAVAILABLE)
                } else {
                    val permissions = locationPermissionRequest(access) { permission ->
                        ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (permissions.isEmpty()) {
                        refreshTargetAndFinish(
                            AndroidCapabilityId.LOCATION,
                            AndroidCapabilityRequestResult.DENIED,
                        )
                    } else {
                        launchLocationPermissions(permissions)
                    }
                }
            }
            capability == AndroidCapabilityId.NOTIFICATIONS -> {
                launchNotificationPermission()
            }
            capability == AndroidCapabilityId.ACCESSIBILITY_CONTROL -> {
                specialAccessLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            capability == AndroidCapabilityId.SCREEN_CAPTURE -> {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            }
            capability == AndroidCapabilityId.ALL_FILES -> {
                if (!launchTargetAllFilesSettings()) {
                    latestFinishCapabilityRequest(AndroidCapabilityRequestResult.UNAVAILABLE)
                }
            }
            capability == AndroidCapabilityId.SHIZUKU_SHELL_UID -> {
                if (
                    states.firstOrNull { it.id == capability }?.availability ==
                    CapabilityAvailability.NOT_GRANTED
                ) {
                    shizukuPermissionAutoRequested = true
                }
                container.shizukuController.performPrimaryAction(context)
            }
        }
    }
    val latestLaunchRequestedCapability by rememberUpdatedState(launchRequestedCapability)
    var capabilityAutoStarted by rememberSaveable(capabilityRequestId) {
        mutableStateOf(false)
    }
    LaunchedEffect(capabilityRequestId, requestedCapabilityId, states.isNotEmpty()) {
        if (
            capabilityRequestId != null &&
            requestedCapabilityId != null &&
            states.isNotEmpty() &&
            !capabilityAutoStarted
        ) {
            if (
                requestedCapabilityId == AndroidCapabilityId.SHIZUKU_SHELL_UID &&
                states.firstOrNull { it.id == requestedCapabilityId }?.availability ==
                CapabilityAvailability.NOT_GRANTED
            ) {
                shizukuPermissionAutoRequested = true
            }
            capabilityAutoStarted = true
            withFrameNanos { }
            withFrameNanos { }
            delay(300)
            latestLaunchRequestedCapability()
        }
    }
    LaunchedEffect(capabilityRequestId, requestedCapabilityId, states) {
        val requestId = capabilityRequestId ?: return@LaunchedEffect
        val capability = requestedCapabilityId ?: return@LaunchedEffect
        val availability = states.firstOrNull { it.id == capability }?.availability
        if (
            capability == AndroidCapabilityId.SHIZUKU_SHELL_UID &&
            capabilityAutoStarted &&
            !shizukuPermissionAutoRequested &&
            pendingCapabilityRequest?.requestId == requestId &&
            availability == CapabilityAvailability.NOT_GRANTED
        ) {
            shizukuPermissionAutoRequested = true
            container.shizukuController.performPrimaryAction(context)
        }
        if (
            capability != AndroidCapabilityId.SAF_FOLDERS &&
            pendingCapabilityRequest?.requestId == requestId &&
            isCapabilityReady(capability)
        ) {
            latestFinishCapabilityRequest(AndroidCapabilityRequestResult.READY)
        }
    }
    LaunchedEffect(capabilityRequestId, pendingCapabilityRequest?.requestId) {
        if (
            capabilityRequestId != null &&
            pendingCapabilityRequest?.requestId != capabilityRequestId
        ) {
            onBack()
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
    val closeCapabilities = {
        capabilityRequestId?.let { requestId ->
            container.androidCapabilityRequestCoordinator.respond(
                requestId,
                AndroidCapabilityRequestResult.DENIED,
            )
        }
        onBack()
    }
    BackHandler(onBack = closeCapabilities)
    DeviceCapabilitiesScreen(
        states = states,
        onBack = closeCapabilities,
        onRefresh = container.androidCapabilityRegistry::refresh,
        onSetUpFullAccess = startAvailableAccessSetup,
        onOpenFolders = { onOpenFolders(false) },
        onManageAccessibility = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        },
        onManageScreenCapture = {
            if (MomodingScreenCaptureRuntime.mediaProjection.state.value.active) {
                MomodingScreenCaptureRuntime.mediaProjection.stop(context)
            } else {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            }
        },
        onManageAllFiles = {
            openAllFilesSettings()
        },
        onManageShizuku = {
            container.shizukuController.performPrimaryAction(context)
        },
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
        onManageCalendarAccess = {
            val calendarAccess = states.firstOrNull {
                it.id == AndroidCapabilityId.CALENDAR
            }
            if (calendarAccess?.availability in setOf(
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
                launchCalendarPermissions(
                    listOf(Manifest.permission.READ_CALENDAR),
                )
            }
        },
        onManageContactsAccess = {
            val contactsAccess = states.firstOrNull {
                it.id == AndroidCapabilityId.CONTACTS
            }
            if (contactsAccess?.availability in setOf(
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
                launchContactsPermissions(
                    listOf(Manifest.permission.READ_CONTACTS),
                )
            }
        },
        onManageLocationAccess = {
            val locationAccess = states.firstOrNull {
                it.id == AndroidCapabilityId.LOCATION
            }
            if (locationAccess?.availability in setOf(
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
                launchLocationPermissions(
                    listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        },
        onManageNotificationAccess = {
            val notificationAccess = states.firstOrNull {
                it.id == AndroidCapabilityId.NOTIFICATIONS
            }
            if (notificationAccess?.availability == CapabilityAvailability.READY) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            } else {
                launchNotificationPermission()
            }
        },
    )
}

private fun wasCapabilityPermissionAsked(
    context: android.content.Context,
    permission: String,
): Boolean = context.getSharedPreferences(
    CAPABILITY_PERMISSION_PREFS,
    android.content.Context.MODE_PRIVATE,
).getBoolean(permission, false)

private fun markCapabilityPermissionsAsked(
    context: android.content.Context,
    permissions: List<String>,
) {
    context.getSharedPreferences(
        CAPABILITY_PERMISSION_PREFS,
        android.content.Context.MODE_PRIVATE,
    ).edit {
        permissions.forEach { permission -> putBoolean(permission, true) }
    }
}

private const val CAPABILITY_RESULT_REFRESH_ATTEMPTS = 10
private const val CAPABILITY_RESULT_REFRESH_DELAY_MILLIS = 200L
private const val CAPABILITY_PERMISSION_PREFS = "tool_permission_requests"
private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

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
private fun NewTaskRouteContent(
    route: NewTaskRoute,
    container: AppContainer,
    modelId: String,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    onOpenFullAccessSetup: () -> Unit,
    onOpenNavigation: () -> Unit,
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
    NewTaskScreen(
        state = state,
        onAction = viewModel::dispatch,
        interactionPolicy = NewTaskInteractionPolicy.All,
        importNotice = route.importNotice,
        onOpenNavigation = onOpenNavigation,
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
    onOpenNavigation: () -> Unit,
    onNewTask: () -> Unit,
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
    TaskDetailScreen(
        state = state,
        onAction = viewModel::dispatch,
        questionState = questionState,
        onQuestionIntent = { intent -> questionViewModel?.dispatch(intent) },
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onOpenNavigation = onOpenNavigation,
        onNewTask = onNewTask,
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
        Text("This request is not currently actionable. Dismiss does not approve or reject it.")
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
