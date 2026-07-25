package app.momoding.feature.taskdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionOption
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.TaskAttentionKind
import app.momoding.feature.attention.AttentionActionPolicy
import app.momoding.feature.attention.AttentionIdentity
import app.momoding.feature.attention.AttentionIntent
import app.momoding.feature.attention.AttentionUiState
import app.momoding.feature.attention.AttentionVisibleState
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionComposerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pendingQuestionReplacesMessageComposerAndDoesNotRenderAWorkCard() {
        val intents = mutableListOf<AttentionIntent>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = questionTaskState(),
                    onAction = {},
                    questionState = questionState(selectedOptionIndex = null),
                    onQuestionIntent = intents::add,
                )
            }
        }

        compose.onNodeWithTag("question-composer").assertIsDisplayed()
        compose.onNodeWithTag("question-composer-heading").assertIsDisplayed()
        compose.onNodeWithText("Which implementation should Momoding use?").assertIsDisplayed()
        compose.onNodeWithTag("action-EditComposer").assertDoesNotExist()
        compose.onNodeWithTag("action-OpenAttention").assertDoesNotExist()
        compose.onNodeWithText("Asked a question").assertDoesNotExist()

        compose.onNodeWithTag("attention-option-0").performClick()
        compose.runOnIdle {
            assertEquals(listOf(AttentionIntent.SelectOption(0)), intents)
        }
    }

    @Test
    fun selectedAnswerCanBeSubmittedFromTheComposer() {
        val intents = mutableListOf<AttentionIntent>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = questionTaskState(),
                    onAction = {},
                    questionState = questionState(selectedOptionIndex = 0),
                    onQuestionIntent = intents::add,
                )
            }
        }

        compose.onNodeWithTag("question-action-submit")
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(AttentionIntent.SubmitAnswer), intents)
        }
    }

    @Test
    fun approvalStillUsesTheDedicatedAttentionSurface() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = questionTaskState().copy(
                        attention = TaskAttentionUiModel(
                            callId = CALL_ID,
                            label = "An action needs confirmation",
                            kind = TaskAttentionKind.CONFIRMATION,
                        ),
                        timeline = TimelineWindow(
                            activeItem = TimelineItem.ToolActivity(
                                stableKey = "tool:$CALL_ID",
                                toolCallId = CALL_ID,
                                title = "Requested confirmation",
                                detail = "Working",
                                state = ToolActivityState.RUNNING,
                            ),
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithTag("question-composer").assertDoesNotExist()
        compose.onNodeWithTag("action-OpenAttention").assertIsDisplayed()
    }

    private fun questionTaskState() = TaskDetailUiState(
        taskId = TASK_ID,
        title = "Release readiness",
        loadState = TaskDetailLoadState.READY,
        connection = TaskDetailConnectionState.CONNECTED,
        runState = TaskDetailRunState.WAITING,
        composerMode = TaskComposerMode.BLOCKED,
        composerBlockedReason = "Waiting for your answer.",
        attention = TaskAttentionUiModel(
            callId = CALL_ID,
            label = "Momoding asked a question",
            kind = TaskAttentionKind.QUESTION,
        ),
        timeline = TimelineWindow(
            activeItem = TimelineItem.ToolActivity(
                stableKey = "tool:$CALL_ID",
                toolCallId = CALL_ID,
                title = "Asked a question",
                detail = "Working",
                state = ToolActivityState.RUNNING,
                kind = ToolActivityKind.USER_INPUT,
            ),
        ),
    )

    private fun questionState(selectedOptionIndex: Int?) = AttentionUiState.Visible(
        identity = AttentionIdentity(TASK_ID, CALL_ID),
        state = AttentionVisibleState.QuestionPending,
        prompt = AttentionPrompt.Question(
            question = "Which implementation should Momoding use?",
            options = listOf(
                AttentionOption("Balanced", "Prefer correctness and speed", recommended = true),
                AttentionOption("Fast", "Prefer the smallest change", recommended = false),
            ),
        ),
        draft = AttentionDraft(
            selectedOptionIndex = selectedOptionIndex,
            customAnswer = "",
            selectionStart = 0,
            selectionEnd = 0,
            validationCode = null,
        ),
        actions = AttentionActionPolicy(
            canEditCustom = true,
            canSelectOption = true,
            canSubmitAnswer = true,
            canSkip = true,
        ),
    )

    private companion object {
        const val TASK_ID = "question-task"
        const val CALL_ID = "question-call"
    }
}
