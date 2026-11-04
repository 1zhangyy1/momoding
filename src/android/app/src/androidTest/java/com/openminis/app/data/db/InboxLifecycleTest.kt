package com.openminis.app.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.task.Inbox
import app.momoding.task.InboxEntity
import app.momoding.task.TaskEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.chat.QueuedPrompt
import com.openminis.app.ui.chat.QueuedPromptOrigin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite behavior, in a separate in-memory database. Never touches the person's DB. */
@RunWith(AndroidJUnit4::class)
class InboxLifecycleTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var sid: String
    private val parts = """[{"type":"text","value":"original words"}]"""

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
        sid = repo.createSession("fixture").id
    }

    @After fun close() { db.close() }

    private suspend fun receive(id: String) = db.taskDao().insertInbox(InboxEntity(
        id = id, toSession = sid, sender = "USER", text = "original words", partsJson = parts, arrivedAt = 1,
    ))

    private suspend fun consume(id: String) = db.withTransaction {
        repo.appendMessage(sid, "user", parts, id = id)
        db.taskDao().markRead(listOf(id), 10)
    }

    @Test fun aFailedConsumeRollsBackTranscriptAndReadState() = runBlocking {
        receive("one")
        val result = runCatching { db.withTransaction {
            consume("one")
            error("injected transaction failure")
        } }
        assertTrue(result.isFailure)
        assertNull(db.chatDao().message("one"))
        assertNull(db.taskDao().inbox("one")!!.readAt)
        assertEquals(listOf("one"), db.taskDao().unread(sid).map { it.id })
    }

    @Test fun eachMessageKeepsItsIdentityAndSameMillisecondOrder() = runBlocking {
        for (id in listOf("z-first", "a-second", "m-third")) receive(id)
        val ids = db.taskDao().unread(sid).map { it.id }
        assertEquals(listOf("z-first", "a-second", "m-third"), ids)
        db.withTransaction { for (id in ids) consume(id) }
        assertEquals(ids, db.chatDao().loadMessages(sid).map { it.id })
        assertEquals(3, repo.messageCount(sid))
        assertTrue(db.taskDao().unread(sid).isEmpty())
    }

    @Test fun thirdFailureStopsAutomaticRetriesWithoutDuplicatingTheMessage() = runBlocking {
        receive("retry-me")
        for (attempt in 1..3) {
            consume("retry-me")
            db.taskDao().failRead(sid, 100L + attempt, terminal = false)
            val row = db.taskDao().inbox("retry-me")!!
            assertEquals(attempt, row.tries)
            assertEquals(attempt == 3, row.failedAt != null)
            assertEquals(1, repo.messageCount(sid))
            assertEquals(if (attempt < 3) 1 else 0, db.taskDao().unread(sid).size)
        }
        db.taskDao().retryInbox("retry-me")
        assertEquals(0, db.taskDao().inbox("retry-me")!!.tries)
        assertNull(db.taskDao().inbox("retry-me")!!.failedAt)
        consume("retry-me")
        assertEquals(1, repo.messageCount(sid))
        db.taskDao().completeRead(sid)
        assertTrue(db.taskDao().inboxFor(sid).isEmpty())
    }

    @Test fun failureBeforeConsumptionIsBoundedAndKeepsTheOriginalReceive() = runBlocking {
        receive("setup-failure")
        for (attempt in 1..3) {
            db.taskDao().failInbox(listOf("setup-failure"), 100L + attempt, terminal = false)
            val row = db.taskDao().inbox("setup-failure")!!
            assertEquals(attempt, row.tries)
            assertEquals(attempt == 3, row.failedAt != null)
            assertNull(db.chatDao().message("setup-failure"))
        }
        // Late cleanup of the same failed attempt cannot count a fourth failure.
        db.taskDao().failInbox(listOf("setup-failure"), 104, terminal = false)
        assertEquals(3, db.taskDao().inbox("setup-failure")!!.tries)
        db.taskDao().retryInbox("setup-failure")
        consume("setup-failure")
        assertEquals("setup-failure", db.chatDao().loadMessages(sid).single().id)
    }

    @Test fun queuedWorkRetainsItsPrefillAndRoutineOriginInTheSameCommit() = runBlocking {
        val task = TaskEntity("routine-work", sid, title = "fixture", hand = sid, state = "QUEUED", createdAt = 1, updatedAt = 1)
        val fire = app.momoding.task.RoutineFire("routine", 1234, true)
        db.withTransaction {
            db.taskDao().insertTask(task)
            db.taskDao().insertLog(app.momoding.task.TaskLogEntity(taskId = task.id, at = 1, kind = "ROUTINE", body = fire.json()))
            Inbox.post(db.taskDao(), sid, "fixture", task.id,
                prefill = listOf(com.openminis.app.scheduled.PrefilledToolCall("shell_execute", "{\"command\":\"printf fixture\"}")))
        }
        val row = db.taskDao().unread(sid).single()
        assertEquals(task.id, row.taskId)
        assertEquals("printf fixture", org.json.JSONArray(row.prefill!!).getJSONObject(0).getJSONObject("args").getString("command"))
        assertEquals(fire, app.momoding.task.RoutineFire.parse(db.taskDao().log(task.id).single().body))
    }

    @Test fun activeWorkIsNotLostBehindTwoHundredNewerTasks() = runBlocking {
        val other = repo.createSession("fixture").id
        db.taskDao().insertTask(TaskEntity("older-open", sid, title = "older", hand = sid, state = "RUNNING", createdAt = 1, updatedAt = 1))
        for (n in 1..205) db.taskDao().insertTask(TaskEntity("newer-$n", other, title = "newer", hand = other,
            state = "ENDED", outcome = "DONE", createdAt = n + 1L, updatedAt = n + 1L))
        assertTrue(db.taskDao().recentTasks(200).none { it.id == "older-open" })
        assertEquals(listOf("older-open"), db.taskDao().openIn(sid).map { it.id })
        assertEquals(listOf("newer-205", "newer-204", "newer-203"), db.taskDao().endedIn(other).map { it.id })
    }

    @Test fun successCannotDiscardANewerUnreadMessage() = runBlocking {
        receive("read")
        consume("read")
        receive("not-read-yet")
        db.taskDao().completeRead(sid)
        assertEquals(listOf("not-read-yet"), db.taskDao().unread(sid).map { it.id })
        assertNotNull(db.chatDao().message("read"))
    }

    @Test fun taskStateAndItsPostedMessageCommitOrRollbackTogether() = runBlocking {
        val task = TaskEntity("task-one", sid, title = "fixture", hand = "hand", state = "RUNNING", createdAt = 1, updatedAt = 1)
        assertTrue(runCatching { db.withTransaction {
            db.taskDao().insertTask(task)
            Inbox.post(db.taskDao(), sid, "a result", task.id)
            error("crash before commit")
        } }.isFailure)
        assertNull(db.taskDao().task(task.id))
        assertTrue(db.taskDao().inboxFor(sid).isEmpty())
        db.withTransaction {
            db.taskDao().insertTask(task)
            Inbox.post(db.taskDao(), sid, "a result", task.id)
        }
        assertEquals(task.id, db.taskDao().unread(sid).single().taskId)
        assertEquals("a result", db.taskDao().unread(sid).single().text)
    }

    @Test fun aMessageIdCannotBeReusedByAnotherConversation() = runBlocking {
        repo.appendMessage(sid, "user", parts, id = "owned-id")
        val other = repo.createSession("fixture").id
        assertTrue(runCatching { repo.appendMessage(other, "user", parts, id = "owned-id") }.isFailure)
        assertEquals(sid, db.chatDao().message("owned-id")!!.sessionId)
    }

    @Test fun onlyAnUnreadFireOfTheSameRoutineIsSuperseded() = runBlocking {
        fun fire(id: String, routine: String) = QueuedPrompt(id, ScheduledTaskMarker(routine, "fixture", null, "run").xml,
            origin = QueuedPromptOrigin.PROGRAMMATIC, scheduledTaskId = routine)
        db.withTransaction { Inbox.putIn(db.taskDao(), sid, fire("old-a", "A")) }
        db.withTransaction { Inbox.putIn(db.taskDao(), sid, fire("old-b", "B")) }
        receive("human")
        db.withTransaction { Inbox.putIn(db.taskDao(), sid, fire("new-a", "A")) }
        assertNull(db.taskDao().inbox("old-a"))
        assertNotNull(db.taskDao().inbox("old-b"))
        assertNotNull(db.taskDao().inbox("human"))
        db.taskDao().markRead(listOf("new-a"), 5)
        db.withTransaction { Inbox.putIn(db.taskDao(), sid, fire("later-a", "A")) }
        assertNotNull(db.taskDao().inbox("new-a"))
        assertNotNull(db.taskDao().inbox("later-a"))
    }
}
