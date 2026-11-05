package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real SQLite recovery of a detached command's original call, including calls made by a helper. */
class CommandHandoffTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private lateinit var hand: String
    private lateinit var task: TaskEntity
    private lateinit var call: ToolCall
    private lateinit var args: String
    private val dao get() = db.taskDao()
    private val command get() = ShellWork.Command("command", "$hand~command~fixture", "sleep 12; cat input.txt", call)

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
        owner = repo.createSession("fixture", "owner").id
        hand = repo.createSession("fixture", "helper", parentSessionId = owner).id
        task = TaskEntity("work", owner, title = "read input", hand = hand, state = "RUNNING", createdAt = 1, updatedAt = 1)
        dao.insertTask(task)
        args = JSONObject().put("command", "sleep 12; cat input.txt").put("timeout", 47).put("delay", 2)
            .put("tool_title", "read the saved file").put("extra", JSONObject().put("keep", true)).toString()
        val row = repo.appendMessage(hand, "assistant", JSONArray().put(use("read", args)).toString())
        call = ToolCall(row.id, "read", "shell_execute")
    }

    @After fun close() { db.close() }
    private fun use(id: String, input: String) = JSONObject().put("type", "toolUse").put("value", JSONObject()
        .put("toolUseId", id).put("name", "shell_execute").put("input", input))
    private suspend fun record(value: ShellWork.Command = command) = db.withTransaction { TaskEngine.recordCommandIn(dao, task, value) }
    private suspend fun recover() = db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) }
    private suspend fun receipt() = JSONArray(requireNotNull(db.chatDao().nextMessage(hand,
        requireNotNull(db.chatDao().message(call.message)).sortOrder)).partsJson).getJSONObject(0).getJSONObject("value")
    private suspend fun result(success: Boolean, unknown: Boolean = false) {
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 2, kind = "COMMAND_RESULT", body = JSONObject()
            .put("id", command.id).put("output", "the actual observed bytes").put("success", success).put("unknown", unknown).toString()))
    }

    @Test fun commandAndItsReturnPathCommitTogetherWithExactArguments(): Unit = runBlocking {
        record()
        val log = dao.log(task.id)
        assertEquals(listOf("COMMAND", "HANDOFF"), log.map { it.kind })
        val saved = requireNotNull(ShellWork.Command.parse(log.first().body))
        assertEquals(command, saved); assertEquals(args, saved.savedArgs(dao, hand))
        val handoff = JSONObject(log.last().body)
        assertEquals(hand, handoff.getString("session")); assertEquals(command.id, handoff.getString("command"))
    }

    @Test fun anInterruptedHelperCallRestoresItsExistingTaskInTheHelpersTranscriptOnce(): Unit = runBlocking {
        record(); assertEquals(1, recover())
        val value = receipt(); assertFalse(value.getBoolean("success"))
        assertTrue(value.getString("output").contains(task.id)); assertTrue(value.getString("output").contains("Do not create duplicates"))
        assertTrue(db.chatDao().loadMessages(owner).isEmpty())
        assertEquals(0, recover()); assertEquals(1, dao.recentTasks(10).size)
        assertEquals(1, dao.log(task.id).count { it.kind == "TRANSCRIBED" })
    }

    @Test fun anObservedCommandReturnIsRecoveredInsteadOfAnInterruptedPlaceholder(): Unit = runBlocking {
        record(); result(true); recover()
        assertTrue(receipt().getBoolean("success")); assertTrue(receipt().getString("output").contains("the actual observed bytes"))
    }

    @Test fun aRecordedFailureKeepsItsActualCause(): Unit = runBlocking {
        record(); result(false); recover()
        assertFalse(receipt().getBoolean("success")); assertTrue(receipt().getString("output").contains("the actual observed bytes"))
    }

    @Test fun anUnknownReturnNeverBecomesAClaimOfSuccess(): Unit = runBlocking {
        record(); result(true, unknown = true); recover()
        assertFalse(receipt().getBoolean("success")); assertTrue(receipt().getString("output").contains("outcome is unknown"))
    }

    @Test fun transactionFailureLeavesNeitherHalfOfTheHandoff(): Unit = runBlocking {
        assertTrue(runCatching { db.withTransaction { TaskEngine.recordCommandIn(dao, task, command); error("rollback") } }.isFailure)
        assertTrue(dao.log(task.id).isEmpty())
    }

    @Test fun deletedForeignAndChangedCallsCannotRegisterNewWork(): Unit = runBlocking {
        assertNull(command.savedArgs(dao, owner))
        assertTrue(runCatching { record(command.copy(text = "different command")) }.isFailure)
        assertTrue(runCatching { record(command.copy(call = call.copy(id = "missing"))) }.isFailure)
        assertTrue(runCatching { record(command.copy(call = call.copy(name = "file_write"))) }.isFailure)
        db.chatDao().deleteMessages(hand)
        assertTrue(runCatching { record() }.isFailure)
        assertTrue(dao.log(task.id).isEmpty())
    }

    @Test fun legacyRecordsRemainReadableWithoutInventingAnInvocation(): Unit = runBlocking {
        val legacy = requireNotNull(ShellWork.Command.parse("""{"id":"old","process":"old-process","text":"cat old"}"""))
        assertNull(legacy.call); assertNull(legacy.savedArgs(dao, hand)); record(legacy)
        assertEquals(listOf("COMMAND"), dao.log(task.id).map { it.kind })
        assertEquals(legacy, ShellWork.Command.parse(legacy.json()))
    }

    @Test fun ambiguousDuplicateCallIdsAreNotAnExactRecipe(): Unit = runBlocking {
        val row = repo.appendMessage(hand, "assistant", JSONArray().put(use("same", args)).put(use("same", args)).toString())
        val ambiguous = command.copy(call = ToolCall(row.id, "same", "shell_execute"))
        assertNull(ambiguous.savedArgs(dao, hand)); assertTrue(runCatching { record(ambiguous) }.isFailure)
    }

    @Test fun aNormalReturnSettlesTheHandoffWithoutASecondRecoveryRow(): Unit = runBlocking {
        record()
        val returned = JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", call.id).put("name", call.name).put("output", "normal return").put("success", true))
        db.withTransaction { TaskTranscript.resultsIn(db.chatDao(), dao, call.message, JSONArray().put(returned)) }
        assertEquals(0, recover()); assertEquals("normal return", receipt().getString("output"))
    }
}
