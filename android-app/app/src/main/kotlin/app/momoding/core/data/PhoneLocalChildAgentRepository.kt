package app.momoding.core.data

import app.momoding.core.runtime.local.PiChildAgentEventEnvelope
import app.momoding.core.runtime.local.PiChildAgentSnapshot
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Android-owned durability and recovery boundary for Pi child Agent product state. */
class PhoneLocalChildAgentRepository(
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.p2Dao()
    private val json = Json { explicitNulls = false }

    /** Called synchronously from the Runtime owner thread after one destructive native outbox drain. */
    fun persistRuntimeUpdate(
        events: List<PiChildAgentEventEnvelope>,
        snapshots: List<PiChildAgentSnapshot>,
    ) {
        if (events.isEmpty() && snapshots.isEmpty()) return
        val taskIds = (events.map { it.parentTaskId } + snapshots.map { it.parentTaskId }).toSet()
        require(taskIds.size == 1) { "PI_MOBILE_CHILD_UPDATE_TASK_MISMATCH" }
        val taskId = taskIds.single()
        require(taskId.isNotBlank()) { "PI_MOBILE_CHILD_TASK_ID_INVALID" }
        val snapshotByParent = snapshots.associateBy(PiChildAgentSnapshot::parentToolCallId)
        require(snapshotByParent.size == snapshots.size) { "PI_MOBILE_CHILD_PARENT_DUPLICATED" }
        require(events.all { event -> snapshotByParent[event.parentToolCallId]?.let { snapshot ->
            snapshot.childId == event.childId && snapshot.childName == event.childName
        } == true }) { "PI_MOBILE_CHILD_EVENT_BINDING_MISMATCH" }

        database.runInTransaction {
            val now = nowMillis()
            val rows = snapshots.map { snapshot ->
                snapshot.toEntity(
                    prior = dao.taskChildAgent(taskId, snapshot.parentToolCallId),
                    now = now,
                    json = json,
                )
            }
            if (rows.isNotEmpty()) dao.upsertTaskChildAgents(rows)
            events.groupBy(PiChildAgentEventEnvelope::parentToolCallId).forEach { (parent, batch) ->
                val sorted = batch.sortedBy(PiChildAgentEventEnvelope::eventOrdinal)
                require(sorted.map(PiChildAgentEventEnvelope::eventOrdinal).distinct().size == sorted.size) {
                    "PI_MOBILE_CHILD_EVENT_ORDINAL_DUPLICATED"
                }
                var highWater = dao.lastTaskChildAgentEventOrdinal(taskId, parent) ?: -1
                val rowsToInsert = mutableListOf<TaskChildAgentEventEntity>()
                sorted.forEach { event ->
                    require(event.eventOrdinal >= 0) { "PI_MOBILE_CHILD_EVENT_ORDINAL_INVALID" }
                    require(event.eventOrdinal < requireNotNull(snapshotByParent[parent]).eventCount) {
                        "PI_MOBILE_CHILD_EVENT_ORDINAL_OUT_OF_RANGE"
                    }
                    val eventJson = event.event.toString()
                    val row = TaskChildAgentEventEntity(
                        taskId = taskId,
                        parentToolCallId = parent,
                        eventOrdinal = event.eventOrdinal,
                        childId = event.childId,
                        childName = event.childName,
                        eventType = event.event["type"]?.jsonPrimitive?.content
                            ?: error("PI_MOBILE_CHILD_EVENT_TYPE_MISSING"),
                        eventJson = eventJson,
                        digest = eventJson.sha256(),
                    )
                    val existing = dao.taskChildAgentEvent(taskId, parent, event.eventOrdinal)
                    if (existing == null) {
                        require(event.eventOrdinal == highWater + 1) {
                            "PI_MOBILE_CHILD_EVENT_ORDINAL_GAP"
                        }
                        rowsToInsert += row
                        highWater = event.eventOrdinal
                    } else {
                        require(existing == row) { "PI_MOBILE_CHILD_EVENT_RETRY_MISMATCH" }
                    }
                }
                if (rowsToInsert.isNotEmpty()) dao.insertTaskChildAgentEvents(rowsToInsert)
            }
        }
    }

    /** Runtime death never recreates a child Harness or repeats a Provider request. */
    fun recoverDestroyedRuntime(): Int = database.runInTransaction<Int> {
        dao.recoverRunningTaskChildAgents(
            reason = "runtime_rebuilt",
            updatedAtMillis = nowMillis(),
        )
    }

    fun runningChild(taskId: String, parentToolCallId: String): TaskChildAgentEntity? =
        dao.taskChildAgent(taskId, parentToolCallId)?.takeIf { it.state == "RUNNING" }

    fun childEvents(taskId: String, parentToolCallId: String): List<TaskChildAgentEventEntity> =
        dao.taskChildAgentEvents(taskId, parentToolCallId)
}

