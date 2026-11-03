package app.momoding.task

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionTest {
    @Test fun `a Bot saying it started is not evidence that its task is done`() {
        val result = TaskCompletion.afterTurn(helper = false, finalText = "开始动手了", error = null)
        assertFalse(result.done)
        assertTrue(result.words.contains("without reporting"))
    }

    @Test fun `a Bot still needs an explicit report even with a plausible answer`() {
        assertFalse(TaskCompletion.afterTurn(false, "已经全部做好了", null).done)
    }

    @Test fun `the helper returns its entire final deliverable`() {
        val answer = "完整结果，附凭据。".repeat(1500)
        val result = TaskCompletion.afterTurn(true, answer, null)
        assertTrue(result.done)
        assertEquals(answer, result.words)
    }

    @Test fun `an error cannot turn partial assistant text into a completed task`() {
        val result = TaskCompletion.afterTurn(true, "我查到了第一项", "HTTP 429")
        assertFalse(result.done)
        assertTrue(result.words.contains("HTTP 429"))
    }

    @Test fun `an empty or missing final answer fails honestly`() {
        for (text in listOf(null, "", "   ")) {
            assertFalse(TaskCompletion.afterTurn(true, text, null).done)
            assertFalse(TaskCompletion.afterTurn(false, text, null).done)
        }
    }

    @Test fun `interruption has its own explanation and never succeeds`() {
        val result = TaskCompletion.afterTurn(true, "之前的文字", "The run was interrupted.")
        assertFalse(result.done)
        assertTrue(result.words.contains("interrupted"))
    }

    @Test fun `an earlier answer is not the result of a run that produced nothing`() {
        val old = LLMMessage(LLMMessage.Role.ASSISTANT, "上一次的答案")
        assertFalse(TaskCompletion.fromHistory(true, old, old, null).done)
    }

    @Test fun `assistant text followed by another tool call is not a final deliverable`() {
        val working = LLMMessage(LLMMessage.Role.ASSISTANT, "开始动手", contentParts = listOf(
            AgentContentPart.Text("开始动手"),
            AgentContentPart.ToolUse("call1", "shell_execute", JSONObject().put("command", "date")),
        ))
        assertFalse(TaskCompletion.fromHistory(true, null, working, null).done)
    }

    @Test fun `a tool result cannot stand in for the helper final answer`() {
        val toolResult = LLMMessage(LLMMessage.Role.USER, "command finished")
        assertFalse(TaskCompletion.fromHistory(true, null, toolResult, null).done)
    }
}
