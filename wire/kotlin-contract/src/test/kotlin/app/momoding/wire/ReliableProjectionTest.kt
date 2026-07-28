package app.momoding.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReliableProjectionTest {
    @Test
    fun `applies contiguous events deduplicates exact bytes and fails closed on conflicts gaps and streams`() {
        assertFailsWith<IllegalArgumentException> { event(0) }

        val store = ReferenceProjectionStore()
        val projection = ReliableProjection(store)
        val first = projection.applyEvent(event(1))
        assertEquals(1, first.state.throughSequence)
        assertEquals(PiEventAckFrame(TASK_ID, STREAM_ID, 1), first.ack)

        val secondFrame = event(2)
        val second = projection.applyEvent(secondFrame)
        assertEquals(2, second.state.throughSequence)
        assertEquals(2, second.state.rawEvents.size)
        val transactionsBeforeDuplicate = store.transactionCount
        val commitsBeforeDuplicate = store.commitCount
        val stagedBeforeDuplicate = second.state.stagedRawFrameBatches
        store.failAtStage = ProjectionWriteStage.RAW
        store.failFinalCommit = true

        val duplicate = projection.applyEvent(secondFrame)
        assertEquals(2, duplicate.state.throughSequence)
        assertEquals(2, duplicate.state.rawEvents.size)
        assertEquals(PiEventAckFrame(TASK_ID, STREAM_ID, 2), duplicate.ack)
        assertEquals(transactionsBeforeDuplicate, store.transactionCount)
        assertEquals(commitsBeforeDuplicate, store.commitCount)
        assertSame(second.state, duplicate.state)
        assertSame(stagedBeforeDuplicate, duplicate.state.stagedRawFrameBatches)
        assertSame(
            second.state.rawEvents.getValue(2),
            duplicate.state.rawEvents.getValue(2),
        )

        val durable = store.state(TASK_ID)
        assertFailsWith<ProjectionException> {
            projection.applyEvent(event(2, rawSuffix = "conflict"))
        }
        assertEquals(transactionsBeforeDuplicate, store.transactionCount)
        assertSame(durable, store.state(TASK_ID))
        store.failAtStage = null
        store.failFinalCommit = false
        assertFailsWith<ProjectionException> { projection.applyEvent(event(4)) }
        assertSame(durable, store.state(TASK_ID))
        assertFailsWith<ProjectionException> {
            projection.applyEvent(event(3, streamId = OTHER_STREAM_ID))
        }
        assertSame(durable, store.state(TASK_ID))
    }

    @Test
    fun `commits a contiguous new event batch once without changing exact event bytes`() {
        val store = ReferenceProjectionStore()
        val projection = ReliableProjection(store)
        val events = (1L..24L).map(::event)

        val result = projection.applyEventBatch(events)

        assertEquals(1, store.transactionCount)
        assertEquals(1, store.commitCount)
        assertEquals(24, result.state.rawEvents.size)
        assertEquals(24, result.state.throughSequence)
        assertEquals(PiEventAckFrame(TASK_ID, STREAM_ID, 24), result.ack)
        assertEquals(1, result.state.stagedRawFrameBatches.size)
        assertEquals(24, result.state.stagedRawFrameBatches.single().frames.size)
        events.forEachIndexed { index, received ->
            assertContentEquals(
                received.rawBytes,
                result.state.rawEvents.getValue(index + 1L).rawBytes,
            )
        }
    }

    @Test
    fun `event batch rolls back as one unit on a gap or write failure`() {
        val gapStore = ReferenceProjectionStore()
        val gapProjection = ReliableProjection(gapStore)
        assertFailsWith<InjectedProjectionFailure> {
            gapStore.failAtStage = ProjectionWriteStage.PROJECTION
            gapProjection.applyEventBatch(listOf(event(1), event(2)))
        }
        assertNull(gapStore.state(TASK_ID))
        assertEquals(0, gapStore.commitCount)

        val sequenceStore = ReferenceProjectionStore()
        assertFailsWith<ProjectionException> {
            ReliableProjection(sequenceStore).applyEventBatch(listOf(event(1), event(3)))
        }
        assertNull(sequenceStore.state(TASK_ID))
        assertEquals(0, sequenceStore.commitCount)
    }

    @Test
    fun `rolls back every write stage and final commit before any ACK can be observed`() {
        val failures: List<ProjectionWriteStage?> = ProjectionWriteStage.entries + listOf(null)
        failures.forEach { stage ->
            val store = ReferenceProjectionStore().apply {
                seed(DurableTaskProjection(taskId = TASK_ID))
                failAtStage = stage
                failFinalCommit = stage == null
            }
            val projection = ReliableProjection(store)
            var ackObserverCount = 0
            val before = store.state(TASK_ID)

            assertFailsWith<InjectedProjectionFailure> {
                projection.applyEvent(event(1)).ack?.let { ackObserverCount += 1 }
            }

            assertSame(before, store.state(TASK_ID), "failure=$stage must keep old durable state")
            assertEquals(0, store.commitCount)
            assertEquals(0, ackObserverCount)
        }
    }

    @Test
    fun `validates replay boundaries without ACK or cursor movement`() {
        val store = ReferenceProjectionStore().apply {
            seed(baseState(through = 9, snapshotVersion = 7))
        }
        val projection = ReliableProjection(store)
        val valid = projection.applyReplayComplete(replay(9, 10))
        assertEquals(9, valid.state.throughSequence)
        assertNull(valid.ack)

        val durable = store.state(TASK_ID)
        assertFailsWith<ProjectionException> {
            projection.applyReplayComplete(replay(8, 10))
        }
        assertSame(durable, store.state(TASK_ID))
        assertFailsWith<ProjectionException> {
            projection.applyReplayComplete(replay(9, 11))
        }
        assertSame(durable, store.state(TASK_ID))
    }

    @Test
    fun `handles all resync reasons and only matching authoritative snapshot switches stream`() {
        PiResyncReason.entries.forEach { reason ->
            val requestedStream = if (reason == PiResyncReason.STREAM_CHANGED) {
                OTHER_STREAM_ID
            } else {
                STREAM_ID
            }
            val store = ReferenceProjectionStore().apply {
                seed(baseState(streamId = requestedStream, through = 5, snapshotVersion = 6))
            }
            val projection = ReliableProjection(store)
            val pending = projection.applyResyncRequired(
                resync(reason, requestedStream, STREAM_ID, snapshotVersion = 7),
            )
            assertEquals(reason, pending.state.pendingResync?.reason)
            assertNull(pending.ack)

            assertFailsWith<ProjectionException> {
                projection.applyEvent(event(6, streamId = requestedStream))
            }
            assertFailsWith<ProjectionException> {
                projection.applyDirectSnapshot(snapshot(streamId = STREAM_ID, version = 8, through = 9))
            }

            val replacement = projection.applyDirectSnapshot(
                snapshot(streamId = STREAM_ID, version = 7, through = 9),
            )
            assertEquals(STREAM_ID, replacement.state.streamId)
            assertEquals(9, replacement.state.throughSequence)
            assertEquals(7, replacement.state.snapshotVersion)
            assertNull(replacement.state.pendingResync)
            assertEquals(PiEventAckFrame(TASK_ID, STREAM_ID, 9), replacement.ack)
        }

        val staleStore = ReferenceProjectionStore().apply {
            seed(baseState(through = 9, snapshotVersion = 8))
        }
        assertFailsWith<ProjectionException> {
            ReliableProjection(staleStore).applyResyncRequired(
                resync(PiResyncReason.CURSOR_EXPIRED, STREAM_ID, STREAM_ID, snapshotVersion = 7),
            )
        }
    }

    @Test
    fun `prevents snapshot regression and prepends only contiguous current history without ACK`() {
        val store = ReferenceProjectionStore()
        val projection = ReliableProjection(store)
        val initial = projection.applyDirectSnapshot(
            snapshot(messages = listOf(JsonPrimitive("recent-2"), JsonPrimitive("recent-3"))),
        )
        assertEquals(2, initial.state.windowEndExclusive)

        val durable = store.state(TASK_ID)
        assertFailsWith<ProjectionException> {
            projection.applyDirectSnapshot(snapshot(version = 6, through = 9))
        }
        assertSame(durable, store.state(TASK_ID))
        assertFailsWith<ProjectionException> {
            projection.applyDirectSnapshot(snapshot(version = 7, through = 8))
        }
        assertSame(durable, store.state(TASK_ID))

        store.seed(
            checkNotNull(store.state(TASK_ID)).copy(
                windowStart = 2,
                windowEndExclusive = 4,
                messages = listOf(JsonPrimitive("recent-2"), JsonPrimitive("recent-3")),
            ),
        )
        val history = assembledHistory(cursorThrough = 9, windowStart = 0, windowEnd = 2)
        val prepended = projection.applyAssembledSnapshot(history)
        assertEquals(0, prepended.state.windowStart)
        assertEquals(4, prepended.state.windowEndExclusive)
        assertEquals(4, prepended.state.messages.size)
        assertNull(prepended.ack)

        val afterHistory = store.state(TASK_ID)
        assertFailsWith<ProjectionException> {
            projection.applyAssembledSnapshot(
                assembledHistory(cursorThrough = 8, windowStart = 0, windowEnd = 2),
            )
        }
        assertSame(afterHistory, store.state(TASK_ID))
        assertFailsWith<ProjectionException> {
            projection.applyAssembledSnapshot(
                assembledHistory(cursorThrough = 9, windowStart = 0, windowEnd = 1),
            )
        }
        assertSame(afterHistory, store.state(TASK_ID))
    }

    private fun event(
        sequence: Long,
        streamId: String = STREAM_ID,
        rawSuffix: String = "same",
    ): ReceivedP1bServerFrame {
        val frame = PiEventFrame(
            protocolVersion = 1,
            kind = "pi.event",
            taskId = TASK_ID,
            piSessionId = SESSION_ID,
            piVersion = P1aProtocol.PI_VERSION,
            streamId = streamId,
            sequence = sequence,
            emittedAt = "2026-07-15T00:00:00.000Z",
            event = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("message_update"),
                    "value" to JsonPrimitive(sequence),
                ),
            ),
        )
        return ReceivedP1bServerFrame(frame, "event-$streamId-$sequence-$rawSuffix".encodeToByteArray())
    }

    private fun replay(through: Long, liveFrom: Long) = ReceivedP1bServerFrame(
        PiReplayCompleteFrame(1, "pi.replay.complete", TASK_ID, STREAM_ID, through, liveFrom),
        "replay-$through-$liveFrom".encodeToByteArray(),
    )

    private fun resync(
        reason: PiResyncReason,
        requestedStream: String,
        currentStream: String,
        snapshotVersion: Long,
    ) = ReceivedP1bServerFrame(
        PiResyncRequiredFrame(
            1,
            "pi.resync_required",
            TASK_ID,
            reason,
            requestedStream,
            currentStream,
            snapshotVersion,
        ),
        "resync-$reason".encodeToByteArray(),
    )

    private fun snapshot(
        streamId: String = STREAM_ID,
        version: Long = 7,
        through: Long = 9,
        messages: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    ) = ReceivedP1bServerFrame(
        TaskSnapshotFrame(
            kind = "task.snapshot",
            requestId = "snapshot",
            taskId = TASK_ID,
            snapshotVersion = version,
            recoveryState = RecoveryState.NORMAL,
            runState = TaskRunState.IDLE,
            piSessionId = SESSION_ID,
            pi = RawPiSnapshot(messages, false, emptyList()),
            pendingAttention = emptyList(),
            deviceCalls = emptyList(),
            cursor = StreamCursor(streamId, through, 1),
        ),
        "snapshot-$streamId-$version-$through-${messages.size}".encodeToByteArray(),
    )

    private fun assembledHistory(
        cursorThrough: Long,
        windowStart: Long,
        windowEnd: Long,
    ): AssembledSnapshot {
        val begin = TaskSnapshotBeginFrame(
            protocolVersion = 1,
            kind = "task.snapshot.begin",
            requestId = "history",
            transferMode = SnapshotTransferMode.PREPEND_HISTORY,
            taskId = TASK_ID,
            snapshotVersion = 7,
            recoveryState = RecoveryState.NORMAL,
            runState = TaskRunState.IDLE,
            piSessionId = SESSION_ID,
            isStreaming = false,
            queue = emptyList(),
            pendingAttention = emptyList(),
            deviceCalls = emptyList(),
            cursor = StreamCursor(STREAM_ID, cursorThrough, 1),
            totalMessages = 4,
            window = SnapshotWindow(windowStart, windowEnd, false),
        )
        val messages = (windowStart until windowEnd).map { JsonPrimitive("history-$it") }
        val page = TaskSnapshotPageFrame(
            1,
            "task.snapshot.page",
            TASK_ID,
            7,
            0,
            windowStart,
            messages,
        )
        return AssembledSnapshot(
            begin,
            "begin".encodeToByteArray(),
            listOf(RawSnapshotPage(page, "page".encodeToByteArray())),
            "end".encodeToByteArray(),
            4,
        )
    }

    private fun baseState(
        streamId: String = STREAM_ID,
        through: Long,
        snapshotVersion: Long,
    ) = DurableTaskProjection(
        taskId = TASK_ID,
        streamId = streamId,
        throughSequence = through,
        snapshotVersion = snapshotVersion,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val SESSION_ID = "22222222-2222-4222-8222-222222222222"
        const val STREAM_ID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_STREAM_ID = "99999999-9999-4999-8999-999999999999"
    }
}
