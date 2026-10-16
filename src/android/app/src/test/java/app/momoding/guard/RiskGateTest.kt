package app.momoding.guard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The approval queue: one card at a time, and a caller that goes away takes its card with it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RiskGateTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun reset() { Dispatchers.resetMain() }

    private fun request(preview: String) = RiskRequest(
        sessionId = "s1",
        kind = GuardKind.SHELL,
        assessment = RiskAssessment(RiskClass.DESTRUCTIVE, "deletes $preview", "/var/minis/workspace"),
        preview = preview,
    )

    @Test fun `a decision answers the asker and records it`() = runTest(dispatcher) {
        val req = request("rm a")
        val answer = async { RiskGate.ask(req) }
        runCurrent()
        assertEquals(req.id, RiskGate.pending.value?.id)
        RiskGate.decide(req.id, RiskDecision.ALLOW_ONCE)
        runCurrent()
        assertEquals(RiskDecision.ALLOW_ONCE, answer.await())
        assertNull(RiskGate.pending.value)
        assertEquals(RiskDecision.ALLOW_ONCE, RiskGate.recent.value.first { it.first.id == req.id }.second)
    }

    @Test fun `cancelling the asker withdraws the card and the next one comes up`() = runTest(dispatcher) {
        val first = request("rm b")
        val second = request("rm c")
        val job = launch { RiskGate.ask(first) }
        runCurrent()
        val waiting = async { RiskGate.ask(second) }
        runCurrent()
        assertEquals(first.id, RiskGate.pending.value?.id)
        assertEquals(1, RiskGate.queued.value)

        job.cancel()
        runCurrent()
        assertEquals(second.id, RiskGate.pending.value?.id)
        assertEquals(0, RiskGate.queued.value)
        assertTrue(RiskGate.recent.value.none { it.first.id == first.id })

        RiskGate.decide(second.id, RiskDecision.DENY)
        runCurrent()
        assertEquals(RiskDecision.DENY, waiting.await())
        assertNull(RiskGate.pending.value)
    }

    @Test fun `cancelling a queued asker drops it from the line`() = runTest(dispatcher) {
        val first = request("rm d")
        val second = request("rm e")
        val front = async { RiskGate.ask(first) }
        runCurrent()
        val behind = launch { RiskGate.ask(second) }
        runCurrent()
        assertEquals(1, RiskGate.queued.value)

        behind.cancel()
        runCurrent()
        assertEquals(first.id, RiskGate.pending.value?.id)
        assertEquals(0, RiskGate.queued.value)

        RiskGate.decide(first.id, RiskDecision.ALLOW_ONCE)
        runCurrent()
        assertEquals(RiskDecision.ALLOW_ONCE, front.await())
        assertNull(RiskGate.pending.value)
    }
}
