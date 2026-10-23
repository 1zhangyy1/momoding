package app.momoding.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressNudgeTest {
    private fun person(text: String) = LLMMessage(LLMMessage.Role.USER, text, contentParts = listOf(AgentContentPart.Text(text)))
    private fun step(i: Int, said: String = "") = listOf(
        LLMMessage(LLMMessage.Role.ASSISTANT, said, contentParts = listOfNotNull(
            said.takeIf { it.isNotEmpty() }?.let { AgentContentPart.Text(it) },
            AgentContentPart.ToolUse("t$i", "shell_execute", JSONObject()),
        )),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("t$i", "shell_execute", "out $i"))),
    )

    @Test fun eightQuietStepsMakeANoteDue() {
        val h = listOf(person("比价")) + (1..8).flatMap { step(it, if (it == 1) "在查两款手机" else "") }
        assertEquals(7, ProgressNudge.quietSteps(h)) // the first step came with a line
        assertFalse(ProgressNudge.due(h))
        val h2 = h + step(9)
        assertTrue(ProgressNudge.due(h2))
    }

    @Test fun theNoteItselfStartsTheCountAgain() {
        val h = listOf(person("比价")) + (1..8).flatMap { step(it) } + person(ProgressNudge.TEXT) + (9..12).flatMap { step(it) }
        assertEquals(4, ProgressNudge.quietSteps(h))
        assertFalse(ProgressNudge.due(h))
    }

    @Test fun shortJobsAreLeftAlone() {
        assertFalse(ProgressNudge.due(listOf(person("几点了")) + (1..3).flatMap { step(it) }))
    }
}
