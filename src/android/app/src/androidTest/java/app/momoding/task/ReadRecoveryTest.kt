package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReadRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var session: String
    private lateinit var input: String
    private lateinit var task: TaskEntity
    private lateinit var command: ShellWork.Command
    private lateinit var args: String
    private val dao get() = db.taskDao()

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
        session = repo.createSession("fixture", "read recovery").id
        input = repo.appendMessage(session, "user", """[{"type":"text","value":"read file"}]""").id
        dao.insertInbox(InboxEntity(input, session, sender = "USER", text = "read file", arrivedAt = 1, readAt = 2))
        val now = System.currentTimeMillis()
        task = TaskEntity("read-task", session, input, "read file", session, "RUNNING", createdAt = now, updatedAt = now)
        dao.insertTask(task)
        command = register("read", "sleep 12; cat input.txt")
    }
    @After fun close() = db.close()

    private suspend fun register(id: String, text: String, hand: String = session): ShellWork.Command {
        args = JSONObject().put("command", text).put("timeout", 47).put("delay", 2).put("tool_title", "original read").toString()
        val source = repo.appendMessage(hand, "assistant", JSONArray().put(JSONObject().put("type", "toolUse").put("value",
            JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", args))).toString())
        return ShellWork.Command(id, "$hand~command~$id", text, ToolCall(source.id, id, "shell_execute")).also {
            db.withTransaction { TaskEngine.recordCommandIn(dao, requireNotNull(dao.task(task.id)), it) }
        }
    }
    private suspend fun recover() = db.withTransaction { TaskEngine.recoverCommandsIn(dao) }
    private suspend fun result(value: ShellWork.Command = command, unknown: Boolean = false) {
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 3, kind = "COMMAND_RESULT", body = JSONObject()
            .put("id", value.id).put("output", "observed").put("success", true).put("unknown", unknown).toString()))
    }

    @Test fun resumesOriginalIdentityAndCompleteArgumentsWithoutInventingAResult(): Unit = runBlocking {
        val read = recover().single()
        assertEquals(command, read.command); assertEquals(task.id, read.task.id); assertEquals(args, read.args)
        assertEquals(listOf("COMMAND", "HANDOFF"), dao.log(task.id).map { it.kind })
        assertEquals(task, dao.task(task.id)); assertTrue(dao.unread(session).isEmpty())
    }
    @Test fun knownSuccessAndKnownUnknownAreNeverRescheduled(): Unit = runBlocking {
        result(); assertTrue(recover().isEmpty())
        val other = register("uncertain", "cat other"); result(other, unknown = true)
        assertTrue(recover().isEmpty()); assertEquals(2, dao.log(task.id).count { it.kind == "COMMAND_RESULT" })
    }
    @Test fun deletedInvocationFallsBackToWaitingRatherThanGuessing(): Unit = runBlocking {
        db.chatDao().deleteMessages(session)
        assertTrue(recover().isEmpty()); assertEquals("WAITING", dao.task(task.id)?.state)
        assertTrue(JSONObject(requireNotNull(dao.task(task.id)?.ask)).getString("kind").contains("unknown"))
    }
    @Test fun outwardAndLegacyCommandsStillNeedVerification(): Unit = runBlocking {
        register("write", "echo new > input.txt")
        val legacy = ShellWork.Command("legacy", "legacy-process", "cat input.txt")
        db.withTransaction { TaskEngine.recordCommandIn(dao, task, legacy) }
        assertEquals(listOf(command), recover().map { it.command })
        assertEquals("RUNNING", dao.task(task.id)?.state) // The read still runs; the outward action remains on ask.
        assertNotNull(dao.task(task.id)?.ask)
        assertEquals(setOf("write", "legacy"), dao.log(task.id).filter { it.kind == "COMMAND_RESULT" }.map { JSONObject(it.body).getString("id") }.toSet())
    }
    @Test fun repeatedStartupKeepsOnePendingCommandAndNoSyntheticFailure(): Unit = runBlocking {
        assertEquals(recover(), recover()); assertEquals(1, ShellWork.pending(dao.log(task.id)).size)
        assertEquals(1, dao.recentTasks(10).size); assertEquals(2, dao.log(task.id).size)
    }
    @Test fun stoppedAndQueuedWorkCannotResume(): Unit = runBlocking {
        dao.updateTask(task.copy(state = "ENDED", outcome = "STOPPED")); assertTrue(recover().isEmpty())
        dao.updateTask(task.copy(state = "QUEUED")); assertTrue(recover().isEmpty())
    }
    @Test fun onlyOriginalConsumedInputIsDeferred(): Unit = runBlocking {
        val later = repo.appendMessage(session, "user", """[{"type":"text","value":"another message"}]""").id
        dao.insertInbox(InboxEntity(later, session, sender = "USER", text = "another message", arrivedAt = 4, readAt = 5))
        db.withTransaction {
            val reads = TaskEngine.recoverCommandsIn(dao)
            assertEquals(setOf(input), ReadRecovery.deferredInputsIn(dao, reads))
            Inbox.recoverIn(dao, ReadRecovery.deferredInputsIn(dao, reads))
        }
        assertEquals(2L, dao.inbox(input)?.readAt); assertEquals(0, dao.inbox(input)?.tries)
        assertNull(dao.inbox(later)?.readAt); assertEquals(1, dao.inbox(later)?.tries)
    }
    @Test fun waitingAndStoppedInputsAreNotKeptByTheDeferral(): Unit = runBlocking {
        val reads = recover()
        dao.updateTask(task.copy(state = "WAITING", ask = "verify something else"))
        assertTrue(ReadRecovery.deferredInputsIn(dao, reads).isEmpty())
        db.withTransaction { Inbox.recoverIn(dao, setOf(input)) }
        assertNull(dao.inbox(input))
    }
    @Test fun readResultCannotClearAnUnrelatedUnknownEffect(): Unit = runBlocking {
        register("outward", "echo new > input.txt"); recover()
        val waiting = requireNotNull(dao.task(task.id))
        db.withTransaction { TaskEngine.commandFinishedIn(context, dao, mutableListOf(), task.id, command, ToolExecutionResult("actual bytes", true)) }
        assertEquals("WAITING", dao.task(task.id)?.state); assertEquals(waiting.ask, dao.task(task.id)?.ask)
        assertNull(dao.task(task.id)?.outcome)
    }
    @Test fun readResultCannotClearAHold(): Unit = runBlocking {
        dao.updateTask(task.copy(hold = "wait for me")); assertEquals(1, recover().size)
        db.withTransaction { TaskEngine.commandFinishedIn(context, dao, mutableListOf(), task.id, command, ToolExecutionResult("actual bytes", true)) }
        assertEquals("RUNNING", dao.task(task.id)?.state); assertEquals("wait for me", dao.task(task.id)?.hold)
        assertNull(dao.task(task.id)?.outcome)
    }
    @Test fun ordinaryReadClosesOnlyAfterItsActualResult(): Unit = runBlocking {
        recover(); assertNull(dao.task(task.id)?.outcome)
        db.withTransaction { TaskEngine.commandFinishedIn(context, dao, mutableListOf(), task.id, command, ToolExecutionResult("actual bytes", true)) }
        assertEquals("DONE", dao.task(task.id)?.outcome)
        assertTrue(recover().isEmpty()); assertEquals(1, dao.log(task.id).count { it.kind == "COMMAND_RESULT" })
    }
    @Test fun helperKeepsItsOwnInvocationAndParentsTask(): Unit = runBlocking {
        result()
        val helper = repo.createSession("fixture", "helper", parentSessionId = session).id
        task = task.copy(hand = helper); dao.updateTask(task)
        val helperCommand = register("helper-read", "cat input.txt", helper)
        val read = recover().single()
        assertEquals(helperCommand, read.command); assertEquals(session, read.task.conversation); assertEquals(helper, read.task.hand)
        db.withTransaction { TaskEngine.commandFinishedIn(context, dao, mutableListOf(), task.id, helperCommand, ToolExecutionResult("actual bytes", true)) }
        assertEquals("RUNNING", dao.task(task.id)?.state)
        assertEquals(helper, dao.inboxFor(helper).single().toSession)
    }
}
