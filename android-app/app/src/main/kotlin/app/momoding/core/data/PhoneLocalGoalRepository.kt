package app.momoding.core.data

import app.momoding.core.runtime.local.PiTaskGoalSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PhoneLocalGoalState {
    ACTIVE,
    PAUSE_PENDING,
    PAUSED,
    BLOCKED,
    LIMITED,
    FAILED,
    ACHIEVED,
    CLEARED,
}

data class PhoneLocalGoalRecord(
    val taskId: String,
    val goalId: String,
    val instruction: String,
    val state: PhoneLocalGoalState,
    val progressSummary: String?,
    val progressMarker: String?,
    val terminalReason: String?,
    val pauseReason: String?,
    val automaticTurnCount: Int,
    val lastTurnIndex: Int,
    val nextTurnClaimed: Boolean,
    val generation: Int,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
)

sealed interface GoalContinuationDecision {
    data object None : GoalContinuationDecision
    data class Continue(val goal: PhoneLocalGoalRecord, val turnIndex: Int) : GoalContinuationDecision
    data class Limit(val goal: PhoneLocalGoalRecord) : GoalContinuationDecision
}

/** Android-authoritative durable lifecycle and continuation latch for one task Goal. */
class PhoneLocalGoalRepository(
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.p2Dao()

    fun observe(taskId: String): Flow<PhoneLocalGoalRecord?> =
        dao.observeTaskGoal(taskId).map { it?.toRecord() }

    fun current(taskId: String): PhoneLocalGoalRecord? = dao.taskGoal(taskId)?.toRecord()

    fun create(taskId: String, goalId: String, instruction: String): PhoneLocalGoalRecord =
        database.runInTransaction<PhoneLocalGoalRecord> {
            val normalized = validateInstruction(instruction)
            require(goalId.matches(GOAL_ID)) { "Goal id is invalid" }
            requireSettledTask(taskId)
            val existing = dao.taskGoal(taskId)
            require(existing == null || existing.state in setOf(
                PhoneLocalGoalState.LIMITED.name,
                PhoneLocalGoalState.FAILED.name,
                PhoneLocalGoalState.ACHIEVED.name,
                PhoneLocalGoalState.CLEARED.name,
            )) {
                "The existing Goal must be finished or cleared"
            }
            val now = nowMillis()
            val entity = TaskGoalEntity(
                taskId = taskId,
                goalId = goalId,
                instruction = normalized,
                state = PhoneLocalGoalState.ACTIVE.name,
                progressSummary = null,
                progressMarker = null,
                terminalReason = null,
                pauseReason = null,
                automaticTurnCount = 0,
                lastTurnIndex = -1,
                nextTurnClaimed = true,
                generation = (existing?.generation ?: 0) + 1,
                startedAtMillis = now,
                updatedAtMillis = now,
            )
            dao.upsertTaskGoal(entity)
            entity.toRecord()
        }

    fun edit(taskId: String, instruction: String): PhoneLocalGoalRecord =
        database.runInTransaction<PhoneLocalGoalRecord> {
            val normalized = validateInstruction(instruction)
            requireSettledTask(taskId)
            val existing = requireNotNull(dao.taskGoal(taskId)) { "Goal is missing" }
            require(existing.state !in setOf(
                PhoneLocalGoalState.ACTIVE.name,
                PhoneLocalGoalState.PAUSE_PENDING.name,
            )) { "Pause the Goal before editing" }
            val now = nowMillis()
            val edited = existing.copy(
                instruction = normalized,
                state = PhoneLocalGoalState.ACTIVE.name,
                progressSummary = null,
                progressMarker = null,
                terminalReason = null,
                pauseReason = null,
                automaticTurnCount = 0,
                lastTurnIndex = -1,
                nextTurnClaimed = true,
                generation = existing.generation + 1,
                startedAtMillis = now,
                updatedAtMillis = now,
            )
            dao.upsertTaskGoal(edited)
            edited.toRecord()
        }

    fun requestPause(taskId: String): PhoneLocalGoalRecord =
        database.runInTransaction<PhoneLocalGoalRecord> {
            val existing = requireNotNull(dao.taskGoal(taskId)) { "Goal is missing" }
            require(existing.state == PhoneLocalGoalState.ACTIVE.name) { "Goal is not active" }
            val task = requireNotNull(dao.task(taskId)) { "Task is missing" }
            val turnIsRunning = task.isStreaming || task.runState in ACTIVE_TASK_STATES
            val paused = existing.copy(
                state = if (turnIsRunning) {
                    PhoneLocalGoalState.PAUSE_PENDING.name
                } else {
                    PhoneLocalGoalState.PAUSED.name
                },
                nextTurnClaimed = false,
                pauseReason = "user_requested",
                updatedAtMillis = nowMillis(),
            )
            dao.upsertTaskGoal(paused)
            paused.toRecord()
        }

    fun claimResume(taskId: String): PhoneLocalGoalRecord =
        database.runInTransaction<PhoneLocalGoalRecord> {
            requireSettledTask(taskId)
            val existing = requireNotNull(dao.taskGoal(taskId)) { "Goal is missing" }
            require(existing.state in setOf(
                PhoneLocalGoalState.PAUSED.name,
                PhoneLocalGoalState.BLOCKED.name,
            )) { "Goal cannot be resumed" }
            val resumed = existing.copy(
                state = PhoneLocalGoalState.ACTIVE.name,
                terminalReason = null,
                pauseReason = null,
                nextTurnClaimed = true,
                updatedAtMillis = nowMillis(),
            )
            dao.upsertTaskGoal(resumed)
            resumed.toRecord()
        }

    /**
     * Final durable start fence for a previously claimed Goal turn.
     *
     * Pause wins without a Provider request when it commits before this transaction. Once this
     * transaction commits, the turn is durably STARTING and a later Pause applies after that turn.
     */
    fun beginClaimedTurn(
        taskId: String,
        goalId: String,
        generation: Int,
        turnIndex: Int,
        automatic: Boolean,
        restartExistingTurn: Boolean = false,
    ): PhoneLocalGoalRecord? = database.runInTransaction<PhoneLocalGoalRecord?> {
        val task = requireNotNull(dao.task(taskId)) { "Task is missing" }
        val goal = dao.taskGoal(taskId) ?: return@runInTransaction null
        val expectedTurnIndex = if (restartExistingTurn && goal.lastTurnIndex >= 0) {
            goal.lastTurnIndex
        } else {
            goal.lastTurnIndex + 1
        }
        if (
            task.isStreaming ||
            task.runState in ACTIVE_TASK_STATES ||
            goal.goalId != goalId ||
            goal.generation != generation ||
            turnIndex != expectedTurnIndex ||
            goal.state != PhoneLocalGoalState.ACTIVE.name ||
            !goal.nextTurnClaimed
        ) return@runInTransaction null
        dao.upsertTask(
            task.copy(
                runState = "STARTING",
                isStreaming = true,
                updatedAtMillis = nowMillis(),
            ),
        )
        val begun = goal.copy(
            automaticTurnCount = goal.automaticTurnCount + if (automatic) 1 else 0,
            lastTurnIndex = turnIndex,
            updatedAtMillis = nowMillis(),
        )
        dao.upsertTaskGoal(begun)
        begun.toRecord()
    }

    fun finalizePendingPause(taskId: String): PhoneLocalGoalRecord? =
        database.runInTransaction<PhoneLocalGoalRecord?> {
            val task = requireNotNull(dao.task(taskId)) { "Task is missing" }
            val goal = dao.taskGoal(taskId) ?: return@runInTransaction null
            if (goal.state != PhoneLocalGoalState.PAUSE_PENDING.name) {
                return@runInTransaction goal.toRecord()
            }
            if (task.isStreaming || task.runState in ACTIVE_TASK_STATES) {
                return@runInTransaction goal.toRecord()
            }
            val paused = goal.copy(
                state = PhoneLocalGoalState.PAUSED.name,
                nextTurnClaimed = false,
                updatedAtMillis = nowMillis(),
            )
            dao.upsertTaskGoal(paused)
            paused.toRecord()
        }

    fun clear(taskId: String): PhoneLocalGoalRecord =
        database.runInTransaction<PhoneLocalGoalRecord> {
            requireSettledTask(taskId)
            val existing = requireNotNull(dao.taskGoal(taskId)) { "Goal is missing" }
            val cleared = existing.copy(
                state = PhoneLocalGoalState.CLEARED.name,
                nextTurnClaimed = false,
                pauseReason = null,
                updatedAtMillis = nowMillis(),
            )
            dao.upsertTaskGoal(cleared)
            cleared.toRecord()
        }

    fun syncRuntime(taskId: String, runtime: PiTaskGoalSnapshot?): PhoneLocalGoalRecord? =
        database.runInTransaction<PhoneLocalGoalRecord?> {
            if (runtime == null) return@runInTransaction dao.taskGoal(taskId)?.toRecord()
            val existing = requireNotNull(dao.taskGoal(taskId)) { "Goal is missing" }
            // Starting a replacement generation first emits Pi's settled pre-transition snapshot.
            // Ignore only an older, non-active Goal; every current/future or active mismatch remains
            // fail-closed until Pi publishes the new generation's trusted Goal entry.
            if (runtime.generation < existing.generation && runtime.state != "active") {
                return@runInTransaction existing.toRecord()
            }
            require(existing.goalId == runtime.goalId) { "Goal identity changed" }
            require(existing.generation == runtime.generation) { "Goal generation changed" }
            require(existing.instruction == runtime.instruction) { "Goal instruction changed" }
            val runtimeState = runtime.state.toAndroidGoalState()
            val preservePausePending = existing.state in setOf(
                PhoneLocalGoalState.PAUSE_PENDING.name,
                PhoneLocalGoalState.PAUSED.name,
                PhoneLocalGoalState.LIMITED.name,
                PhoneLocalGoalState.FAILED.name,
                PhoneLocalGoalState.CLEARED.name,
            ) && runtimeState == PhoneLocalGoalState.ACTIVE
            val updated = existing.copy(
                state = if (preservePausePending) existing.state else runtimeState.name,
                progressSummary = runtime.progressSummary,
                progressMarker = runtime.progressMarker,
                terminalReason = runtime.terminalReason,
                nextTurnClaimed = if (runtimeState in TERMINAL_STATES) false else existing.nextTurnClaimed,
                updatedAtMillis = nowMillis(),
            )
            dao.upsertTaskGoal(updated)
            updated.toRecord()
        }

    fun settleTurn(
        taskId: String,
        stopped: Boolean,
        failed: Boolean,
    ): PhoneLocalGoalRecord? = database.runInTransaction<PhoneLocalGoalRecord?> {
        val existing = dao.taskGoal(taskId) ?: return@runInTransaction null
        val state = PhoneLocalGoalState.valueOf(existing.state)
        val updated = when {
            state in TERMINAL_STATES -> existing.copy(nextTurnClaimed = false)
            failed -> existing.copy(
                state = PhoneLocalGoalState.FAILED.name,
                terminalReason = "turn_failed",
                nextTurnClaimed = false,
            )
            stopped -> existing.copy(
                state = PhoneLocalGoalState.PAUSED.name,
                pauseReason = "turn_stopped",
                nextTurnClaimed = false,
            )
            state == PhoneLocalGoalState.PAUSE_PENDING -> existing.copy(
                state = PhoneLocalGoalState.PAUSED.name,
                nextTurnClaimed = false,
            )
            else -> existing.copy(nextTurnClaimed = false)
        }.copy(updatedAtMillis = nowMillis())
        dao.upsertTaskGoal(updated)
        updated.toRecord()
    }

    fun claimAutomaticContinuation(taskId: String): GoalContinuationDecision =
        database.runInTransaction<GoalContinuationDecision> {
            requireSettledTask(taskId)
            val existing = dao.taskGoal(taskId) ?: return@runInTransaction GoalContinuationDecision.None
            if (
                existing.state != PhoneLocalGoalState.ACTIVE.name ||
                existing.nextTurnClaimed
            ) return@runInTransaction GoalContinuationDecision.None
            val now = nowMillis()
            if (
                existing.automaticTurnCount >= MAX_AUTOMATIC_TURNS ||
                now - existing.startedAtMillis >= MAX_DURATION_MILLIS
            ) {
                val limited = existing.copy(
                    state = PhoneLocalGoalState.LIMITED.name,
                    terminalReason = "limit_reached",
                    nextTurnClaimed = false,
                    updatedAtMillis = now,
                )
                dao.upsertTaskGoal(limited)
                return@runInTransaction GoalContinuationDecision.Limit(limited.toRecord())
            }
            val claimed = existing.copy(
                nextTurnClaimed = true,
                updatedAtMillis = now,
            )
            dao.upsertTaskGoal(claimed)
            GoalContinuationDecision.Continue(
                goal = claimed.toRecord(),
                turnIndex = claimed.lastTurnIndex + 1,
            )
        }

    fun failClaim(taskId: String, reason: String): PhoneLocalGoalRecord? =
        database.runInTransaction<PhoneLocalGoalRecord?> {
            val existing = dao.taskGoal(taskId) ?: return@runInTransaction null
            if (!existing.nextTurnClaimed) return@runInTransaction existing.toRecord()
            val failed = existing.copy(
                state = PhoneLocalGoalState.FAILED.name,
                terminalReason = reason.take(128),
                nextTurnClaimed = false,
                updatedAtMillis = nowMillis(),
            )
            dao.upsertTaskGoal(failed)
            failed.toRecord()
        }

    fun recoverInterruptedGoals(): Int = database.runInTransaction<Int> {
        var count = 0
        dao.allTaskGoals().forEach { goal ->
            if (
                goal.state in setOf(
                    PhoneLocalGoalState.ACTIVE.name,
                    PhoneLocalGoalState.PAUSE_PENDING.name,
                ) || goal.nextTurnClaimed
            ) {
                dao.upsertTaskGoal(
                    goal.copy(
                        state = PhoneLocalGoalState.PAUSED.name,
                        pauseReason = "runtime_rebuilt",
                        nextTurnClaimed = false,
                        updatedAtMillis = nowMillis(),
                    ),
                )
                count += 1
            }
        }
        count
    }

    private fun requireSettledTask(taskId: String): TaskEntity {
        val task = requireNotNull(dao.task(taskId)) { "Task is missing" }
        require(!task.isStreaming && task.runState !in ACTIVE_TASK_STATES) { "Task is still running" }
        return task
    }

    private fun validateInstruction(value: String): String = value.trim().also {
        require(it.length in 1..4096) { "Goal instruction is invalid" }
    }

    private fun TaskGoalEntity.toRecord() = PhoneLocalGoalRecord(
        taskId = taskId,
        goalId = goalId,
        instruction = instruction,
        state = PhoneLocalGoalState.valueOf(state),
        progressSummary = progressSummary,
        progressMarker = progressMarker,
        terminalReason = terminalReason,
        pauseReason = pauseReason,
        automaticTurnCount = automaticTurnCount,
        lastTurnIndex = lastTurnIndex,
        nextTurnClaimed = nextTurnClaimed,
        generation = generation,
        startedAtMillis = startedAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    private fun String.toAndroidGoalState(): PhoneLocalGoalState = when (this) {
        "active" -> PhoneLocalGoalState.ACTIVE
        "paused" -> PhoneLocalGoalState.PAUSED
        "blocked" -> PhoneLocalGoalState.BLOCKED
        "limited" -> PhoneLocalGoalState.LIMITED
        "failed" -> PhoneLocalGoalState.FAILED
        "achieved" -> PhoneLocalGoalState.ACHIEVED
        "cleared" -> PhoneLocalGoalState.CLEARED
        else -> error("Runtime Goal state is invalid")
    }

    companion object {
        const val MAX_AUTOMATIC_TURNS = 20
        const val MAX_DURATION_MILLIS = 60 * 60 * 1_000L
        private val GOAL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        private val ACTIVE_TASK_STATES = setOf(
            "STARTING", "RUNNING", "WAITING", "RETRYING", "COMPACTING", "STOPPING",
        )
        private val TERMINAL_STATES = setOf(
            PhoneLocalGoalState.BLOCKED,
            PhoneLocalGoalState.LIMITED,
            PhoneLocalGoalState.FAILED,
            PhoneLocalGoalState.ACHIEVED,
            PhoneLocalGoalState.CLEARED,
        )
    }
}
