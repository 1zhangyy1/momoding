package app.momoding.feature.attention

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

class AttentionScreenFinalValidationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val identity = AttentionIdentity("fixture-task", "fixture-call")

    @Test
    fun measuredButtonsStayHorizontalAtDefaultScaleAndStackBeforeLargeTextWraps() {
        val width = mutableStateOf(412.dp)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                    Box(Modifier.width(width.value).height(800.dp)) {
                        AttentionScreen(confirmState(), onIntent = {})
                    }
                }
            }
        }

        compose.onNodeWithTag("attention-action-layout-row").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-decline").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("attention-action-confirm").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { fontScale.value = 2f }
        compose.onNodeWithTag("attention-action-decline").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-confirm").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-decline").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("attention-action-confirm").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { width.value = 360.dp }
        compose.onNodeWithTag("attention-action-layout-column").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-decline").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("attention-action-confirm").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun radioCollectionImeAndTouchTargetsExposeOneStandardEditableValueWithoutImplicitSubmit() {
        val intents = mutableListOf<AttentionIntent>()
        val current = mutableStateOf(questionState())
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                Box(Modifier.width(360.dp).height(800.dp)) {
                    AttentionScreen(
                        state = current.value,
                        onIntent = { intent ->
                            intents += intent
                            if (intent is AttentionIntent.EditCustom) {
                                current.value = current.value.copy(
                                    draft = AttentionDraft(
                                        selectedOptionIndex = null,
                                        customAnswer = intent.text,
                                        selectionStart = intent.selectionStart,
                                        selectionEnd = intent.selectionEnd,
                                        validationCode = null,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("attention-heading").assertIsFocused()
        compose.onNodeWithTag("attention-option-group").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo),
        )
        repeat(3) { index ->
            compose.onNodeWithTag("attention-option-$index")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionItemInfo))
                .assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("attention-close").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("attention-action-skip").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("attention-action-submit").assertHeightIsAtLeast(48.dp)

        val field = compose.onNodeWithTag("attention-custom-answer")
        field.performClick()
        field.performTextInput("Typed answer")
        field.performImeAction()
        compose.waitForIdle()
        assertTrue(intents.any { it is AttentionIntent.EditCustom })
        assertTrue(intents.none { it == AttentionIntent.SubmitAnswer })
        assertEquals(
            1,
            compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("attention-action-submit").assertIsDisplayed()
    }

    @Test
    fun validationErrorIsAssociatedOnlyWithTheEditableFieldAndDark412HasNoHorizontalOverflow() {
        compose.setContent {
            MomodingTheme(AppearanceMode.DARK, systemDark = false) {
                Box(Modifier.width(412.dp).height(915.dp)) {
                    AttentionScreen(validationState(), onIntent = {})
                }
            }
        }

        compose.onNodeWithTag("attention-custom-answer").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Error),
        )
        assertEquals(
            1,
            compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Error),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        val sheetBounds = compose.onNodeWithTag("attention-sheet").fetchSemanticsNode().boundsInRoot
        listOf(
            "attention-heading",
            "attention-option-group",
            "attention-custom-answer",
            "attention-action-layout-row",
        ).forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag starts outside the sheet", bounds.left >= sheetBounds.left - 1f)
            assertTrue("$tag overflows the sheet", bounds.right <= sheetBounds.right + 1f)
        }
    }

    @Test
    fun respondingKeepsCompleteStaticStatusWhenAnimationsDoNotAdvance() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(
                    visible(
                        state = AttentionVisibleState.Responding,
                        prompt = null,
                        draft = null,
                        actions = AttentionActionPolicy(),
                    ),
                    onIntent = {},
                )
            }
        }

        compose.onNodeWithTag("attention-visual-responding-info").assertIsDisplayed()
        compose.onNodeWithTag("attention-responding-status").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-submit").assertDoesNotExist()
        compose.onNodeWithTag("attention-close").assertDoesNotExist()
    }

    private fun confirmState() = visible(
        state = AttentionVisibleState.ConfirmPending,
        prompt = AttentionPrompt.Confirmation(
            summary = "Allow this synthetic step?",
            details = "This confirmation applies only to the current request.",
        ),
        draft = AttentionDraft(null, "", 0, 0, null),
        actions = AttentionActionPolicy(
            canConfirm = true,
            canDecline = true,
            canDismiss = true,
        ),
    )

    private fun questionState() = visible(
        state = AttentionVisibleState.QuestionPending,
        prompt = question(),
        draft = AttentionDraft(0, "", 0, 0, null),
        actions = AttentionActionPolicy(
            canEditCustom = true,
            canSelectOption = true,
            canSubmitAnswer = true,
            canSkip = true,
            canDismiss = true,
        ),
    )

    private fun validationState() = visible(
        state = AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_REQUIRED),
        prompt = question(),
        draft = AttentionDraft(null, "", 0, 0, AttentionValidationCode.ANSWER_REQUIRED),
        actions = AttentionActionPolicy(
            canEditCustom = true,
            canSkip = true,
            canDismiss = true,
        ),
    )

    private fun question() = AttentionPrompt.Question(
        question = "Which validation should run next?",
        options = listOf(
            AttentionOption("Focused checks", "Validate the changed feature.", true),
            AttentionOption("Full suite", "Validate the entire task shell.", false),
            AttentionOption("Review first", "Inspect the implementation again.", false),
        ),
    )

    private fun visible(
        state: AttentionVisibleState,
        prompt: AttentionPrompt?,
        draft: AttentionDraft?,
        actions: AttentionActionPolicy,
    ) = AttentionUiState.Visible(
        identity = identity,
        state = state,
        prompt = prompt,
        draft = draft,
        actions = actions,
    )
}
