package app.momoding.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeResultsTest {
    private fun result(id: String, n: Int) = AgentContentPart.ToolResult(id = id, name = "browser_use", content = "x".repeat(n))
    private fun turn(vararg parts: AgentContentPart) = LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    @Test
    fun `small results pass through untouched, as the same list`() {
        val msgs = listOf(turn(result("a", LargeResults.LIMIT_CHARS)))
        assertSame(msgs, LargeResults.cap(msgs) { error("nothing to save") })
    }

    @Test
    fun `a huge result keeps its start and points at the whole file`() {
        val saved = mutableListOf<String>()
        val out = LargeResults.cap(listOf(turn(result("a", 100), result("b", 50_000)))) { saved += it.id; "/var/minis/offloads/tools/b.full.txt" }
        val parts = out.single().contentParts.map { it as AgentContentPart.ToolResult }
        assertEquals(listOf("b"), saved)
        assertEquals(100, parts[0].content.length)
        assertTrue(parts[1].content.startsWith("x".repeat(LargeResults.PREVIEW_CHARS) + "\n\n[Long result"))
        assertTrue(parts[1].content.contains("of 50000 characters"))
        assertTrue(parts[1].content.contains("/var/minis/offloads/tools/b.full.txt"))
    }

    @Test
    fun `when the file cannot be written the result stays whole`() {
        val out = LargeResults.cap(listOf(turn(result("b", 30_000)))) { "" }
        assertEquals(30_000, (out.single().contentParts.single() as AgentContentPart.ToolResult).content.length)
    }
}
