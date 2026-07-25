package app.momoding.feature.attention

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.ContentReadDocument
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContentReadConsentInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun exactFilesBudgetsAndOneRequestScopeAreVisibleBeforeEitherDecision() {
        val intents = mutableListOf<AttentionIntent>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(
                    state = contentReadState(),
                    onIntent = { intents += it },
                )
            }
        }

        compose.onNodeWithText("Allow Momoding to read 2 files?").assertIsDisplayed()
        compose.onNodeWithText("PLAN.md").assertIsDisplayed()
        compose.onNodeWithText("README.md").assertIsDisplayed()
        compose.onNodeWithText("Android SAF · this request only").assertIsDisplayed()
        compose.onNodeWithText(
            "Total limit 96 KB. Access ends when this request finishes, the task stops, " +
                "the folder is revoked, or the approval expires.",
        ).assertIsDisplayed()

        compose.onNodeWithTag("attention-action-deny-content")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("attention-action-allow-content")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    AttentionIntent.DenyContentRead,
                    AttentionIntent.AllowContentRead,
                ),
                intents,
            )
        }
    }

    @Test
    fun unresolvedProviderNamesKeepAllowDisabledAndExplainRecovery() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                AttentionScreen(
                    state = contentReadState().copy(
                        prompt = (contentReadState().prompt as AttentionPrompt.ContentRead).copy(
                            filesVerified = false,
                        ),
                        actions = contentReadState().actions.copy(
                            canAllowContentRead = false,
                        ),
                    ),
                    onIntent = {},
                )
            }
        }

        compose.onNodeWithTag("content-read-unverified").assertIsDisplayed()
        compose.onNodeWithTag("attention-action-allow-content")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithTag("attention-action-deny-content")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    private fun contentReadState() = AttentionUiState.Visible(
        identity = AttentionIdentity(
            taskId = "content-read-instrumented-task",
            callId = "content-read-instrumented-call",
        ),
        state = AttentionVisibleState.ContentReadPending,
        prompt = AttentionPrompt.ContentRead(
            grantId = "11111111-1111-4111-8111-111111111111",
            purpose = "Read the project notes needed to prepare the next implementation step.",
            documents = listOf(
                ContentReadDocument(
                    alias = "doc-111111111111111111111111",
                    displayName = "PLAN.md",
                    expectedMimeType = "text/markdown",
                    maxBytes = 64 * 1_024,
                ),
                ContentReadDocument(
                    alias = "doc-222222222222222222222222",
                    displayName = "README.md",
                    expectedMimeType = "text/markdown",
                    maxBytes = 32 * 1_024,
                ),
            ),
            totalMaxBytes = 96 * 1_024,
            filesVerified = true,
        ),
        draft = AttentionDraft(null, "", 0, 0, null),
        actions = AttentionActionPolicy(
            canAllowContentRead = true,
            canDenyContentRead = true,
            canDismiss = true,
        ),
    )
}
