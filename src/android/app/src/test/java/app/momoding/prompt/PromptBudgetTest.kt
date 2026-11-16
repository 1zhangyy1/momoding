package app.momoding.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each role's fixed instructions have a ceiling (调研/M50 A5). They ride on every call, and without
 * one they only ever grow: a new rule here, a new card there. Raising a ceiling is allowed, on
 * purpose, in the same change that needs it.
 */
class PromptBudgetTest {
    private val identity = "You are Momoding, the person's assistant on their Android phone."

    @Test fun `the main chat's instructions stay under their ceiling`() {
        val prompt = MomodingPrompt.build("Routines", MomodingPrompt.Inputs(
            identity = identity, browserEnabled = false, memoryOn = true, skills = null, mcp = null,
            recentNotes = null, aboutPerson = null, ladder = "", addenda = null, helpersEnabled = true,
        ))
        assertTrue("main chat: ${prompt.length} characters", prompt.length <= MAIN_CHAT)
    }

    @Test fun `a helper's instructions stay under their ceiling`() {
        val prompt = MomodingHelper.build(
            app.momoding.task.HelperConfig("chat", "task", MomodingHelper.MAX_TURNS, "Compare two phones' battery life"),
            browserEnabled = true, skills = null, addenda = null,
        )
        assertTrue("helper: ${prompt.length} characters", prompt.length <= HELPER)
    }

    private companion object {
        const val MAIN_CHAT = 18_000
        const val HELPER = 3_800
    }
}
