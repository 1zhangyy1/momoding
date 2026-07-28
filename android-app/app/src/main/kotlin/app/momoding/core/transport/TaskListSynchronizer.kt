package app.momoding.core.transport

import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.P1bProtocol
import app.momoding.wire.ReceivedP1bServerFrame
import app.momoding.wire.WireErrorCode
import app.momoding.core.data.HostTaskSummary
import app.momoding.core.data.RoomTaskListMerger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

fun interface TaskListWirePort {
    suspend fun requestPage(cursor: String?, limit: Int): ReceivedP1bServerFrame
}

fun interface TaskListPublisher {
    suspend fun publish(listRevision: Long, summaries: List<HostTaskSummary>): String
}

data class TaskListSyncResult(
    val listRevision: Long,
    val taskCount: Int,
    val pageCount: Int,
    val cursorRestarts: Int,
    val generation: String,
)

class TaskListSynchronizer(
    private val wire: TaskListWirePort,
    private val publisher: TaskListPublisher,
) {
    constructor(wire: TaskListWirePort, merger: RoomTaskListMerger) : this(
        wire,
        TaskListPublisher(merger::mergeCompleteList),
    )

    suspend fun synchronize(): TaskListSyncResult {
        var cumulativeBytes = 0L
        var cumulativePages = 0
        var cursorRestarts = 0
        var cursor: String? = null
        var expectedRevision: Long? = null
        var summaries = mutableListOf<HostTaskSummary>()
        var taskIds = hashSetOf<String>()

        while (true) {
            val received = wire.requestPage(cursor, PAGE_LIMIT)
            cumulativePages += 1
            cumulativeBytes += received.byteLength
            require(cumulativePages <= MAX_PAGES) { "Task list exceeds $MAX_PAGES response pages" }
            require(cumulativeBytes <= MAX_RESPONSE_BYTES) { "Task list exceeds $MAX_RESPONSE_BYTES bytes" }
            val response = received.frame as? CommandResponseFrame
                ?: error("task.list returned a non-response frame")
            if (!response.ok) {
                if (response.error?.code == WireErrorCode.CURSOR_INVALID && cursor != null) {
                    require(cursorRestarts < MAX_CURSOR_RESTARTS) { "Task list cursor restart limit exceeded" }
                    cursorRestarts += 1
                    cursor = null
                    expectedRevision = null
                    summaries = mutableListOf()
                    taskIds = hashSetOf()
                    continue
                }
                error("task.list failed")
            }
            val page = TaskListPageDecoder.decode(response)
            if (expectedRevision == null) expectedRevision = page.listRevision
            require(page.listRevision == expectedRevision) { "Task list revision changed during pagination" }
            page.tasks.forEach { summary ->
                require(taskIds.add(summary.taskId)) { "Task list contains a duplicate taskId" }
                summaries.lastOrNull()?.let { previous ->
                    require(isBefore(previous, summary)) { "Task list global ordering is invalid" }
                }
                summaries += summary
            }
            require(summaries.size <= MAX_TASKS) { "Task list exceeds $MAX_TASKS tasks" }
            if (page.nextCursor == null) {
                val revision = requireNotNull(expectedRevision)
                val generation = publisher.publish(revision, summaries)
                return TaskListSyncResult(
                    listRevision = revision,
                    taskCount = summaries.size,
                    pageCount = cumulativePages,
                    cursorRestarts = cursorRestarts,
                    generation = generation,
                )
            }
            require(page.tasks.isNotEmpty()) { "Task list page cannot advance without tasks" }
            cursor = page.nextCursor
        }
    }

    private fun isBefore(left: HostTaskSummary, right: HostTaskSummary): Boolean =
        left.hostUpdatedAtMillis > right.hostUpdatedAtMillis ||
            left.hostUpdatedAtMillis == right.hostUpdatedAtMillis && left.taskId < right.taskId

    private companion object {
        const val PAGE_LIMIT = 100
        const val MAX_PAGES = 100
        const val MAX_TASKS = 10_000
        const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
        const val MAX_CURSOR_RESTARTS = 3
    }
}

data class DecodedTaskListPage(
    val tasks: List<HostTaskSummary>,
    val listRevision: Long,
    val nextCursor: String?,
)

