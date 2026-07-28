package app.momoding.core.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.P1bProtocol
import app.momoding.wire.ReceivedP1bServerFrame
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.HostTaskSummary
import app.momoding.core.data.RoomTaskListMerger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.test.runTest
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
class TaskListSynchronizerTest {
    private lateinit var database: MomodingDatabase
    private lateinit var merger: RoomTaskListMerger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        merger = RoomTaskListMerger(database, nowMillis = { 10L }, generationFactory = ::uuidFor)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completePagesMergeOnlyAfterFinalPage() = runTest {
        val first = summary(1, 3_000)
        val second = summary(2, 2_000)
        val port = QueueTaskListPort(
            mutableListOf(
                page(listOf(first), revision = 7, nextCursor = "cursor-1"),
                page(listOf(second), revision = 7),
            ),
        )

        val result = TaskListSynchronizer(port, merger).synchronize()

        assertEquals(7, result.listRevision)
        assertEquals(2, result.taskCount)
        assertEquals(2, result.pageCount)
        assertEquals(listOf(first.taskId, second.taskId), merger.listedTasks().map { it.taskId })
        assertEquals(listOf(null, "cursor-1"), port.requestedCursors)
    }

    @Test
    fun partialFailurePreservesPreviousCacheExactly() = runTest {
        val old = summary(9, 9_000)
        merger.mergeCompleteList(1, listOf(old))
        val before = database.momodingDao().allTasks()
        val newer = summary(1, 3_000)
        val outOfOrder = summary(2, 4_000)
        val port = QueueTaskListPort(
            mutableListOf(
                page(listOf(newer), revision = 2, nextCursor = "cursor"),
                page(listOf(outOfOrder), revision = 2),
            ),
        )

        expectFailure { TaskListSynchronizer(port, merger).synchronize() }

        assertEquals(before, database.momodingDao().allTasks())
    }

    @Test
    fun cursorInvalidRestartsFromFirstPageAtMostThreeTimes() = runTest {
        val task = summary(1, 3_000)
        val responses = mutableListOf<ReceivedP1bServerFrame>()
        repeat(3) {
            responses += page(listOf(task), revision = 4, nextCursor = "cursor-$it")
            responses += cursorInvalid()
        }
        responses += page(listOf(task), revision = 4)
        val port = QueueTaskListPort(responses)

        val result = TaskListSynchronizer(port, merger).synchronize()

        assertEquals(3, result.cursorRestarts)
        assertEquals(listOf(null, "cursor-0", null, "cursor-1", null, "cursor-2", null), port.requestedCursors)

        val failing = mutableListOf<ReceivedP1bServerFrame>()
        repeat(4) {
            failing += page(listOf(task), revision = 5, nextCursor = "cursor-$it")
            failing += cursorInvalid()
        }
        expectFailure { TaskListSynchronizer(QueueTaskListPort(failing), merger).synchronize() }
    }

    @Test
    fun pageByteAndRevisionLimitsAreCumulativeAcrossRestarts() = runTest {
        val inflated = mutableListOf<ReceivedP1bServerFrame>()
        repeat(5) { index ->
            val received = page(
                listOf(summary(index + 1, 10_000L - index)),
                revision = 8,
                nextCursor = "cursor-$index",
            )
            inflated += ReceivedP1bServerFrame(received.frame, ByteArray(1_048_576))
        }
        expectFailure { TaskListSynchronizer(QueueTaskListPort(inflated), merger).synchronize() }

        val changedRevision = QueueTaskListPort(
            mutableListOf(
                page(listOf(summary(1, 3_000)), revision = 8, nextCursor = "cursor"),
                page(listOf(summary(2, 2_000)), revision = 9),
            ),
        )
        expectFailure { TaskListSynchronizer(changedRevision, merger).synchronize() }
    }

