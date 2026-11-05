package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.scheduled.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoutineDeliveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var owner: String
    private val dao get() = db.taskDao()
    private val parts = """[{"type":"text","value":"owned routine input"}]"""
    private val ownedDefinitions = mutableListOf<String>()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao()); owner = repo.createSession("fixture", "routine delivery").id
    }
    @After fun close() { ownedDefinitions.forEach { ScheduledTaskManager(context).delete(it) }; db.close() }
    private suspend fun input(id: String = "fire", origin: String = id, sender: String = "ROUTINE"): String {
        val body = RoutineReceipt.frame(parts, RoutineFire("owned", 123, true, origin))
        repo.appendMessage(owner, "user", body, id = id)
        dao.insertInbox(InboxEntity(id, owner, sender = sender, text = "owned", partsJson = body, arrivedAt = 1, readAt = 2))
        return id
    }
    private suspend fun reply() = TaskEngine.replyIn(context, dao, mutableListOf(), owner, null, TaskCompletion(true, "actual answer"), false) {
        repo.appendMessage(owner, "assistant", """[{"type":"text","value":"actual answer"}]""")
    }
    @Test fun replyAndReceiptCommitBeforeTheInputCursorIsRemoved(): Unit = runBlocking {
        val id = input(); val answer = db.withTransaction { reply() }
        val result = RoutineReceipt.result(dao.routineMessage(id)!!.partsJson)!!
        assertEquals(answer.id, result.response); assertTrue(result.done); assertFalse(result.recorded)
        assertNull(dao.inbox(id)); assertEquals(listOf(id), dao.routineResultsFlow().first().map { it.id })
        assertEquals("actual answer", RoutineReceipt.text(dao.routineMessage(result.response!!)!!.partsJson))
    }
    @Test fun replyFailureRollsBackReceiptAndCursorTogether(): Unit = runBlocking {
        val id = input()
        assertTrue(runCatching { db.withTransaction { reply(); error("fault after receipt") } }.isFailure)
        assertNull(RoutineReceipt.result(dao.routineMessage(id)!!.partsJson)); assertNotNull(dao.inbox(id)?.readAt)
        assertEquals(listOf(id), db.chatDao().loadMessages(owner).map { it.id })
    }
    @Test fun aNormalMainReplyCompletesItsRoutineWithoutADelegatedBotReport(): Unit = runBlocking {
        val id = input()
        db.withTransaction { TaskEngine.replyIn(context, dao, mutableListOf(), owner, null,
            TaskCompletion.afterTurn(false, "actual answer", null), false) {
            repo.appendMessage(owner, "assistant", """[{"type":"text","value":"actual answer"}]""")
        } }
        assertTrue(RoutineReceipt.result(dao.routineMessage(id)!!.partsJson)!!.done)
    }
    @Test fun metadataCannotBindADifferentReceiveOrAHumanInput(): Unit = runBlocking {
        val bad = input("bad", "another-id"); val human = input("human", sender = "USER")
        db.withTransaction { reply() }
        assertNull(RoutineReceipt.result(dao.routineMessage(bad)!!.partsJson))
        assertNull(RoutineReceipt.result(dao.routineMessage(human)!!.partsJson))
    }
    @Test fun anOldAcknowledgementCannotHideANewerRetryResult(): Unit = runBlocking {
        val id = input(); val first = db.withTransaction { reply() }
        dao.insertInbox(InboxEntity(id, owner, sender = "ROUTINE", text = "retry", arrivedAt = 3, readAt = 4))
        val second = db.withTransaction { reply() }
        db.withTransaction { RoutineReceipt.acknowledgeIn(dao, id, first.id) }
        assertFalse(RoutineReceipt.result(dao.routineMessage(id)!!.partsJson)!!.recorded)
        db.withTransaction { RoutineReceipt.acknowledgeIn(dao, id, second.id) }
        assertTrue(dao.routineResultsFlow().first().isEmpty())
    }
    @Test fun failedInputHasAStableReceiptWithoutPretendingThereWasAReply(): Unit = runBlocking {
        val id = input(); dao.failRead(owner, 99, true)
        val row = dao.inbox(id)!!
        db.withTransaction { RoutineReceipt.completeIn(dao, listOf(row), TaskCompletion(false, "failed"), null) }
        val first = RoutineReceipt.result(dao.routineMessage(id)!!.partsJson)!!
        db.withTransaction { RoutineReceipt.acknowledgeIn(dao, id, first.key); RoutineReceipt.completeIn(dao, listOf(row), TaskCompletion(false, "failed"), null) }
        val last = RoutineReceipt.result(dao.routineMessage(id)!!.partsJson)!!
        assertEquals("failed:99", last.key); assertTrue(last.recorded); assertFalse(last.done); assertNull(last.response)
        assertNotNull(dao.inbox(id)); assertFalse(dao.hasRoutineInput("\"routine\":\"owned\""))
    }
    @Test fun replayingAChildFireDoesNotCreateAnotherHandOrInput(): Unit = runBlocking {
        val after = mutableListOf<suspend () -> Unit>(); val fire = RoutineFire("owned", 123, true, "dispatch")
        suspend fun open() = db.withTransaction { TaskEngine.openHelperIn(context, dao, after, owner, "owned", "brief", emptyList(), fire, null) {
            repo.createSession("fixture", parentSessionId = owner).id
        } }
        val first = open(); assertEquals(first, open())
        assertEquals(1, db.chatDao().childSessionIds(owner).size); assertEquals(1, dao.inboxFor(first.hand).size); assertEquals(1, after.size)
    }
    @Test fun completionClaimKeepsItsExactInputUntilTheMatchingAcknowledgement(): Unit = runBlocking {
        val manager = ScheduledTaskManager(context)
        val task = ScheduledTask(label = "owned delivery test", timeOfDayHour = 0, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE, prompt = "result: {{result}}", triggerKind = ScheduledTriggerKind.ON_COMPLETION,
            onCompletionOf = "missing owned upstream", targetMode = ScheduledTargetMode.AppendToSession("missing owned target"))
        ownedDefinitions += task.id; manager.create(task)
        val pending = manager.claimCompletionFire(task.id, "full upstream")!!
        assertNull(manager.claimCompletionFire(task.id, "different"))
        assertEquals("result: full upstream", pending.task.prompt)
        assertEquals(pending, ScheduledTaskStore(context).get(task.id)!!.pendingDispatch)
        assertFalse(manager.acknowledgeDispatch(task.id, "unrelated")); assertNotNull(manager.get(task.id)!!.pendingDispatch)
        assertTrue(manager.acknowledgeDispatch(task.id, pending.id)); assertNull(manager.get(task.id)!!.pendingDispatch)
        assertEquals(1, manager.get(task.id)!!.triggeredCount); assertFalse(manager.get(task.id)!!.enabled)
    }
    @Test fun editingPreservesThePendingPayloadAndExplicitDisableCancelsIt(): Unit = runBlocking {
        val manager = ScheduledTaskManager(context)
        val task = ScheduledTask(label = "owned pending edit", timeOfDayHour = 0, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE, prompt = "original", triggerKind = ScheduledTriggerKind.ON_COMPLETION,
            onCompletionOf = "missing owned upstream", targetMode = ScheduledTargetMode.AppendToSession("missing owned target"))
        ownedDefinitions += task.id; manager.create(task); val pending = manager.claimCompletionFire(task.id, null)!!
        manager.update(task.copy(prompt = "edited")); assertEquals(pending, manager.get(task.id)!!.pendingDispatch)
        manager.setEnabled(task.id, false); assertNull(manager.get(task.id)!!.pendingDispatch)
        manager.setEnabled(task.id, true); assertEquals(0, manager.get(task.id)!!.triggeredCount)
        val next = manager.claimCompletionFire(task.id, null)!!; assertNotEquals(pending.id, next.id); assertEquals("edited", next.task.prompt)
    }
}
