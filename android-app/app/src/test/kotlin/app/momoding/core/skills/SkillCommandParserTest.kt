package app.momoding.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCommandParserTest {
    @Test
    fun ordinaryPromptIsPreservedExactly() {
        val text = "  explain /skill:mobile-review without invoking it  "

        assertEquals(SkillComposerInput.Prompt(text), parseSkillComposerInput(text))
    }

    @Test
    fun explicitSkillParsesNameAndOptionalInstructions() {
        val withoutInstructions = parseSkillComposerInput("/skill:mobile-review")
            as SkillComposerInput.Skill
        assertEquals("mobile-review", withoutInstructions.name)
        assertNull(withoutInstructions.additionalInstructions)

        val withInstructions = parseSkillComposerInput(
            "/skill:mobile-review   Check only the current diff.  ",
        ) as SkillComposerInput.Skill
        assertEquals("/skill:mobile-review   Check only the current diff.  ", withInstructions.originalText)
        assertEquals("mobile-review", withInstructions.name)
        assertEquals("Check only the current diff.", withInstructions.additionalInstructions)
    }

    @Test
    fun recognizedMalformedSkillCommandNeverFallsBackToPrompt() {
        assertEquals(
            SkillCommandInvalidReason.NAME_REQUIRED,
            (parseSkillComposerInput("/skill:") as SkillComposerInput.Invalid).reason,
        )
        assertEquals(
            SkillCommandInvalidReason.NAME_INVALID,
            (parseSkillComposerInput("/skill:Mobile_Review") as SkillComposerInput.Invalid).reason,
        )
        assertTrue(parseSkillComposerInput("Use /skill:mobile-review") is SkillComposerInput.Prompt)
    }
}
