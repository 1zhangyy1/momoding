package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.scheduled.PrefilledToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoutineHandoffTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val prefill = listOf(PrefilledToolCall("shell_execute", """{"command":"printf owned","timeout":29}"""))
    private val fire = RoutineFire("fixture-routine", 123, true)
    private val after = mutableListOf<suspend () -> Unit>()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao()); owner = repo.createSession("fixture", "routine handoff").id
    }
    @After fun close() = db.close()
    private suspend fun open(routine: RoutineFire? = fire, call: ToolCall? = null, create: (suspend () -> String)? = null) =
        db.withTransaction { TaskEngine.openHelperIn(context, dao, after, owner, "fixture", "original brief", prefill, routine, call,
            create ?: { repo.createSession("fixture", parentSessionId = owner).id }) }
    private fun abort(table: String, condition: String = "1") = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER fixture_abort BEFORE INSERT ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'owned handoff fault'); END")

    @Test fun helperTaskOriginAndInputAreOneCommittedHandoff(): Unit = runBlocking {
        val task = open(); assertEquals(owner, db.chatDao().getSession(task.hand)?.parentSessionId)
        assertEquals(task, dao.task(task.id)); assertEquals("RUNNING", task.state); assertEquals(1, after.size)
        val input = dao.inboxFor(task.hand).single(); assertEquals(task.id, input.taskId); assertEquals("original brief", input.text)
        assertEquals(prefill.single().args().toString(), org.json.JSONArray(input.prefill!!).getJSONObject(0).getJSONObject("args").toString())
        assertEquals(fire, RoutineFire.parse(dao.log(task.id).single { it.kind == "ROUTINE" }.body))
        assertNull(task.sourceMessageId); assertTrue(dao.log(task.id).none { it.kind == "AUTHORIZED" })
    }
    @Test fun failedTaskInsertRollsBackTheHelperSession(): Unit = runBlocking {
        abort("task"); assertTrue(runCatching { open() }.isFailure)
        assertTrue(db.chatDao().childSessionIds(owner).isEmpty()); assertTrue(dao.openIn(owner).isEmpty()); assertTrue(after.isEmpty())
    }
    @Test fun failedInputInsertRollsBackSessionTaskAndOrigin(): Unit = runBlocking {
        abort("inbox"); assertTrue(runCatching { open() }.isFailure)
        assertTrue(db.chatDao().childSessionIds(owner).isEmpty()); assertTrue(dao.recentTasks(20).isEmpty()); assertTrue(after.isEmpty())
    }
    @Test fun failedOriginInsertRollsBackTheWholeHandoff(): Unit = runBlocking {
        abort("task_log", "NEW.kind = 'ROUTINE'"); assertTrue(runCatching { open() }.isFailure)
        assertTrue(db.chatDao().childSessionIds(owner).isEmpty()); assertTrue(dao.openIn(owner).isEmpty()); assertTrue(after.isEmpty())
    }
    @Test fun aCreationFailureLeavesNoHalfCreatedChild(): Unit = runBlocking {
        assertTrue(runCatching { open(create = { repo.createSession("fixture", parentSessionId = owner); error("after child insert") }) }.isFailure)
        assertTrue(db.chatDao().childSessionIds(owner).isEmpty()); assertTrue(dao.recentTasks(10).isEmpty())
    }
    @Test fun anAbsentParentIsNotRecreatedByTheHandoff(): Unit = runBlocking {
        db.chatDao().deleteSession(owner); assertTrue(runCatching { open() }.isFailure)
        assertNull(db.chatDao().getSession(owner)); assertTrue(db.chatDao().childSessionIds(owner).isEmpty()); assertTrue(dao.recentTasks(10).isEmpty())
    }
    @Test fun queuedHelpersKeepTheirPayloadWithoutStartingEarly(): Unit = runBlocking {
        val tasks = (1..4).map { open() }
        assertEquals(listOf("RUNNING", "RUNNING", "RUNNING", "QUEUED"), tasks.map { it.state }); assertEquals(3, after.size)
        for (task in tasks) assertEquals(task.id, dao.inboxFor(task.hand).single().taskId)
        assertTrue(dao.log(tasks.last().id).none { it.kind == "STARTED" })
    }
    @Test fun ordinaryHelpersKeepTheirActualHumanSource(): Unit = runBlocking {
        val input = repo.appendMessage(owner, "user", """[{"type":"text","value":"actual human words"}]""")
        dao.insertInbox(InboxEntity(input.id, owner, sender = "USER", text = "actual human words", arrivedAt = 1, readAt = 2))
        val task = open(routine = null); assertEquals(input.id, task.sourceMessageId)
        assertEquals("actual human words", dao.log(task.id).single { it.kind == "AUTHORIZED" }.body)
    }
    @Test fun aDeletedInvocationCannotCreateAHand(): Unit = runBlocking {
        var created = false
        assertTrue(runCatching { open(call = ToolCall("deleted", "call", "shell_execute"), create = { created = true; "never" }) }.isFailure)
        assertFalse(created); assertTrue(db.chatDao().childSessionIds(owner).isEmpty())
    }
}
