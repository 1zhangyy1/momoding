package app.momoding.feature.tasks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.appearance.AppearanceMode
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskHomeManagementInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectedHomePrioritizesTasksAndExposesAnAccessibleNewTaskAction() {
        var lastAction: TaskHomeAction? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskHomeScreen(
                    state = state(),
                    contentPadding = PaddingValues(),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithText("Ready for a new task").assertDoesNotExist()
        compose.onNodeWithText("On-device", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("New task").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TaskHomeAction.NewTask, lastAction) }
    }

    @Test
    fun offlineHomeShowsOneConnectionExplanation() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskHomeScreen(
                    state = state().copy(connection = TaskHomeConnectionState.OFFLINE),
                    contentPadding = PaddingValues(),
                    onAction = {},
                )
            }
        }

        compose.onAllNodesWithText(
            "Working from local history",
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @Test
    fun activeTaskMenuExposesMomodingManagementActionsAndRenameDialog() {
        var lastAction: TaskHomeAction? = null
        var uiState by mutableStateOf(state())
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskHomeScreen(
                    state = uiState,
                    contentPadding = PaddingValues(),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithTag("task-actions-task-1").performClick()
        compose.onNodeWithText("Pin task").assertIsDisplayed()
        compose.onNodeWithText("Rename task").performClick()
        compose.runOnIdle {
            assertEquals(TaskHomeAction.RequestRename("task-1", "Review Android project"), lastAction)
        }

        compose.runOnIdle {
            uiState = uiState.copy(
                managementDialog = TaskManagementDialogUiModel(
                    kind = TaskManagementDialogKind.RENAME,
                    taskId = "task-1",
                    taskTitle = "Review Android project",
                ),
            )
        }
        compose.onNodeWithText("Keep it short and easy to recognize.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-rename-task").performClick()
        compose.runOnIdle { assertEquals(TaskHomeAction.ConfirmRename, lastAction) }
    }

    @Test
    fun archivedTaskOffersRestoreAndExplicitPermanentDeleteConfirmation() {
        var lastAction: TaskHomeAction? = null
        val archivedRow = state().sections.single().rows.single().copy(archived = true)
        var uiState by mutableStateOf(
            state().copy(
                archivedVisible = true,
                sections = listOf(TaskSectionUiModel("Archived", listOf(archivedRow))),
            ),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskHomeScreen(
                    state = uiState,
                    contentPadding = PaddingValues(),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithTag("task-actions-task-1").performClick()
        compose.onNodeWithText("Restore task").assertIsDisplayed()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.runOnIdle {
            assertEquals(TaskHomeAction.RequestDelete("task-1", "Review Android project"), lastAction)
        }

        compose.runOnIdle {
            uiState = uiState.copy(
                managementDialog = TaskManagementDialogUiModel(
                    kind = TaskManagementDialogKind.DELETE,
                    taskId = "task-1",
                    taskTitle = "Review Android project",
                ),
            )
        }
        compose.onNodeWithText("This can’t be undone.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-delete-task").performClick()
        compose.runOnIdle { assertEquals(TaskHomeAction.ConfirmDelete, lastAction) }
    }

    @Test
    fun failedTaskMenuOffersProviderRecoveryForTheSameTask() {
        var lastAction: TaskHomeAction? = null
        val failedRow = state().sections.single().rows.single().copy(
            detail = "Provider needs attention",
            status = TaskRowStatus.FAILED,
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskHomeScreen(
                    state = state().copy(
                        sections = listOf(TaskSectionUiModel("Failed", listOf(failedRow))),
                    ),
                    contentPadding = PaddingValues(),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithTag("task-actions-task-1").performClick()
        compose.onNodeWithText("Fix Provider").performClick()
        compose.runOnIdle {
            assertEquals(TaskHomeAction.FixProvider("task-1"), lastAction)
        }
    }

    private fun state() = TaskHomeUiState(
        hostAlias = "On-device · test/model",
        connection = TaskHomeConnectionState.CONNECTED,
        loadState = TaskHomeLoadState.READY,
        managementEnabled = true,
        sections = listOf(
            TaskSectionUiModel(
                title = "Recent",
                rows = listOf(
                    TaskRowUiModel(
                        taskId = "task-1",
                        title = "Review Android project",
                        detail = "Task completed",
                        ageLabel = "now",
                        status = TaskRowStatus.COMPLETED,
                    ),
                ),
            ),
        ),
    )
}
