package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecoveryLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val text = """[{"type":"text","value":"fixture"}]"""
    private val use = """[{"type":"toolUse","value":{"toolUseId":"fixture","name":"shell_execute","input":"{}"}}]"""

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
        owner = repo.createSession("fixture", "M41 recovery fixture").id
    }
    @After fun close() { db.close() }
    private suspend fun input(to: String = owner, task: String? = null, sender: String = "USER", read: Long? = 2, tries: Int = 0, prefill: String? = null): String {
        val row = repo.appendMessage(to, "user", text)
        dao.insertInbox(InboxEntity(row.id, to, task, sender, "fixture", prefill = prefill, arrivedAt = 1, readAt = read, tries = tries))
        return row.id
    }
    private suspend fun task(id: String, hand: String = owner, source: String? = null, state: String = "RUNNING", outcome: String? = null, ask: String? = null) =
        TaskEntity(id, owner, source, "fixture", hand, state, ask = ask, outcome = outcome, createdAt = 1, updatedAt = 1).also { dao.insertTask(it) }
    private suspend fun recover() = db.withTransaction { Inbox.recoverIn(dao) }
    private suspend fun reply(hand: String, task: String, after: MutableList<suspend () -> Unit> = mutableListOf()) =
        TaskEngine.replyIn(context, dao, after, hand, task, TaskCompletion(true, "the actual final answer"), true) {
            repo.appendMessage(hand, "assistant", text)
        }

    @Test fun aConsumedInputRetriesWithItsOriginalId(): Unit = runBlocking {
        val id = input(); recover()
        val row = dao.inbox(id)!!
        assertNull(row.readAt); assertNull(row.failedAt); assertEquals(1, row.tries)
        assertEquals(listOf(id), db.chatDao().loadMessagesPage(owner, 0, 20).map { it.id })
        recover(); assertEquals(1, dao.inbox(id)!!.tries)
    }
    @Test fun theThirdInterruptedAttemptBecomesAnExplicitFailure(): Unit = runBlocking {
        val id = input(tries = 2); recover()
        assertEquals(3, dao.inbox(id)!!.tries); assertNotNull(dao.inbox(id)!!.failedAt)
        assertTrue(dao.sessionsWithUnread().isEmpty())
    }
    @Test fun recipeConsumptionRollsBackWithItsInvocation(): Unit = runBlocking {
        val id = input(prefill = "recipe")
        assertTrue(runCatching { db.withTransaction {
            repo.appendMessage(owner, "assistant", use); dao.clearReadPrefill(owner); error("injected after recipe consumption")
        } }.isFailure)
        assertEquals("recipe", dao.inbox(id)!!.prefill)
        assertEquals(1, db.chatDao().loadMessagesPage(owner, 0, 20).size)
    }
    @Test fun aCommittedInvocationConsumesItsRecipeBeforeRetry(): Unit = runBlocking {
        val id = input(prefill = "recipe")
        db.withTransaction { repo.appendMessage(owner, "assistant", use); dao.clearReadPrefill(owner) }
        recover(); assertNull(dao.inbox(id)!!.prefill); assertEquals(id, dao.unread(owner).single().id)
    }
    @Test fun stoppingOneSourceDoesNotDiscardAnotherReceivedInput(): Unit = runBlocking {
        val old = input(); task("stopped", source = old, state = "ENDED", outcome = "STOPPED")
        val other = input(); recover()
        assertNull(dao.inbox(old)); assertEquals(other, dao.unread(owner).single().id)
    }
    @Test fun stoppingAChildDoesNotDiscardItsOwnersRemainingInput(): Unit = runBlocking {
        val id = input(); task("stopped-child", hand = "child", source = id, state = "ENDED", outcome = "STOPPED")
        val call = repo.appendMessage(owner, "assistant", use).id
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, call), outward = true))
        recover(); assertEquals(id, dao.unread(owner).single().id)
    }
    @Test fun anAutomaticWaitingReportMaySteerAnotherRunningTaskButCannotAnswerAnotherAsk(): Unit = runBlocking {
        task("waiting", hand = "first", state = "WAITING", ask = "unknown")
        task("running", hand = "second"); input(task = "waiting", sender = "HAND")
        val call = repo.appendMessage(owner, "assistant", use).id
        val binding = TaskGuard.Binding(owner, null, call)
        assertNotNull(TaskGuard.checkIn(dao, binding, outward = true))
        assertNull(TaskGuard.checkIn(dao, binding, outward = true, targetTaskId = "running"))
        assertNotNull(TaskGuard.checkIn(dao, binding, outward = true, targetTaskId = "waiting"))
    }
    @Test fun aWaitingHandDoesNotResumeButItsOwnerStillReceivesTheReport(): Unit = runBlocking {
        val hand = repo.createSession("fixture", parentSessionId = owner).id
        task("waiting", hand = hand, state = "WAITING", ask = """{"kind":"action_outcome_unknown"}""")
        val brief = input(hand, "waiting", "HAND")
        val report = input(task = "waiting", sender = "HAND")
        recover(); assertNull(dao.inbox(brief)); assertEquals(report, dao.unread(owner).single().id)
    }
    @Test fun aKnownCompletedActionStillAllowsItsOriginalAnswerToFinish(): Unit = runBlocking {
        val id = input(); task("done", source = id, state = "ENDED", outcome = "DONE")
        recover(); assertEquals(id, dao.unread(owner).single().id)
    }
    @Test fun waitingWorkBlocksTheOldSourceButAllowsReadingAndAnIndependentInput(): Unit = runBlocking {
        val id = input(); task("waiting", hand = "other-hand", source = id, state = "WAITING", ask = "unknown")
        val call = repo.appendMessage(owner, "assistant", use).id
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, call)))
        assertNotNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, call), outward = true))
        input(read = 3); val later = repo.appendMessage(owner, "assistant", use).id
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, later), outward = true))
    }
    @Test fun anAutomaticUnknownReportDoesNotAuthorizeOutwardWorkOrLockANewUserInput(): Unit = runBlocking {
        task("waiting", state = "WAITING", ask = "unknown")
        input(task = "waiting", sender = "HAND")
        val call = repo.appendMessage(owner, "assistant", use).id
        assertNotNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, call), outward = true))
        val user = input(read = 3)
        input(task = "waiting", sender = "HAND", read = 3)
        val fresh = repo.appendMessage(owner, "assistant", use).id
        assertEquals(user, dao.inputForCall(owner, fresh)!!.id)
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, fresh), outward = true))
    }
    @Test fun aStoredApprovalMayRunWhileOtherApprovalsStillWait(): Unit = runBlocking {
        val id = input()
        val request = app.momoding.guard.RiskRequest(sessionId = owner, kind = app.momoding.guard.GuardKind.SHELL,
            assessment = app.momoding.guard.RiskAssessment(app.momoding.guard.RiskClass.OUTBOUND, "fixture", "fixture"), preview = "fixture")
        task("approved", source = id, state = "WAITING", ask = Approval.ask(listOf(Approval.Spec(request, Approval.Mode.SHELL, "{}"))))
        val call = repo.appendMessage(owner, "assistant", use).id
        val binding = TaskGuard.Binding(owner, "approved", call)
        assertNotNull(TaskGuard.checkIn(dao, binding, outward = true))
        assertNull(TaskGuard.checkIn(dao, binding, outward = true, allowWaiting = true))
    }
    @Test fun finalReplyInputAndTaskEndCommitTogetherOrAllRollBack(): Unit = runBlocking {
        val hand = repo.createSession("fixture", parentSessionId = owner).id
        task("helper", hand = hand); val id = input(hand, "helper", "HAND")
        val after = mutableListOf<suspend () -> Unit>()
        assertTrue(runCatching { db.withTransaction { reply(hand, "helper", after); error("injected before commit") } }.isFailure)
        assertEquals("RUNNING", dao.task("helper")!!.state); assertNotNull(dao.inbox(id)!!.readAt)
        assertEquals(1, db.chatDao().loadMessagesPage(hand, 0, 20).size); assertTrue(dao.inboxFor(owner).isEmpty())
        db.withTransaction { reply(hand, "helper") }
        assertEquals("DONE", dao.task("helper")!!.outcome); assertNull(dao.inbox(id))
        assertEquals(1, dao.log("helper").count { it.kind == "END" }); assertEquals(1, dao.inboxFor(owner).size)
    }
    @Test fun anUnreadFollowupKeepsTheOriginalTaskOpenAfterItsReply(): Unit = runBlocking {
        val hand = repo.createSession("fixture", parentSessionId = owner).id
        task("helper", hand = hand); input(hand, "helper", "HAND"); val later = input(hand, "helper", "HAND", read = null)
        db.withTransaction { reply(hand, "helper") }
        assertEquals("RUNNING", dao.task("helper")!!.state); assertTrue(dao.log("helper").none { it.kind == "END" })
        assertEquals(later, dao.unread(hand).single().id)
    }
    @Test fun anOldReplyCannotFinishALaterTaskOnTheSameBot(): Unit = runBlocking {
        val hand = repo.createSession("fixture").id
        task("old", hand = hand, state = "ENDED", outcome = "DONE"); task("later", hand = hand)
        input(hand, "old", "HAND"); db.withTransaction { reply(hand, "old") }
        assertEquals("RUNNING", dao.task("later")!!.state); assertTrue(dao.log("later").isEmpty())
    }
    @Test fun aCloseoutUsesItsOriginalTaskEvenWithOtherOwnCommands(): Unit = runBlocking {
        task("first"); task("second"); input(task = "second")
        db.withTransaction { reply(owner, "second") }
        assertEquals("RUNNING", dao.task("first")!!.state); assertEquals("DONE", dao.task("second")!!.outcome)
    }
}
