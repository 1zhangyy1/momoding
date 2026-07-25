package app.momoding.debug

import android.content.Context
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomProjectionTransactionStore
import app.momoding.core.data.TaskDetailRepository
import app.momoding.core.runtime.local.PhoneLocalPiEventProjector
import app.momoding.feature.taskdetail.PiUiReducer
import app.momoding.feature.taskdetail.TaskDetailRunState
import app.momoding.feature.taskdetail.TimelineItem
import app.momoding.feature.taskdetail.ToolActivityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class PhoneLocalPiRoomUiProof(
    val projectedTaskCount: Int,
    val rawEventCount: Int,
    val nativePayloadsStructurallyEqual: Boolean,
    val successToolState: ToolActivityState,
    val errorToolState: ToolActivityState,
    val successRunState: TaskDetailRunState,
    val errorRunState: TaskDetailRunState,
)

class PhoneLocalPiRoomUiRunner(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun verify(scenarioProof: PhoneLocalPiScenarioProof): PhoneLocalPiRoomUiProof =
        withContext(Dispatchers.IO) {
            appContext.deleteDatabase(DATABASE_NAME)
            val database = MomodingDatabase.open(appContext, DATABASE_NAME)
            try {
                val success = scenarioProof.scenarios.single { it.kind == "tool_success" }
                val failure = scenarioProof.scenarios.single { it.kind == "tool_error" }
                val successResult = projectAndReduce(
                    database = database,
                    taskId = SUCCESS_TASK_ID,
                    piSessionId = SUCCESS_SESSION_ID,
                    streamId = SUCCESS_STREAM_ID,
                    events = success.events,
                )
                val failureResult = projectAndReduce(
                    database = database,
                    taskId = ERROR_TASK_ID,
                    piSessionId = ERROR_SESSION_ID,
                    streamId = ERROR_STREAM_ID,
                    events = failure.events,
                )

                PhoneLocalPiRoomUiProof(
                    projectedTaskCount = 2,
                    rawEventCount = success.events.size + failure.events.size,
                    nativePayloadsStructurallyEqual =
                        successResult.exactPayloads && failureResult.exactPayloads,
                    successToolState = successResult.toolState,
                    errorToolState = failureResult.toolState,
                    successRunState = successResult.runState,
                    errorRunState = failureResult.runState,
                )
            } finally {
                database.close()
                appContext.deleteDatabase(DATABASE_NAME)
            }
        }

    private suspend fun projectAndReduce(
        database: MomodingDatabase,
        taskId: String,
        piSessionId: String,
        streamId: String,
        events: List<kotlinx.serialization.json.JsonObject>,
    ): ProjectionUiResult {
        val proof = PhoneLocalPiEventProjector(
            database = database,
            emittedAt = { FIXED_EMITTED_AT },
        ).append(
            taskId = taskId,
            piSessionId = piSessionId,
            streamId = streamId,
            events = events,
        )
        check(proof.eventCount == events.size && proof.throughSequence == events.size.toLong()) {
            "PI_MOBILE_ROOM_CURSOR_MISMATCH"
        }

        val durable = requireNotNull(RoomProjectionTransactionStore(database).read(taskId))
        val retained = durable.rawEvents.toSortedMap().values.map { it.event }
        val snapshot = requireNotNull(
            TaskDetailRepository(database).observe(taskId).filterNotNull().first(),
        )
        val snapshotEvents = snapshot.rawEvents.map { json.parseToJsonElement(it.eventJson) }
        val ui = PiUiReducer().reduce(snapshot)
        val tool = ui.timeline.settledItems
            .filterIsInstance<TimelineItem.ToolActivity>()
            .single()

        check(ui.runState == TaskDetailRunState.SETTLED) {
            "PI_MOBILE_UI_NOT_SETTLED state=${ui.runState}"
        }
        return ProjectionUiResult(
            exactPayloads = retained == events && snapshotEvents == events,
            toolState = tool.state,
            runState = ui.runState,
        )
    }

    private data class ProjectionUiResult(
        val exactPayloads: Boolean,
        val toolState: ToolActivityState,
        val runState: TaskDetailRunState,
    )

    private companion object {
        const val DATABASE_NAME = "phone-local-pi-gate.db"
        const val FIXED_EMITTED_AT = "2026-07-20T00:00:00.000Z"

        const val SUCCESS_TASK_ID = "10000000-0000-4000-8000-000000000001"
        const val SUCCESS_SESSION_ID = "10000000-0000-4000-8000-000000000002"
        const val SUCCESS_STREAM_ID = "10000000-0000-4000-8000-000000000003"

        const val ERROR_TASK_ID = "20000000-0000-4000-8000-000000000001"
        const val ERROR_SESSION_ID = "20000000-0000-4000-8000-000000000002"
        const val ERROR_STREAM_ID = "20000000-0000-4000-8000-000000000003"
    }
}
