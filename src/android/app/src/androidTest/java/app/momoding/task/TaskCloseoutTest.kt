package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real task/log rows; no model, external command or screen gesture is executed. */
@RunWith(AndroidJUnit4::class)
class TaskCloseoutTest {
    private lateinit var db: AppDatabase
    private lateinit var task: TaskEntity
    private val dao get() = db.taskDao()

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        val chat = ChatRepository(db.chatDao()).createSession("closeout fixture").id
        task = TaskEntity("parent", chat, title = "mixed work", hand = chat, state = "RUNNING", createdAt = 1, updatedAt = 1)
        dao.insertTask(task)
        mark("STARTED", "started")
    }
    @After fun close() { db.close() }
    private suspend fun mark(kind: String, body: String) = dao.insertLog(TaskLogEntity(taskId = task.id, at = 1, kind = kind, body = body))
    private suspend fun command(success: Boolean, words: String) {
        mark("COMMAND_RESULT", JSONObject().put("id", words).put("success", success).put("output", words).toString())
    }
    private suspend fun child(id: String, outcome: String) {
        dao.insertTask(TaskEntity(id, task.hand, title = id, hand = "screen", state = "ENDED", outcome = outcome, createdAt = 1, updatedAt = 1))
        mark("CHILD", id)
    }
    private suspend fun result() = TaskEngine.closeoutIn(dao, task.id, TaskEntity.Outcome.DONE, "last step succeeded")
    private suspend fun complete() = db.withTransaction {
        TaskEngine.completeOwnIn(dao, task.id, TaskEntity.Outcome.DONE, "last step succeeded")
    }

    @Test fun continuingAfterHumanWaitingCannotHideAnEarlierFailedCommand() = runBlocking {
        command(false, "M41 earlier command failed")
        mark("PAUSED", """{"elapsed_ms":100}""")
        mark("STARTED", """{"elapsed_ms":100,"reason":"screen continued"}""")
        assertEquals(TaskEntity.Outcome.FAILED, result().outcome)
    }

    @Test fun theLastSuccessfulScreenCannotHideAnotherFailedChild() = runBlocking {
        child("first screen", "FAILED"); child("last screen", "DONE")
        assertEquals(TaskEntity.Outcome.FAILED, result().outcome)
    }

    @Test fun stoppingOneChildDoesNotBecomeSuccessWhenAnotherFinishes() = runBlocking {
        child("stopped screen", "STOPPED"); child("last screen", "DONE")
        assertEquals(TaskEntity.Outcome.STOPPED, result().outcome)
    }

    @Test fun aFailureFromAnEndedAttemptDoesNotPoisonAnExplicitNewAttempt() = runBlocking {
        command(false, "old command"); child("old screen", "FAILED")
        mark("END", "old attempt failed"); mark("STARTED", """{"elapsed_ms":0,"reason":"resumed"}""")
        command(true, "new command"); child("new screen", "DONE")
        assertEquals(TaskEntity.Outcome.DONE, result().outcome)
    }

    @Test fun theCloseoutKeepsTheEarlierFailureInsteadOfReportingOnlyTheLastSuccess() = runBlocking {
        command(false, "M41 failure cause must survive")
        val result = result()
        assertEquals(TaskEntity.Outcome.FAILED, result.outcome)
        assertTrue(result.words, result.words.contains("M41 failure cause must survive"))
    }

    @Test fun theLastSuccessfulCommandWritesOneFailedEndWithTheEarlierCause() = runBlocking {
        command(false, "M41 previous failure"); command(true, "M41 final success")
        val closed = complete()!!
        assertEquals(TaskEntity.Outcome.FAILED, closed.outcome)
        assertTrue(closed.words.contains("M41 previous failure"))
        assertEquals("FAILED", dao.task(task.id)!!.outcome)
        assertNull(complete())
        assertEquals(1, dao.log(task.id).count { it.kind == "END" })
        assertEquals(closed.words, dao.log(task.id).last().body)
    }

    @Test fun commandsAndChildrenKeepTheirParentOpenUntilEveryResultArrives() = runBlocking {
        val command = ShellWork.Command("pending", "fixture-process", "printf fixture")
        mark("COMMAND", command.json())
        assertNull(complete())
        command(true, "pending")
        val screen = task.copy(id = "screen", hand = "screen", outcome = null)
        dao.insertTask(screen); mark("CHILD", screen.id)
        assertNull(complete())
        dao.updateTask(screen.copy(state = "ENDED", outcome = "DONE"))
        assertEquals(TaskEntity.Outcome.DONE, complete()!!.outcome)
        assertEquals(1, dao.log(task.id).count { it.kind == "END" })
    }

    @Test fun anUnansweredQuestionKeepsOwnWorkOpenEvenAfterTheLastCommand() = runBlocking {
        command(true, "last command")
        dao.updateTask(task.copy(ask = """{"question":"which date?"}"""))
        assertNull(complete())
        assertTrue(dao.log(task.id).none { it.kind == "END" })
        dao.updateTask(task.copy(ask = null))
        assertEquals(TaskEntity.Outcome.DONE, complete()!!.outcome)
    }

    @Test fun aDelegatedHandStillOwesItsOwnFormalReport() = runBlocking {
        val owner = ChatRepository(db.chatDao()).createSession("owner fixture").id
        dao.updateTask(task.copy(conversation = owner))
        assertNull(complete())
        assertEquals("RUNNING", dao.task(task.id)!!.state)
        assertTrue(dao.log(task.id).none { it.kind == "END" })
    }

    @Test fun theEndAndItsStateRollBackTogether() = runBlocking {
        try {
            db.withTransaction {
                TaskEngine.completeOwnIn(dao, task.id, TaskEntity.Outcome.DONE, "finished")
                error("fault before commit")
            }
        } catch (expected: IllegalStateException) { assertEquals("fault before commit", expected.message) }
        assertEquals("RUNNING", dao.task(task.id)!!.state)
        assertTrue(dao.log(task.id).none { it.kind == "END" })
        assertNotNull(complete())
    }

    @Test fun concurrentResultsCannotAppendTwoEnds() = runBlocking {
        val closed = coroutineScope { listOf(async { complete() }, async { complete() }).map { it.await() } }
        assertEquals(1, closed.count { it != null })
        assertEquals(1, dao.log(task.id).count { it.kind == "END" })
    }
}
