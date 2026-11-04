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

/** The production transaction code, real SQLite, and no executed command or system permission. */
@RunWith(AndroidJUnit4::class)
class ApprovalLifecycleTest {
    private lateinit var db: AppDatabase
    private lateinit var chat: String
    private val dao get() = db.taskDao()
    private val args = """{"command":"rm -rf /var/minis/workspace/fixture","timeout":37,"tool_title":"fixture"}"""
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        chat = ChatRepository(db.chatDao()).createSession("approval fixture").id
    }
    @After fun close() { db.close() }
    private fun proposal(task: String? = null, id: String = java.util.UUID.randomUUID().toString()) =
        Approval.Spec(RiskRequest(id = id, taskId = task, sessionId = chat, kind = GuardKind.SHELL,
            assessment = RiskAssessment(RiskClass.DESTRUCTIVE, "removes only the fixture", "/var/minis/workspace/fixture"),
            preview = "rm -rf /var/minis/workspace/fixture"), Approval.Mode.SHELL, args)
    private suspend fun queue(spec: Approval.Spec = proposal()) = db.withTransaction { TaskEngine.queueApprovalIn(dao, spec) }
    private suspend fun decide(spec: Approval.Spec, answer: RiskDecision = RiskDecision.ALLOW_ONCE, live: Boolean = false) =
        db.withTransaction { TaskEngine.decideApprovalIn(dao, spec, answer, live) }
    private suspend fun claim(spec: Approval.Spec, supplied: String = args, session: String = chat) =
        db.withTransaction { TaskEngine.claimApprovalIn(dao, session, spec.ticket, supplied) }
    private suspend fun task(spec: Approval.Spec) = dao.task(spec.request.taskId!!)!!

    @Test fun receivingAnApprovalCommitsTheTaskAndEveryParameterBeforeTheCard() = runBlocking {
        val saved = queue()
        val task = task(saved)
        assertEquals("WAITING", task.state)
        assertEquals(task.id, saved.request.taskId)
        assertEquals(chat, saved.request.conversation)
        assertEquals(saved.request, Approval.pending(task.ask).single().request)
        val restoredArgs = requireNotNull(Approval.pending(task.ask).single().shellArgs)
        assertEquals(JSONObject(args).getString("command"), JSONObject(restoredArgs).getString("command"))
        assertEquals(Approval.shellSignature(args), Approval.shellSignature(restoredArgs))
        assertEquals(1, dao.log(task.id).count { it.kind == "APPROVAL" })
        assertTrue(dao.log(task.id).none { it.kind == "COMMAND" })
        assertTrue(TaskEngine.pendingWork(dao, task.id))
    }

    @Test fun approvalAndItsExactInboxHandoffCommitTogetherAndDuplicatesDoNothing() = runBlocking {
        val saved = queue()
        try {
            db.withTransaction {
                TaskEngine.decideApprovalIn(dao, saved, RiskDecision.ALLOW_ONCE, false)
                error("fault before commit")
            }
        } catch (expected: IllegalStateException) { assertEquals("fault before commit", expected.message) }
        assertEquals("WAITING", task(saved).state)
        assertNull(dao.inbox("approval:" + saved.request.id))
        assertTrue(dao.log(saved.request.taskId!!).none { it.kind == "APPROVAL_DECISION" })
        assertNotNull(decide(saved))
        assertNull(decide(saved))
        val row = dao.inbox("approval:" + saved.request.id)!!
        val call = org.json.JSONArray(row.prefill).getJSONObject(0).getJSONObject("args")
        assertEquals(saved.ticket, call.getString(Approval.TICKET_ARG))
        assertEquals(Approval.shellSignature(args), Approval.shellSignature(call.toString()))
        assertEquals(1, dao.inboxFor(chat).size)
        assertTrue(TaskEngine.pendingWork(dao, saved.request.taskId!!))
    }

    @Test fun concurrentConsumersCanClaimOnlyOneExecution() = runBlocking {
        val saved = queue(); decide(saved)
        val claims = coroutineScope { listOf(async { claim(saved) }, async { claim(saved) }).map { it.await() } }
        assertEquals(1, claims.count { it is Approval.Claim.Granted })
        assertEquals(1, claims.count { it is Approval.Claim.Denied })
        val history = dao.log(saved.request.taskId!!)
        assertEquals(1, history.count { it.kind == "APPROVAL_USED" })
        assertEquals(1, history.count { it.kind == "COMMAND" })
        assertEquals(saved.request.id, ShellWork.Command.parse(history.single { it.kind == "COMMAND" }.body)!!.id)
    }

    @Test fun changingParametersWithdrawsPermissionWithoutStartingAnything() = runBlocking {
        val saved = queue(); decide(saved)
        val result = claim(saved, JSONObject(args).put("timeout", 38).toString())
        assertTrue(result is Approval.Claim.Denied && result.reason.contains("parameters changed"))
        assertTrue(dao.log(saved.request.taskId!!).none { it.kind == "COMMAND" })
        assertEquals("WAITING", task(saved).state)
        assertTrue(Approval.unclaimed(dao.log(saved.request.taskId!!)).isEmpty())
    }

    @Test fun anotherChatCannotUseOrWithdrawThePersonsPermission() = runBlocking {
        val saved = queue(); decide(saved)
        assertTrue(claim(saved, session = "other-chat") is Approval.Claim.Denied)
        assertTrue(claim(saved) is Approval.Claim.Granted)
    }

    @Test fun newInstructionsWithdrawAnUnusedPermissionAndItsQueuedEnvelope() = runBlocking {
        val saved = queue(); decide(saved)
        db.withTransaction { TaskEngine.invalidateApprovals(dao, saved.request.taskId!!) }
        assertNull(dao.inbox("approval:" + saved.request.id))
        assertTrue(claim(saved) is Approval.Claim.Denied)
        assertTrue(dao.log(saved.request.taskId!!).none { it.kind == "COMMAND" })
    }

    @Test fun aStoppedTaskDoesNotAcceptALateApproval() = runBlocking {
        val saved = queue()
        dao.updateTask(task(saved).copy(state = "ENDED", outcome = "STOPPED", ask = null))
        assertNull(decide(saved))
        assertTrue(claim(saved) is Approval.Claim.Denied)
        assertTrue(dao.inboxFor(chat).isEmpty())
        assertTrue(dao.log(saved.request.taskId!!).none { it.kind == "COMMAND" })
    }

    @Test fun multipleApprovalsShareTheTaskWithoutOverwritingEachOtherOrItsPausedBudget() = runBlocking {
        val before = System.currentTimeMillis() - 100_000
        dao.insertTask(TaskEntity("budget", chat, title = "budget", hand = chat, state = "RUNNING", createdAt = before, updatedAt = before))
        val one = queue(proposal("budget"))
        val paused = TaskEngine.elapsedIn(dao, task(one).id)
        val two = queue(proposal(one.request.taskId))
        assertEquals(2, Approval.pending(task(one).ask).size)
        decide(one)
        assertEquals(listOf(two.request.id), Approval.pending(task(one).ask).map { it.request.id })
        assertTrue(claim(one) is Approval.Claim.Granted)
        decide(two)
        assertNull(task(two).ask)
        val resumed = dao.log(two.request.taskId!!).last { it.kind == "STARTED" }
        val spent = JSONObject(resumed.body).getLong("elapsed_ms")
        assertTrue(spent >= paused && spent < paused + 5_000)
        assertTrue(claim(two) is Approval.Claim.Granted)
    }

    @Test fun pureHumanWaitingDoesNotConsumeOrResetTheRemainingBudget() = runBlocking {
        val before = System.currentTimeMillis() - 100_000
        dao.insertTask(TaskEntity("paused", chat, title = "paused", hand = chat, state = "RUNNING", createdAt = before, updatedAt = before))
        val saved = queue(proposal("paused"))
        assertTrue(TaskEngine.elapsedIn(dao, "paused", System.currentTimeMillis() + 500_000) in 100_000L..105_000L)
        decide(saved)
        assertTrue(TaskEngine.elapsedIn(dao, "paused") in 100_000L..105_000L)
        assertTrue(claim(saved) is Approval.Claim.Granted)
        assertTrue(TaskEngine.elapsedIn(dao, "paused") in 100_000L..105_000L)
    }

    @Test fun restartRepairsAConsumedEnvelopeOnlyBeforeThePermissionWasClaimed() = runBlocking {
        val saved = queue()
        db.withTransaction { TaskEngine.recoverApprovalsIn(dao) }
        assertNull(dao.inbox("approval:" + saved.request.id))
        decide(saved)
        val id = "approval:" + saved.request.id
        dao.markRead(listOf(id), 123)
        db.withTransaction { TaskEngine.recoverApprovalsIn(dao) }
        assertNull(dao.inbox(id)!!.readAt)
        assertEquals(1, dao.inboxFor(chat).size)
        claim(saved)
        dao.markRead(listOf(id), 456)
        db.withTransaction { TaskEngine.recoverApprovalsIn(dao) }
        assertEquals(456L, dao.inbox(id)!!.readAt)
        assertEquals(1, dao.log(saved.request.taskId!!).count { it.kind == "COMMAND" })
    }

    @Test fun aScreenApprovalKeepsItsOwningConversationAndResumesTheSameParent() = runBlocking {
        val owner = ChatRepository(db.chatDao()).createSession("owner").id
        val now = System.currentTimeMillis()
        dao.insertTask(TaskEntity("parent", owner, title = "parent", hand = chat, state = "RUNNING", createdAt = now, updatedAt = now))
        dao.insertTask(TaskEntity("screen", chat, title = "screen", hand = TaskEntity.SCREEN, state = "RUNNING", createdAt = now, updatedAt = now))
        dao.insertLog(TaskLogEntity(taskId = "screen", at = now, kind = "SCREEN", body = """{"parent":"parent"}"""))
        dao.insertLog(TaskLogEntity(taskId = "parent", at = now, kind = "CHILD", body = "screen"))
        val saved = queue(proposal("screen").copy(mode = Approval.Mode.SCREEN, shellArgs = null,
            request = proposal("screen").request.copy(kind = GuardKind.SCREEN, preview = "Tap Delete", pageUrl = "fixture app", elementText = "Delete")))
        assertEquals(owner, saved.request.conversation)
        assertEquals("WAITING", dao.task("parent")!!.state)
        assertEquals(saved.request.id, Approval.pending(dao.task("parent")!!.ask).single().request.id)
        decide(saved, live = true)
        assertEquals("RUNNING", dao.task("parent")!!.state)
        assertNull(dao.task("parent")!!.ask)
        val granted = db.withTransaction { TaskEngine.claimApprovalIn(dao, chat, saved.ticket, actual = saved.request) }
        assertTrue(granted is Approval.Claim.Granted)
        assertTrue(dao.log("screen").none { it.kind == "COMMAND" })
    }
}
