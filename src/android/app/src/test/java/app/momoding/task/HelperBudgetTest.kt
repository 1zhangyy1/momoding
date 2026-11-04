package app.momoding.task

import app.momoding.prompt.MomodingHelper
import com.openminis.app.agent.jobs.HelperRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HelperBudgetTest {
    @Test fun `a slow helper first gets a chance to deliver then expires`() = runTest {
        val events = mutableListOf<String>()
        Helpers.watchBudget(this, { events += "wrap up" }, { events += "expired" })
        runCurrent()
        advanceTimeBy(MomodingHelper.MAX_MINUTES * 60_000L - 1)
        runCurrent()
        assertEquals(emptyList<String>(), events)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("wrap up"), events)
        advanceTimeBy(HelperRunner.WRAP_UP_GRACE_MS)
        runCurrent()
        assertEquals(listOf("wrap up", "expired"), events)
    }

    @Test fun `a completed run has no delayed cancellation of its next task`() = runTest {
        val events = mutableListOf<String>()
        val budget = Helpers.watchBudget(this, { events += "wrap up" }, { events += "expired" })
        runCurrent()
        budget.cancel()
        advanceTimeBy(MomodingHelper.MAX_MINUTES * 60_000L + HelperRunner.WRAP_UP_GRACE_MS + 1)
        runCurrent()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun `resuming after a command uses the remaining task budget`() = runTest {
        val events = mutableListOf<String>()
        val remaining = 20_000L
        Helpers.watchBudget(this, { events += "wrap up" }, { events += "expired" },
            elapsedMs = MomodingHelper.MAX_MINUTES * 60_000L - remaining)
        advanceTimeBy(remaining)
        runCurrent()
        assertEquals(listOf("wrap up"), events)
        advanceTimeBy(HelperRunner.WRAP_UP_GRACE_MS)
        runCurrent()
        assertEquals(listOf("wrap up", "expired"), events)
    }

    @Test fun `resuming during grace does not grant a fresh grace period`() = runTest {
        val events = mutableListOf<String>()
        val remaining = 500L
        Helpers.watchBudget(this, { events += "wrap up" }, { events += "expired" },
            elapsedMs = MomodingHelper.MAX_MINUTES * 60_000L + HelperRunner.WRAP_UP_GRACE_MS - remaining)
        runCurrent()
        assertEquals(listOf("wrap up"), events)
        advanceTimeBy(remaining)
        runCurrent()
        assertEquals(listOf("wrap up", "expired"), events)
    }
}
