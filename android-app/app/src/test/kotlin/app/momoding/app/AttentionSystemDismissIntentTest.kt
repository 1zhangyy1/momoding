package app.momoding.app

import app.momoding.feature.attention.AttentionActionPolicy
import app.momoding.feature.attention.AttentionIdentity
import app.momoding.feature.attention.AttentionIntent
import app.momoding.feature.attention.AttentionUiState
import app.momoding.feature.attention.AttentionVisibleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionSystemDismissIntentTest {
    private val identity = AttentionIdentity("task-1", "call-1")

    @Test
    fun `pending dismiss policy maps system gestures to typed dismiss`() {
        assertEquals(
            AttentionIntent.Dismiss,
            visible(
                AttentionVisibleState.QuestionPending,
                AttentionActionPolicy(canDismiss = true),
            ).systemDismissIntent(),
        )
    }

    @Test
    fun `terminal return policy maps system gestures to typed return`() {
        assertEquals(
            AttentionIntent.ReturnToTask,
            visible(
                AttentionVisibleState.Resolved,
                AttentionActionPolicy(canReturnToTask = true),
            ).systemDismissIntent(),
        )
    }

    @Test
    fun `responding and pending draft barriers lock all system dismiss gestures`() {
        assertNull(
            visible(
                AttentionVisibleState.Responding,
                AttentionActionPolicy(),
            ).systemDismissIntent(),
        )
        assertNull(
            visible(
                AttentionVisibleState.QuestionPending,
                AttentionActionPolicy(
                    canEditCustom = true,
                    canSelectOption = true,
                    canReturnToTask = true,
                ),
            ).systemDismissIntent(),
        )
    }

    private fun visible(
        state: AttentionVisibleState,
        actions: AttentionActionPolicy,
    ) = AttentionUiState.Visible(
        identity = identity,
        state = state,
        prompt = null,
        draft = null,
        actions = actions,
    )
}
