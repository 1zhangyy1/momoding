package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LegacyRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val prefs by lazy { context.getSharedPreferences("m41-legacy-test", 0) }
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao()); owner = repo.createSession("fixture", "legacy recovery").id
        prefs.edit().clear().commit()
    }
    @After fun close() { db.close(); prefs.edit().clear().commit(); context.deleteSharedPreferences("m41-legacy-test") }
    private suspend fun work(id: String = "work", hand: String = owner, source: String? = null, state: String = "RUNNING", ask: String? = null): TaskEntity =
        TaskEntity(id, owner, source, "legacy fixture", hand, state, ask = ask, createdAt = 1, updatedAt = 1).also { dao.insertTask(it) }
    private suspend fun input(to: String = owner, read: Boolean = true, task: String? = null): String {
        val row = repo.appendMessage(to, "user", """[{"type":"text","value":"old words"}]""")
        dao.insertInbox(InboxEntity(row.id, to, task, "USER", "old words", arrivedAt = 1, readAt = if (read) 2 else null)); return row.id
    }
    private suspend fun call(to: String = owner, id: String = "original", text: String = "printf owned"): ToolCall {
        val value = JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", JSONObject().put("command", text).toString())
        val row = repo.appendMessage(to, "assistant", JSONArray().put(JSONObject().put("type", "toolUse").put("value", value)).toString())
        return ToolCall(row.id, id, "shell_execute")
    }
    private suspend fun mark(task: TaskEntity, kind: String, body: String): TaskLogEntity {
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 3, kind = kind, body = body)); return dao.log(task.id).last()
    }
    private fun flag(sid: String = owner): String = JSONObject().put(sid, JSONObject().put("at", 1).put("tries", 1)).toString()
    private suspend fun recover(raw: String? = null): Set<String> = db.withTransaction {
        val turns = TaskTranscript.recoveryTurnsIn(db.chatDao(), raw)
        val saved = TaskEngine.recoverLegacyIn(db.chatDao(), dao, raw, turns)
        TaskEngine.recoverOrphansIn(dao, turns)
        TaskTranscript.recoverIn(db.chatDao(), dao, turns); Inbox.recoverIn(dao)
        saved
    }

    @Test fun aFlagIsDurableBeforeItsPreferenceAcknowledgmentAndDoesNotReplayInput(): Unit = runBlocking {
        val id = input(); val old = call(); val raw = flag(); prefs.edit().putString(LegacyWork.KEY, raw).commit()
        assertEquals(setOf(owner), recover(raw)); val task = dao.recentTasks(10).single()
        assertEquals("WAITING", task.state); assertNull(task.sourceMessageId); assertNotNull(task.ask)
        assertNull(dao.inbox(id)); assertNotNull(db.chatDao().message(id)); assertEquals(raw, prefs.getString(LegacyWork.KEY, null))
        assertNotNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, old.message), outward = true))
        assertTrue(dao.log(task.id).none { it.kind == "AUTHORIZED" })
        val before = dao.log(task.id); recover(raw); assertEquals(before, dao.log(task.id))
        assertTrue(LegacyWork.acknowledge(prefs, raw, setOf(owner))); assertFalse(prefs.contains(LegacyWork.KEY))
    }
    @Test fun aPreInboxCallCannotBecomeAuthorizedByLaterWords(): Unit = runBlocking {
        val old = call(); recover(flag()); assertTrue(dao.interruptedInbox().isEmpty())
        input(); val fresh = call(id = "new-user")
        assertNotNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, old.message), outward = true))
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, fresh.message), outward = true))
        val row = db.chatDao().nextMessage(owner, db.chatDao().message(old.message)!!.sortOrder)!!
        assertFalse(JSONArray(row.partsJson).getJSONObject(0).getJSONObject("value").getBoolean("success"))
    }
    @Test fun rollbackKeepsTheFlagAndReceiveUntilTheRoomWriteCommits(): Unit = runBlocking {
        val id = input(); call(); val raw = flag(); prefs.edit().putString(LegacyWork.KEY, raw).commit()
        assertTrue(runCatching { db.withTransaction { TaskEngine.recoverLegacyIn(db.chatDao(), dao, raw, db.chatDao().interruptedToolTurns()); error("before commit") } }.isFailure)
        assertTrue(dao.recentTasks(10).isEmpty()); assertNotNull(dao.inbox(id)?.readAt); assertEquals(raw, prefs.getString(LegacyWork.KEY, null))
        assertEquals(setOf(owner), recover(raw)); assertEquals(1, dao.recentTasks(10).size)
    }
    @Test fun partialAcknowledgmentKeepsUnroutableAndMalformedEvidence(): Unit = runBlocking {
        val raw = JSONObject(flag()).put("missing-session", JSONObject.NULL).toString(); prefs.edit().putString(LegacyWork.KEY, raw).commit()
        val saved = recover(raw); assertEquals(setOf(owner), saved); assertTrue(LegacyWork.acknowledge(prefs, raw, saved))
        assertEquals(setOf("missing-session"), JSONObject(prefs.getString(LegacyWork.KEY, null)!!).keys().asSequence().toSet())
        val broken = "{broken flag"; prefs.edit().putString(LegacyWork.KEY, broken).commit()
        assertTrue(recover(broken).isEmpty()); assertTrue(LegacyWork.acknowledge(prefs, broken, emptySet()))
        assertEquals(broken, prefs.getString(LegacyWork.KEY, null))
    }
    @Test fun acknowledgmentCannotEraseAChangedSnapshot(): Unit = runBlocking {
        val raw = flag(); recover(raw); prefs.edit().putString(LegacyWork.KEY, "{newer-evidence").commit()
        assertTrue(LegacyWork.acknowledge(prefs, raw, setOf(owner))); assertEquals("{newer-evidence", prefs.getString(LegacyWork.KEY, null))
    }
    @Test fun aMalformedEntryStillParksInItsExistingConversation(): Unit = runBlocking {
        val raw = JSONObject().put(owner, "partial entry").toString(); recover(raw)
        val task = dao.recentTasks(10).single(); assertEquals(owner, task.conversation); assertEquals("WAITING", task.state)
        assertTrue(dao.log(task.id).any { it.kind == "REPORT" && it.body.contains("partial entry") })
    }
    @Test fun aDeletedConversationIsNotRecreated(): Unit = runBlocking {
        val raw = flag(); db.chatDao().deleteSession(owner); assertTrue(recover(raw).isEmpty()); assertTrue(dao.recentTasks(10).isEmpty())
    }
    @Test fun anExistingQuestionAndItsExactPayloadSurviveMigration(): Unit = runBlocking {
        val ask = """{"kind":"question","question":"verify recipient","payload":{"recipient":"fixture"}}"""
        val task = work(ask = ask, state = "WAITING"); input(); call(); recover(flag())
        assertEquals(listOf(task.id), dao.recentTasks(10).map { it.id }); assertEquals(ask, dao.task(task.id)?.ask)
    }
    @Test fun aChildFlagKeepsItsOriginalParentAndHand(): Unit = runBlocking {
        val child = repo.createSession("fixture", parentSessionId = owner).id; input(child); call(child); recover(flag(child))
        val task = dao.recentTasks(10).single(); assertEquals(owner, task.conversation); assertEquals(child, task.hand)
        assertEquals(task.id, dao.inboxFor(owner).single().taskId); assertTrue(dao.inboxFor(child).isEmpty())
    }
    @Test fun aLostRunningTaskWaitsWithoutInventingAContinuation(): Unit = runBlocking {
        val task = work(); recover(); assertEquals("WAITING", dao.task(task.id)?.state); assertNotNull(dao.task(task.id)?.ask)
        assertTrue(dao.log(task.id).none { it.kind in listOf("COMMAND", "HANDOFF", "AUTHORIZED", "END") })
        val before = dao.log(task.id); recover(); assertEquals(before, dao.log(task.id))
    }
    @Test fun aNewUnreadMessageIsNotUsedAsTheOldTasksContinuation(): Unit = runBlocking {
        val task = work(); val fresh = input(read = false); recover()
        assertEquals("WAITING", dao.task(task.id)?.state); assertEquals(fresh, dao.unread(owner).first().id)
    }
    @Test fun anExactDurableReceiveStillContinuesItsOwnTask(): Unit = runBlocking {
        val id = input(); val task = work(source = id); call(); recover()
        assertEquals("RUNNING", dao.task(task.id)?.state); assertEquals(id, dao.unread(owner).single().id); assertNull(dao.task(task.id)?.ask)
    }
    @Test fun queuedAndEndedRowsAreNotConvertedToNewWork(): Unit = runBlocking {
        val queued = work("queued", state = "QUEUED"); val ended = work("ended", state = "ENDED")
        recover(); assertEquals(queued, dao.task(queued.id)); assertEquals(ended, dao.task(ended.id)); assertTrue(dao.log(ended.id).isEmpty())
    }
    @Test fun aParkedOldCallCannotBorrowNewerInputEvenAfterTheTaskContinues(): Unit = runBlocking {
        input(); val old = call(); recover(flag()); val task = dao.recentTasks(10).single()
        input(); val fresh = call(id = "fresh")
        dao.updateTask(task.copy(state = "RUNNING", ask = null))
        assertNotNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, old.message), outward = true))
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, fresh.message), outward = true))
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, task.id, old.message)))
    }
    @Test fun aSavedReadKeepsItsIdentityAndCanFinishWhileTheLostLoopWaits(): Unit = runBlocking {
        val id = input(); val task = work(source = id); val source = call(text = "printf owned")
        val command = ShellWork.Command("read", "owned-process", "printf owned", source)
        db.withTransaction { TaskEngine.recordCommandIn(dao, task, command) }
        val reads = db.withTransaction { TaskEngine.recoverCommandsIn(dao) }; assertEquals(listOf(command), reads.map { it.command })
        recover(flag()); val parked = dao.task(task.id)!!; assertNotNull(parked.ask); assertEquals("PAUSED", TaskClock.latest(dao.log(task.id))?.kind)
        assertEquals(command, ReadRecovery.pendingIn(dao, parked, command)?.command)
        db.withTransaction { TaskEngine.commandFinishedIn(context, dao, mutableListOf(), task.id, command, ToolExecutionResult("owned bytes", true)) }
        assertEquals(parked.ask, dao.task(task.id)?.ask); assertEquals("WAITING", dao.task(task.id)?.state); assertNull(dao.task(task.id)?.outcome)
    }
    @Test fun aBrokenReferenceParksBeforeAHealthyReceiptRestores(): Unit = runBlocking {
        input(); val source = call(); val task = work()
        mark(task, "ABOUT_TO", JSONObject().put("id", "known").put("session", owner).put("tool", source.json()).toString())
        mark(task, "RESULT", """{"id":"known","success":true,"output":"actual receipt"}""")
        val bad = mark(task, "HANDOFF", JSONObject().put("session", owner).put("tool", JSONObject().put("message", source.message)).toString())
        assertEquals(1, db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) })
        assertEquals("WAITING", dao.task(task.id)?.state); assertEquals(bad, dao.log(task.id).first { it.id == bad.id })
        assertEquals(task.id, dao.recovered("record:${bad.id}")?.id); assertTrue(dao.untranscribedCalls().isEmpty())
        val row = db.chatDao().nextMessage(owner, db.chatDao().message(source.message)!!.sortOrder)!!
        assertTrue(JSONArray(row.partsJson).getJSONObject(0).getJSONObject("value").getBoolean("success"))
        val before = dao.log(task.id); assertEquals(0, db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) }); assertEquals(before, dao.log(task.id))
    }
    @Test fun foreignReferencesCannotAuthorizeOrBlockTheForeignConversation(): Unit = runBlocking {
        input(); val task = work(); val foreign = repo.createSession("fixture").id
        val fresh = input(foreign); val source = call(foreign)
        mark(task, "HANDOFF", JSONObject().put("session", foreign).put("tool", source.json()).toString())
        db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) }
        assertEquals("WAITING", dao.task(task.id)?.state); assertNotNull(dao.inbox(fresh)?.readAt)
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(foreign, null, source.message), outward = true))
    }
    @Test fun anUnreadableReferenceRetainsItsRowAndBecomesOneQuestion(): Unit = runBlocking {
        input(); call(); val task = work(); val raw = """{"tool":{"message":"partial"""
        val row = mark(task, "HANDOFF", raw)
        db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) }
        assertEquals("WAITING", dao.task(task.id)?.state); assertEquals(row, dao.log(task.id).first())
        assertEquals(1, dao.inboxFor(owner).size); assertTrue(dao.untranscribedCalls().isEmpty())
        db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) }; assertEquals(1, dao.inboxFor(owner).size)
    }
    @Test fun anEndedTaskWithBadMetadataStaysEndedAndWakesNoNewLoop(): Unit = runBlocking {
        val task = work(state = "ENDED"); val row = mark(task, "HANDOFF", """{"tool":{}}""")
        db.withTransaction { TaskTranscript.recoverIn(db.chatDao(), dao) }
        assertEquals(task, dao.task(task.id)); assertTrue(dao.inboxFor(owner).isEmpty())
        assertEquals(task.id, dao.recovered("record:${row.id}")?.id); assertTrue(dao.untranscribedCalls().isEmpty())
    }
}
