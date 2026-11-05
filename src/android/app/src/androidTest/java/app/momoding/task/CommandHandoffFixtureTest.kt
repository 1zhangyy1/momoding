package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in crash boundary: actual ShellWork handoff with a held local read callback, no Linux effect. */
class CommandHandoffFixtureTest {
    @Test fun prepareOwnedUnreturnedCommand(): Unit = runBlocking {
        val sid = InstrumentationRegistry.getArguments().getString("m41_command_link_session")
        assumeTrue(!sid.isNullOrBlank())
        val session = requireNotNull(sid)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(session)?.title?.startsWith("M41 command link") == true)
        val file = File(context.filesDir, "minis-sessions/$session/workspace/m41-command-link-owned.txt")
        file.parentFile?.mkdirs(); file.writeText("M41_COMMAND_LINK_OWNED_BYTES")
        val text = "sleep 60; cat /var/minis/workspace/m41-command-link-owned.txt"
        val args = JSONObject().put("command", text).put("timeout", 123).put("delay", 2).put("tool_title", "M41 command link read")
        val input = app.chatRepository.appendMessage(session, "user", """[{"type":"text","value":"M41_COMMAND_LINK_REQUEST"}]""")
        TaskEngine.dao(context).insertInbox(InboxEntity(input.id, session, sender = "USER", text = "M41_COMMAND_LINK_REQUEST",
            arrivedAt = System.currentTimeMillis(), readAt = System.currentTimeMillis()))
        val id = "m41_command_link_read"
        val source = app.chatRepository.appendMessage(session, "assistant", JSONArray().put(JSONObject().put("type", "toolUse")
            .put("value", JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", args.toString()))).toString())
        val call = ToolCall(source.id, id, "shell_execute")
        val result = withContext(call) {
            ShellWork.run(context, session, null, "M41 command link read", text) { _, _ ->
                delay(60_000); ToolExecutionResult(file.readText(), true)
            }
        }
        assertTrue(result.success)
        val task = TaskEngine.dao(context).recentTasks(100).single { it.conversation == session }
        assertEquals("RUNNING", task.state)
        assertTrue(TaskEngine.dao(context).log(task.id).none { it.kind == "COMMAND_RESULT" })
        File(context.filesDir, "m41-command-link-proof.json").writeText(JSONObject().put("session", session).put("task", task.id)
            .put("source", source.id).put("input", input.id).put("call", id).put("args", args).toString())
    }
}