private fun PiChildAgentSnapshot.toEntity(
    prior: TaskChildAgentEntity?,
    now: Long,
    json: Json,
): TaskChildAgentEntity {
    require(parentTaskId.isNotBlank() && parentToolCallId.isNotBlank()) {
        "PI_MOBILE_CHILD_BINDING_INVALID"
    }
    require(childId.isNotBlank() && childName.isNotBlank() && instruction.isNotBlank()) {
        "PI_MOBILE_CHILD_IDENTITY_INVALID"
    }
    val normalizedState = state.uppercase()
    require(normalizedState in CHILD_STATES) { "PI_MOBILE_CHILD_STATE_INVALID" }
    require(turnCount >= 0 && inputTokens >= 0 && outputTokens >= 0) {
        "PI_MOBILE_CHILD_USAGE_INVALID"
    }
    require(cacheReadTokens >= 0 && cacheWriteTokens >= 0 && contextTokens >= 0) {
        "PI_MOBILE_CHILD_USAGE_INVALID"
    }
    require(costUsd.isFinite() && costUsd >= 0.0 && eventCount >= 0) {
        "PI_MOBILE_CHILD_USAGE_INVALID"
    }
    require(eventTypes.distinct().size == eventTypes.size) { "PI_MOBILE_CHILD_EVENT_TYPES_INVALID" }
    if (normalizedState == "RUNNING") {
        require(terminalReason == null) { "PI_MOBILE_CHILD_RUNNING_HAS_TERMINAL_REASON" }
    }
    if (normalizedState == "FAILED" || normalizedState == "CANCELLED") {
        require(!terminalReason.isNullOrBlank()) { "PI_MOBILE_CHILD_TERMINAL_REASON_MISSING" }
    }
    prior?.let { existing ->
        require(existing.childId == childId && existing.childName == childName) {
            "PI_MOBILE_CHILD_IDENTITY_CHANGED"
        }
        require(existing.instruction == instruction) { "PI_MOBILE_CHILD_INSTRUCTION_CHANGED" }
        require(existing.state == "RUNNING" || existing.state == normalizedState) {
            "PI_MOBILE_CHILD_TERMINAL_STATE_CHANGED"
        }
        require(eventCount >= existing.eventCount) { "PI_MOBILE_CHILD_EVENT_COUNT_REGRESSED" }
    }
    return TaskChildAgentEntity(
        taskId = parentTaskId,
        parentToolCallId = parentToolCallId,
        childId = childId,
        childName = childName,
        instruction = instruction,
        state = normalizedState,
        resultSummary = resultSummary,
        resultText = resultText,
        resultTruncated = resultTruncated,
        terminalReason = terminalReason,
        stopReason = stopReason,
        model = model,
        turnCount = turnCount,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        contextTokens = contextTokens,
        costUsd = costUsd,
        eventTypesJson = json.encodeToString(eventTypes),
        eventCount = eventCount,
        createdAtMillis = prior?.createdAtMillis ?: now,
        updatedAtMillis = now,
    )
}

private val CHILD_STATES = setOf("RUNNING", "COMPLETED", "FAILED", "CANCELLED")

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