    @Test
    fun decoderRejectsSchemaTimestampEnumOrderAndSafeIntegerViolations() {
        val valid = summaryJson(summary(1, 3_000))
        val second = summaryJson(summary(2, 2_000))
        val invalidDataObjects = listOf(
            "{\"tasks\":[$valid],\"listRevision\":1,\"unknown\":true}",
            "{\"tasks\":[$valid],\"listRevision\":1.0}",
            "{\"tasks\":[$valid],\"listRevision\":${P1bProtocol.MAX_SAFE_INTEGER + 1}}",
            "{\"tasks\":[${valid.replace("idle", "unknown")}],\"listRevision\":1}",
            "{\"tasks\":[${valid.replace("1970-01-01T00:00:03.000Z", "1970-01-01T00:00:03Z")}],\"listRevision\":1}",
            "{\"tasks\":[$second,$valid],\"listRevision\":1}",
        )

        invalidDataObjects.forEach { data ->
            val frame = responseWithData(data).frame as CommandResponseFrame
            assertThrows(Exception::class.java) { TaskListPageDecoder.decode(frame) }
        }
    }

    @Test
    fun oneHundredAndFirstPageFailsWithoutPublishingPartialRows() = runTest {
        val old = summary(500, 20_000)
        merger.mergeCompleteList(1, listOf(old))
        val responses = mutableListOf<ReceivedP1bServerFrame>()
        repeat(101) { index ->
            responses += page(
                listOf(summary(index + 1, 10_000L - index)),
                revision = 2,
                nextCursor = "cursor-$index",
            )
        }

        expectFailure { TaskListSynchronizer(QueueTaskListPort(responses), merger).synchronize() }

        assertEquals(listOf(old.taskId), merger.listedTasks().map { it.taskId })
    }

    private fun page(
        tasks: List<HostTaskSummary>,
        revision: Long,
        nextCursor: String? = null,
    ): ReceivedP1bServerFrame {
        val data = buildString {
            append("{\"tasks\":[")
            append(tasks.joinToString(",", transform = ::summaryJson))
            append("],\"listRevision\":$revision")
            nextCursor?.let { append(",\"nextCursor\":\"$it\"") }
            append('}')
        }
        return responseWithData(data)
    }

    private fun responseWithData(data: String): ReceivedP1bServerFrame = ReliabilityContractDecoder.decode(
        """{"protocolVersion":1,"kind":"response","requestId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","ok":true,"data":$data}""",
    )

    private fun cursorInvalid(): ReceivedP1bServerFrame = ReliabilityContractDecoder.decode(
        """{"protocolVersion":1,"kind":"response","requestId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","ok":false,"error":{"code":"CURSOR_INVALID","message":"cursor invalid","retryable":true}}""",
    )

    private fun summary(index: Int, updatedAtMillis: Long): HostTaskSummary = HostTaskSummary(
        taskId = uuidFor(index),
        title = "Task $index",
        hostUpdatedAtMillis = updatedAtMillis,
        runState = "idle",
        recoveryState = "normal",
        snapshotVersion = 1,
    )

    private fun summaryJson(summary: HostTaskSummary): String =
        """{"taskId":"${summary.taskId}","title":"${summary.title}","updatedAt":"${TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(summary.hostUpdatedAtMillis))}","runState":"${summary.runState}","recoveryState":"${summary.recoveryState}","snapshotVersion":${summary.snapshotVersion}}"""

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Expected task.list synchronization to fail", failed)
    }

    private class QueueTaskListPort(
        private val responses: MutableList<ReceivedP1bServerFrame>,
    ) : TaskListWirePort {
        val requestedCursors = mutableListOf<String?>()

        override suspend fun requestPage(cursor: String?, limit: Int): ReceivedP1bServerFrame {
            assertEquals(100, limit)
            requestedCursors += cursor
            return responses.removeAt(0)
        }
    }

    companion object {
        private val TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

        private fun uuidFor(index: Int = 1): String =
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
    }
}
