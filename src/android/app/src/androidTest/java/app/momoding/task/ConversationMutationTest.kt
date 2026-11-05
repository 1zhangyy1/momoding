package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.chat.MessageCorrection
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real persisted sources and transactional stop/delete boundaries. No model or shell runs. */
class ConversationMutationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val after = mutableListOf<suspend () -> Unit>()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao()); owner = repo.createSession("fixture", "M41 mutation fixture").id
    }
    @After fun close() { db.close() }
    private fun parts(text: String) = JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString()
    private suspend fun message(text: String, role: String = "user") = repo.appendMessage(owner, role, parts(text))
    private suspend fun task(id: String, source: String?, hand: String = "fixture-hand") =
        TaskEntity(id, owner, source, id, hand, "RUNNING", createdAt = 1, updatedAt = 1).also { dao.insertTask(it) }
    private suspend fun mark(id: String, kind: String, body: String) = dao.insertLog(TaskLogEntity(taskId = id, at = 1, kind = kind, body = body))
    private suspend fun remove(id: String, selected: Set<String> = setOf(id)) = db.withTransaction {
        TaskEngine.deleteMessagesFromIn(context, db.chatDao(), dao, after, owner, id, selected)
    }
    private suspend fun pending(id: String, task: String? = null, read: Long? = null) = dao.insertInbox(
        InboxEntity(id, owner, task, "USER", id, arrivedAt = 1, readAt = read))

    @Test fun deleteStopsOnlyTheRemovedSourcesAndTheirDescendants(): Unit = runBlocking {
        val kept = message("keep"); val removed = message("remove"); val tail = message("later")
        val independent = task("keep-task", kept.id)
        val parent = task("stop-parent", removed.id); val child = task("stop-child", null, TaskEntity.SCREEN)
        mark(parent.id, "CHILD", child.id)
        val result = remove(removed.id)
        assertEquals(setOf(removed.id, tail.id), result.ids); assertEquals(2, result.stopped)
        assertEquals(listOf(kept.id), db.chatDao().loadMessages(owner).map { it.id })
        assertEquals("RUNNING", dao.task(independent.id)!!.state)
        assertEquals("STOPPED", dao.task(parent.id)!!.outcome); assertEquals("STOPPED", dao.task(child.id)!!.outcome)
        assertEquals(1, dao.unread(owner).size); assertTrue(dao.unread(owner).single().text.contains("Stop snapshot:"))
    }
    @Test fun removingASplitBubbleFindsItsOriginalSource(): Unit = runBlocking {
        val source = message("one stored row"); task("split", source.id + "#1")
        remove(source.id); assertEquals("STOPPED", dao.task("split")!!.outcome)
    }
    @Test fun stopDeleteAndNotificationAllRollBackTogether(): Unit = runBlocking {
        val source = message("source"); task("work", source.id); pending(source.id, read = 2)
        assertTrue(runCatching { db.withTransaction {
            TaskEngine.deleteMessagesFromIn(context, db.chatDao(), dao, after, owner, source.id, setOf(source.id))
            error("injected before delete commit")
        } }.isFailure)
        assertNotNull(db.chatDao().message(source.id)); assertEquals("RUNNING", dao.task("work")!!.state)
        assertNotNull(dao.inbox(source.id)); assertTrue(dao.log("work").isEmpty())
        assertEquals(1, dao.inboxFor(owner).size)
    }
    @Test fun anUnconsumedTargetRemovesOnlyItAndLaterReceives(): Unit = runBlocking {
        pending("older"); pending("target"); pending("later")
        val result = remove("target", setOf("target", "later"))
        assertEquals(setOf("target", "later"), result.ids)
        assertEquals(listOf("older"), dao.inboxFor(owner).map { it.id })
    }
    @Test fun aPersistedTargetKeepsAnOlderFailedInputButDropsLaterUnreadInput(): Unit = runBlocking {
        val older = message("older"); val target = message("target")
        pending(older.id); pending(target.id); pending("not yet consumed")
        remove(target.id, setOf(target.id, "not yet consumed"))
        assertEquals(listOf(older.id), dao.inboxFor(owner).map { it.id })
        assertEquals(listOf(older.id), db.chatDao().loadMessages(owner).map { it.id })
    }
    @Test fun deletingALaterMessageKeepsAnEarlierFailedReceiveWithNoTranscriptRow(): Unit = runBlocking {
        dao.insertInbox(InboxEntity("older-failed", owner, sender = "USER", text = "older rejected input",
            arrivedAt = 1, failedAt = 2))
        val target = message("later accepted input")
        remove(target.id)
        assertNotNull("An earlier failed receive is not part of the later deletion", dao.inbox("older-failed"))
    }
    @Test fun aNewReceiveOutsideTheDisplayedDeletionRangeRemainsReceived(): Unit = runBlocking {
        val source = message("source")
        val selected = setOf(source.id)
        pending("new-unshown-input")
        remove(source.id, selected)
        assertNotNull(dao.inbox("new-unshown-input"))
        assertNull(db.chatDao().message(source.id))
    }
    @Test fun anInboxOnlyTargetAlsoRemovesItsDisplayedLaterHistoryAndWork(): Unit = runBlocking {
        pending("inbox-only-source")
        val later = message("later user input"); val reply = message("later reply", "assistant")
        val work = task("later-work", later.id)
        remove("inbox-only-source", setOf("inbox-only-source", later.id, reply.id))
        assertTrue(db.chatDao().loadMessages(owner).isEmpty())
        assertNull(dao.inbox("inbox-only-source"))
        assertEquals("STOPPED", dao.task(work.id)!!.outcome)
    }
    @Test fun deletingAnActiveHandsBriefStopsItsOriginalOwningTask(): Unit = runBlocking {
        val otherOwner = repo.createSession("fixture", "original owner").id
        val work = task("delegated", "source in another conversation", owner).copy(conversation = otherOwner)
        dao.updateTask(work); pending("brief", work.id, read = 2)
        remove("brief"); assertEquals("STOPPED", dao.task(work.id)!!.outcome)
        assertEquals(1, dao.inboxFor(otherOwner).size); assertTrue(dao.inboxFor(owner).isEmpty())
    }
    @Test fun aMissingOrForeignTargetDoesNotFallBackToAnotherRowsPosition(): Unit = runBlocking {
        val source = message("keep"); task("work", source.id)
        val other = repo.createSession("fixture", "other").id
        val foreign = repo.appendMessage(other, "user", parts("foreign"))
        assertTrue(remove("missing").ids.isEmpty()); assertTrue(remove(foreign.id).ids.isEmpty())
        assertNotNull(db.chatDao().message(source.id)); assertNotNull(db.chatDao().message(foreign.id))
        assertEquals("RUNNING", dao.task("work")!!.state)
    }
    @Test fun removingTheInvocationStopsWorkWhoseOriginalUserMessageWasEarlier(): Unit = runBlocking {
        val earlier = message("keep original authorization"); val call = message("later invocation", "assistant")
        val work = task("work", earlier.id)
        mark(work.id, "HANDOFF", JSONObject().put("tool", ToolCall(call.id, "call", "shell_execute").json()).toString())
        remove(call.id)
        assertEquals("STOPPED", dao.task(work.id)!!.outcome); assertNotNull(db.chatDao().message(earlier.id))
    }
    @Test fun aDeletedInvocationCannotStartAnOutwardEffectAfterTheCommit(): Unit = runBlocking {
        val source = message("authorization"); val call = message("tool invocation", "assistant")
        remove(source.id)
        assertTrue(runCatching { ToolCall(call.id, "call", "shell_execute").requireStoredIn(dao, owner) }.isFailure)
    }
    @Test fun deletingAgainDoesNotAppendAnotherEndOrStopNotice(): Unit = runBlocking {
        val source = message("source"); val work = task("work", source.id)
        remove(source.id); remove(source.id)
        assertEquals(1, dao.log(work.id).count { it.kind == "END" }); assertEquals(1, dao.inboxFor(owner).size)
    }
    @Test fun completedWorkKeepsItsActualOutcomeAndJournal(): Unit = runBlocking {
        val source = message("source"); val work = task("done", source.id)
        dao.updateTask(work.copy(state = "ENDED", outcome = "DONE")); mark(work.id, "END", "actual completed result")
        remove(source.id)
        assertEquals("DONE", dao.task(work.id)!!.outcome); assertEquals("actual completed result", dao.log(work.id).single().body)
        assertTrue(dao.inboxFor(owner).isEmpty())
    }
    @Test fun anEmptyRemainderClearsTheDeletedPreviewInTheSameTransaction(): Unit = runBlocking {
        val source = message("old preview")
        remove(source.id)
        assertNull(db.chatDao().getSession(owner)!!.lastMessage)
    }
    @Test fun correctionMetadataRoundTripsWhileProvidersStillSeeOrdinaryQuotedText(): Unit = runBlocking {
        val correction = MessageCorrection("source", "first line\nsecond line", "replacement")
        val result = JSONArray(correction.prepend(parts("Change to: replacement")))
        assertEquals(correction, MessageCorrection.fromPart(result.getJSONObject(0)))
        assertEquals("> first line\n> second line\n\n", result.getJSONObject(0).getString("value"))
        assertEquals("Change to: replacement", result.getJSONObject(1).getString("value"))
        assertEquals(listOf("text", "text"), (0 until result.length()).map { result.getJSONObject(it).getString("type") })
    }
    @Test fun quotingDoesNotConsumePasteMarkersOrModifyAttachmentParts(): Unit = runBlocking {
        val media = JSONObject().put("type", "mediaRef").put("value", JSONObject().put("path", "fixture.png"))
        val original = JSONArray(parts("Change to: new")).put(media).toString()
        val correction = MessageCorrection("source", "[Pasted#1] \"quoted\"", "new")
        val result = JSONArray(correction.prepend(original))
        assertEquals(media.toString(), result.getJSONObject(2).toString())
        assertTrue(result.getJSONObject(0).getString("value").contains("[Pasted#1]"))
        assertEquals(2, JSONArray(original).length())
    }
}
