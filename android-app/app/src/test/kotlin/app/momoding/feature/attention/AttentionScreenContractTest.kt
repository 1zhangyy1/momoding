package app.momoding.feature.attention

import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionValidationCode
import app.momoding.core.data.ContentReadDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionScreenContractTest {
    private val identity = AttentionIdentity("task", "call")

    @Test
    fun `all twelve states map to one exact visual role`() {
        val mappings = listOf(
            AttentionVisibleState.ConfirmPending to AttentionVisualRole.CONFIRM_WARNING,
            AttentionVisibleState.ContentReadPending to AttentionVisualRole.CONTENT_READ_WARNING,
            AttentionVisibleState.QuestionPending to AttentionVisualRole.QUESTION_WARNING,
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_REQUIRED) to
                AttentionVisualRole.VALIDATION_ERROR,
            AttentionVisibleState.OfflinePending to AttentionVisualRole.OFFLINE_INFO,
            AttentionVisibleState.Responding to AttentionVisualRole.RESPONDING_INFO,
            AttentionVisibleState.Resolved to AttentionVisualRole.RESOLVED_SUCCESS,
            AttentionVisibleState.Rejected to AttentionVisualRole.REJECTED_DANGER,
            AttentionVisibleState.Skipped to AttentionVisualRole.SKIPPED_NEUTRAL,
            AttentionVisibleState.Expired to AttentionVisualRole.EXPIRED_NEUTRAL,
            AttentionVisibleState.Cancelled to AttentionVisualRole.CANCELLED_NEUTRAL,
            AttentionVisibleState.AlreadyAnswered to AttentionVisualRole.ALREADY_ANSWERED_INFO,
        )

        assertEquals(
            mappings.map { it.second },
            mappings.map { (state, _) -> state.visualRole() },
        )
        assertEquals(12, mappings.map { it.second.testTag }.toSet().size)
    }

    @Test
    fun `pending copy consumes only bounded typed prompt fields`() {
        val confirmation = visible(
            state = AttentionVisibleState.ConfirmPending,
            prompt = AttentionPrompt.Confirmation(
                summary = "Run the synthetic check?",
                details = "This continues only the current request.",
            ),
        ).stateCopy()
        assertEquals(
            AttentionStateCopy(
                eyebrow = "Review required",
                heading = "Run the synthetic check?",
                body = "This continues only the current request.",
            ),
            confirmation,
        )

        val question = visible(
            state = AttentionVisibleState.QuestionPending,
            prompt = AttentionPrompt.Question(
                question = "Choose a safe path.",
                options = emptyList(),
            ),
        ).stateCopy()
        assertEquals(
            AttentionStateCopy(
                eyebrow = "Momoding question",
                heading = "Choose a safe path.",
            ),
            question,
        )

        val contentRead = visible(
            state = AttentionVisibleState.ContentReadPending,
            prompt = AttentionPrompt.ContentRead(
                grantId = "grant",
                purpose = "Read project instructions",
                documents = listOf(
                    ContentReadDocument(
                        alias = "doc-0123456789abcdef01234567",
                        displayName = "README.md",
                        expectedMimeType = "text/markdown",
                        maxBytes = 32_768,
                    ),
                ),
                totalMaxBytes = 32_768,
                filesVerified = true,
            ),
        ).stateCopy()
        assertEquals(
            AttentionStateCopy(
                eyebrow = "File access request",
                heading = "Allow Momoding to read 1 file?",
                body = "Read project instructions",
            ),
            contentRead,
        )
    }

    @Test
    fun `offline responding and terminal copy is exact and does not claim extra delivery`() {
        val copies = listOf(
            AttentionVisibleState.OfflinePending to AttentionStateCopy(
                "Host offline",
                "Your draft is saved on this device.",
                "Reconnect before sending a response.",
            ),
            AttentionVisibleState.Responding to AttentionStateCopy(
                "Response saved",
                "Waiting for Momoding",
                "Your response is saved on this device and is waiting for Momoding.",
            ),
            AttentionVisibleState.Resolved to AttentionStateCopy(
                "Answer sent",
                "Momoding received your response.",
            ),
            AttentionVisibleState.Rejected to AttentionStateCopy(
                "Step declined",
                "Momoding received your decision.",
                "This step will not continue.",
            ),
            AttentionVisibleState.Skipped to AttentionStateCopy(
                "Question skipped",
                "The task can continue without an answer.",
            ),
            AttentionVisibleState.Expired to AttentionStateCopy(
                "Request expired",
                "This request is no longer active.",
            ),
            AttentionVisibleState.Cancelled to AttentionStateCopy(
                "Request cancelled",
                "This request was cancelled.",
            ),
            AttentionVisibleState.AlreadyAnswered to AttentionStateCopy(
                "Already handled",
                "This request was already handled.",
            ),
        )

        copies.forEach { (state, expected) ->
            assertEquals(expected, visible(state).stateCopy())
        }
    }

    @Test
    fun `five transient notices expose only frozen public copy`() {
        assertEquals(
            listOf(
                "Your response wasn't sent. Try the action again or dismiss this request.",
                "Your latest edit wasn't saved. Edit the answer or choose an option to try again. Your last saved draft is still available.",
                "Couldn't save the dismissal. Try closing again, or return to the task without dismissing.",
                "This answer is too long to save. Shorten it to 8,192 characters or fewer. Your last saved draft is unchanged.",
                "Couldn't reconnect to the Host. Try again.",
            ),
            AttentionTransientNotice.entries.map { it.fixedCopy() },
        )
    }

    private fun visible(
        state: AttentionVisibleState,
        prompt: AttentionPrompt? = null,
    ) = AttentionUiState.Visible(
        identity = identity,
        state = state,
        prompt = prompt,
        draft = prompt?.let {
            AttentionDraft(
                selectedOptionIndex = null,
                customAnswer = "",
                selectionStart = 0,
                selectionEnd = 0,
                validationCode = null,
            )
        },
        actions = AttentionActionPolicy(),
    )
}
