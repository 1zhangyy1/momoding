package app.momoding.task

import org.junit.Assert.*
import org.junit.Test

class TaskClockTest {
    private fun task(state: String, ask: String? = null) =
        TaskEntity("t", "chat", title = "fixture", hand = "chat", state = state, ask = ask, createdAt = 0, updatedAt = 150)
    private fun mark(at: Long, kind: String, body: String = "started") =
        TaskLogEntity(taskId = "t", at = at, kind = kind, body = body)

    @Test fun pausesAndQueuedContinuationsKeepTheSameSpentTime() {
        val history = listOf(mark(0, "STARTED"), mark(100, "PAUSED", """{"elapsed_ms":100}"""))
        assertEquals(100L, TaskClock.elapsed(task("WAITING"), history, 600))
        assertEquals(100L, TaskClock.elapsed(task("QUEUED"), history, 600))
        val resumed = history + mark(600, "STARTED", """{"elapsed_ms":100}""")
        assertEquals(125L, TaskClock.elapsed(task("RUNNING"), resumed, 625))
        assertEquals(125L, TaskClock.elapsed(task("WAITING"), resumed + mark(625, "PAUSED", """{"elapsed_ms":125}"""), 10_000))
    }

    @Test fun timeBeforeTheFirstStartDoesNotConsumeAQueuedTasksBudget() {
        assertEquals(0L, TaskClock.elapsed(task("QUEUED"), emptyList(), 100_000))
    }

    @Test fun legacyApprovalsAndWaitingRowsRemainReadable() {
        val history = listOf(mark(100, "STARTED"))
        assertEquals(40L, TaskClock.elapsed(task("WAITING", """{"elapsed_ms":40}"""), history, 100_000))
        assertEquals(50L, TaskClock.elapsed(task("WAITING", """{"question":"scan"}"""), history, 100_000))
    }

    @Test fun completionStopsTheClockAndALaterAttemptGetsItsOwnStart() {
        val history = listOf(mark(100, "STARTED"), mark(200, "END", "failed"))
        assertEquals(100L, TaskClock.elapsed(task("ENDED"), history, 10_000))
        val retry = history + mark(500, "STARTED", """{"elapsed_ms":0}""") + mark(550, "END", "done")
        assertEquals(50L, TaskClock.elapsed(task("ENDED"), retry, 10_000))
    }

    @Test fun progressAndInstructionsDoNotResetTheClock() {
        val history = listOf(mark(100, "STARTED"), mark(120, "REPORT", "working"), mark(130, "PASSED_ON", "also check tomorrow"))
        assertEquals(100L, TaskClock.elapsed(task("RUNNING"), history, 200))
    }
}
