package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.guard.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionJournalTest {
    private lateinit var db: AppDatabase
    private lateinit var chat: String
    private val dao get() = db.taskDao()
    private val args = """{"command":"fixture-send exact-recipient exact-text","timeout":12}"""
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        chat = ChatRepository(db.chatDao()).createSession("action journal fixture").id
    }
    @After fun close() { db.close() }
    private fun spec(task: String? = null, id: String = java.util.UUID.randomUUID().toString(), screen: Boolean = false) =
        Approval.Spec(RiskRequest(id = id, sessionId = chat, taskId = task,
            kind = if (screen) GuardKind.SCREEN else GuardKind.SHELL,
            assessment = RiskAssessment(RiskClass.OUTBOUND, "fixture only", "fixture:recipient"), preview = "Send exact fixture text"),
            if (screen) Approval.Mode.SCREEN else Approval.Mode.SHELL, if (screen) null else args)
    private suspend fun start(s: Approval.Spec = spec(), source: String = "card", draft: String? = null) = db.withTransaction {
        TaskEngine.actionStartedIn(dao, s, sourceId = source, authorized = "exact human authorization", draft = draft)
    }
    private suspend fun mark(task: String, kind: String, body: String) = dao.insertLog(TaskLogEntity(taskId = task, at = 1, kind = kind, body = body))

    @Test fun intentAndNativeProcessBindingAreCommittedBeforeAnyExecution() = runBlocking {
        val action = start()
        val task = dao.task(action.taskId)!!
        val history = dao.log(task.id)
        assertEquals("card", task.sourceMessageId)
        assertEquals("exact human authorization", history.first { it.kind == "AUTHORIZED" }.body)
        assertEquals(args, JSONObject(history.single { it.kind == "ABOUT_TO" }.body).getJSONObject("shell").toString())
        assertEquals(task.id, TaskGuard.bindIn(dao, chat, process = action.command!!.process).taskId)
        assertEquals(1, TaskAction.pending(history).size)
        assertTrue(TaskEngine.pendingWork(dao, task.id))
    }

    @Test fun concurrentExecutorsCannotStartTheSameActionTwice() = runBlocking {
        val s = spec()
        val results = coroutineScope { listOf(async { runCatching { start(s) } }, async { runCatching { start(s) } }).map { it.await() } }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, dao.recentTasks(100).size)
        assertEquals(1, dao.log(s.request.id).count { it.kind == "ABOUT_TO" })
    }

    @Test fun aRolledBackIntentNeverLeavesAnExecutableTaskOrReceiptHandle() = runBlocking {
        val s = spec()
        try { db.withTransaction { TaskEngine.actionStartedIn(dao, s); error("fault before commit") } }
        catch (expected: IllegalStateException) { assertEquals("fault before commit", expected.message) }
        assertNull(dao.task(s.request.id))
    }

    @Test fun duplicateResultsDoNotAppendASecondReceipt() = runBlocking {
        val action = start()
        assertTrue(db.withTransaction { TaskEngine.actionResultIn(dao, action.taskId, action.id, "receipt", true) })
        assertFalse(db.withTransaction { TaskEngine.actionResultIn(dao, action.taskId, action.id, "duplicate", true) })
        assertEquals(1, dao.log(action.taskId).count { it.kind == "RESULT" })
        assertTrue(TaskAction.pending(dao.log(action.taskId)).isEmpty())
    }

    @Test fun aMissingResultBecomesAnUnknownAskAndIsNotReplayedOnASecondRecovery() = runBlocking {
        val action = start()
        assertNotNull(db.withTransaction { TaskEngine.recoverActionsIn(dao, dao.task(action.taskId)!!) })
        val task = dao.task(action.taskId)!!
        assertEquals("WAITING", task.state)
        assertTrue(task.ask!!.contains("outcome_unknown"))
        assertTrue(ShellWork.pending(dao.log(task.id)).isEmpty())
        assertTrue(JSONObject(dao.log(task.id).single { it.kind == "RESULT" }.body).getBoolean("unknown"))
        assertNull(db.withTransaction { TaskEngine.recoverActionsIn(dao, dao.task(task.id)!!) })
        assertTrue(dao.log(task.id).none { it.kind == "END" })
    }

    @Test fun anotherOutwardActionCannotBypassAnUnknownAsk() = runBlocking {
        val action = start()
        db.withTransaction { TaskEngine.recoverActionsIn(dao, dao.task(action.taskId)!!) }
        assertTrue(runCatching { start(spec(action.taskId)) }.isFailure)
        assertEquals(1, dao.log(action.taskId).count { it.kind == "ABOUT_TO" })
    }

    @Test fun aKnownReceiptDoesNotBecomeUnknownDuringJournalRecovery() = runBlocking {
        val action = start()
        db.withTransaction { TaskEngine.actionResultIn(dao, action.taskId, action.id, "observed receipt", true) }
        assertNull(db.withTransaction { TaskEngine.recoverActionsIn(dao, dao.task(action.taskId)!!) })
        assertNull(dao.task(action.taskId)!!.ask)
    }

    @Test fun stoppedHeldAndDeletedTasksCannotStartAnAction() = runBlocking {
        val s = spec(); val action = start(s)
        val task = dao.task(action.taskId)!!
        dao.updateTask(task.copy(hold = "wait for me"))
        assertTrue(runCatching { start(spec(task.id)) }.isFailure)
        dao.updateTask(task.copy(state = "ENDED", outcome = "STOPPED"))
        assertTrue(runCatching { start(spec(task.id)) }.isFailure)
        assertTrue(runCatching { start(spec("deleted")) }.isFailure)
        assertEquals(1, dao.log(task.id).count { it.kind == "ABOUT_TO" })
    }

    @Test fun aMissingScreenResultWaitsInBothTheScreenAndItsParent() = runBlocking {
        val parent = TaskEntity("parent", chat, title = "parent", hand = chat, state = "RUNNING", createdAt = 1, updatedAt = 1)
        val screen = parent.copy(id = "screen", hand = "screen")
        dao.insertTask(parent); dao.insertTask(screen)
        mark(parent.id, "CHILD", screen.id)
        mark(screen.id, "SCREEN", ScreenWork.Spec(app.momoding.hands.HandsOperator.Options("fixture", sessionId = chat), parent.id).json())
        start(spec(screen.id, screen = true))
        db.withTransaction { TaskEngine.recoverActionsIn(dao, dao.task(screen.id)!!) }
        assertEquals("WAITING", dao.task(screen.id)!!.state)
        assertEquals("WAITING", dao.task(parent.id)!!.state)
        assertEquals(screen.id, JSONObject(dao.task(parent.id)!!.ask!!).getString("screen"))
    }

    @Test fun aDuplicateDraftCardCannotSubmitAgainEvenWithANewActionId() = runBlocking {
        val draft = """{"id":"draft","to":"recipient","text":"exact edited text"}"""
        val action = start(draft = draft)
        assertTrue(runCatching { start(draft = draft) }.isFailure)
        assertEquals(action.taskId, dao.draftTask(chat, "card", "\"id\":\"draft\"")!!.id)
        assertEquals(1, dao.recentTasks(100).size)
    }

    @Test fun theSameDraftInANewSourceMessageHasItsOwnTask() = runBlocking {
        val draft = """{"id":"draft","text":"same text"}"""
        val one = start(source = "one", draft = draft); val two = start(source = "two", draft = draft)
        assertNotEquals(one.taskId, two.taskId)
        assertEquals(2, dao.recentTasks(100).size)
    }

    @Test fun anUnpromptedHelperScreenActionStillBelongsToItsOriginalOwningConversation() = runBlocking {
        val helper = ChatRepository(db.chatDao()).createSession("helper fixture").id
        val parent = TaskEntity("parent", chat, title = "parent", hand = helper, state = "RUNNING", createdAt = 1, updatedAt = 1)
        val screen = parent.copy(id = "screen", conversation = helper, hand = "screen")
        dao.insertTask(parent); dao.insertTask(screen)
        mark(parent.id, "CHILD", screen.id)
        mark(screen.id, "SCREEN", ScreenWork.Spec(app.momoding.hands.HandsOperator.Options("fixture", sessionId = helper), parent.id).json())
        val s = spec(screen.id, screen = true).let { it.copy(request = it.request.copy(sessionId = helper)) }
        start(s)
        val recorded = JSONObject(dao.log(screen.id).single { it.kind == "ABOUT_TO" }.body)
        assertEquals(chat, recorded.getString("conversation"))
        assertEquals(helper, recorded.getString("session"))
    }
}
