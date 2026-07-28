package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.runtime.local.PiTaskGoalSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalGoalRepositoryTest {
    private lateinit var database: MomodingDatabase
    private var now = 1_000L
    private lateinit var repository: PhoneLocalGoalRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.momodingDao().upsertTask(settledTask())
        repository = PhoneLocalGoalRepository(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create progress pause resume achieve clear and edit are durable generations`() {
        val created = repository.create(TASK_ID, GOAL_ID, " Verify two checkpoints. ")
        assertEquals(PhoneLocalGoalState.ACTIVE, created.state)
        assertEquals("Verify two checkpoints.", created.instruction)
        assertEquals(1, created.generation)
        assertTrue(created.nextTurnClaimed)
        assertEquals(0, beginTurn(created, turnIndex = 0, automatic = false).lastTurnIndex)
        finishTaskTurn()

        repository.syncRuntime(
            TASK_ID,
            runtimeGoal(
                state = "active",
                progressSummary = "First checkpoint passed.",
                progressMarker = "1/2",
            ),
        )
        val afterFirstTurn = requireNotNull(repository.settleTurn(TASK_ID, stopped = false, failed = false))
        assertEquals("1/2", afterFirstTurn.progressMarker)
        assertFalse(afterFirstTurn.nextTurnClaimed)

        val continuation = repository.claimAutomaticContinuation(TASK_ID)
        assertTrue(continuation is GoalContinuationDecision.Continue)
        assertEquals(1, (continuation as GoalContinuationDecision.Continue).turnIndex)
        val automatic = beginTurn(continuation.goal, turnIndex = 1, automatic = true)
        assertEquals(1, automatic.automaticTurnCount)

        val pending = repository.requestPause(TASK_ID)
        assertEquals(PhoneLocalGoalState.PAUSE_PENDING, pending.state)
        finishTaskTurn()
        val paused = requireNotNull(repository.settleTurn(TASK_ID, stopped = false, failed = false))
        assertEquals(PhoneLocalGoalState.PAUSED, paused.state)
        assertEquals("user_requested", paused.pauseReason)

        val resumed = repository.claimResume(TASK_ID)
        assertEquals(PhoneLocalGoalState.ACTIVE, resumed.state)
        assertTrue(resumed.nextTurnClaimed)
        val resumedTurn = beginTurn(resumed, turnIndex = 2, automatic = false)
        assertEquals(1, resumedTurn.automaticTurnCount)
        assertEquals(2, resumedTurn.lastTurnIndex)
        repository.syncRuntime(
            TASK_ID,
            runtimeGoal(
                state = "achieved",
                progressSummary = "Both checkpoints passed.",
                progressMarker = "2/2",
                terminalReason = "achieved",
            ),
        )
        finishTaskTurn()
        val achieved = requireNotNull(repository.settleTurn(TASK_ID, stopped = false, failed = false))
        assertEquals(PhoneLocalGoalState.ACHIEVED, achieved.state)
        assertFalse(achieved.nextTurnClaimed)

        assertEquals(PhoneLocalGoalState.CLEARED, repository.clear(TASK_ID).state)
        now += 1_000
        val edited = repository.edit(TASK_ID, "Verify three checkpoints.")
        assertEquals(PhoneLocalGoalState.ACTIVE, edited.state)
        assertEquals(2, edited.generation)
        assertEquals(0, edited.automaticTurnCount)
        assertEquals(null, edited.progressMarker)
    }

    @Test
    fun `automatic continuation stops after twenty claimed turns`() {
        val created = repository.create(TASK_ID, GOAL_ID, "Keep verifying safely.")
        beginTurn(created, turnIndex = 0, automatic = false)
        finishTaskTurn()
        repository.settleTurn(TASK_ID, stopped = false, failed = false)

        repeat(PhoneLocalGoalRepository.MAX_AUTOMATIC_TURNS) { index ->
            val decision = repository.claimAutomaticContinuation(TASK_ID)
            assertTrue(decision is GoalContinuationDecision.Continue)
            assertEquals(index + 1, (decision as GoalContinuationDecision.Continue).turnIndex)
            beginTurn(decision.goal, turnIndex = index + 1, automatic = true)
            finishTaskTurn()
            repository.settleTurn(TASK_ID, stopped = false, failed = false)
        }

        val limited = repository.claimAutomaticContinuation(TASK_ID)
        assertTrue(limited is GoalContinuationDecision.Limit)
        val goal = (limited as GoalContinuationDecision.Limit).goal
        assertEquals(PhoneLocalGoalState.LIMITED, goal.state)
        assertEquals("limit_reached", goal.terminalReason)
    }

    @Test
    fun `automatic continuation stops at sixty minutes`() {
        val created = repository.create(TASK_ID, GOAL_ID, "Keep verifying safely.")
        beginTurn(created, turnIndex = 0, automatic = false)
        finishTaskTurn()
        repository.settleTurn(TASK_ID, stopped = false, failed = false)
        now += PhoneLocalGoalRepository.MAX_DURATION_MILLIS

        val limited = repository.claimAutomaticContinuation(TASK_ID)
        assertTrue(limited is GoalContinuationDecision.Limit)
        assertEquals(
            PhoneLocalGoalState.LIMITED,
            (limited as GoalContinuationDecision.Limit).goal.state,
        )
    }

    @Test
    fun `runtime rebuild pauses claimed work and never creates an automatic continuation`() {
        repository.create(TASK_ID, GOAL_ID, "Continue only after explicit resume.")

        assertEquals(1, repository.recoverInterruptedGoals())
        val recovered = requireNotNull(repository.current(TASK_ID))
        assertEquals(PhoneLocalGoalState.PAUSED, recovered.state)
        assertEquals("runtime_rebuilt", recovered.pauseReason)
        assertFalse(recovered.nextTurnClaimed)
        assertEquals(GoalContinuationDecision.None, repository.claimAutomaticContinuation(TASK_ID))
    }

    @Test
    fun `durable start fence gives pause an exact before or after turn boundary`() {
        val created = repository.create(TASK_ID, GOAL_ID, "Pause safely.")
        val begun = repository.beginClaimedTurn(
            TASK_ID,
            created.goalId,
            created.generation,
            turnIndex = 0,
            automatic = false,
        )
        assertEquals(PhoneLocalGoalState.ACTIVE, begun?.state)
        assertEquals("STARTING", database.momodingDao().task(TASK_ID)?.runState)

        val pending = repository.requestPause(TASK_ID)
        assertEquals(PhoneLocalGoalState.PAUSE_PENDING, pending.state)
        database.momodingDao().upsertTask(
            requireNotNull(database.momodingDao().task(TASK_ID)).copy(
                runState = "COMPLETED",
                isStreaming = false,
            ),
        )
        val paused = repository.finalizePendingPause(TASK_ID)
        assertEquals(PhoneLocalGoalState.PAUSED, paused?.state)
        assertFalse(paused?.nextTurnClaimed ?: true)
    }

    @Test
    fun `pause committed before the durable start fence cancels the claimed turn`() {
        val created = repository.create(TASK_ID, GOAL_ID, "Do not start after pause.")

        val paused = repository.requestPause(TASK_ID)
        assertEquals(PhoneLocalGoalState.PAUSED, paused.state)
        assertEquals(
            null,
            repository.beginClaimedTurn(
                TASK_ID,
                created.goalId,
                created.generation,
                turnIndex = 0,
                automatic = false,
            ),
        )
        assertEquals("COMPLETED", database.momodingDao().task(TASK_ID)?.runState)
    }

    @Test
    fun `replacement generation ignores only the prior non-active Pi transition snapshot`() {
        val first = repository.create(TASK_ID, GOAL_ID, "Verify two checkpoints.")
        beginTurn(first, turnIndex = 0, automatic = false)
        repository.syncRuntime(
            TASK_ID,
            runtimeGoal(
                state = "achieved",
                progressSummary = "First generation finished.",
                progressMarker = "2/2",
                terminalReason = "achieved",
            ),
        )
        finishTaskTurn()
        repository.settleTurn(TASK_ID, stopped = false, failed = false)

        val replacement = repository.edit(TASK_ID, "Verify the replacement generation.")
        assertEquals(2, replacement.generation)
        val ignored = requireNotNull(
            repository.syncRuntime(
                TASK_ID,
                runtimeGoal(
                    state = "achieved",
                    progressSummary = "First generation finished.",
                    progressMarker = "2/2",
                    terminalReason = "achieved",
                ),
            ),
        )
        assertEquals(2, ignored.generation)
        assertEquals(PhoneLocalGoalState.ACTIVE, ignored.state)

        val unsafePriorActive = runCatching {
            repository.syncRuntime(
                TASK_ID,
                runtimeGoal(
                    state = "active",
                    progressSummary = null,
                    progressMarker = null,
                ),
            )
        }
        assertTrue(unsafePriorActive.isFailure)
    }

    private fun runtimeGoal(
        state: String,
        progressSummary: String?,
        progressMarker: String?,
        terminalReason: String? = null,
    ) = PiTaskGoalSnapshot(
        goalId = GOAL_ID,
        instruction = "Verify two checkpoints.",
        state = state,
        progressSummary = progressSummary,
        progressMarker = progressMarker,
        terminalReason = terminalReason,
        generation = 1,
        startedAtMillis = 1_000L,
        preGoalActiveToolNames = listOf("read_file"),
    )

    private fun beginTurn(
        goal: PhoneLocalGoalRecord,
        turnIndex: Int,
        automatic: Boolean,
    ): PhoneLocalGoalRecord = requireNotNull(
        repository.beginClaimedTurn(
            taskId = TASK_ID,
            goalId = goal.goalId,
            generation = goal.generation,
            turnIndex = turnIndex,
            automatic = automatic,
        ),
    )

    private fun finishTaskTurn() {
        database.momodingDao().upsertTask(
            requireNotNull(database.momodingDao().task(TASK_ID)).copy(
                runState = "COMPLETED",
                isStreaming = false,
            ),
        )
    }

    private fun settledTask() = TaskEntity(
        taskId = TASK_ID,
        title = "Goal task",
        runState = "COMPLETED",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = "stream-goal",
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = "session-goal",
        isStreaming = false,
        updatedAtMillis = 1,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val GOAL_ID = "goal-11111111"
    }
}
