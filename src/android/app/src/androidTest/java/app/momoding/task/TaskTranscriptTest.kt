package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.guard.GuardKind
import app.momoding.guard.RiskAssessment
import app.momoding.guard.RiskClass
import app.momoding.guard.RiskRequest
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskTranscriptTest {
    private lateinit var db: AppDatabase
    private lateinit var chat: String
    private lateinit var message: String
    private lateinit var receive: String
    private val tasks get() = db.taskDao()

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        val repo = ChatRepository(db.chatDao())
        chat = repo.createSession("M41 transcript fixture").id
        receive = repo.appendMessage(chat, "user", """[{"type":"text","value":"the original request"}]""").id
        val uses = JSONArray(listOf("one", "two").map { id -> JSONObject().put("type", "toolUse").put("value",
            JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", "{}")) })
        message = repo.appendMessage(chat, "assistant", uses.toString()).id
    }

    @After fun close() { db.close() }

    private suspend fun action(call: String = "one", output: String? = "actual receipt", success: Boolean = true, unknown: Boolean = false,
        source: String = message, nestedMessage: String? = null,
    ): TaskAction = db.withTransaction {
        val spec = Approval.Spec(RiskRequest(sessionId = chat, kind = GuardKind.SHELL,
            assessment = RiskAssessment(RiskClass.OUTBOUND, "fixture", "fixture:recipient"), preview = "fixture only"),
            Approval.Mode.SHELL, JSONObject().put("command", "fixture only").apply { nestedMessage?.let { put("message", it) } }.toString())
        val action = TaskEngine.actionStartedIn(tasks, spec, call = ToolCall(source, call, "shell_execute"))
        if (output != null) TaskEngine.actionResultIn(tasks, action.taskId, action.id, output, success, unknown)
        action
    }

    private fun result(id: String, output: String) = JSONObject().put("type", "toolResult").put("value",
        JSONObject().put("toolUseId", id).put("name", "shell_execute").put("output", output).put("success", true))
    private suspend fun recover() = db.withTransaction { TaskTranscript.recoverIn(db.chatDao()) }
    private suspend fun receipt() = requireNotNull(db.chatDao().nextMessage(chat, db.chatDao().message(message)!!.sortOrder))

    private suspend fun pendingTurn() {
        tasks.insertInbox(InboxEntity(id = receive, toSession = chat, sender = "USER", text = "fixture", arrivedAt = 1, readAt = 2))
    }

    private suspend fun handoff(id: String = "saved-task", state: String = "RUNNING", outcome: String? = null) {
        val now = System.currentTimeMillis()
        tasks.insertTask(TaskEntity(id, chat, title = "saved helper", hand = "saved-helper-$id", state = state,
            outcome = outcome, createdAt = now, updatedAt = now))
        tasks.insertLog(TaskLogEntity(taskId = id, at = now, kind = "HANDOFF", body = JSONObject()
            .put("tool", ToolCall(message, "one", "shell_execute").json()).put("session", chat).put("task", id).toString()))
    }

    @Test fun largeOutputsRetainBalancedToolIdsWithinTheExistingRowBudget(): Unit = runBlocking {
        val huge = "\"\\\n".repeat(200_000)
        db.withTransaction { TaskTranscript.resultsIn(db.chatDao(), message, JSONArray().put(result("one", huge)).put(result("two", "small result"))) }
        val row = receipt()
        assertTrue(row.partsJson.length <= ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH)
        val values = JSONArray(row.partsJson)
        assertEquals(2, values.length())
        assertEquals("one", values.getJSONObject(0).getJSONObject("value").getString("toolUseId"))
        assertEquals("small result", values.getJSONObject(1).getJSONObject("value").getString("output"))
    }

    @Test fun anInterruptedReadTurnNeverLooksLikeCompletedTools(): Unit = runBlocking {
        pendingTurn()
        assertEquals(2, recover())
        val parts = JSONArray(receipt().partsJson)
        assertEquals(2, parts.length())
        for (i in 0 until parts.length()) assertFalse(parts.getJSONObject(i).getJSONObject("value").getBoolean("success"))
        assertTrue(tasks.recentTasks(20).isEmpty())
        assertEquals(0, recover())
    }

    @Test fun aDeletedOrMismatchedInvocationCannotStartAnEffect(): Unit = runBlocking {
        val spec = Approval.Spec(RiskRequest(sessionId = chat, kind = GuardKind.SHELL,
            assessment = RiskAssessment(RiskClass.OUTBOUND, "fixture", "fixture:recipient"), preview = "fixture"),
            Approval.Mode.SHELL, """{"command":"fixture only"}""")
        assertTrue(runCatching { db.withTransaction { TaskEngine.actionStartedIn(tasks, spec, call = ToolCall(message, "missing", "shell_execute")) } }.isFailure)
        db.chatDao().deleteMessagesAfter(chat, db.chatDao().message(message)!!.sortOrder)
        assertTrue(runCatching { action() }.isFailure)
        assertTrue(tasks.recentTasks(10).isEmpty())
    }
}
