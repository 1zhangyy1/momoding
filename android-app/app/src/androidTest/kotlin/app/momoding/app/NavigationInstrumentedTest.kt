package app.momoding.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.auth.HostClientProfile
import app.momoding.core.data.AttentionDataSource
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionRecord
import app.momoding.core.data.AttentionRecordState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.AttentionTerminalKind
import app.momoding.core.data.AttentionDraftWrite
import app.momoding.core.data.TaskAttentionSummary
import app.momoding.core.transport.AttentionUserDecision
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.feature.settings.AgentProfileState
import app.momoding.feature.settings.SettingsAction
import app.momoding.feature.settings.SettingsUiState
import app.momoding.feature.attention.AttentionIdentity
import app.momoding.feature.attention.AttentionOneShot
import app.momoding.feature.attention.AttentionReturnReason
import app.momoding.feature.attention.AttentionViewModel
import app.momoding.feature.taskdetail.ATTENTION_UNAVAILABLE_NOTICE
import app.momoding.feature.taskdetail.TaskAttentionUiModel
import app.momoding.feature.taskdetail.TaskDetailLoadState
import app.momoding.feature.taskdetail.TaskDetailRunState
import app.momoding.feature.taskdetail.TaskDetailScreen
import app.momoding.feature.taskdetail.TaskDetailUiState
import app.momoding.feature.taskdetail.TaskDetailOneShot
import app.momoding.feature.taskdetail.TASK_DETAIL_TITLE_FOCUS_KEY
import app.momoding.feature.tasks.TaskHomeOneShot
import app.momoding.ui.navigation.AttentionRoute
import app.momoding.ui.navigation.MomodingBottomSheetDismissRegistry
import app.momoding.ui.navigation.FileChangeRoute
import app.momoding.ui.navigation.SettingsRoute
import app.momoding.ui.navigation.TaskDetailRoute
import app.momoding.ui.navigation.TaskHomeRoute
import app.momoding.ui.navigation.taskDetailAttentionFocusKey
import app.momoding.ui.theme.MomodingTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pairAndUnpairReplaceRootWithoutReturningToHostGate() {
        val state = mutableStateOf(SettingsUiState(transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED)))
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) { MomodingApp(state.value, onAction = {}) }
        }
        compose.onNodeWithText("Connect your Pi Host").assertIsDisplayed()

        compose.runOnIdle { state.value = readyState() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
        compose.onNodeWithText("Connect your Pi Host").assertDoesNotExist()

        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithText("Developer edition").assertIsDisplayed()

        compose.runOnIdle { state.value = SettingsUiState(transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED)) }
        compose.onNodeWithText("Connect your Pi Host").assertIsDisplayed()
        compose.onNodeWithText("Developer edition").assertDoesNotExist()

        compose.runOnIdle { state.value = readyState() }
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
        compose.onNodeWithText("Connect your Pi Host").assertDoesNotExist()
    }

    @Test
    fun startingDoesNotDestroyRestoredRouteAndRootDetailBackFallsToTasks() {
        val state = mutableStateOf(SettingsUiState())
        var backDispatcher: OnBackPressedDispatcher? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    state.value,
                    onAction = {},
                    initialBackStack = listOf(TaskDetailRoute("task-restored")),
                )
            }
        }

        compose.onNodeWithText("Task task-restored").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Task task-restored").assertIsDisplayed()
        compose.runOnIdle { state.value = readyState() }
        compose.onNodeWithText("Task task-restored").assertIsDisplayed()

        compose.runOnIdle { state.value = SettingsUiState() }
        compose.onNodeWithText("Task task-restored").assertIsDisplayed()
        compose.runOnIdle { state.value = readyState() }
        compose.onNodeWithText("Task task-restored").assertIsDisplayed()

        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Task task-restored").assertDoesNotExist()
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
    }

    @Test
    fun rootSettingsBackUsesSafeTaskHomeFallback() {
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(readyState(), onAction = {}, initialBackStack = listOf(SettingsRoute))
            }
        }

        compose.onNodeWithText("Developer edition").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
    }

    @Test
    fun settingsDoesNotDuplicateAndBackReturnsToTasks() {
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(readyState(), onAction = {}, initialBackStack = listOf(TaskHomeRoute))
            }
        }

        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithText("Developer edition").assertIsDisplayed()
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
    }

    @Test
    fun durableTaskHomeOneShotsOwnTaskAndAttentionNavigation() {
        val oneShots = MutableSharedFlow<TaskHomeOneShot>(extraBufferCapacity = 2)
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskHomeOneShots = oneShots,
                    initialBackStack = listOf(TaskHomeRoute),
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { check(oneShots.tryEmit(TaskHomeOneShot.OpenTask("task-durable", cached = false))) }
        compose.onNodeWithText("Task task-durable").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()

        compose.runOnIdle {
            check(oneShots.tryEmit(TaskHomeOneShot.OpenAttention("task-attention", "call-attention")))
        }
        compose.onNodeWithText("Action needs attention").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Task task-attention").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
    }

    @Test
    fun fileChangeAttentionOpensTheExactFullScreenReviewAndBackReturnsToTask() {
        val oneShots = MutableSharedFlow<TaskDetailOneShot>(extraBufferCapacity = 1)
        val openedRoutes = mutableListOf<FileChangeRoute>()
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = { route, _, _, _, _, onOneShot ->
                        LaunchedEffect(oneShots, route) { oneShots.collect(onOneShot) }
                        Text("Task under review ${route.taskId}")
                    },
                    fileChangeEntry = { route, _ ->
                        LaunchedEffect(route) { openedRoutes += route }
                        Text("File review ${route.commitCallId}")
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute("task-file-review"),
                    ),
                )
            }
        }

        compose.runOnIdle {
            check(
                oneShots.tryEmit(
                    TaskDetailOneShot.OpenAttention(
                        callId = "commit-file-review",
                        fileChanges = true,
                    ),
                ),
            )
        }
        compose.onNodeWithText("File review commit-file-review").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf(FileChangeRoute("task-file-review", "commit-file-review")),
                openedRoutes,
            )
            backDispatcher?.onBackPressed()
        }
        compose.onNodeWithText("Task under review task-file-review").assertIsDisplayed()
    }

    @Test
    fun fullAccessSelectionOpensTheCapabilitySetupRoute() {
        val oneShots = MutableSharedFlow<TaskDetailOneShot>(extraBufferCapacity = 1)
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = { route, _, _, _, _, onOneShot ->
                        LaunchedEffect(oneShots, route) { oneShots.collect(onOneShot) }
                        Text("Task ${route.taskId}")
                    },
                    deviceCapabilitiesEntry = { route, _, _ ->
                        Text(
                            "Full access setup ${route.startFullAccessSetup} " +
                                "${route.setupRunId != null}",
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute("task-full-access"),
                    ),
                )
            }
        }

        compose.runOnIdle {
            check(oneShots.tryEmit(TaskDetailOneShot.OpenFullAccessSetup))
        }
        compose.onNodeWithText("Full access setup true true").assertIsDisplayed()
    }

    @Test
    fun attentionDismissReturnsToExactDetailCardFocus() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = { actions += it },
                    taskDetailEntry = {
                            route,
                            restoreFocusKey,
                            _,
                            onFocusRestored,
                            _,
                            _,
                        ->
                        TaskDetailScreen(
                            state = TaskDetailUiState(
                                taskId = route.taskId,
                                title = "Task one",
                                loadState = TaskDetailLoadState.READY,
                                runState = TaskDetailRunState.WAITING,
                                attention = TaskAttentionUiModel("call-1", "Choose a validation"),
                            ),
                            onAction = {},
                            restoreFocusKey = restoreFocusKey,
                            onFocusRestored = onFocusRestored,
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute("task-1"),
                        AttentionRoute(
                            "task-1",
                            "call-1",
                            taskDetailAttentionFocusKey("task-1", "call-1"),
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithText("Action needs attention").assertIsFocused()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Action needs attention").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onNode(
                    androidx.compose.ui.test.hasTestTag("action-OpenAttention"),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().config
                    .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true
            }.getOrDefault(false)
        }
        compose.onNode(
            androidx.compose.ui.test.hasTestTag("action-OpenAttention"),
            useUnmergedTree = true,
        ).assertIsFocused()
        compose.runOnIdle { check(actions.isEmpty()) }
    }

    @Test
    fun attentionSystemBackOnlyDismissesOverlay() {
        val actions = mutableListOf<SettingsAction>()
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = { actions += it },
                    initialBackStack = attentionBackStack(),
                )
            }
        }

        compose.onNodeWithText("Action needs attention").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Action needs attention").assertDoesNotExist()
        compose.onNodeWithText("Task task-1").assertIsDisplayed()
        compose.runOnIdle { check(actions.isEmpty()) }
    }

    @Test
    fun attentionOutsideTapOnlyDismissesOverlay() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = { actions += it },
                    initialBackStack = attentionBackStack(),
                )
            }
        }

        compose.onNodeWithText("Action needs attention").assertIsDisplayed()
        compose.onNode(isRoot() and hasAnyDescendant(hasText("Action needs attention")))
            .performTouchInput { click(Offset(centerX, 80f)) }
        compose.onNodeWithText("Action needs attention").assertDoesNotExist()
        compose.onNodeWithText("Task task-1").assertIsDisplayed()
        compose.runOnIdle { check(actions.isEmpty()) }
    }

    @Test
    fun attentionSwipeDownOnlyDismissesOverlay() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = { actions += it },
                    initialBackStack = attentionBackStack(),
                )
            }
        }

        compose.onNodeWithText("Action needs attention").assertIsDisplayed()
        compose.onNode(isRoot() and hasAnyDescendant(hasText("Action needs attention"))).performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.8f),
                end = Offset(centerX, height * 0.95f),
                durationMillis = 500,
            )
        }
        compose.onNodeWithText("Action needs attention").assertDoesNotExist()
        compose.onNodeWithText("Task task-1").assertIsDisplayed()
        compose.runOnIdle { check(actions.isEmpty()) }
    }

    @Test
    fun productionPendingSystemBackRunsExactTypedDismissBeforePopping() {
        val route = attentionRoute("task-production-dismiss", "call-production-dismiss")
        val source = FakeRouteAttentionSource(
            route,
            AttentionRecordState.Available(attentionRecord(route)),
        )
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    attentionEntry = { entryRoute, dismissRegistry, onOneShot ->
                        AttentionRouteContent(
                            route = entryRoute,
                            ownerFactory = attentionOwnerFactory(source),
                            dismissRegistry = dismissRegistry,
                            onOneShot = onOneShot,
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute(route.taskId),
                        route,
                    ),
                )
            }
        }

        compose.onNodeWithText("Choose a validation").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Choose a validation").assertDoesNotExist()
        compose.onNodeWithText("Task ${route.taskId}").assertIsDisplayed()
        compose.runOnIdle { check(source.dismissCalls == 1) }
    }

    @Test
    fun productionRespondingLocksBackOutsideTapAndSwipe() {
        val route = attentionRoute("task-production-responding", "call-production-responding")
        val source = FakeRouteAttentionSource(
            route,
            AttentionRecordState.Available(
                attentionRecord(route).copy(
                    ledgerState = AttentionLedgerState.TERMINAL,
                    deliveryState = AttentionDeliveryState.READY_TO_SEND,
                    responseState = AttentionResponseState.RESPONDING,
                    terminalKind = AttentionTerminalKind.SUCCEEDED,
                ),
            ),
        )
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    attentionEntry = { entryRoute, dismissRegistry, onOneShot ->
                        AttentionRouteContent(
                            route = entryRoute,
                            ownerFactory = attentionOwnerFactory(source),
                            dismissRegistry = dismissRegistry,
                            onOneShot = onOneShot,
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute(route.taskId),
                        route,
                    ),
                )
            }
        }

        compose.onNodeWithText("Waiting for Momoding").assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Waiting for Momoding").assertIsDisplayed()

        compose.onNode(isRoot() and hasAnyDescendant(hasText("Waiting for Momoding")))
            .performTouchInput { click(Offset(centerX, 80f)) }
        compose.onNodeWithText("Waiting for Momoding").assertIsDisplayed()

        compose.onNode(isRoot() and hasAnyDescendant(hasText("Waiting for Momoding")))
            .performTouchInput {
                swipe(
                    start = Offset(centerX, height * 0.8f),
                    end = Offset(centerX, height * 0.95f),
                    durationMillis = 500,
                )
            }
        compose.onNodeWithText("Waiting for Momoding").assertIsDisplayed()
        compose.runOnIdle { check(source.dismissCalls == 0) }
    }

    @Test
    fun productionSwipeKeepsSheetVisibleWhenDurableDismissFails() {
        val route = attentionRoute("task-production-dismiss-fail", "call-production-dismiss-fail")
        val source = FakeRouteAttentionSource(
            expectedRoute = route,
            initial = AttentionRecordState.Available(attentionRecord(route)),
            failDismiss = true,
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    attentionEntry = { entryRoute, dismissRegistry, onOneShot ->
                        AttentionRouteContent(
                            route = entryRoute,
                            ownerFactory = attentionOwnerFactory(source),
                            dismissRegistry = dismissRegistry,
                            onOneShot = onOneShot,
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute(route.taskId),
                        route,
                    ),
                )
            }
        }

        compose.onNodeWithText("Choose a validation").assertIsDisplayed()
        compose.onNode(isRoot() and hasAnyDescendant(hasText("Choose a validation")))
            .performTouchInput {
                swipe(
                    start = Offset(centerX, height * 0.8f),
                    end = Offset(centerX, height * 0.95f),
                    durationMillis = 500,
                )
            }
        compose.waitUntil(timeoutMillis = 5_000) { source.dismissCalls == 1 }
        compose.onNodeWithText("Choose a validation").assertIsDisplayed()
    }

    @Test
    fun productionReplacementRejectsStaleSceneKeyBeforeCurrentBackDismisses() {
        val route = attentionRoute("task-production-replacement", "call-production-replacement")
        val source = FakeRouteAttentionSource(
            route,
            AttentionRecordState.Available(attentionRecord(route)),
        )
        val staleContentKey = "stale-${route.taskId}:${route.callId}"
        val staleOwner = Any()
        var staleRequests = 0
        var dismissRegistry: MomodingBottomSheetDismissRegistry? = null
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    attentionEntry = { entryRoute, registry, onOneShot ->
                        dismissRegistry = registry
                        DisposableEffect(entryRoute, registry) {
                            registry.register(
                                key = staleContentKey,
                                routeIdentity = entryRoute,
                                owner = staleOwner,
                                enabled = true,
                                request = { staleRequests += 1 },
                            )
                            onDispose { registry.unregister(staleContentKey, staleOwner) }
                        }
                        AttentionRouteContent(
                            route = entryRoute,
                            ownerFactory = attentionOwnerFactory(source),
                            dismissRegistry = registry,
                            onOneShot = onOneShot,
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute(route.taskId),
                        route,
                    ),
                )
            }
        }

        compose.onNodeWithText("Choose a validation").assertIsDisplayed()
        compose.runOnIdle {
            check(dismissRegistry?.requestDismiss(staleContentKey) == false)
            check(staleRequests == 0)
            check(source.dismissCalls == 0)
        }
        compose.onNodeWithText("Choose a validation").assertIsDisplayed()

        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Choose a validation").assertDoesNotExist()
        compose.onNodeWithText("Task ${route.taskId}").assertIsDisplayed()
        compose.runOnIdle {
            check(staleRequests == 0)
            check(source.dismissCalls == 1)
        }
    }

    @Test
    fun routeScopedAttentionReturnPopsOnceAndCarriesOnlyFixedUnavailableNotice() {
        val oneShots = MutableSharedFlow<AttentionOneShot>(extraBufferCapacity = 2)
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = {
                            route,
                            _,
                            attentionReturn,
                            _,
                            _,
                            _,
                        ->
                        Text(
                            if (attentionReturn?.reason == AttentionReturnReason.UNAVAILABLE) {
                                ATTENTION_UNAVAILABLE_NOTICE
                            } else {
                                "Detail ${route.taskId}"
                            },
                        )
                    },
                    attentionEntry = { route, _, onOneShot ->
                        LaunchedEffect(oneShots, route) {
                            oneShots.collect(onOneShot)
                        }
                        Text("Attention ${route.taskId}/${route.callId}")
                    },
                    initialBackStack = attentionBackStack(),
                )
            }
        }
        compose.onNodeWithText("Attention task-1/call-1").assertIsDisplayed()

        compose.runOnIdle {
            check(
                oneShots.tryEmit(
                    AttentionOneShot.ReturnToTask(
                        effectId = "task-1:call-1:route-exit",
                        reason = AttentionReturnReason.UNAVAILABLE,
                    ),
                ),
            )
        }
        compose.onNodeWithText(ATTENTION_UNAVAILABLE_NOTICE).assertIsDisplayed()

        compose.runOnIdle {
            check(
                oneShots.tryEmit(
                    AttentionOneShot.ReturnToTask(
                        effectId = "task-1:call-1:route-exit",
                        reason = AttentionReturnReason.UNAVAILABLE,
                    ),
                ),
            )
        }
        compose.onNodeWithText(ATTENTION_UNAVAILABLE_NOTICE).assertIsDisplayed()
        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
    }

    @Test
    fun respondingAnnouncementRunsOnceForTheExactTopRoute() {
        val oneShots = MutableSharedFlow<AttentionOneShot>(extraBufferCapacity = 4)
        val announcements = mutableListOf<String>()
        val activeRoute = attentionRoute("task-1", "call-1")
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = { route, _, _, _, _, _ ->
                        Text("Detail ${route.taskId}")
                    },
                    attentionEntry = { route, _, onOneShot ->
                        LaunchedEffect(oneShots, route) {
                            oneShots.collect(onOneShot)
                        }
                        Text("Attention ${route.taskId}/${route.callId}")
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute(activeRoute.taskId),
                        activeRoute,
                    ),
                    onAccessibilityAnnouncement = { announcements += it },
                )
            }
        }
        compose.onNodeWithText("Attention task-1/call-1").assertIsDisplayed()

        compose.runOnIdle {
            check(
                oneShots.tryEmit(
                    AttentionOneShot.AnnounceResponding("task-1:call-1:responding"),
                ),
            )
            check(
                oneShots.tryEmit(
                    AttentionOneShot.AnnounceResponding("task-1:call-1:responding"),
                ),
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) { announcements.size == 1 }
        compose.runOnIdle {
            check(announcements == listOf("Response pending"))
        }
    }

    @Test
    fun respondingAnnouncementReachesBoundTalkBackThroughProductionSink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val accessibility = instrumentation.targetContext.getSystemService(
            Context.ACCESSIBILITY_SERVICE,
        ) as AccessibilityManager
        assumeTrue(accessibility.isEnabled && accessibility.isTouchExplorationEnabled)

        val oneShots = MutableSharedFlow<AttentionOneShot>(extraBufferCapacity = 2)
        val activeRoute = attentionRoute("task-1", "call-1")
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = { route, _, _, _, _, _ ->
                        Text("Detail ${route.taskId}")
                    },
                    attentionEntry = { route, _, onOneShot ->
                        LaunchedEffect(oneShots, route) {
                            oneShots.collect(onOneShot)
                        }
                        Text("Attention ${route.taskId}/${route.callId}")
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute(activeRoute.taskId),
                        activeRoute,
                    ),
                )
            }
        }
        compose.onNodeWithText("Attention task-1/call-1").assertIsDisplayed()
        SystemClock.sleep(4_000)

        Log.i(TALKBACK_SMOKE_TAG, "ANNOUNCEMENT_BEGIN")
        compose.runOnIdle {
            check(
                oneShots.tryEmit(
                    AttentionOneShot.AnnounceResponding("task-1:call-1:responding"),
                ),
            )
            check(
                oneShots.tryEmit(
                    AttentionOneShot.AnnounceResponding("task-1:call-1:responding"),
                ),
            )
        }
        compose.waitForIdle()
        SystemClock.sleep(3_000)
        Log.i(TALKBACK_SMOKE_TAG, "ANNOUNCEMENT_END")
    }

    @Test
    fun staleDetailBackOneShotCannotPopAnActiveAttentionRoute() {
        val detailOneShots = MutableSharedFlow<TaskDetailOneShot>(extraBufferCapacity = 1)
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = { route, _, _, _, _, onOneShot ->
                        LaunchedEffect(detailOneShots, route) {
                            detailOneShots.collect(onOneShot)
                        }
                        Text("Detail underlay ${route.taskId}")
                    },
                    initialBackStack = attentionBackStack(),
                )
            }
        }
        compose.onNodeWithText("Action needs attention").assertIsDisplayed()

        compose.runOnIdle { check(detailOneShots.tryEmit(TaskDetailOneShot.Back)) }

        compose.onNodeWithText("Action needs attention").assertIsDisplayed()
    }

    @Test
    fun terminalCompletedReturnFocusesHeadingBeforeDelayedCardRemoval() {
        assertTerminalReturnFocusesHeading(
            reason = AttentionReturnReason.COMPLETED,
            nextAttention = null,
        )
    }

    @Test
    fun terminalCancelledReturnFocusesHeadingBeforeDelayedCardRemoval() {
        assertTerminalReturnFocusesHeading(
            reason = AttentionReturnReason.CANCELLED,
            nextAttention = null,
        )
    }

    @Test
    fun unavailableReturnFocusesHeadingBeforeDelayedPrimaryReplacement() {
        assertTerminalReturnFocusesHeading(
            reason = AttentionReturnReason.UNAVAILABLE,
            nextAttention = TaskAttentionUiModel("call-2", "Replacement request"),
        )
    }

    @Test
    fun staleAndDuplicateHomeAndDetailAttentionOneShotsCannotGrowActiveStack() {
        val homeOneShots = MutableSharedFlow<TaskHomeOneShot>(extraBufferCapacity = 4)
        val detailOneShots = MutableSharedFlow<TaskDetailOneShot>(extraBufferCapacity = 2)
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskHomeOneShots = homeOneShots,
                    taskDetailEntry = { route, _, _, _, _, onOneShot ->
                        LaunchedEffect(detailOneShots, route) {
                            detailOneShots.collect(onOneShot)
                        }
                        Text("Detail underlay ${route.taskId}")
                    },
                    initialBackStack = listOf(TaskHomeRoute),
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            check(homeOneShots.tryEmit(TaskHomeOneShot.OpenAttention("task-1", "call-1")))
        }
        compose.onNodeWithText("Action needs attention").assertIsDisplayed()

        compose.runOnIdle {
            check(homeOneShots.tryEmit(TaskHomeOneShot.OpenAttention("task-1", "call-1")))
            check(homeOneShots.tryEmit(TaskHomeOneShot.OpenAttention("task-stale", "call-stale")))
            check(detailOneShots.tryEmit(TaskDetailOneShot.OpenAttention("call-stale")))
        }
        compose.onNodeWithText("Action needs attention").assertIsDisplayed()

        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Action needs attention").assertDoesNotExist()
        compose.onNodeWithText("Detail underlay task-1").assertIsDisplayed()

        compose.runOnIdle {
            check(homeOneShots.tryEmit(TaskHomeOneShot.OpenAttention("task-stale", "call-stale")))
        }
        compose.onNodeWithText("Detail underlay task-1").assertIsDisplayed()

        compose.runOnIdle { backDispatcher?.onBackPressed() }
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
    }

    @Test
    fun productionAttentionOwnerBindsExactRoutesAndReturnsLiveCompletionAndCancelOnce() {
        val completedRoute = attentionRoute("task-owner-complete", "call-owner-complete")
        val cancelledRoute = attentionRoute("task-owner-cancel", "call-owner-cancel")
        val completedSource = FakeRouteAttentionSource(
            completedRoute,
            AttentionRecordState.Available(attentionRecord(completedRoute)),
        )
        val cancelledSource = FakeRouteAttentionSource(
            cancelledRoute,
            AttentionRecordState.Available(attentionRecord(cancelledRoute)),
        )
        val effects = mutableListOf<Pair<AttentionRoute, AttentionOneShot>>()

        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                val dismissRegistry = remember {
                    MomodingBottomSheetDismissRegistry()
                }
                Box {
                    listOf(
                        completedRoute to completedSource,
                        cancelledRoute to cancelledSource,
                    ).forEach { (route, source) ->
                        key(route.taskId, route.callId) {
                            AttentionRouteContent(
                                route = route,
                                ownerFactory = attentionOwnerFactory(source),
                                dismissRegistry = dismissRegistry,
                                onOneShot = { effects += route to it },
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            completedSource.observedExactOnly() && cancelledSource.observedExactOnly()
        }
        compose.runOnIdle {
            check(effects.isEmpty())
            completedSource.publish(
                attentionRecord(completedRoute).copy(
                    ledgerState = AttentionLedgerState.TERMINAL,
                    deliveryState = AttentionDeliveryState.PI_DELIVERED,
                    responseState = AttentionResponseState.RESOLVED,
                    terminalKind = AttentionTerminalKind.SUCCEEDED,
                ),
            )
            cancelledSource.publish(
                attentionRecord(cancelledRoute).copy(
                    ledgerState = AttentionLedgerState.CANCELLED,
                    responseState = AttentionResponseState.CANCELLED,
                    terminalKind = AttentionTerminalKind.CANCELLED,
                ),
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            effects.count { it.second is AttentionOneShot.ReturnToTask } == 2
        }
        compose.runOnIdle {
            completedSource.publish(completedSource.state.value)
            cancelledSource.publish(cancelledSource.state.value)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val returns = effects.mapNotNull { (route, effect) ->
                (effect as? AttentionOneShot.ReturnToTask)?.let { route to it }
            }
            check(
                returns == listOf(
                    completedRoute to AttentionOneShot.ReturnToTask(
                        effectId = "${completedRoute.taskId}:${completedRoute.callId}:route-exit",
                        reason = AttentionReturnReason.COMPLETED,
                    ),
                    cancelledRoute to AttentionOneShot.ReturnToTask(
                        effectId = "${cancelledRoute.taskId}:${cancelledRoute.callId}:route-exit",
                        reason = AttentionReturnReason.CANCELLED,
                    ),
                ),
            )
        }
    }

    @Test
    fun productionAttentionOwnerKeepsTerminalRestoreAndClosesAllUnavailableStates() {
        val restoredRoute = attentionRoute("task-owner-restored", "call-owner-restored")
        val restoredSource = FakeRouteAttentionSource(
            restoredRoute,
            AttentionRecordState.Available(
                attentionRecord(restoredRoute).copy(
                    ledgerState = AttentionLedgerState.TERMINAL,
                    deliveryState = AttentionDeliveryState.PI_DELIVERED,
                    responseState = AttentionResponseState.RESOLVED,
                    terminalKind = AttentionTerminalKind.SUCCEEDED,
                ),
            ),
        )
        val closedCases = listOf(
            attentionRoute("task-owner-missing", "call-owner-missing") to AttentionRecordState.Missing,
            attentionRoute("task-owner-corrupt", "call-owner-corrupt") to AttentionRecordState.Corrupt,
            attentionRoute("task-owner-hidden", "call-owner-hidden") to AttentionRecordState.FailedClosedHidden,
            attentionRoute("task-owner-cross", "call-owner-cross") to AttentionRecordState.Available(
                attentionRecord(attentionRoute("foreign-task", "call-owner-cross")),
            ),
        ).map { (route, state) -> route to FakeRouteAttentionSource(route, state) }
        val effects = mutableListOf<Pair<AttentionRoute, AttentionOneShot>>()

        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                val dismissRegistry = remember {
                    MomodingBottomSheetDismissRegistry()
                }
                Box {
                    listOf(restoredRoute to restoredSource).plus(closedCases)
                        .forEach { (route, source) ->
                            key(route.taskId, route.callId) {
                                AttentionRouteContent(
                                    route = route,
                                    ownerFactory = attentionOwnerFactory(source),
                                    dismissRegistry = dismissRegistry,
                                    onOneShot = { effects += route to it },
                                )
                            }
                        }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            restoredSource.observedExactOnly() &&
                closedCases.all { (_, source) -> source.observedExactOnly() } &&
                effects.count { it.second is AttentionOneShot.ReturnToTask } == closedCases.size
        }
        compose.runOnIdle {
            check(effects.none { (route, _) -> route == restoredRoute })
            val returns = effects.mapNotNull { (route, effect) ->
                (effect as? AttentionOneShot.ReturnToTask)?.let { route to it }
            }
            check(returns.map { it.first }.toSet() == closedCases.map { it.first }.toSet())
            check(returns.all { (_, effect) -> effect.reason == AttentionReturnReason.UNAVAILABLE })
            check(returns.map { it.second.effectId }.toSet().size == closedCases.size)
        }
    }

    private fun assertTerminalReturnFocusesHeading(
        reason: AttentionReturnReason,
        nextAttention: TaskAttentionUiModel?,
    ) {
        val detailState = mutableStateOf(
            TaskDetailUiState(
                taskId = "task-1",
                title = "Task one",
                loadState = TaskDetailLoadState.READY,
                runState = TaskDetailRunState.WAITING,
                attention = TaskAttentionUiModel("call-1", "Choose a validation"),
            ),
        )
        val oneShots = MutableSharedFlow<AttentionOneShot>(extraBufferCapacity = 1)
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    readyState(),
                    onAction = {},
                    taskDetailEntry = { _, restoreFocusKey, _, onFocusRestored, _, _ ->
                        TaskDetailScreen(
                            state = detailState.value,
                            onAction = {},
                            restoreFocusKey = restoreFocusKey,
                            onFocusRestored = onFocusRestored,
                        )
                    },
                    attentionEntry = { route, _, onOneShot ->
                        LaunchedEffect(oneShots, route) {
                            oneShots.collect(onOneShot)
                        }
                        Text("Live attention ${route.callId}")
                    },
                    initialBackStack = attentionBackStack(),
                )
            }
        }
        compose.onNodeWithText("Live attention call-1").assertIsDisplayed()

        compose.runOnIdle {
            check(
                oneShots.tryEmit(
                    AttentionOneShot.ReturnToTask(
                        effectId = "task-1:call-1:terminal-$reason",
                        reason = reason,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Live attention call-1").assertDoesNotExist()
        compose.onNodeWithTag(TASK_DETAIL_TITLE_FOCUS_KEY, useUnmergedTree = true).assertIsFocused()

        compose.runOnIdle {
            detailState.value = detailState.value.copy(attention = nextAttention)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TASK_DETAIL_TITLE_FOCUS_KEY, useUnmergedTree = true).assertIsFocused()
    }

    private fun attentionRoute(taskId: String, callId: String) = AttentionRoute(
        taskId = taskId,
        callId = callId,
        originFocusKey = taskDetailAttentionFocusKey(taskId, callId),
    )

    private companion object {
        const val TALKBACK_SMOKE_TAG = "P2TalkBackSmoke"
    }

    private fun attentionOwnerFactory(
        source: FakeRouteAttentionSource,
    ): AttentionRouteViewModelFactory = { identity ->
        source.factoryIdentities += identity
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass.isAssignableFrom(AttentionViewModel::class.java))
                return AttentionViewModel(
                    identity = identity,
                    repository = source,
                    transportStatus = MutableStateFlow(
                        SecureTransportUiStatus(SecureTransportUiPhase.READY),
                    ),
                    submitDecision = { _: AttentionUserDecision -> error("No decision expected") },
                    retryConnection = { error("No retry expected") },
                ) as T
            }
        }
    }

    private fun attentionRecord(route: AttentionRoute) = AttentionRecord(
        taskId = route.taskId,
        callId = route.callId,
        prompt = AttentionPrompt.Question("Choose a validation", emptyList()),
        ledgerState = AttentionLedgerState.AWAITING_USER,
        deliveryState = AttentionDeliveryState.NOT_READY,
        responseState = AttentionResponseState.PENDING,
        terminalKind = null,
        draft = AttentionDraft(null, "", 0, 0, null),
        expiresAtMillis = Long.MAX_VALUE,
        receivedAtMillis = 1L,
        dismissed = false,
    )

    private class FakeRouteAttentionSource(
        private val expectedRoute: AttentionRoute,
        initial: AttentionRecordState,
        private val failDismiss: Boolean = false,
    ) : AttentionDataSource {
        val state = MutableStateFlow(initial)
        val factoryIdentities = mutableListOf<AttentionIdentity>()
        var dismissCalls = 0
        private val observed = mutableListOf<Pair<String, String>>()

        override fun observe(taskId: String, callId: String): Flow<AttentionRecordState> {
            observed += taskId to callId
            return state
        }

        override fun observeTaskAttention(taskId: String): Flow<List<TaskAttentionSummary>> =
            MutableStateFlow(emptyList())

        override suspend fun current(taskId: String, callId: String): AttentionRecordState =
            if (taskId == expectedRoute.taskId && callId == expectedRoute.callId) {
                state.value
            } else {
                AttentionRecordState.Missing
            }

        override suspend fun saveDraft(
            taskId: String,
            callId: String,
            draft: AttentionDraftWrite,
        ) = error("No draft expected")

        override suspend fun dismiss(taskId: String, callId: String) {
            check(taskId == expectedRoute.taskId && callId == expectedRoute.callId)
            dismissCalls += 1
            if (failDismiss) error("Synthetic durable dismiss failure")
        }

        fun publish(record: AttentionRecord) {
            state.value = AttentionRecordState.Available(record)
        }

        fun publish(value: AttentionRecordState) {
            state.value = value
        }

        fun observedExactOnly(): Boolean =
            factoryIdentities.isNotEmpty() &&
                factoryIdentities.all {
                    it == AttentionIdentity(expectedRoute.taskId, expectedRoute.callId)
                } &&
            observed.isNotEmpty() &&
                observed.all { it == expectedRoute.taskId to expectedRoute.callId }
    }

    private fun attentionBackStack() = listOf(
        TaskHomeRoute,
        TaskDetailRoute("task-1"),
        AttentionRoute(
            "task-1",
            "call-1",
            taskDetailAttentionFocusKey("task-1", "call-1"),
        ),
    )

    private fun readyState(): SettingsUiState {
        val profile = HostClientProfile("Mac Studio", "OpenRouter", "deepseek/deepseek-v4-pro", "default", false, 42)
        return SettingsUiState(
            transport = SecureTransportUiStatus(
                SecureTransportUiPhase.READY,
                profile.hostAlias,
                profile,
                profile.configRevision,
            ),
            agentProfile = AgentProfileState.Ready(profile),
        )
    }
}
