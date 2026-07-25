package app.momoding.feature.approval

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.feature.newtask.NewTaskAction
import app.momoding.feature.newtask.NewTaskConnectionState
import app.momoding.feature.newtask.NewTaskProfileState
import app.momoding.feature.newtask.NewTaskScreen
import app.momoding.feature.newtask.NewTaskUiState
import app.momoding.feature.taskdetail.TaskComposerMode
import app.momoding.feature.taskdetail.TaskDetailAction
import app.momoding.feature.taskdetail.TaskDetailConnectionState
import app.momoding.feature.taskdetail.TaskDetailLoadState
import app.momoding.feature.taskdetail.TaskDetailRunState
import app.momoding.feature.taskdetail.TaskDetailScreen
import app.momoding.feature.taskdetail.TaskDetailUiState
import app.momoding.ui.theme.MomodingTheme
import org.junit.Rule
import org.junit.Test

class ApprovalModeComposerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun newTaskUsesCompactApprovalTriggerAtTwoHundredPercentFont() {
        var state by mutableStateOf(
            NewTaskUiState(
                draftId = "draft-approval",
                draftInitialized = true,
                phoneLocal = true,
                connection = NewTaskConnectionState.READY,
                profile = NewTaskProfileState.Ready(
                    hostAlias = "On this phone",
                    provider = "OpenRouter",
                    model = "deepseek/deepseek-v4-pro",
                    thinking = "default",
                ),
            ),
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                    Box(Modifier.size(360.dp, 800.dp)) {
                        NewTaskScreen(
                            state = state,
                            onAction = { action ->
                                if (action is NewTaskAction.SelectApprovalMode) {
                                    state = state.copy(approvalMode = action.mode)
                                }
                            },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("approval-mode-trigger")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Selected: Request approval",
                ),
            )
            .performClick()
        compose.onNodeWithText("How should Momoding handle actions?").assertIsDisplayed()
        compose.onNodeWithTag("approval-mode-option-FULL_ACCESS")
            .performScrollTo()
            .performClick()

        compose.onNodeWithContentDescription("Full access approval mode").assertIsDisplayed()
        compose.onNodeWithTag("approval-mode-trigger").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Selected: Full access",
            ),
        )
    }

    @Test
    fun taskDetailRestoresAutoApproveAndUsesTheSameThreeOptionSheet() {
        var selected: TaskApprovalMode? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.DARK, systemDark = true) {
                Box(Modifier.size(412.dp, 915.dp)) {
                    TaskDetailScreen(
                        state = TaskDetailUiState(
                            taskId = "task-approval",
                            loadState = TaskDetailLoadState.READY,
                            connection = TaskDetailConnectionState.CONNECTED,
                            runState = TaskDetailRunState.SETTLED,
                            composerMode = TaskComposerMode.PROMPT,
                            composerBlockedReason = null,
                            phoneLocal = true,
                            approvalMode = TaskApprovalMode.AUTO_APPROVE,
                            planActionPending = true,
                        ),
                        onAction = { action ->
                            if (action is TaskDetailAction.SelectApprovalMode) selected = action.mode
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("approval-mode-trigger")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Selected: Approve for me",
                ),
            )
            .performClick()
        compose.onNodeWithTag("approval-mode-option-AUTO_APPROVE").assertIsSelected()
        compose.onNodeWithText("Request approval").assertIsDisplayed()
        compose.onNodeWithTag("approval-mode-option-FULL_ACCESS").performScrollTo()
        compose.onNodeWithText("Full access").assertIsDisplayed()
        compose.onNodeWithTag("approval-mode-option-REQUEST_APPROVAL")
            .performScrollTo()
            .performClick()
        compose.runOnIdle { check(selected == TaskApprovalMode.REQUEST_APPROVAL) }
        compose.onNodeWithText("Saving").assertDoesNotExist()
        compose.onNodeWithText("Updating Plan mode").assertDoesNotExist()
    }
}
