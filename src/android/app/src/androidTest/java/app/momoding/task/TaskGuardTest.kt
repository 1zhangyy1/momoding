package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite identity and parent lookup, without any system permissions or external action. */
@RunWith(AndroidJUnit4::class)
class TaskGuardTest {
    private lateinit var db: AppDatabase
    private lateinit var chat: String
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        chat = ChatRepository(db.chatDao()).createSession("task guard fixture").id
    }
    @After fun close() { db.close() }

    private suspend fun task(id: String, hand: String = chat, state: String = "RUNNING", hold: String? = null) =
        TaskEntity(id, chat, title = id, hand = hand, state = state, hold = hold, createdAt = 1, updatedAt = 1)
            .also { db.taskDao().insertTask(it) }
    private suspend fun check(binding: TaskGuard.Binding, outward: Boolean = false) =
        db.withTransaction { TaskGuard.checkIn(db.taskDao(), binding, outward) }

    @Test fun aPendingQuestionStillBlocksOutwardActionsWhileOtherWorkContinues() = runBlocking {
        val row = task("question")
        db.taskDao().updateTask(row.copy(ask = """{"question":"which date?"}"""))
        val binding = TaskGuard.Binding(chat, row.id)
        assertNull(check(binding))
        assertTrue(check(binding, outward = true)!!.contains("waiting"))
    }

    @Test fun anIndependentMessageDoesNotBorrowTheMainChatsDetachedTask() = runBlocking {
        task("background", hold = "wait for me")
        val independent = db.withTransaction { TaskGuard.bindIn(db.taskDao(), chat) }
        assertNull(independent.taskId)
        assertNull(check(independent, outward = true))
        val original = TaskGuard.Binding(chat, "background")
        assertTrue(check(original, outward = true)!!.contains("on hold"))
    }

    @Test fun aBoundRunDoesNotBecomeTheNextTaskAfterItIsStopped() = runBlocking {
        task("old", state = "ENDED")
        task("next")
        val old = TaskGuard.Binding(chat, "old")
        assertTrue(check(old)!!.contains("ended"))
        assertNull(check(TaskGuard.Binding(chat, "next")))
    }

    @Test fun aBackgroundProcessKeepsItsOriginalTaskAfterTheModelMovesOn() = runBlocking {
        task("old", state = "ENDED")
        task("next")
        val command = ShellWork.Command("command", "$chat~command~old", "fixture")
        db.taskDao().insertLog(TaskLogEntity(taskId = "old", at = 1, kind = "COMMAND", body = command.json()))
        val bound = db.withTransaction { TaskGuard.bindIn(db.taskDao(), chat, process = command.process, modelTaskId = "next") }
        assertEquals("old", bound.taskId)
        assertTrue(check(bound)!!.contains("ended"))
    }

    @Test fun anApprovalCannotReviveTheWorkThatWasStoppedWhileItWaited() = runBlocking {
        val running = task("old")
        val bound = TaskGuard.Binding(chat, running.id)
        assertNull(check(bound, outward = true))
        db.taskDao().updateTask(running.copy(state = "ENDED", outcome = "STOPPED"))
        assertTrue(check(bound, outward = true)!!.contains("stopped"))
    }

    @Test fun heldOrWaitingWorkCanReadButCannotActOutward() = runBlocking {
        task("held", hold = "only cancel if refundable")
        assertNull(check(TaskGuard.Binding(chat, "held")))
        assertTrue(check(TaskGuard.Binding(chat, "held"), outward = true)!!.contains("only cancel if refundable"))
        task("waiting", state = "WAITING")
        assertNull(check(TaskGuard.Binding(chat, "waiting")))
        assertTrue(check(TaskGuard.Binding(chat, "waiting"), outward = true)!!.contains("waiting"))
    }

    @Test fun aScreenTaskInheritsItsParentsHoldAndStop() = runBlocking {
        val parent = task("parent", hold = "ask me first")
        task("screen", hand = TaskEntity.SCREEN)
        db.taskDao().insertLog(TaskLogEntity(taskId = "screen", at = 1, kind = "SCREEN", body = "{\"parent\":\"parent\"}"))
        val screen = TaskGuard.Binding(TaskEntity.SCREEN, "screen")
        assertNull(check(screen))
        assertTrue(check(screen, outward = true)!!.contains("ask me first"))
        db.taskDao().updateTask(parent.copy(state = "ENDED", outcome = "STOPPED", hold = null))
        assertTrue(check(screen)!!.contains("stopped"))
    }

    @Test fun missingQueuedOrMisboundWorkDoesNotUseTools() = runBlocking {
        assertTrue(check(TaskGuard.Binding(chat, "deleted"))!!.contains("deleted"))
        task("queued", state = "QUEUED")
        assertTrue(check(TaskGuard.Binding(chat, "queued"))!!.contains("not started"))
        task("other", hand = TaskEntity.SCREEN)
        assertTrue(check(TaskGuard.Binding(chat, "other"))!!.contains("another hand"))
    }
}
