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
    private suspend fun recover() = db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), tasks) }
    private suspend fun receipt() = requireNotNull(db.chatDao().nextMessage(chat, db.chatDao().message(message)!!.sortOrder))

    private suspend fun pendingTurn() {
        tasks.insertInbox(InboxEntity(id = receive, toSession = chat, sender = "USER", text = "fixture", arrivedAt = 1, readAt = 2))
    }

    @Test fun aCommittedReceiptFillsItsOriginalInvocationOnce(): Unit = runBlocking {
        val action = action()
        assertEquals(1, recover())
        val row = receipt()
        assertEquals("actual receipt", JSONArray(row.partsJson).getJSONObject(0).getJSONObject("value").getString("output"))
        assertEquals(0, recover())
        assertEquals(row.id, receipt().id)
        assertEquals(1, tasks.log(action.taskId).count { it.kind == "TRANSCRIBED" })
    }

    @Test fun aReceiptGoesBeforeLaterUserWordsWithoutMovingOrDuplicatingThem(): Unit = runBlocking {
        val later = ChatRepository(db.chatDao()).appendMessage(chat, "user", """[{"type":"text","value":"newer words"}]""")
        action()
        recover()
        val rows = db.chatDao().loadMessagesPage(chat, 0, 20)
        assertEquals(listOf("user", "assistant", "user", "user"), rows.map { it.role })
        assertEquals(later.id, rows.last().id)
        assertEquals(later.partsJson, rows.last().partsJson)
        assertEquals(rows.size, rows.map { it.sortOrder }.distinct().size)
    }

    @Test fun concurrentEffectsRestoreInInvocationOrderAndKeepFailureTruth(): Unit = runBlocking {
        action("two", "failed delivery", success = false)
        action("one", "first receipt")
        assertEquals(2, recover())
        val parts = JSONArray(receipt().partsJson)
        assertEquals("one", parts.getJSONObject(0).getJSONObject("value").getString("toolUseId"))
        assertEquals("two", parts.getJSONObject(1).getJSONObject("value").getString("toolUseId"))
        assertFalse(parts.getJSONObject(1).getJSONObject("value").getBoolean("success"))
    }

    @Test fun anObservedReceiptReplacesAnUncommittedCancellationPlaceholder(): Unit = runBlocking {
        val placeholder = ChatRepository(db.chatDao()).appendMessage(chat, "user", JSONArray().put(result("one", "interrupted placeholder")).toString())
        action()
        recover()
        assertEquals(placeholder.id, receipt().id)
        assertTrue(receipt().partsJson.contains("actual receipt"))
        assertFalse(receipt().partsJson.contains("interrupted placeholder"))
    }

    @Test fun normalResultsCommitTheirReceiptMarkerInTheSameTransaction(): Unit = runBlocking {
        action()
        db.withTransaction { TaskTranscript.resultsIn(db.chatDao(), tasks, message, JSONArray().put(result("one", "complete tool output"))) }
        assertEquals(0, recover())
        assertTrue(receipt().partsJson.contains("complete tool output"))
        assertTrue(tasks.untranscribedActions().isEmpty())
    }

    @Test fun anAbsentResultIsNotFabricatedAndUnknownStaysAnError(): Unit = runBlocking {
        val pending = action(output = null)
        assertEquals(0, recover())
        assertNull(db.chatDao().nextMessage(chat, db.chatDao().message(message)!!.sortOrder))
        db.withTransaction { TaskEngine.actionResultIn(tasks, pending.taskId, pending.id, "unconfirmed effect", false, unknown = true) }
        assertEquals(1, recover())
        val value = JSONArray(receipt().partsJson).getJSONObject(0).getJSONObject("value")
        assertFalse(value.getBoolean("success"))
        assertTrue(value.getString("output").contains("do not repeat"))
    }

    @Test fun recoveryRollsBackTheMessageOrderAndMarkerTogether(): Unit = runBlocking {
        val action = action()
        val later = ChatRepository(db.chatDao()).appendMessage(chat, "user", """[{"type":"text","value":"later"}]""")
        try { db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), tasks); error("injected rollback") } }
        catch (expected: IllegalStateException) { assertEquals("injected rollback", expected.message) }
        assertEquals(later, db.chatDao().nextMessage(chat, db.chatDao().message(message)!!.sortOrder))
        assertFalse(tasks.log(action.taskId).any { it.kind == "TRANSCRIBED" })
        assertEquals(1, recover())
    }

    @Test fun largeOutputsRetainBalancedToolIdsWithinTheExistingRowBudget(): Unit = runBlocking {
        val huge = "\"\\\n".repeat(200_000)
        db.withTransaction { TaskTranscript.resultsIn(db.chatDao(), tasks, message, JSONArray().put(result("one", huge)).put(result("two", "small result"))) }
        val row = receipt()
        assertTrue(row.partsJson.length <= ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH)
        val values = JSONArray(row.partsJson)
        assertEquals(2, values.length())
        assertEquals("one", values.getJSONObject(0).getJSONObject("value").getString("toolUseId"))
        assertEquals("small result", values.getJSONObject(1).getJSONObject("value").getString("output"))
    }

    @Test fun deletingAnInvocationDoesNotResurrectItDuringRecovery(): Unit = runBlocking {
        action()
        val before = db.chatDao().message(message)!!
        db.chatDao().deleteMessagesAfter(chat, before.sortOrder)
        assertEquals(0, recover())
        assertEquals(1, db.chatDao().loadMessagesPage(chat, 0, 20).size)
        assertTrue(tasks.untranscribedActions().isEmpty())
    }

    @Test fun argumentsCannotMakeAnotherMessagesReceiptLookTranscribed(): Unit = runBlocking {
        val second = ChatRepository(db.chatDao()).appendMessage(chat, "assistant", db.chatDao().message(message)!!.partsJson)
        action(source = second.id, nestedMessage = message)
        db.withTransaction { TaskTranscript.resultsIn(db.chatDao(), tasks, message, JSONArray().put(result("one", "first turn result"))) }
        assertEquals(1, tasks.untranscribedActions().size)
        assertEquals(1, recover())
        val row = db.chatDao().nextMessage(chat, db.chatDao().message(second.id)!!.sortOrder)!!
        assertTrue(row.partsJson.contains("actual receipt"))
        assertTrue(receipt().partsJson.contains("first turn result"))
    }

    @Test fun interruptedSiblingsGetExplicitErrorsBesideTheKnownReceipt(): Unit = runBlocking {
        pendingTurn()
        action()
        assertEquals(2, recover())
        val parts = JSONArray(receipt().partsJson)
        assertTrue(parts.getJSONObject(0).getJSONObject("value").getBoolean("success"))
        val interrupted = parts.getJSONObject(1).getJSONObject("value")
        assertFalse(interrupted.getBoolean("success"))
        assertTrue(interrupted.getString("output").contains("interrupted"))
        assertEquals(0, recover())
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
}
