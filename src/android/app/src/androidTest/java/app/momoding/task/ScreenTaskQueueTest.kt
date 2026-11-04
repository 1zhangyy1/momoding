package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real acquisition code and SQLite transactions, in a separate database. */
@RunWith(AndroidJUnit4::class)
class ScreenTaskQueueTest {
    private lateinit var db: AppDatabase
    private lateinit var chat: String
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        chat = ChatRepository(db.chatDao()).createSession("fixture").id
    }
    @After fun close() { db.close() }

    private suspend fun queued(id: String) = db.taskDao().insertTask(TaskEntity(id, chat, title = id,
        hand = TaskEntity.SCREEN, state = TaskEntity.State.QUEUED.name, createdAt = 1, updatedAt = 1))
    private suspend fun take(claim: () -> Boolean = { true }) = db.withTransaction { TaskEngine.acquireScreen(db.taskDao(), claim) }

    private suspend fun takeover(): Long {
        val dao = db.taskDao()
        val started = System.currentTimeMillis() - 100_000
        dao.insertTask(TaskEntity("parent", chat, title = "parent", hand = chat, state = "RUNNING", createdAt = started, updatedAt = started))
        dao.insertLog(TaskLogEntity(taskId = "parent", at = started, kind = "STARTED", body = "started"))
        queued("screen"); take()
        val spec = ScreenWork.Spec(app.momoding.hands.HandsOperator.Options("look", sessionId = chat), "parent").json()
        dao.insertLog(TaskLogEntity(taskId = "screen", at = started, kind = "SCREEN", body = spec))
        dao.insertLog(TaskLogEntity(taskId = "parent", at = started, kind = "CHILD", body = "screen"))
        val cursor = dao.insertLog(TaskLogEntity(taskId = "screen", at = started, kind = "PASSED_ON", body = "look"))
        db.withTransaction { TaskEngine.screenWaitingIn(dao, "screen", "scan the fixture") }
        return cursor
    }

    @Test fun takeoverWaitingDoesNotSpendTheParentsRemainingBudget() = runBlocking {
        takeover()
        val afterFiveHundredSecondsOfWaiting = TaskEngine.elapsedIn(db.taskDao(), "parent", System.currentTimeMillis() + 500_000)
        assertTrue("human waiting spent the budget: $afterFiveHundredSecondsOfWaiting", afterFiveHundredSecondsOfWaiting in 100_000L..105_000L)
    }

    @Test fun takeoverContinuationDoesNotResetTheParentsSpentBudget() = runBlocking {
        val cursor = takeover()
        db.taskDao().insertLog(TaskLogEntity(taskId = "screen", at = System.currentTimeMillis(), kind = "PASSED_ON", body = "continue"))
        assertTrue(db.withTransaction { TaskEngine.requeueScreenFollowUp(db.taskDao(), "screen", cursor) })
        assertEquals("RUNNING", db.taskDao().task("parent")!!.state)
        val spent = TaskEngine.elapsedIn(db.taskDao(), "parent")
        assertTrue("continuation reset the budget: $spent", spent in 100_000L..105_000L)
    }

    @Test fun aContinuedScreenKeepsItsSpentTimeWhileItWaitsToBeAcquiredAgain() = runBlocking {
        val cursor = takeover()
        val dao = db.taskDao()
        val spent = TaskEngine.elapsedIn(dao, "screen")
        dao.insertLog(TaskLogEntity(taskId = "screen", at = System.currentTimeMillis(), kind = "PASSED_ON", body = "continue"))
        assertTrue(db.withTransaction { TaskEngine.requeueScreenFollowUp(dao, "screen", cursor) })
        assertEquals(spent, TaskEngine.elapsedIn(dao, "screen", System.currentTimeMillis() + 500_000))
        take()
        assertTrue(TaskEngine.elapsedIn(dao, "screen") in spent..spent + 5_000)
    }

    @Test fun aQuestionDoesNotPauseWorkThatIsStillRunning() = runBlocking {
        val dao = db.taskDao()
        val started = System.currentTimeMillis() - 100_000
        val task = TaskEntity("work", chat, title = "work", hand = chat, state = "RUNNING", createdAt = started, updatedAt = started)
        dao.insertTask(task)
        dao.insertLog(TaskLogEntity(taskId = "work", at = started, kind = "STARTED", body = "started"))
        val command = ShellWork.Command("one", "fixture-process", "printf fixture")
        dao.insertLog(TaskLogEntity(taskId = "work", at = started, kind = "COMMAND", body = command.json()))
        val ask = """{"question":"which date?"}"""
        db.withTransaction { TaskEngine.setAskIn(dao, task, ask) }
        assertEquals("RUNNING", dao.task("work")!!.state)
        assertTrue(TaskEngine.elapsedIn(dao, "work", System.currentTimeMillis() + 500_000) >= 600_000)
        dao.insertLog(TaskLogEntity(taskId = "work", at = System.currentTimeMillis(), kind = "COMMAND_RESULT", body = """{"id":"one","success":true}"""))
        assertTrue(TaskEngine.pendingWork(dao, "work"))
        db.withTransaction { TaskEngine.setAskIn(dao, dao.task("work")!!, ask) }
        val paused = TaskEngine.elapsedIn(dao, "work")
        assertEquals("WAITING", dao.task("work")!!.state)
        assertEquals(paused, TaskEngine.elapsedIn(dao, "work", System.currentTimeMillis() + 500_000))
    }

    @Test fun aBusyPhysicalScreenLeavesEveryTaskQueuedThenStartsInArrivalOrder() = runBlocking {
        queued("z-first"); queued("a-second")
        assertNull(take { false })
        assertEquals("QUEUED", db.taskDao().task("z-first")!!.state)
        val first = take()!!
        assertEquals("z-first", first.id)
        assertNull(take { error("an occupied logical hand must not claim the physical screen") })
        db.taskDao().updateTask(first.copy(state = "ENDED", outcome = "DONE"))
        assertEquals("a-second", take()!!.id)
    }

    @Test fun waitingForThePersonKeepsTheScreenReserved() = runBlocking {
        queued("first"); queued("second")
        val first = take()!!
        db.taskDao().updateTask(first.copy(state = "WAITING", ask = "{\"question\":\"scan\"}"))
        assertNull(take { error("a waiting screen task keeps the lease") })
        assertEquals("QUEUED", db.taskDao().task("second")!!.state)
    }

    @Test fun twoConcurrentAcquirersCannotStartTwoTasks() = runBlocking {
        queued("one"); queued("two")
        val acquired = coroutineScope { listOf(async { take() }, async { take() }).map { it.await() } }
        assertEquals(1, acquired.count { it != null })
        assertEquals("one", acquired.filterNotNull().single().id)
        assertEquals("QUEUED", db.taskDao().task("two")!!.state)
    }

    @Test fun aDelegatingTaskCannotCompleteWhileItsScreenTaskIsRunningOrWaiting() = runBlocking {
        db.taskDao().insertTask(TaskEntity("parent", chat, title = "parent", hand = chat, state = "RUNNING", createdAt = 0, updatedAt = 0))
        queued("child")
        db.taskDao().insertLog(TaskLogEntity(taskId = "parent", at = 1, kind = "CHILD", body = "child"))
        assertTrue(TaskEngine.pendingWork(db.taskDao(), "parent"))
        val child = take()!!
        db.taskDao().updateTask(child.copy(state = "WAITING"))
        assertTrue(TaskEngine.pendingWork(db.taskDao(), "parent"))
        db.taskDao().updateTask(child.copy(state = "ENDED", outcome = "DONE"))
        assertFalse(TaskEngine.pendingWork(db.taskDao(), "parent"))
    }

    @Test fun aStoppedProcessStillResolvesToItsOriginalTaskInsteadOfNewWork() = runBlocking {
        db.taskDao().insertTask(TaskEntity("stopped", chat, title = "old command", hand = chat,
            state = "ENDED", outcome = "STOPPED", createdAt = 0, updatedAt = 1))
        val command = ShellWork.Command("command", "$chat~command~old", "printf fixture")
        db.taskDao().insertLog(TaskLogEntity(taskId = "stopped", at = 1, kind = "COMMAND", body = command.json()))
        val found = db.taskDao().byExecution(chat, "\"process\":" + org.json.JSONObject.quote(command.process))
        assertEquals("stopped", found!!.id)
        assertEquals("STOPPED", found.outcome)
        assertNull(db.taskDao().byExecution(chat, "\"process\":" + org.json.JSONObject.quote(command.process + "-different")))
    }

    @Test fun aDiagnosticCannotBypassQueuedOrWaitingScreenWork() = runBlocking {
        queued("first")
        assertFalse(db.withTransaction { TaskEngine.acquireDiagnosticScreen(db.taskDao()) { error("queued work goes first") } })
        val first = take()!!
        db.taskDao().updateTask(first.copy(state = "WAITING"))
        assertFalse(db.withTransaction { TaskEngine.acquireDiagnosticScreen(db.taskDao()) { error("the person still owns the screen") } })
        db.taskDao().updateTask(first.copy(state = "ENDED", outcome = "DONE"))
        assertTrue(db.withTransaction { TaskEngine.acquireDiagnosticScreen(db.taskDao()) { true } })
    }

    @Test fun wordsArrivingAsARunFinishesStayOnTheSameScreenTask() = runBlocking {
        queued("first"); queued("second")
        take()
        db.taskDao().insertLog(TaskLogEntity(taskId = "first", at = 1, kind = "PASSED_ON", body = "look"))
        val read = db.taskDao().log("first").last().id
        db.taskDao().insertLog(TaskLogEntity(taskId = "first", at = 2, kind = "PASSED_ON", body = "also check tomorrow"))
        assertTrue(db.withTransaction { TaskEngine.requeueScreenFollowUp(db.taskDao(), "first", read) })
        assertEquals("QUEUED", db.taskDao().task("first")!!.state)
        assertEquals("first", take()!!.id)
        assertTrue(db.taskDao().log("first").none { it.kind == "END" })
        assertEquals("also check tomorrow", db.taskDao().log("first").last { it.kind == "PASSED_ON" }.body)
    }

    @Test fun wordsAlreadyReadDoNotStartAnotherScreenRun() = runBlocking {
        queued("first")
        take()
        db.taskDao().insertLog(TaskLogEntity(taskId = "first", at = 1, kind = "PASSED_ON", body = "look"))
        val read = db.taskDao().log("first").last().id
        assertFalse(db.withTransaction { TaskEngine.requeueScreenFollowUp(db.taskDao(), "first", read) })
        assertEquals("RUNNING", db.taskDao().task("first")!!.state)
        assertFalse(db.withTransaction { TaskEngine.requeueScreenFollowUp(db.taskDao(), "first", null) })
    }

    @Test fun aLateResultNeverRestartsATaskThePersonStopped() = runBlocking {
        queued("first")
        val task = take()!!
        db.taskDao().insertLog(TaskLogEntity(taskId = "first", at = 2, kind = "PASSED_ON", body = "also check tomorrow"))
        db.taskDao().updateTask(task.copy(state = "ENDED", outcome = "STOPPED"))
        assertFalse(db.withTransaction { TaskEngine.requeueScreenFollowUp(db.taskDao(), "first", 0) })
        assertEquals("STOPPED", db.taskDao().task("first")!!.outcome)
        assertNull(take())
    }
}
