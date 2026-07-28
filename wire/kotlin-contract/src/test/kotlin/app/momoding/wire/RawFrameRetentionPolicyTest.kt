package app.momoding.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawFrameRetentionPolicyTest {
    @Test
    fun `binds raw event and staged audit limits to the P2 contract`() {
        assertEquals(3_584, RawFrameRetentionPolicy.RAW_EVENT_SOFT_MAX_EVENTS)
        assertEquals(7L * 1024 * 1024, RawFrameRetentionPolicy.RAW_EVENT_SOFT_MAX_BYTES)
        assertEquals(4_096, RawFrameRetentionPolicy.RAW_EVENT_HARD_MAX_EVENTS)
        assertEquals(8L * 1024 * 1024, RawFrameRetentionPolicy.RAW_EVENT_HARD_MAX_BYTES)
        assertEquals(4_096, RawFrameRetentionPolicy.MAX_STAGED_AUDIT_FRAMES)
        assertEquals(69_468_160L, RawFrameRetentionPolicy.MAX_STAGED_AUDIT_BYTES)
    }

    @Test
    fun `admits exact hard boundaries recommends at soft gates and fences overflow`() {
        val belowSoft = stateWithRawEvents(3_583, 1)
        val reachesSoft = assertIs<AcceptRawEvent>(
            RawFrameRetentionPolicy.admitEvent(
                belowSoft,
                TASK_ID,
                STREAM_ID,
                3_584,
                1,
            ),
        )
        assertNotNull(reachesSoft.snapshotRecommendation)

        val hardCount = stateWithRawEvents(4_095, 1)
        assertIs<AcceptRawEvent>(
            RawFrameRetentionPolicy.admitEvent(hardCount, TASK_ID, STREAM_ID, 4_096, 1),
        )
        val countOverflow = RawFrameRetentionPolicy.admitEvent(
            stateWithRawEvents(4_096, 1),
            TASK_ID,
            STREAM_ID,
            4_097,
            1,
        )
        assertEquals(4_097, assertIs<RequireRawBudgetSnapshot>(countOverflow).expectation.rejectedSequence)

        val exactHardBytes = stateWithRawEvents(1, (8 * 1024 * 1024) - 1)
        assertIs<AcceptRawEvent>(
            RawFrameRetentionPolicy.admitEvent(exactHardBytes, TASK_ID, STREAM_ID, 2, 1),
        )
        assertIs<RequireRawBudgetSnapshot>(
            RawFrameRetentionPolicy.admitEvent(exactHardBytes, TASK_ID, STREAM_ID, 2, 2),
        )

        val reachesSoftBytes = assertIs<AcceptRawEvent>(
            RawFrameRetentionPolicy.admitEvent(
                stateWithRawEvents(1, (7 * 1024 * 1024) - 1),
                TASK_ID,
                STREAM_ID,
                2,
                1,
            ),
        )
        assertNotNull(reachesSoftBytes.snapshotRecommendation)
    }

    @Test
    fun `admits one legal oversize event only for an empty generation`() {
        val accepted = assertIs<AcceptRawEvent>(
            RawFrameRetentionPolicy.admitEvent(
                null,
                TASK_ID,
                STREAM_ID,
                1,
                P1bProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong(),
            ),
        )
        assertNotNull(accepted.snapshotRecommendation)

        val oversizeState = stateWithRawEvents(1, (8 * 1024 * 1024) + 1)
        assertIs<RequireRawBudgetSnapshot>(
            RawFrameRetentionPolicy.admitEvent(oversizeState, TASK_ID, STREAM_ID, 2, 1),
        )
        assertFailsWith<RetentionPolicyException> {
            RawFrameRetentionPolicy.admitEvent(
                null,
                TASK_ID,
                STREAM_ID,
                1,
                P1bProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong() + 1,
            )
        }

        val tailPlusMaximum = assertIs<RequireRawBudgetSnapshot>(
            RawFrameRetentionPolicy.admitEvent(
                stateWithRawEvents(1, 1),
                TASK_ID,
                STREAM_ID,
                2,
                P1bProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong(),
            ),
        )
        assertEquals(2, tailPlusMaximum.expectation.rejectedSequence)
        assertTrue(
            RawFrameRetentionPolicy.snapshotSatisfies(
                tailPlusMaximum.expectation,
                TASK_ID,
                STREAM_ID,
                2,
            ),
        )
    }

    @Test
    fun `retains a maximum legal batch and compacts only complete prior batches`() {
        val maximumBatch = buildList {
            add(StagedRawFrame("pi.event", ByteArray(P1bProtocol.SNAPSHOT_MAX_LOGICAL_BYTES)))
            repeat(P1bProtocol.SNAPSHOT_MAX_PAGES) {
                add(StagedRawFrame("task.snapshot.page", ByteArray(1024)))
            }
            add(StagedRawFrame("task.snapshot.begin", ByteArray(P1aProtocol.MAX_FRAME_BYTES)))
            add(StagedRawFrame("task.snapshot.end", ByteArray(P1aProtocol.MAX_FRAME_BYTES)))
        }
        assertEquals(RawFrameRetentionPolicy.MAX_STAGED_AUDIT_BYTES, maximumBatch.sumOf { it.byteCount })
        val exact = RawFrameRetentionPolicy.retainBatch(
            priorBatches = emptyList(),
            nextBatchOrdinal = 1,
            batchKind = "task.snapshot.replace",
            currentFrames = maximumBatch,
            mode = StagedBatchMode.REPLACE,
        )
        assertEquals(listOf(1L), exact.batches.map { it.batchOrdinal })

        val compacted = RawFrameRetentionPolicy.retainBatch(
            priorBatches = exact.batches,
            nextBatchOrdinal = 2,
            batchKind = "pi.event",
            currentFrames = listOf(StagedRawFrame("pi.event", byteArrayOf(1))),
            mode = StagedBatchMode.APPEND,
        )
        assertEquals(listOf(2L), compacted.batches.map { it.batchOrdinal })
        assertEquals(1, compacted.batches.single().frames.size)

        assertFailsWith<RetentionPolicyException> {
            RawFrameRetentionPolicy.retainBatch(
                priorBatches = emptyList(),
                nextBatchOrdinal = 1,
                batchKind = "task.snapshot.replace",
                currentFrames = maximumBatch + StagedRawFrame("extra", byteArrayOf(1)),
                mode = StagedBatchMode.REPLACE,
            )
        }
    }

    @Test
    fun `frame pressure evicts a whole old batch and replace discards every prior batch`() {
        val first = batch(1, 2_048)
        val second = batch(2, 2_048)
        val compacted = RawFrameRetentionPolicy.retainBatch(
            priorBatches = listOf(first, second),
            nextBatchOrdinal = 3,
            batchKind = "pi.event",
            currentFrames = listOf(StagedRawFrame("pi.event", byteArrayOf(3))),
            mode = StagedBatchMode.APPEND,
        )
        assertEquals(listOf(2L, 3L), compacted.batches.map { it.batchOrdinal })
        assertEquals(2_049, compacted.batches.sumOf { it.frames.size })

        val replaced = RawFrameRetentionPolicy.retainBatch(
            priorBatches = compacted.batches,
            nextBatchOrdinal = 4,
            batchKind = "task.snapshot.replace",
            currentFrames = listOf(StagedRawFrame("task.snapshot", byteArrayOf(4))),
            mode = StagedBatchMode.REPLACE,
        )
        assertEquals(listOf(4L), replaced.batches.map { it.batchOrdinal })
        assertTrue(replaced.batches.none { it.batchOrdinal < 4 })
    }

    @Test
    fun `snapshot expectation requires exact task stream and covering sequence`() {
        val expectation = RawBudgetSnapshotExpectation(TASK_ID, STREAM_ID, 9)
        assertTrue(RawFrameRetentionPolicy.snapshotSatisfies(expectation, TASK_ID, STREAM_ID, 9))
        assertTrue(RawFrameRetentionPolicy.snapshotSatisfies(expectation, TASK_ID, STREAM_ID, 10))
        assertTrue(!RawFrameRetentionPolicy.snapshotSatisfies(expectation, OTHER_TASK_ID, STREAM_ID, 9))
        assertTrue(!RawFrameRetentionPolicy.snapshotSatisfies(expectation, TASK_ID, OTHER_STREAM_ID, 9))
        assertTrue(!RawFrameRetentionPolicy.snapshotSatisfies(expectation, TASK_ID, STREAM_ID, 8))
    }

    private fun stateWithRawEvents(count: Int, firstBytes: Int): DurableTaskProjection {
        val records = (1..count).associate { sequence ->
            val bytes = if (sequence == 1) firstBytes else 1
            sequence.toLong() to RawPiEventRecord(
                sequence.toLong(),
                "digest-$sequence",
                ByteArray(bytes),
                JsonObject(mapOf("type" to JsonPrimitive("fixture"))),
            )
        }
        return DurableTaskProjection(
            taskId = TASK_ID,
            streamId = STREAM_ID,
            throughSequence = count.toLong(),
            rawEvents = records,
        )
    }

    private fun batch(ordinal: Long, frameCount: Int) = StagedRawFrameBatch(
        ordinal,
        "fixture-$ordinal",
        List(frameCount) { StagedRawFrame("fixture", byteArrayOf(1)) },
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val STREAM_ID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_STREAM_ID = "44444444-4444-4444-8444-444444444444"
    }
}
