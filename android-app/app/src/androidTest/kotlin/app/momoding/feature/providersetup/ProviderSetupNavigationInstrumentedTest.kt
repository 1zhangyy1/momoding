package app.momoding.feature.providersetup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.momoding.app.MomodingApp
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.feature.localtask.PhoneLocalTaskScreen
import app.momoding.feature.localtask.PhoneLocalTaskUiState
import app.momoding.feature.settings.SettingsUiState
import app.momoding.feature.taskdetail.TaskDetailAction
import app.momoding.feature.taskdetail.TaskDetailLoadState
import app.momoding.feature.taskdetail.TaskDetailOneShot
import app.momoding.feature.taskdetail.TaskDetailRunState
import app.momoding.feature.taskdetail.TaskDetailScreen
import app.momoding.feature.taskdetail.TaskDetailUiState
import app.momoding.feature.taskdetail.TimelineItem
import app.momoding.feature.taskdetail.TimelineWindow
import app.momoding.feature.tasks.TaskHomeConnectionState
import app.momoding.feature.tasks.TaskHomeLoadState
import app.momoding.feature.tasks.TaskHomeOneShot
import app.momoding.feature.tasks.TaskHomeUiState
import app.momoding.ui.navigation.ProviderSetupRoute
import app.momoding.ui.navigation.TaskDetailRoute
import app.momoding.ui.navigation.TaskHomeRoute
import app.momoding.ui.theme.MomodingTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderSetupNavigationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun phoneLocalDefaultMovesFromProviderSetupToEmptyTasksAndCanManageProvider() {
        var providerState by mutableStateOf(
            ProviderSetupUiState(loadState = ProviderSetupLoadState.MISSING),
        )
        var lastAction: ProviderSetupAction? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                MomodingApp(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED),
                    ),
                    onAction = {},
                    providerSetupState = providerState,
                    onProviderSetupAction = { lastAction = it },
                    taskHomeState = TaskHomeUiState(
                        hostAlias = "On-device · $MODEL_ID",
                        connection = TaskHomeConnectionState.CONNECTED,
                        loadState = TaskHomeLoadState.READY,
                    ),
                    initialBackStack = listOf(ProviderSetupRoute(onboarding = true)),
                )
            }
        }

        compose.onNodeWithText("Set up Momoding").assertIsDisplayed()
        compose.onNodeWithText("Connect your Pi Host").assertDoesNotExist()
        compose.onNodeWithTag("provider-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ProviderSetupAction.Save, lastAction) }

        compose.runOnIdle {
            providerState = ProviderSetupUiState(
                loadState = ProviderSetupLoadState.CONFIGURED,
                savedProfile = profile(),
                modelId = MODEL_ID,
                hasSavedApiKey = true,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Start your first task").assertIsDisplayed()
        compose.onNodeWithText("Connect your Pi Host").assertDoesNotExist()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("OpenRouter").assertIsDisplayed()
        compose.onNodeWithTag("action-OpenProvider").performClick()
        compose.onNodeWithText("Momoding on this phone").assertIsDisplayed()
        compose.onNodeWithText("Connect your Pi Host").assertDoesNotExist()
    }

    @Test
    fun configuredProviderOpensPhoneLocalTaskWithoutHostSetup() {
        val configured = ProviderSetupUiState(
            loadState = ProviderSetupLoadState.CONFIGURED,
            savedProfile = profile(),
            modelId = MODEL_ID,
            hasSavedApiKey = true,
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                MomodingApp(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED),
                    ),
                    onAction = {},
                    providerSetupState = configured,
                    taskHomeState = TaskHomeUiState(
                        hostAlias = "On-device · $MODEL_ID",
                        connection = TaskHomeConnectionState.CONNECTED,
                        loadState = TaskHomeLoadState.READY,
                    ),
                    newTaskEntry = { _, _, _, _ ->
                        PhoneLocalTaskScreen(
                            state = PhoneLocalTaskUiState(),
                            modelId = MODEL_ID,
                            onAction = {},
                        )
                    },
                    initialBackStack = listOf(ProviderSetupRoute(onboarding = true)),
                )
            }
        }

        compose.onNodeWithText("Start your first task").assertIsDisplayed()
        compose.onNodeWithText("New task").performClick()
        compose.onNodeWithText("What should Momoding do?").assertIsDisplayed()
        compose.onNodeWithText("On-device Pi · $MODEL_ID").assertIsDisplayed()
        compose.onNodeWithText("Connect your Pi Host").assertDoesNotExist()
    }

    @Test
    fun failedTaskRepairsProviderAndReturnsToTheExactTask() {
        var providerState by mutableStateOf(
            ProviderSetupUiState(
                loadState = ProviderSetupLoadState.CONFIGURED,
                health = ProviderHealth.SAVED,
                savedProfile = profile(),
                modelId = MODEL_ID,
                hasSavedApiKey = true,
            ),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                MomodingApp(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED),
                    ),
                    onAction = {},
                    providerSetupState = providerState,
                    taskDetailEntry = { route, _, _, _, _, onOneShot ->
                        TaskDetailScreen(
                            state = TaskDetailUiState(
                                taskId = route.taskId,
                                title = "Failed Provider task",
                                loadState = TaskDetailLoadState.READY,
                                runState = TaskDetailRunState.FAILED,
                                phoneLocal = true,
                                timeline = TimelineWindow(
                                    settledItems = listOf(
                                        TimelineItem.UserMessage("user-1", "Inspect the project"),
                                        TimelineItem.Error(
                                            "error-1",
                                            "OpenRouter API key is invalid. Update it in Settings.",
                                        ),
                                    ),
                                ),
                            ),
                            onAction = { action ->
                                if (action == TaskDetailAction.FixProvider) {
                                    onOneShot(TaskDetailOneShot.OpenProvider)
                                }
                            },
                        )
                    },
                    initialBackStack = listOf(
                        TaskHomeRoute,
                        TaskDetailRoute("task-provider-recovery"),
                    ),
                )
            }
        }

        compose.onNodeWithText("Failed Provider task").assertIsDisplayed()
        compose.onNodeWithTag("action-FixProvider").performClick()
        compose.onNodeWithText("Provider saved").assertIsDisplayed()
        compose.onNodeWithTag("provider-return-to-task").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Test connection to return").assertIsDisplayed()

        compose.runOnIdle {
            providerState = providerState.copy(health = ProviderHealth.READY)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("provider-return-to-task").performClick()
        compose.onNodeWithText("Failed Provider task").assertIsDisplayed()
        compose.onNodeWithTag("action-RetryOriginal").assertIsDisplayed()
    }

    @Test
    fun failedTaskRowCanOpenProviderAndReturnToTheExactTask() {
        var providerState by mutableStateOf(
            ProviderSetupUiState(
                loadState = ProviderSetupLoadState.CONFIGURED,
                health = ProviderHealth.SAVED,
                savedProfile = profile(),
                modelId = MODEL_ID,
                hasSavedApiKey = true,
            ),
        )
        val homeOneShots = MutableSharedFlow<TaskHomeOneShot>(extraBufferCapacity = 1)
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                MomodingApp(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED),
                    ),
                    onAction = {},
                    providerSetupState = providerState,
                    taskHomeOneShots = homeOneShots,
                    initialBackStack = listOf(TaskHomeRoute),
                )
            }
        }

        compose.runOnIdle {
            check(homeOneShots.tryEmit(TaskHomeOneShot.OpenProvider("task-from-failed-row")))
        }
        compose.onNodeWithText("Provider saved").assertIsDisplayed()

        compose.runOnIdle {
            providerState = providerState.copy(health = ProviderHealth.READY)
        }
        compose.onNodeWithTag("provider-return-to-task").performScrollTo().performClick()
        compose.onNodeWithText("Task task-from-failed-row").assertIsDisplayed()
    }

    private fun profile(): ProviderProfile =
        ProviderProfile(
            id = "44444444-4444-4444-8444-444444444444",
            kind = ProviderKind.OPENROUTER,
            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
            modelId = MODEL_ID,
            displayName = "OpenRouter",
        )

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
    }
}
