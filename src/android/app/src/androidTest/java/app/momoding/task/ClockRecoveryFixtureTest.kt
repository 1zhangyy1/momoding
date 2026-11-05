package app.momoding.task

import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.prompt.MomodingHelper
import com.openminis.app.MinisApp
import com.openminis.app.agent.jobs.HelperRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in saved near-budget helper. Recovery uses the real model/shell path, on owned local fixtures. */
class ClockRecoveryFixtureTest {
    @Test fun prepareOwnedHelper(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val parent = arguments.getString("m41_clock_parent")
        assumeTrue(!parent.isNullOrBlank())
        val owner = requireNotNull(parent)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(owner)?.title?.startsWith("M41 clock") == true)
        val mode = arguments.getString("m41_clock_mode") ?: "model"
        val hand = Helpers.create(context, owner, "M41 clock helper")
        val limit = MomodingHelper.MAX_MINUTES * 60_000L + HelperRunner.WRAP_UP_GRACE_MS
        val spent = limit - 12_000L
        val now = System.currentTimeMillis()
        val task = TaskEntity(java.util.UUID.randomUUID().toString(), owner, title = "M41 clock helper", hand = hand,
            state = "RUNNING", createdAt = now, updatedAt = now)
        app.database.withTransaction {
            val dao = TaskEngine.dao(context)
            dao.insertTask(task)
            dao.insertLog(TaskLogEntity(taskId = task.id, at = now, kind = "PASSED_ON", body = "M41_CLOCK_HELPER"))
            dao.insertLog(TaskLogEntity(taskId = task.id, at = now, kind = "STARTED",
                body = JSONObject().put("elapsed_ms", spent).put("reason", "owned previously spent time").toString()))
            if (mode == "read") {
                val file = File(context.filesDir, "minis-sessions/$owner/workspace/m41-clock-owned.txt")
                file.parentFile?.mkdirs(); file.writeText("M41_CLOCK_OWNED_BYTES")
                val input = app.chatRepository.appendMessage(hand, "user", """[{"type":"text","value":"M41_CLOCK_HELPER"}]""")
                Inbox.post(dao, hand, "M41_CLOCK_HELPER", task.id, id = input.id)
                dao.markRead(listOf(input.id), now)
                val text = "sleep 5; cat /var/minis/workspace/m41-clock-owned.txt"
                val args = JSONObject().put("command", text).put("timeout", 31).put("delay", 1).put("tool_title", "M41 clock read").toString()
                val callId = "m41_clock_read"
                val source = app.chatRepository.appendMessage(hand, "assistant", JSONArray().put(JSONObject().put("type", "toolUse")
                    .put("value", JSONObject().put("toolUseId", callId).put("name", "shell_execute").put("input", args))).toString())
                TaskEngine.recordCommandIn(dao, task, ShellWork.Command(callId, "$hand~command~$callId", text, ToolCall(source.id, callId, "shell_execute")))
            } else Inbox.post(dao, hand, "M41_CLOCK_HELPER", task.id)
        }
        delay(1_500) // Let the production liveness checkpoint, when present, reach disk.
        File(context.filesDir, "m41-clock-proof.json").writeText(JSONObject().put("parent", owner).put("hand", hand)
            .put("task", task.id).put("mode", mode).put("seeded_elapsed", spent).put("limit", limit).put("seeded_at", now)
            .put("last_alive", context.getSharedPreferences("momoding", 0).getLong("work.last_alive_at", 0)).toString())
    }
}
