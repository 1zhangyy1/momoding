package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.runtime.local.PiChildAgentEventEnvelope
import app.momoding.core.runtime.local.PiChildAgentSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalChildAgentRepositoryTest {
    private lateinit var database: MomodingDatabase
    private var now = 1_000L
    private lateinit var repository: PhoneLocalChildAgentRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.p2Dao().upsertTask(task())
        repository = PhoneLocalChildAgentRepository(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `native events and authoritative snapshot persist and reopen without translation`() = runTest {
        val running = child(parentToolCallId = "delegate-a", state = "running", eventCount = 2)
        val firstEvent = buildJsonObject { put("type", "agent_start"); put("nativeValue", "kept") }
        val secondEvent = buildJsonObject { put("type", "message_start"); put("messageId", "m-1") }
        val events = listOf(event(running, 0, firstEvent), event(running, 1, secondEvent))
        repository.persistRuntimeUpdate(
            events = events,
            snapshots = listOf(running),
        )
        repository.persistRuntimeUpdate(events = events, snapshots = listOf(running))

        val storedEvents = repository.childEvents(TASK_ID, "delegate-a")
        assertEquals(listOf(0, 1), storedEvents.map { it.eventOrdinal })
        assertEquals(firstEvent.toString(), storedEvents.first().eventJson)
        assertEquals("agent_start", storedEvents.first().eventType)
        assertEquals(64, storedEvents.first().digest.length)

        now += 100
        repository.persistRuntimeUpdate(
            events = emptyList(),
            snapshots = listOf(
                running.copy(
                    state = "completed",
                    resultSummary = "Dependency is pinned.",
                    resultText = "**Dependency** is pinned.",
                    stopReason = "stop",
                    model = "deepseek/deepseek-v4-pro",
                    turnCount = 1,
                    inputTokens = 21,
                    outputTokens = 8,
                    contextTokens = 29,
                ),
            ),
        )
        val detail = requireNotNull(TaskDetailRepository(database).observe(TASK_ID).first())
        val child = detail.childAgents.single()
        assertEquals("COMPLETED", child.state)
        assertEquals("**Dependency** is pinned.", child.resultText)
        assertEquals(21, child.inputTokens)
        assertEquals(1_000L, database.p2Dao().taskChildAgent(TASK_ID, "delegate-a")!!.createdAtMillis)
        assertEquals(1_100L, database.p2Dao().taskChildAgent(TASK_ID, "delegate-a")!!.updatedAtMillis)
    }

    @Test
    fun `runtime child id reuse cannot overwrite another parent tool call`() {
        repository.persistRuntimeUpdate(emptyList(), listOf(child("delegate-a", "running", childId = "child-1")))
        repository.persistRuntimeUpdate(emptyList(), listOf(child("delegate-b", "running", childId = "child-1")))

        assertEquals(
            listOf("delegate-a", "delegate-b"),
            database.p2Dao().taskChildAgents(TASK_ID).map { it.parentToolCallId },
        )
    }

    @Test
    fun `terminal state is monotonic and cannot return to running or switch terminal kind`() {
        val completed = child("delegate-a", "completed")
        repository.persistRuntimeUpdate(emptyList(), listOf(completed))

        assertThrows(IllegalArgumentException::class.java) {
            repository.persistRuntimeUpdate(emptyList(), listOf(completed.copy(state = "running")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.persistRuntimeUpdate(
                emptyList(),
                listOf(completed.copy(state = "failed", terminalReason = "failed")),
            )
        }
        assertEquals("COMPLETED", database.p2Dao().taskChildAgent(TASK_ID, "delegate-a")!!.state)
    }

    @Test
    fun `destroyed runtime recovery cancels running children once without recreating work`() {
        repository.persistRuntimeUpdate(emptyList(), listOf(child("delegate-a", "running")))

        now += 1
        assertEquals(1, repository.recoverDestroyedRuntime())
        assertEquals(0, repository.recoverDestroyedRuntime())
        val recovered = database.p2Dao().taskChildAgent(TASK_ID, "delegate-a")!!
        assertEquals("CANCELLED", recovered.state)
        assertEquals("runtime_rebuilt", recovered.terminalReason)
        assertEquals("aborted", recovered.stopReason)
        assertTrue(repository.childEvents(TASK_ID, "delegate-a").isEmpty())
    }

    private fun child(
        parentToolCallId: String,
        state: String,
        childId: String = "child-1",
        eventCount: Int = 0,
    ) = PiChildAgentSnapshot(
        parentTaskId = TASK_ID,
        parentToolCallId = parentToolCallId,
        childId = childId,
        childName = "Build analyst",
        instruction = "Analyze build reproducibility.",
        state = state,
        eventTypes = if (eventCount == 0) emptyList() else listOf("agent_start", "message_start"),
        eventCount = eventCount,
    )

    private fun event(
        snapshot: PiChildAgentSnapshot,
        ordinal: Int,
        payload: kotlinx.serialization.json.JsonObject,
    ) =
        PiChildAgentEventEnvelope(
            parentTaskId = snapshot.parentTaskId,
            parentToolCallId = snapshot.parentToolCallId,
            childId = snapshot.childId,
            childName = snapshot.childName,
            eventOrdinal = ordinal,
            event = payload,
        )

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Child Agent task",
        runState = "RUNNING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = true,
        updatedAtMillis = 1,
    )

    private companion object {
        const val TASK_ID = "e6000000-0000-4000-8000-000000000003"
    }
}
