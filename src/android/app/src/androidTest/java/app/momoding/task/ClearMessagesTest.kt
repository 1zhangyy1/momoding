package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Drives the persisted task/message boundary, not the ViewModel's implementation. */
class ClearMessagesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val after = mutableListOf<suspend () -> Unit>()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao()); owner = repo.createSession("fixture", "M41 clear fixture").id
    }
    @After fun close() { db.close() }
    private suspend fun message(sid: String = owner) = repo.appendMessage(sid, "user", """[{"type":"text","value":"original"}]""")
    private suspend fun task(id: String, conversation: String = owner, hand: String = "fixture-hand", source: String? = null) =
        TaskEntity(id, conversation, source, id, hand, "RUNNING", createdAt = 1, updatedAt = 1).also { dao.insertTask(it) }
    private suspend fun clear(sid: String = owner) = db.withTransaction {
        TaskEngine.clearMessagesIn(context, db.chatDao(), dao, after, sid)
    }
    @Test fun historyAndItsTasksStopTogetherWhileOtherChatsRemain(): Unit = runBlocking {
        val original = message(); val other = repo.createSession("fixture", "other").id; val foreign = message(other)
        task("parent", source = original.id); task("child", hand = TaskEntity.SCREEN)
        dao.insertLog(TaskLogEntity(taskId = "parent", at = 1, kind = "CHILD", body = "child"))
        task("unrelated", conversation = other)
        val removed = clear()
        assertEquals(setOf(original.id), removed.ids); assertEquals(2, removed.stopped)
        assertTrue(db.chatDao().loadMessages(owner).isEmpty()); assertNotNull(db.chatDao().message(foreign.id))
        assertEquals("STOPPED", dao.task("parent")!!.outcome); assertEquals("STOPPED", dao.task("child")!!.outcome)
        assertEquals("RUNNING", dao.task("unrelated")!!.state)
        assertEquals(1, dao.inboxFor(owner).size); assertTrue(dao.inboxFor(owner).single().text.contains("Stop snapshot:"))
    }
    @Test fun failureRollsBackTheWholeClearIncludingStopNotices(): Unit = runBlocking {
        val original = message(); task("work", source = original.id)
        dao.insertInbox(InboxEntity("waiting", owner, sender = "USER", text = "pending", arrivedAt = 1))
        assertTrue(runCatching { db.withTransaction { TaskEngine.clearMessagesIn(context, db.chatDao(), dao, after, owner); error("rollback") } }.isFailure)
        assertNotNull(db.chatDao().message(original.id)); assertEquals("RUNNING", dao.task("work")!!.state)
        assertEquals(listOf("waiting"), dao.inboxFor(owner).map { it.id }); assertTrue(dao.log("work").isEmpty())
    }
    @Test fun existingFailedAndReadInputsDisappearButNewStopReceiptsRemain(): Unit = runBlocking {
        task("work")
        dao.insertInbox(InboxEntity("failed", owner, sender = "USER", text = "failed", arrivedAt = 1, failedAt = 2, tries = 3))
        dao.insertInbox(InboxEntity("read", owner, sender = "USER", text = "read", arrivedAt = 1, readAt = 2))
        assertEquals(setOf("failed", "read"), clear().ids)
        assertTrue(dao.inbox("failed") == null && dao.inbox("read") == null)
        assertEquals(1, dao.inboxFor(owner).size); assertEquals("work", dao.inboxFor(owner).single().taskId)
    }
    @Test fun clearKeepsTheSessionAndRemovesItsPreviewAndCompactMarkers(): Unit = runBlocking {
        val original = message(); db.chatDao().updateLastMessage(owner, "old preview", 1)
        db.chatDao().insertCompactMarker(CompactMarkerEntity("compact", owner, "old summary", 0, 1, 1))
        clear(); val session = requireNotNull(db.chatDao().getSession(owner))
        assertNull(session.lastMessage); assertTrue(session.updatedAt > 1); assertTrue(db.chatDao().listCompactMarkers(owner).isEmpty())
        assertNull(db.chatDao().message(original.id))
    }
    @Test fun legacyWorkWithoutAMessageSourceIsStoppedToo(): Unit = runBlocking {
        task("legacy", source = null); assertEquals(1, clear().stopped); assertEquals("STOPPED", dao.task("legacy")!!.outcome)
    }
    @Test fun clearingAHandStopsOnlyTheWorkItHoldsAndReportsToItsOwner(): Unit = runBlocking {
        val hand = repo.createSession("fixture", "hand").id; message(hand)
        task("held", hand = hand); task("other", hand = "other-hand")
        clear(hand)
        assertEquals("STOPPED", dao.task("held")!!.outcome); assertEquals("RUNNING", dao.task("other")!!.state)
        assertEquals("held", dao.inboxFor(owner).single().taskId); assertTrue(dao.inboxFor(hand).isEmpty())
    }
    @Test fun completedOutcomesArePreservedAndRepeatedClearDoesNotReportTwice(): Unit = runBlocking {
        task("finished"); dao.updateTask(dao.task("finished")!!.copy(state = "ENDED", outcome = "DONE"))
        task("work"); clear(); assertEquals(1, dao.inboxFor(owner).size)
        assertEquals(0, clear().stopped); assertTrue(dao.inboxFor(owner).isEmpty())
        assertEquals("DONE", dao.task("finished")!!.outcome)
        assertEquals(1, dao.log("work").count { it.kind == "END" })
    }
    @Test fun missingSessionDoesNotCreateMessagesOrStopUnrelatedWork(): Unit = runBlocking {
        task("work"); assertEquals(TaskEngine.DeletedMessages(emptySet(), 0), clear("missing")); assertTrue(after.isEmpty())
        assertEquals("RUNNING", dao.task("work")!!.state)
    }
    @Test fun failedReceiveCanOnlyBeClaimedOnce(): Unit = runBlocking {
        dao.insertInbox(InboxEntity("retry", owner, sender = "USER", text = "original", arrivedAt = 1, failedAt = 2, tries = 3))
        assertEquals(1, dao.retryInbox("retry")); assertEquals(0, dao.retryInbox("retry"))
        val row = requireNotNull(dao.inbox("retry")); assertNull(row.failedAt); assertEquals(0, row.tries); assertEquals("original", row.text)
    }
    @Test fun liveMissingAndCompletedReceivesCannotBeRequeued(): Unit = runBlocking {
        dao.insertInbox(InboxEntity("live", owner, sender = "USER", text = "live", arrivedAt = 1, readAt = 2))
        assertEquals(0, dao.retryInbox("live")); assertEquals(2L, dao.inbox("live")!!.readAt)
        assertEquals(0, dao.retryInbox("missing")); dao.completeRead(owner); assertEquals(0, dao.retryInbox("live"))
    }
}
