package app.momoding.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.guard.*
import com.openminis.app.MinisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks only the gate, never executes the described command or grants system permission. */
@RunWith(AndroidJUnit4::class)
class RiskGateTaskTest {
    private lateinit var app: MinisApp
    private lateinit var chat: String
    private lateinit var task: TaskEntity

    @Before fun setup() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }
        chat = app.chatRepository.createSession("M41 task guard gate fixture").id
        task = TaskEntity(java.util.UUID.randomUUID().toString(), chat, title = "gate fixture", hand = chat,
            state = "RUNNING", createdAt = 1, updatedAt = 1)
        app.database.taskDao().insertTask(task)
    }

    @After fun cleanup() = runBlocking {
        if (::chat.isInitialized) {
            app.database.chatDao().deleteSession(chat)
            withTimeout(5_000) { while (RiskGate.open.value.any { it.sessionId == chat }) delay(25) }
            Grants.clearSession(chat)
        }
    }

    private suspend fun request() = RiskGate.check(chat, GuardKind.SHELL,
        RiskAssessment(RiskClass.DESTRUCTIVE, "fixture only; no command is executed", "/tmp/m41-guard-fixture"),
        preview = "fixture only", taskId = task.id, shellArgs = """{"command":"printf fixture"}""")

    @Test fun aRememberedGrantStillSuppliesTheExactActionForJournaling(): Unit = runBlocking {
        Grants.grantSession(RiskClass.DESTRUCTIVE, chat, label = "M41 journal fixture")
        val allowed = request() as GateOutcome.Allowed
        assertNotNull(allowed.effect)
        val action = RiskGate.started(allowed)!!
        assertEquals(task.id, action.taskId)
        assertEquals(1, app.database.taskDao().log(task.id).count { it.kind == "ABOUT_TO" })
        RiskGate.finished(action, "Fake executor result; no system command was executed.", true)
        assertEquals(1, app.database.taskDao().log(task.id).count { it.kind == "RESULT" })
    }

    @Test fun approvingAfterStopCannotActForTheNextTask() = runBlocking {
        val answer = async(Dispatchers.Default) { request() }
        val pending = withTimeout(5_000) { RiskGate.open.first { all -> all.any { it.sessionId == chat } }.single { it.sessionId == chat } }
        app.database.taskDao().updateTask(task.copy(state = "ENDED", outcome = "STOPPED"))
        val next = task.copy(id = java.util.UUID.randomUUID().toString(), title = "next")
        app.database.taskDao().insertTask(next)
        RiskGate.decide(pending.id, RiskDecision.ALLOW_ONCE)
        val result = withTimeout(5_000) { answer.await() }
        assertTrue(result is GateOutcome.Denied && result.message.contains("stopped"))
        assertEquals("RUNNING", app.database.taskDao().task(next.id)!!.state)
    }

    @Test fun aQuestionAfterClaimSettlesTheCommandWithoutStartingAnAction() = runBlocking {
        val answer = async(Dispatchers.Default) { request() }
        val pending = withTimeout(5_000) { RiskGate.open.first { all -> all.any { it.sessionId == chat } }.single { it.sessionId == chat } }
        RiskGate.decide(pending.id, RiskDecision.ALLOW_ONCE)
        val allowed = withTimeout(5_000) { answer.await() } as GateOutcome.Allowed
        val dao = app.database.taskDao()
        assertEquals(1, ShellWork.pending(dao.log(task.id)).size)
        val question = """{"question":"Which account should be used?"}"""
        val current = dao.task(task.id)!!
        dao.updateTask(current.copy(state = "WAITING", ask = question))
        try {
            RiskGate.started(allowed)
            fail("A question added after claim must still prevent the action")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("waiting for the person"))
        }
        val history = dao.log(task.id)
        assertTrue(ShellWork.pending(history).isEmpty())
        assertEquals(1, history.count { it.kind == "COMMAND_RESULT" })
        assertFalse(history.any { it.kind == "ABOUT_TO" || it.kind == "RESULT" || it.kind == "END" })
        assertEquals(question, dao.task(task.id)!!.ask)
        dao.updateTask(dao.task(task.id)!!.copy(state = "RUNNING", ask = null))
        try {
            RiskGate.started(allowed)
            fail("The settled approval may not be reused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already settled"))
        }
    }

    @Test fun aBlockedDuplicateCannotSettleTheExecutorThatAlreadyStarted() = runBlocking {
        val answer = async(Dispatchers.Default) { request() }
        val pending = withTimeout(5_000) { RiskGate.open.first { all -> all.any { it.sessionId == chat } }.single { it.sessionId == chat } }
        RiskGate.decide(pending.id, RiskDecision.ALLOW_ONCE)
        val allowed = withTimeout(5_000) { answer.await() } as GateOutcome.Allowed
        val first = RiskGate.started(allowed)!!
        val dao = app.database.taskDao()
        dao.updateTask(dao.task(task.id)!!.copy(hold = "Wait here"))
        try {
            RiskGate.started(allowed)
            fail("The duplicate must be blocked")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("on hold"))
        }
        assertEquals(1, ShellWork.pending(dao.log(task.id)).size)
        assertFalse(dao.log(task.id).any { it.kind == "COMMAND_RESULT" || it.kind == "RESULT" })
        RiskGate.finished(first, "The first fake executor returned; no system command was executed.", true)
        assertTrue(ShellWork.pending(dao.log(task.id)).isEmpty())
        assertEquals(1, dao.log(task.id).count { it.kind == "RESULT" })
    }

    @Test fun aDeletedInvocationAfterClaimSettlesAsNotStarted(): Unit = runBlocking {
        val source = app.chatRepository.appendMessage(chat, "assistant",
            """[{"type":"toolUse","value":{"toolUseId":"deleted-call","name":"shell_execute","input":"{}"}}]""")
        val answer = async(Dispatchers.Default) { request() }
        val pending = withTimeout(5_000) { RiskGate.open.first { all -> all.any { it.sessionId == chat } }.single { it.sessionId == chat } }
        RiskGate.decide(pending.id, RiskDecision.ALLOW_ONCE)
        val allowed = withTimeout(5_000) { answer.await() } as GateOutcome.Allowed
        app.database.chatDao().deleteMessagesAfter(chat, source.sortOrder)
        val failed = runCatching { withContext(ToolCall(source.id, "deleted-call", "shell_execute")) { RiskGate.started(allowed) } }
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("deleted"))
        val history = app.database.taskDao().log(task.id)
        assertTrue(ShellWork.pending(history).isEmpty())
        assertFalse(history.any { it.kind == "ABOUT_TO" || it.kind == "RESULT" })
        val result = org.json.JSONObject(history.single { it.kind == "COMMAND_RESULT" }.body)
        assertFalse(result.getBoolean("success"))
        assertFalse(result.has("unknown"))
    }

    @Test fun aHoldAddedWhileTheCardWaitsStillBlocksTheApprovedAction() = runBlocking {
        val answer = async(Dispatchers.Default) { request() }
        val pending = withTimeout(5_000) { RiskGate.open.first { all -> all.any { it.sessionId == chat } }.single { it.sessionId == chat } }
        app.database.taskDao().updateTask(app.database.taskDao().task(task.id)!!.copy(hold = "ask before changing anything"))
        RiskGate.decide(pending.id, RiskDecision.ALLOW_ONCE)
        val result = withTimeout(5_000) { answer.await() }
        assertTrue(result is GateOutcome.Denied && result.message.contains("ask before changing anything"))
    }
}
