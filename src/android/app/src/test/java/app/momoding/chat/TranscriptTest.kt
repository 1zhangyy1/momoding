package app.momoding.chat

import app.momoding.chat.Transcript.Line
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: the conversation as text for the summary (fitted fairly) and for the record (whole). */
class TranscriptTest {

    @Test fun `the person's words, tool calls and results each become a line`() {
        val lines = Transcript.lines(listOf(
            LLMMessage(LLMMessage.Role.USER, "我的车牌是京A·7K2Q9"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.Text("查一下"),
                AgentContentPart.ToolUse("t1", "web_search", JSONObject().put("q", "高铁")),
            )),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("t1", "web_search", "G41 08:56"))),
            LLMMessage(LLMMessage.Role.USER, "<system-reminder>note</system-reminder>"),
            LLMMessage(LLMMessage.Role.USER, "[task ab12 · command · done] ok"),
        ))
        assertEquals(listOf("user", "assistant", "assistant [tool:web_search]", "[result:web_search]", "user", "user"), lines.map { it.who })
        assertEquals(listOf(true, false, false, false, false, false), lines.map { it.person })
        assertEquals("user: 我的车牌是京A·7K2Q9\n", Transcript.render(lines.take(1)))
    }

    @Test fun `what fits is left whole`() {
        val lines = listOf(Line("user", "a".repeat(50), person = true), Line("[result:x]", "b".repeat(100)))
        assertEquals(lines, Transcript.fit(lines, 1_000))
    }

    @Test fun `the person's words stay whole and the rest share what is left`() {
        val said = Line("user", "我".repeat(300), person = true)
        val short = Line("assistant", "好的")
        val long1 = Line("[result:a]", "x".repeat(5_000))
        val long2 = Line("[result:b]", "y".repeat(9_000))
        val fitted = Transcript.fit(listOf(said, short, long1, long2), 2_302)
        assertEquals(said, fitted[0])
        assertEquals(short, fitted[1])
        assertTrue(fitted[2].text.startsWith("x".repeat(1_000) + " …[4000 chars left out]"))
        assertTrue(fitted[3].text.startsWith("y".repeat(1_000) + " …[8000 chars left out]"))
    }

    @Test fun `the water level is the largest fair cut`() {
        assertEquals(Int.MAX_VALUE, Transcript.waterLevel(listOf(10, 20), 30))
        assertEquals(10, Transcript.waterLevel(listOf(10, 50, 50), 30))
        assertEquals(20, Transcript.waterLevel(listOf(5, 50, 50), 45))
        assertEquals(0, Transcript.waterLevel(listOf(5, 50), 0))
    }

    @Test fun `a pasted page from the person is cut too, at its own limit`() {
        val page = Line("user", "z".repeat(Transcript.MAX_PERSON_CHARS + 10), person = true)
        val fitted = Transcript.fit(listOf(page), 1_000_000)
        assertFalse(fitted[0].text.length <= Transcript.MAX_PERSON_CHARS)
        assertTrue(fitted[0].text.endsWith("…[10 chars left out]"))
    }
}
