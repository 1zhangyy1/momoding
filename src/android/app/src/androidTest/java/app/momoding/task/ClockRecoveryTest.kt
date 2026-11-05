package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClockRecoveryTest {
    private lateinit var db: AppDatabase
    private lateinit var task: TaskEntity
    private val dao get() = db.taskDao()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        val session = ChatRepository(db.chatDao()).createSession("fixture", "clock recovery").id
        task = TaskEntity("work", session, title = "clock", hand = session, state = "RUNNING", createdAt = 10, updatedAt = 10)
        dao.insertTask(task)
        mark("STARTED", 10, 40)
    }
    @After fun close() = db.close()
    private suspend fun mark(kind: String, at: Long, elapsed: Long? = null, id: String = task.id) {
        dao.insertLog(TaskLogEntity(taskId = id, at = at, kind = kind,
            body = elapsed?.let { JSONObject().put("elapsed_ms", it).toString() } ?: "fixture"))
    }
    private suspend fun recover(alive: Long? = 100, now: Long = 1_000) = db.withTransaction { TaskEngine.recoverClocksIn(dao, alive, now) }
    private suspend fun spent(id: String = task.id, now: Long = 20_000) = TaskClock.elapsed(requireNotNull(dao.task(id)), dao.log(id), now)

    @Test fun aRestartFreezesKnownTimeAndKeepsTheOriginalTask(): Unit = runBlocking {
        recover(); assertEquals(130L, spent()); assertEquals(task, dao.task(task.id))
        assertEquals(listOf("STARTED", "PAUSED"), dao.log(task.id).map { it.kind })
        assertFalse(dao.runningClockFlow().first())
    }
    @Test fun repeatedRecoveryDoesNotAddTimeOrDuplicatePauseRecords(): Unit = runBlocking {
        recover(); val before = dao.log(task.id)
        recover(10_000, 100_000)
        assertEquals(before, dao.log(task.id)); assertEquals(130L, spent())
    }
    @Test fun anUnstartedQueueHasNoClockOrSpentTime(): Unit = runBlocking {
        val queued = task.copy(id = "queued", state = "QUEUED"); dao.insertTask(queued)
        recover(); assertTrue(dao.log(queued.id).isEmpty()); assertEquals(0L, spent(queued.id))
    }
    @Test fun aPausedQueueAndQuestionKeepTheirCarriedBudget(): Unit = runBlocking {
        mark("PAUSED", 30, 60); dao.updateTask(task.copy(state = "WAITING", ask = "wait for the person"))
        recover(); assertEquals(60L, spent()); assertEquals(2, dao.log(task.id).size)
        dao.updateTask(task.copy(state = "QUEUED")); recover(15_000, 20_000)
        assertEquals(60L, spent()); assertFalse(dao.runningClockFlow().first())
    }
    @Test fun endedWorkDoesNotAcquireAnotherClock(): Unit = runBlocking {
        mark("END", 50); dao.updateTask(task.copy(state = "ENDED", outcome = "DONE", updatedAt = 50))
        val before = dao.log(task.id); recover(); assertEquals(before, dao.log(task.id)); assertEquals(80L, spent())
        assertFalse(dao.runningClockFlow().first())
    }
    @Test fun allClocksFreezeAtomically(): Unit = runBlocking {
        assertTrue(runCatching { db.withTransaction { TaskEngine.recoverClocksIn(dao, 100, 1_000); error("before commit") } }.isFailure)
        assertEquals(listOf("STARTED"), dao.log(task.id).map { it.kind }); assertTrue(dao.runningClockFlow().first())
    }
    @Test fun runningClockTracksActivityRatherThanTheTasksLastReport(): Unit = runBlocking {
        assertTrue(dao.runningClockFlow().first())
        mark("PAUSED", 30, 60); mark("REPORT", 40)
        assertFalse(dao.runningClockFlow().first())
        dao.updateTask(task.copy(state = "WAITING", ask = "a separate unknown result"))
        mark("STARTED", 50, 60)
        assertTrue(dao.runningClockFlow().first()); assertEquals(70L, spent(now = 60))
        dao.updateTask(task.copy(state = "QUEUED")); assertFalse(dao.runningClockFlow().first())
    }
    @Test fun onePausedTaskDoesNotHideAnotherActiveHand(): Unit = runBlocking {
        mark("PAUSED", 30, 60)
        val other = task.copy(id = "other"); dao.insertTask(other); mark("STARTED", 40, 20, other.id)
        assertTrue(dao.runningClockFlow().first()); recover()
        assertEquals(60L, spent()); assertEquals(80L, spent(other.id)); assertFalse(dao.runningClockFlow().first())
    }
    @Test fun missingCheckpointKeepsTheLastSavedEvidenceAndNoNewOfflineTime(): Unit = runBlocking {
        mark("REPORT", 90); recover(null, 100_000)
        assertEquals(120L, spent()); assertEquals(task, dao.task(task.id))
    }
    @Test fun resumedWorkUsesTheRemainingBudgetRatherThanStartingOver(): Unit = runBlocking {
        recover(); mark("STARTED", 20_000, 130)
        assertEquals(150L, spent(now = 20_020)); assertTrue(dao.runningClockFlow().first())
        recover(20_030, 50_000); assertEquals(160L, spent(now = 50_010)); assertFalse(dao.runningClockFlow().first())
    }

    private suspend fun queuedScreen(): TaskEntity {
        val child = task.copy(id = "screen", hand = TaskEntity.SCREEN, state = "QUEUED")
        dao.insertTask(child)
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 20, kind = "CHILD", body = child.id))
        dao.insertLog(TaskLogEntity(taskId = child.id, at = 20, kind = "SCREEN", body = ScreenWork.Spec(
            app.momoding.hands.HandsOperator.Options(task = "fixture", sessionId = task.conversation), task.id).json()))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db.withTransaction { TaskEngine.replyIn(context, dao, mutableListOf(), task.hand, task.id,
            TaskCompletion(true, "waiting for the screen"), helper = false) {} }
        return child
    }

    @Test fun waitingForAQueuedScreenDoesNotSpendTheParentsBudget(): Unit = runBlocking {
        queuedScreen()
        assertEquals("RUNNING", dao.task(task.id)?.state)
        assertEquals("PAUSED", TaskClock.latest(dao.log(task.id))?.kind)
        assertFalse(dao.runningClockFlow().first())
    }

    @Test fun acquiringTheScreenResumesBothClocks(): Unit = runBlocking {
        val child = queuedScreen()
        db.withTransaction { assertEquals(child.id, TaskEngine.acquireScreen(dao) { true }?.id) }
        assertEquals("STARTED", TaskClock.latest(dao.log(task.id))?.kind)
        assertEquals("STARTED", TaskClock.latest(dao.log(child.id))?.kind)
        assertTrue(dao.runningClockFlow().first())
    }

    @Test fun nativeActionClockNeedsOnlyItsIdAndRecordedOutcome(): Unit = runBlocking {
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 20, kind = "ABOUT_TO", body = """{"id":"native"}"""))
        db.withTransaction { TaskEngine.setAskIn(dao, requireNotNull(dao.task(task.id)), "another question") }
        assertEquals("RUNNING", dao.task(task.id)?.state); assertTrue(dao.runningClockFlow().first())
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 30, kind = "RESULT", body = """{"id":"native","unknown":true}"""))
        db.withTransaction { TaskEngine.setAskIn(dao, requireNotNull(dao.task(task.id)), "another question") }
        assertEquals("WAITING", dao.task(task.id)?.state); assertEquals("another question", dao.task(task.id)?.ask)
        assertFalse(dao.runningClockFlow().first())
    }

    @Test fun aColdScreenContinuationKeepsTheParentPausedWhileQueued(): Unit = runBlocking {
        val child = queuedScreen(); val before = spent()
        dao.updateTask(task.copy(state = "WAITING", ask = JSONObject().put("screen", child.id).toString()))
        dao.insertLog(TaskLogEntity(taskId = child.id, at = 30, kind = "PASSED_ON", body = "old instructions"))
        val through = dao.log(child.id).last().id
        dao.insertLog(TaskLogEntity(taskId = child.id, at = 40, kind = "PASSED_ON", body = "continue after restart"))
        db.withTransaction { assertTrue(TaskEngine.requeueScreenFollowUp(dao, child.id, through)) }
        assertEquals("QUEUED", dao.task(child.id)?.state); assertNull(dao.task(task.id)?.ask)
        assertEquals("PAUSED", TaskClock.latest(dao.log(task.id))?.kind)
        assertEquals(before, spent())
    }
}
