package app.momoding.feature.attention

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionOption
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionValidationCode
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AttentionScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val identity = AttentionIdentity("task-synthetic", "call-synthetic")

    @Test
    fun allElevenStatesRenderExactVisualRoleAndPublicEyebrow() {
        val cases = listOf(
            Triple(
                visible(
                    AttentionVisibleState.ConfirmPending,
                    prompt = confirmation(),
                    actions = AttentionActionPolicy(
                        canConfirm = true,
                        canDecline = true,
                        canDismiss = true,
                    ),
                ),
                AttentionVisualRole.CONFIRM_WARNING,
                "REVIEW REQUIRED",
            ),
            Triple(
                visible(
                    AttentionVisibleState.QuestionPending,
                    prompt = question(),
                    actions = questionActions(),
                ),
                AttentionVisualRole.QUESTION_WARNING,
                "MOMODING QUESTION",
            ),
            Triple(
                visible(
                    AttentionVisibleState.ValidationError(
                        AttentionValidationCode.ANSWER_REQUIRED,
                    ),
                    prompt = question(),
                    actions = AttentionActionPolicy(
                        canEditCustom = true,
                        canSelectOption = true,
                        canSkip = true,
                        canDismiss = true,
                    ),
                ),
                AttentionVisualRole.VALIDATION_ERROR,
                "MOMODING QUESTION",
            ),
            Triple(
                visible(
                    AttentionVisibleState.OfflinePending,
                    prompt = question(),
                    actions = AttentionActionPolicy(
                        canDismiss = true,
                        canRetryConnection = true,
                    ),
                ),
                AttentionVisualRole.OFFLINE_INFO,
                "HOST OFFLINE",
            ),
            Triple(
                visible(AttentionVisibleState.Responding),
                AttentionVisualRole.RESPONDING_INFO,
                "RESPONSE SAVED",
            ),
            Triple(
                visible(
                    AttentionVisibleState.Resolved,
                    actions = AttentionActionPolicy(canReturnToTask = true),
                ),
                AttentionVisualRole.RESOLVED_SUCCESS,
                "ANSWER SENT",
            ),
            Triple(
                visible(
                    AttentionVisibleState.Rejected,
                    actions = AttentionActionPolicy(canReturnToTask = true),
                ),
                AttentionVisualRole.REJECTED_DANGER,
                "STEP DECLINED",
            ),
            Triple(
                visible(
                    AttentionVisibleState.Skipped,
                    actions = AttentionActionPolicy(canReturnToTask = true),
                ),
                AttentionVisualRole.SKIPPED_NEUTRAL,
                "QUESTION SKIPPED",
            ),
            Triple(
                visible(
                    AttentionVisibleState.Expired,
                    actions = AttentionActionPolicy(canDismiss = true),
                ),
                AttentionVisualRole.EXPIRED_NEUTRAL,
                "REQUEST EXPIRED",
            ),
            Triple(
                visible(
                    AttentionVisibleState.Cancelled,
                    actions = AttentionActionPolicy(canReturnToTask = true),
                ),
                AttentionVisualRole.CANCELLED_NEUTRAL,
                "REQUEST CANCELLED",
            ),
            Triple(
                visible(
                    AttentionVisibleState.AlreadyAnswered,
                    actions = AttentionActionPolicy(canReturnToTask = true),
                ),
                AttentionVisualRole.ALREADY_ANSWERED_INFO,
                "ALREADY HANDLED",
            ),
        )
        val current = mutableStateOf<AttentionUiState>(cases.first().first)
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(current.value, onIntent = {})
            }
        }

        cases.forEachIndexed { index, (state, role, eyebrow) ->
            compose.runOnIdle { current.value = state }
            compose.onNodeWithTag("attention-visual-${role.testTag}").assertIsDisplayed()
            compose.onNodeWithText(eyebrow).assertIsDisplayed()
            if (index == 0) {
                compose.onNodeWithTag("attention-heading").assertIsFocused()
            }
        }
    }

    @Test
    fun confirmationAndQuestionControlsDispatchOnlyExactTypedIntents() {
        val intents = mutableListOf<AttentionIntent>()
        val current = mutableStateOf<AttentionUiState>(
            visible(
                AttentionVisibleState.ConfirmPending,
                prompt = confirmation(),
                actions = AttentionActionPolicy(
                    canConfirm = true,
                    canDecline = true,
                    canDismiss = true,
                ),
            ),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(current.value, onIntent = { intents += it })
            }
        }

        compose.onNodeWithTag("attention-action-decline").performClick()
        compose.onNodeWithTag("attention-action-confirm").performClick()
        compose.onNodeWithTag("attention-close").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    AttentionIntent.Decline,
                    AttentionIntent.Confirm,
                    AttentionIntent.Dismiss,
                ),
                intents,
            )
            intents.clear()
            current.value = visible(
                AttentionVisibleState.QuestionPending,
                prompt = question(),
                actions = questionActions(),
            )
        }

        compose.onNodeWithTag("attention-option-0").performClick()
        compose.onNodeWithTag("attention-custom-answer").performTextInput("Gamma")
        compose.onNodeWithTag("attention-action-skip").performClick()
        compose.onNodeWithTag("attention-action-submit").performClick()
        compose.runOnIdle {
            assertEquals(AttentionIntent.SelectOption(0), intents.first())
            assertTrue(
                intents.any {
                    it is AttentionIntent.EditCustom && it.text == "Gamma"
                },
            )
            assertEquals(AttentionIntent.Skip, intents[intents.lastIndex - 1])
            assertEquals(AttentionIntent.SubmitAnswer, intents.last())
        }
    }

    @Test
    fun validationAndDraftBarrierKeepDisabledDecisionsVisibleAndExposeSafeReturn() {
        val current = mutableStateOf<AttentionUiState>(
            visible(
                AttentionVisibleState.ValidationError(
                    AttentionValidationCode.ANSWER_REQUIRED,
                ),
                prompt = question(),
                actions = AttentionActionPolicy(
                    canEditCustom = true,
                    canSelectOption = true,
                    canSkip = true,
                    canDismiss = true,
                ),
            ),
        )
        val intents = mutableListOf<AttentionIntent>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(current.value, onIntent = { intents += it })
            }
        }

        compose.onNodeWithText("Enter an answer.").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-skip").assertIsEnabled()
        compose.onNodeWithTag("attention-action-submit").assertIsNotEnabled()
        compose.runOnIdle {
            current.value = visible(
                AttentionVisibleState.QuestionPending,
                prompt = question(),
                actions = AttentionActionPolicy(
                    canEditCustom = true,
                    canSelectOption = true,
                    canReturnToTask = true,
                ),
                notice = AttentionTransientNotice.DRAFT_NOT_SAVED,
            )
        }

        compose.onNodeWithText(AttentionTransientNotice.DRAFT_NOT_SAVED.fixedCopy())
            .assertIsDisplayed()
        compose.onNodeWithTag("attention-close").assertDoesNotExist()
        compose.onNodeWithTag("attention-action-skip").assertIsNotEnabled()
        compose.onNodeWithTag("attention-action-submit").assertIsNotEnabled()
        compose.onNodeWithTag("attention-action-return").performClick()
        compose.runOnIdle {
            assertEquals(listOf(AttentionIntent.ReturnToTask), intents)
        }
    }

    @Test
    fun expiredExactBoundaryExposesOnlyEnabledDismissAndNoDecisionActions() {
        val intents = mutableListOf<AttentionIntent>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(
                    visible(
                        AttentionVisibleState.Expired,
                        actions = AttentionActionPolicy(canDismiss = true),
                    ),
                    onIntent = { intents += it },
                )
            }
        }

        compose.onNodeWithTag("attention-visual-expired-neutral").assertIsDisplayed()
        compose.onNodeWithTag("attention-close")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("attention-action-submit").assertDoesNotExist()
        compose.onNodeWithTag("attention-action-skip").assertDoesNotExist()
        compose.onNodeWithTag("attention-action-confirm").assertDoesNotExist()
        compose.onNodeWithTag("attention-action-decline").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(AttentionIntent.Dismiss), intents)
        }
    }

    @Test
    fun failedClosedStatesNeverRenderPromptDraftOrIdentity() {
        val current = mutableStateOf<AttentionUiState>(
            AttentionUiState.Unavailable(identity),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(current.value, onIntent = {})
            }
        }

        listOf<AttentionUiState>(
            AttentionUiState.Unavailable(identity),
            AttentionUiState.Corrupt(identity),
            AttentionUiState.FailedClosedHidden(identity),
        ).forEach { state ->
            compose.runOnIdle { current.value = state }
            compose.onNodeWithTag("attention-failed-closed").assertExists()
            compose.onNodeWithText("call-synthetic").assertDoesNotExist()
            compose.onNodeWithText("Synthetic secret answer").assertDoesNotExist()
        }
    }

    private fun visible(
        state: AttentionVisibleState,
        prompt: AttentionPrompt? = null,
        actions: AttentionActionPolicy = AttentionActionPolicy(),
        notice: AttentionTransientNotice? = null,
    ) = AttentionUiState.Visible(
        identity = identity,
        state = state,
        prompt = prompt,
        draft = prompt?.let {
            AttentionDraft(
                selectedOptionIndex = null,
                customAnswer = if (state == AttentionVisibleState.OfflinePending) {
                    "Synthetic secret answer"
                } else {
                    ""
                },
                selectionStart = 0,
                selectionEnd = 0,
                validationCode = (state as? AttentionVisibleState.ValidationError)?.code,
            )
        },
        actions = actions,
        notice = notice,
    )

    private fun confirmation() = AttentionPrompt.Confirmation(
        summary = "Run the synthetic check?",
        details = "This continues only the current request.",
    )

    private fun question() = AttentionPrompt.Question(
        question = "Choose a safe path.",
        options = listOf(
            AttentionOption("Alpha", "Use the conservative path.", recommended = true),
            AttentionOption("Beta", "Use the faster path.", recommended = false),
        ),
    )

    private fun questionActions() = AttentionActionPolicy(
        canEditCustom = true,
        canSelectOption = true,
        canSubmitAnswer = true,
        canSkip = true,
        canDismiss = true,
    )
}
