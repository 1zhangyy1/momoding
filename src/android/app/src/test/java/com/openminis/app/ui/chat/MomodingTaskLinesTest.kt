package com.openminis.app.ui.chat

import app.momoding.task.TaskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Momoding (调研/M39): a task handed on in a step has its line there; a hand's report is not the person's bubble. */
class MomodingTaskLinesTest {
    private fun step(command: String, result: String) = AssistantBlock(
        id = "b", kind = "tool_use", toolName = "shell_execute", toolArgs = """{"command":"$command"}""",
        content = result, toolStatus = ToolBlockStatus.SUCCESS,
    )

    @Test fun `the tasks a step handed on come from its result`() {
        assertEquals(listOf("a1b2c3"), tasksHandedOn(step("momoding-task new --to bot:jizhang \\\"加一行\\\"", """{"task": "a1b2c3", "state": "running"}""")))
        assertEquals(listOf("0f0f0f"), tasksHandedOn(step("momoding-app new ledger --brief x", """{"app": "ledger", "task": "0f0f0f"}""")))
        // telling a task more opens none, and neither does any other command
        assertTrue(tasksHandedOn(step("momoding-task tell a1b2c3 \\\"靠窗\\\"", """{"task": "a1b2c3"}""")).isEmpty())
        assertTrue(tasksHandedOn(step("android-alarm set", """{"task": "a1b2c3"}""")).isEmpty())
    }

    @Test fun `a hand report shows as nothing, the task line shows where it was handed on`() {
        val report = ChatMessage(id = "r", role = "user", content = "${TaskEngine.REPORT_CUE}a1b2c3 · 记账 · done] 加好了")
        assertFalse(report.rendersAsUserBubble())
        val msgs = listOf(
            ChatMessage(id = "u", role = "user", content = "让记账加一行"),
            ChatMessage(id = "a", role = "assistant", content = "交出去了", toolBlocks = listOf(step("momoding-task new --to bot:jizhang x", """{"task": "a1b2c3"}"""))),
            report,
            ChatMessage(id = "a2", role = "assistant", content = "加好了"),
        )
        val rows = buildFlatChatItems(msgs, showSteps = false)
        assertEquals(1, rows.count { it is FlatChatItem.UserBubble })
        assertEquals(listOf("a1b2c3"), rows.filterIsInstance<FlatChatItem.MomodingTask>().map { it.taskId })
    }
}
