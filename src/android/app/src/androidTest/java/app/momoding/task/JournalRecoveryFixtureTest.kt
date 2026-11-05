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

/** Opt-in old-journal fixture; no executor or model is called while seeding. */
class JournalRecoveryFixtureTest {
    @Test fun prepareOwnedJournal(): Unit = runBlocking {
        val sid = InstrumentationRegistry.getArguments().getString("m41_journal_session")
        assumeTrue(!sid.isNullOrBlank())
        val session = requireNotNull(sid)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(session)?.title?.startsWith("M41 journal") == true)
        val cases = listOf("partial-action", "unreadable-action", "partial-command", "unreadable-command", "known-action", "known-command")
        val proof = JSONArray()
        app.database.withTransaction {
            val dao = app.database.taskDao()
            for (name in cases) {
                val now = System.currentTimeMillis()
                val task = TaskEntity(java.util.UUID.randomUUID().toString(), session, title = name, hand = session,
                    state = "RUNNING", createdAt = now, updatedAt = now)
                dao.insertTask(task)
                val kind = if (name.endsWith("command")) "COMMAND" else "ABOUT_TO"
                val raw = if (name.startsWith("unreadable")) "{interrupted fixture JSON"
                    else JSONObject().put("id", name).put("preview", "owned fixture only").toString()
                dao.insertLog(TaskLogEntity(taskId = task.id, at = now, kind = kind, body = raw))
                val intent = dao.log(task.id).last()
                if (name.startsWith("known")) dao.insertLog(TaskLogEntity(taskId = task.id, at = now,
                    kind = if (kind == "COMMAND") "COMMAND_RESULT" else "RESULT",
                    body = JSONObject().put("id", name).put("success", name == "known-action").put("output", "observed fixture result").toString()))
                proof.put(JSONObject().put("task", task.id).put("name", name).put("intent", intent.id).put("raw", raw))
            }
        }
        File(context.filesDir, "m41-journal-proof.json").writeText(JSONObject().put("session", session).put("cases", proof).toString())
    }
}
