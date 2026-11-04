package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.hands.HandsOperator
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real transaction boundaries and owner routing; no external operation is executed here. */
class StopReceiptTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val after = mutableListOf<suspend () -> Unit>()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao()); owner = repo.createSession("fixture", "M41 stop receipt").id
    }
    @After fun close() { db.close() }
    private suspend fun task(id: String = "task", hand: String = owner, state: String = "RUNNING", progress: String? = null) =
        TaskEntity(id, owner, title = id, hand = hand, state = state, progress = progress, createdAt = 1, updatedAt = 1).also { dao.insertTask(it) }
    private suspend fun mark(task: String, kind: String, body: String) = dao.insertLog(TaskLogEntity(taskId = task, at = 1, kind = kind, body = body))
    private suspend fun action(task: TaskEntity, id: String = "action"): TaskAction {
        mark(task.id, "ABOUT_TO", JSONObject().put("id", id).put("preview", "fixture outward operation").toString())
        return TaskAction(task.id, id, null)
    }
    private suspend fun command(task: TaskEntity, id: String = "command") =
        ShellWork.Command(id, "fixture-process-$id", "printf fixture").also { mark(task.id, "COMMAND", it.json()) }
    private suspend fun stop(id: String) = db.withTransaction { TaskEngine.stopIn(context, dao, after, id) }
    private suspend fun receive(action: TaskAction, output: String = "observed", unknown: Boolean = false) =
        db.withTransaction { TaskEngine.actionFinishedIn(dao, action, output, true, unknown) }
    private suspend fun receive(task: String, command: ShellWork.Command, unknown: Boolean = false) = db.withTransaction {
        TaskEngine.commandFinishedIn(context, dao, after, task, command, ToolExecutionResult("observed command", true), unknown = unknown)
    }
    private suspend fun snapshot(task: String) = JSONObject(dao.log(task).last { it.kind == "END" }.body.substringAfter("Stop snapshot:\n"))
    private suspend fun late() = dao.inboxFor(owner).filter { it.id.startsWith("late:") }
    private fun screen(stopped: Boolean = false) = HandsOperator.Result(
        if (stopped) HandsOperator.Outcome.Stopped("cancelled") else HandsOperator.Outcome.Done("late observed screen"),
        1, "screen-run", null, null, listOf("last observed step"), null)

    @Test fun stopCapturesCheckpointsKnownAndUnknownEffectsAndUnreadWords(): Unit = runBlocking {
        val root = task(progress = "reviewed 2 of 3"); val sent = action(root, "known"); action(root, "pending")
        receive(sent, "native accepted"); command(root, "read-only")
        Inbox.post(dao, owner, "new requirement not read", root.id)
        val child = task("child", TaskEntity.SCREEN, progress = "opening fixture")
        mark(root.id, "CHILD", child.id); receive(action(child), "child native accepted")
        stop(root.id)
        val snap = snapshot(root.id)
        assertEquals(2, snap.getJSONArray("checkpoints").length())
        assertTrue(snap.toString().contains("reviewed 2 of 3")); assertTrue(snap.toString().contains("opening fixture"))
        val effects = snap.getJSONArray("outward_actions")
        assertEquals(3, effects.length()); assertEquals("success", effects.getJSONObject(0).getString("return"))
        assertEquals("unknown", effects.getJSONObject(1).getString("return"))
        assertEquals("fixture outward operation", effects.getJSONObject(0).getString("preview"))
        assertEquals("unknown", snap.getJSONArray("other_commands").getJSONObject(0).getString("return"))
        assertEquals("new requirement not read", snap.getJSONArray("unread_instructions").getJSONObject(0).getString("text"))
        assertTrue(dao.unread(owner).single().text.contains(dao.log(root.id).last { it.kind == "END" }.body))
        assertEquals("STOPPED", dao.task(child.id)!!.outcome)
    }
    @Test fun aQueuedBriefIsPreservedEvenBeforeItHasAnInboxRow(): Unit = runBlocking {
        val row = task(state = "QUEUED"); mark(row.id, "PASSED_ON", "exact queued brief"); stop(row.id)
        assertEquals("exact queued brief", snapshot(row.id).getJSONArray("unread_instructions").getJSONObject(0).getString("text"))
    }
    @Test fun anExplicitNewAttemptDoesNotClaimOldEffectsAsNewWork(): Unit = runBlocking {
        val row = task(); action(row, "old"); mark(row.id, "END", "earlier failed attempt")
        mark(row.id, "STARTED", "new attempt"); action(row, "new"); stop(row.id)
        val effects = snapshot(row.id).getJSONArray("outward_actions")
        assertEquals(1, effects.length()); assertEquals("new", effects.getJSONObject(0).getString("id"))
    }
    @Test fun stopIsIdempotentAndItsStoredSnapshotDoesNotChangeWithLateFacts(): Unit = runBlocking {
        val row = task(); val action = action(row); stop(row.id)
        val original = dao.log(row.id).last { it.kind == "END" }.body
        stop(row.id); receive(action)
        assertEquals(1, dao.log(row.id).count { it.kind == "END" })
        assertEquals(original, dao.log(row.id).last { it.kind == "END" }.body)
        assertEquals(2, dao.inboxFor(owner).size)
    }
    @Test fun stopStateSnapshotAndNotificationRollBackTogether(): Unit = runBlocking {
        val row = task(); Inbox.post(dao, owner, "not read", row.id)
        assertTrue(runCatching { db.withTransaction {
            TaskEngine.stopIn(context, dao, after, row.id); error("injected before stop commit")
        } }.isFailure)
        assertEquals("RUNNING", dao.task(row.id)!!.state)
        assertTrue(dao.log(row.id).none { it.kind == "END" }); assertEquals("not read", dao.unread(owner).single().text)
    }
    @Test fun aKnownActionAfterUnknownIsReportedOnceWithoutChangingTheAsk(): Unit = runBlocking {
        val row = task(); val action = action(row); receive(action, "unobserved", unknown = true)
        val waiting = dao.task(row.id)!!; assertEquals("WAITING", waiting.state)
        val output = "full receipt ".repeat(400)
        assertEquals(owner, receive(action, output)); assertNull(receive(action, "duplicate"))
        assertNull(receive(action, "later uncertainty", unknown = true))
        assertEquals(waiting, dao.task(row.id)); assertEquals(1, late().size); assertTrue(late().single().text.endsWith(output))
        assertEquals(2, dao.log(row.id).count { it.kind == "RESULT" })
        assertEquals(1, dao.log(row.id).count { it.kind == "REPORT" && it.body.contains("late action") })
    }
    @Test fun aStoppedCommandCanReceiveAKnownReturnAfterUnknownOnlyOnce(): Unit = runBlocking {
        val row = task(); val command = command(row); stop(row.id)
        receive(row.id, command, unknown = true); receive(row.id, command); receive(row.id, command)
        assertEquals("STOPPED", dao.task(row.id)!!.outcome); assertEquals(1, dao.log(row.id).count { it.kind == "END" })
        assertEquals(2, dao.log(row.id).count { it.kind == "COMMAND_RESULT" }); assertEquals(1, late().size)
    }
    @Test fun anOldCommandReturnCannotCloseOrRewriteTheNewAttempt(): Unit = runBlocking {
        val row = task(); val command = command(row); receive(row.id, command, unknown = true)
        mark(row.id, "END", "old attempt ended"); mark(row.id, "STARTED", "explicit new attempt")
        val newer = row.copy(state = "WAITING", ask = """{"kind":"action_outcome_unknown","question":"a different new operation"}""")
        dao.updateTask(newer); receive(row.id, command)
        assertEquals(newer, dao.task(row.id)); assertEquals(1, dao.log(row.id).count { it.kind == "END" }); assertEquals(1, late().size)
    }
    @Test fun aLateFailureFromAnOldCommandDoesNotPoisonTheNewAttemptsCloseout(): Unit = runBlocking {
        val row = task(); val command = command(row)
        mark(row.id, "END", "old failed attempt"); mark(row.id, "STARTED", "explicit new attempt")
        db.withTransaction { TaskEngine.commandFinishedIn(context, dao, after, row.id, command, ToolExecutionResult("old late failure", false)) }
        val closed = TaskEngine.closeoutIn(dao, row.id, TaskEntity.Outcome.DONE, "new work succeeded")
        assertEquals(TaskEntity.Outcome.DONE, closed.outcome)
        assertFalse(closed.words.contains("old late failure"))
    }
    @Test fun lateActionResultAndOwnerNotificationRollBackTogether(): Unit = runBlocking {
        val row = task(); val action = action(row); stop(row.id)
        assertTrue(runCatching { db.withTransaction {
            TaskEngine.actionFinishedIn(dao, action, "observed", true); error("injected after late result")
        } }.isFailure)
        assertTrue(dao.log(row.id).none { it.kind == "RESULT" }); assertTrue(late().isEmpty())
        assertEquals(owner, receive(action)); assertEquals(1, late().size)
    }
    @Test fun aLateScreenResultRoutesToTheOriginalOwnerAndDoesNotReopenItsHelper(): Unit = runBlocking {
        val helper = repo.createSession("helper", parentSessionId = owner).id
        val parent = task("parent", helper); val child = task("screen", TaskEntity.SCREEN).copy(conversation = helper)
        dao.updateTask(child); mark(parent.id, "CHILD", child.id)
        mark(child.id, "SCREEN", ScreenWork.Spec(HandsOperator.Options("fixture", sessionId = helper), parent.id).json())
        stop(parent.id)
        repeat(2) { db.withTransaction { TaskEngine.screenFinishedIn(context, dao, after, child.id, screen()) } }
        assertEquals(1, late().size); assertEquals(parent.id, late().single().taskId); assertTrue(dao.inboxFor(helper).isEmpty())
        assertEquals("STOPPED", dao.task(parent.id)!!.outcome); assertEquals("STOPPED", dao.task(child.id)!!.outcome)
        assertEquals(1, dao.log(child.id).count { it.kind == "SCREEN_RESULT" })
    }
    @Test fun theExpectedScreenCancellationDoesNotCreateAnotherLateReport(): Unit = runBlocking {
        val row = task(hand = TaskEntity.SCREEN); stop(row.id)
        db.withTransaction { TaskEngine.screenFinishedIn(context, dao, after, row.id, screen(stopped = true)) }
        assertTrue(late().isEmpty()); assertEquals(1, dao.inboxFor(owner).size)
    }
    @Test fun aNormalActionReturnDoesNotCreateALateNotification(): Unit = runBlocking {
        val row = task(); assertNull(receive(action(row))); assertEquals("RUNNING", dao.task(row.id)!!.state); assertTrue(late().isEmpty())
    }
    @Test fun aDeletedTaskCannotPostALateActionReceipt(): Unit = runBlocking {
        val row = task(); val action = action(row); repo.deleteSession(owner)
        assertNull(receive(action)); assertTrue(dao.recentTasks(20).isEmpty())
    }
    @Test fun aLateReceiptFromAFailedTaskCannotAuthorizeNewOutwardWork(): Unit = runBlocking {
        val failed = task("failed", "helper"); dao.updateTask(failed.copy(state = "ENDED", outcome = "FAILED"))
        val running = task("running", "other-helper")
        val message = repo.appendMessage(owner, "user", "[]")
        dao.insertInbox(InboxEntity(message.id, owner, failed.id, "HAND", "[task failed · command · late_result] observed", arrivedAt = 1, readAt = 2))
        val binding = TaskGuard.Binding(owner, null, repo.appendMessage(owner, "assistant", "[]").id)
        assertNull(TaskGuard.checkIn(dao, binding))
        assertNotNull(TaskGuard.checkIn(dao, binding, outward = true))
        assertNotNull(TaskGuard.checkIn(dao, binding, outward = true, targetTaskId = failed.id))
        assertNull(TaskGuard.checkIn(dao, binding, outward = true, targetTaskId = running.id))
    }
    @Test fun anAutomaticStoppedChildReportCannotAuthorizeAReplayButIndependentWorkCan(): Unit = runBlocking {
        val child = task("stopped", "helper"); dao.updateTask(child.copy(state = "ENDED", outcome = "STOPPED"))
        val sibling = task("sibling", "other-helper")
        val message = repo.appendMessage(owner, "user", "[]")
        dao.insertInbox(InboxEntity(message.id, owner, child.id, "HAND", "late report", arrivedAt = 1, readAt = 2))
        val call = repo.appendMessage(owner, "assistant", "[]").id; val binding = TaskGuard.Binding(owner, null, call)
        assertNotNull(TaskGuard.checkIn(dao, binding, outward = true))
        assertNull(TaskGuard.checkIn(dao, binding, outward = true, targetTaskId = sibling.id))
        val human = repo.appendMessage(owner, "user", "[]")
        dao.insertInbox(InboxEntity(human.id, owner, sender = "USER", text = "new request", arrivedAt = 3, readAt = 4))
        val independent = repo.appendMessage(owner, "assistant", "[]").id
        assertNull(TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, independent), outward = true))
    }
}