object TaskListPageDecoder {
    fun decode(response: CommandResponseFrame): DecodedTaskListPage {
        require(response.ok && response.error == null) { "task.list response is not successful" }
        val data = response.data as? JsonObject ?: error("task.list data is invalid")
        data.requireExactKeys(REQUIRED_PAGE_KEYS, OPTIONAL_PAGE_KEYS)
        val tasks = data["tasks"] as? JsonArray ?: error("task.list tasks is invalid")
        require(tasks.size <= MAX_PAGE_TASKS) { "task.list page exceeds $MAX_PAGE_TASKS tasks" }
        val summaries = tasks.map { element -> decodeSummary(element as? JsonObject ?: error("Task summary is invalid")) }
        summaries.zipWithNext().forEach { (left, right) ->
            require(
                left.hostUpdatedAtMillis > right.hostUpdatedAtMillis ||
                    left.hostUpdatedAtMillis == right.hostUpdatedAtMillis && left.taskId < right.taskId,
            ) { "Task page ordering is invalid" }
        }
        val revision = data.requirePositiveSafeInteger("listRevision")
        val cursor = data["nextCursor"]?.let { element ->
            val value = (element as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?: error("nextCursor is invalid")
            require(value.isNotEmpty() && value.length <= MAX_CURSOR_CHARS) { "nextCursor is invalid" }
            value
        }
        return DecodedTaskListPage(summaries, revision, cursor)
    }

    private fun decodeSummary(summary: JsonObject): HostTaskSummary {
        summary.requireExactKeys(REQUIRED_SUMMARY_KEYS, OPTIONAL_SUMMARY_KEYS)
        val taskId = summary.requireString("taskId", 36)
        require(UUID_PATTERN.matches(taskId)) { "taskId is invalid" }
        val title = summary["title"]?.let { element ->
            val value = (element as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?: error("title is invalid")
            require(value.isNotEmpty() && value.length <= MAX_TITLE_CHARS) { "title is invalid" }
            value
        }
        val updatedAt = summary.requireString("updatedAt", 24)
        require(TIMESTAMP_PATTERN.matches(updatedAt)) { "updatedAt is not canonical" }
        val instant = try {
            Instant.parse(updatedAt)
        } catch (error: Exception) {
            throw IllegalArgumentException("updatedAt is invalid", error)
        }
        require(TIMESTAMP_FORMATTER.format(instant) == updatedAt) { "updatedAt is not canonical" }
        val runState = summary.requireString("runState", 32)
        require(runState in RUN_STATES) { "runState is invalid" }
        val recoveryState = summary.requireString("recoveryState", 32)
        require(recoveryState in RECOVERY_STATES) { "recoveryState is invalid" }
        return HostTaskSummary(
            taskId = taskId,
            title = title,
            hostUpdatedAtMillis = instant.toEpochMilli(),
            runState = runState,
            recoveryState = recoveryState,
            snapshotVersion = summary.requirePositiveSafeInteger("snapshotVersion"),
        )
    }

    private fun JsonObject.requireExactKeys(required: Set<String>, optional: Set<String>) {
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) {
            "JSON object schema differs"
        }
    }

    private fun JsonObject.requireString(name: String, maxChars: Int): String {
        val primitive = this[name] as? JsonPrimitive ?: error("$name is invalid")
        require(primitive.isString) { "$name is invalid" }
        return requireNotNull(primitive.contentOrNull).also { value ->
            require(value.isNotEmpty() && value.length <= maxChars) { "$name is invalid" }
        }
    }

    private fun JsonObject.requirePositiveSafeInteger(name: String): Long {
        val primitive = this[name] as? JsonPrimitive ?: error("$name is invalid")
        require(!primitive.isString) { "$name is invalid" }
        val value = primitive.longOrNull ?: error("$name is invalid")
        require(value in 1..P1bProtocol.MAX_SAFE_INTEGER) { "$name is invalid" }
        return value
    }

    private const val MAX_PAGE_TASKS = 100
    private const val MAX_CURSOR_CHARS = 4_096
    private const val MAX_TITLE_CHARS = 256
    private val REQUIRED_PAGE_KEYS = setOf("tasks", "listRevision")
    private val OPTIONAL_PAGE_KEYS = setOf("nextCursor")
    private val REQUIRED_SUMMARY_KEYS = setOf(
        "taskId",
        "updatedAt",
        "runState",
        "recoveryState",
        "snapshotVersion",
    )
    private val OPTIONAL_SUMMARY_KEYS = setOf("title")
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val TIMESTAMP_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
    private val TIMESTAMP_FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)
    private val RUN_STATES = setOf(
        "idle",
        "starting",
        "running",
        "waiting",
        "stopping",
        "stopped",
        "completed",
        "failed",
        "interrupted",
    )
    private val RECOVERY_STATES = setOf("normal", "interrupted", "reconciling_device_calls")
}
