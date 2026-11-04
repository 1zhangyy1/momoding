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
            RiskGate.open.value.filter { it.sessionId == chat }.forEach { RiskGate.decide(it.id, RiskDecision.DENY) }
            withTimeout(5_000) { while (RiskGate.open.value.any { it.sessionId == chat }) delay(25) }
            Grants.clearSession(chat)
            app.database.chatDao().deleteSession(chat)
        }
    }

    private suspend fun request() = RiskGate.check(chat, GuardKind.SHELL,
        RiskAssessment(RiskClass.DESTRUCTIVE, "fixture only; no command is executed", "/tmp/m41-guard-fixture"),
        preview = "fixture only", taskId = task.id)

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

    @Test fun aHoldAddedWhileTheCardWaitsStillBlocksTheApprovedAction() = runBlocking {
        val answer = async(Dispatchers.Default) { request() }
        val pending = withTimeout(5_000) { RiskGate.open.first { all -> all.any { it.sessionId == chat } }.single { it.sessionId == chat } }
        app.database.taskDao().updateTask(task.copy(hold = "ask before changing anything"))
        RiskGate.decide(pending.id, RiskDecision.ALLOW_ONCE)
        val result = withTimeout(5_000) { answer.await() }
        assertTrue(result is GateOutcome.Denied && result.message.contains("ask before changing anything"))
    }
}
