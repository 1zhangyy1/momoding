package app.momoding.task

import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly seeded old activity; preparation executes no command. */
class LegacyRecoveryFixtureTest {
    @Test fun cleanupOwnedLegacyActivity(): Unit = runBlocking {
        val sid = InstrumentationRegistry.getArguments().getString("m41_legacy_cleanup_session")
        assumeTrue(!sid.isNullOrBlank()); val owner = requireNotNull(sid)
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }
        val session = app.database.chatDao().getSession(owner)
        require(session == null || session.title?.startsWith("M41 legacy") == true)
        app.database.withTransaction { app.database.chatDao().deleteSession(owner) }
        val prefs = context.getSharedPreferences("momoding", 0)
        prefs.getString("work.running", null)?.let { raw ->
            val value = JSONObject(raw); if (value.has(owner)) {
                value.remove(owner); val edit = prefs.edit()
                if (value.length() == 0) edit.remove("work.running") else edit.putString("work.running", value.toString())
                check(edit.commit())
            }
        }
        File(context.filesDir, "minis-sessions/$owner").deleteRecursively()
        File(context.filesDir, "m41-legacy-proof.json").delete()
    }

    @Test fun prepareOwnedLegacyActivity(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments(); val sid = arguments.getString("m41_legacy_session")
        assumeTrue(!sid.isNullOrBlank()); val owner = requireNotNull(sid)
        val mode = arguments.getString("m41_legacy_mode") ?: "flag"
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(owner)?.title?.startsWith("M41 legacy") == true)
        val now = System.currentTimeMillis(); val id = java.util.UUID.randomUUID().toString()
        val input = app.chatRepository.appendMessage(owner, "user", """[{"type":"text","value":"M41_LEGACY_OLD"}]""")
        val text = "sleep 5; cat /var/minis/workspace/m41-legacy-owned.txt"
        val uses = JSONArray(listOf("old", "healthy").map { name -> JSONObject().put("type", "toolUse").put("value", JSONObject()
            .put("toolUseId", "m41_legacy_$name").put("name", "shell_execute")
            .put("input", JSONObject().put("command", if (name == "old") text else "printf recorded").put("timeout", 25).toString())) })
        val source = app.chatRepository.appendMessage(owner, "assistant", uses.toString())
        val task = TaskEntity(id, owner, title = "M41 legacy activity", hand = owner, state = "RUNNING", createdAt = now, updatedAt = now)
        app.database.withTransaction {
            val dao = app.database.taskDao(); dao.insertTask(task)
            dao.insertInbox(InboxEntity(input.id, owner, sender = "USER", text = "M41_LEGACY_OLD", arrivedAt = now, readAt = now))
            dao.insertLog(TaskLogEntity(taskId = id, at = now, kind = "ABOUT_TO", body = JSONObject().put("id", "known-healthy")
                .put("session", owner).put("tool", ToolCall(source.id, "m41_legacy_healthy", "shell_execute").json()).toString()))
            dao.insertLog(TaskLogEntity(taskId = id, at = now, kind = "RESULT", body = """{"id":"known-healthy","success":true,"output":"M41_LEGACY_RECORDED"}"""))
            if (mode != "flag") dao.insertLog(TaskLogEntity(taskId = id, at = now, kind = "HANDOFF", body = JSONObject()
                .put("session", owner).put("tool", JSONObject().put("message", source.id)).toString()))
            if (mode == "read") TaskEngine.recordCommandIn(dao, task,
                ShellWork.Command("legacy-read", "$owner~command~legacy-read", text, ToolCall(source.id, "m41_legacy_old", "shell_execute")))
        }
        if (mode != "orphan") context.getSharedPreferences("momoding", 0).edit().putString("work.running",
            JSONObject().put(owner, JSONObject().put("at", now).put("tries", 1)).toString()).commit()
        val file = File(context.filesDir, "minis-sessions/$owner/workspace/m41-legacy-owned.txt"); file.parentFile?.mkdirs(); file.writeText("M41_LEGACY_OWNED_BYTES")
        File(context.filesDir, "m41-legacy-proof.json").writeText(JSONObject().put("session", owner).put("task", id).put("input", input.id)
            .put("source", source.id).put("mode", mode).toString())
    }
}
