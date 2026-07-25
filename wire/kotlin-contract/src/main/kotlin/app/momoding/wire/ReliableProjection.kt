package app.momoding.wire

import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class ProjectionWriteStage {
    RAW,
    PROJECTION,
    CURSOR,
    FINAL,
}

interface ProjectionTransaction {
    val current: DurableTaskProjection?
    fun checkpoint(stage: ProjectionWriteStage)
    fun replace(next: DurableTaskProjection)
}

interface ProjectionTransactionStore {
    /** Returns the latest committed state without opening a write transaction. */
    fun read(taskId: String): DurableTaskProjection?

    fun <T> transaction(taskId: String, block: (ProjectionTransaction) -> T): T
}

class StagedRawFrame(
    val kind: String,
    rawBytes: ByteArray,
) {
    private val retained = rawBytes.copyOf()
    val rawBytes: ByteArray get() = retained.copyOf()
    val sha256: String = sha256(retained)
    val byteCount: Long = retained.size.toLong()
}

data class StagedRawFrameBatch(
    val batchOrdinal: Long,
    val batchKind: String,
    val frames: List<StagedRawFrame>,
) {
    val byteCount: Long = frames.fold(0L) { total, frame ->
        try {
            Math.addExact(total, frame.byteCount)
        } catch (error: ArithmeticException) {
            throw RetentionPolicyException("Staged batch byte counter overflow")
        }
    }
}

class RawPiEventRecord(
    val sequence: Long,
    val digest: String,
    rawBytes: ByteArray,
    val event: JsonObject,
) {
    private val retained = rawBytes.copyOf()
    val rawBytes: ByteArray get() = retained.copyOf()
    val byteCount: Long = retained.size.toLong()
}

data class PendingResync(
    val reason: PiResyncReason,
    val requestedStreamId: String,
    val currentStreamId: String,
    val snapshotVersion: Long,
)

data class DurableTaskProjection(
    val taskId: String,
    val streamId: String? = null,
    val throughSequence: Long = 0,
    val snapshotVersion: Long? = null,
    val windowStart: Long = 0,
    val windowEndExclusive: Long = 0,
    val messages: List<JsonElement> = emptyList(),
    val queue: List<JsonElement> = emptyList(),
    val pendingAttention: List<JsonElement> = emptyList(),
    val deviceCalls: List<SnapshotDeviceCall> = emptyList(),
    val recoveryState: RecoveryState? = null,
    val runState: TaskRunState? = null,
    val piSessionId: String? = null,
    val isStreaming: Boolean = false,
    val rawEvents: Map<Long, RawPiEventRecord> = emptyMap(),
    val pendingResync: PendingResync? = null,
    val nextStageBatchOrdinal: Long = 1,
    val stagedRawFrameBatches: List<StagedRawFrameBatch> = emptyList(),
) {
    val stagedRawFrames: List<StagedRawFrame>
        get() = stagedRawFrameBatches.flatMap { it.frames }
}

data class ProjectionApplyResult(
    val state: DurableTaskProjection,
    val kind: String,
    val ack: PiEventAckFrame? = null,
    val snapshotRecommendation: RawSnapshotRecommendation? = null,
)

class RawBudgetSnapshotRequiredException(
    val expectation: RawBudgetSnapshotExpectation,
) : IllegalStateException("Raw-event hard budget requires an authoritative snapshot")

class ProjectionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ReliableProjection(
    private val store: ProjectionTransactionStore,
) {
    /**
     * Commits one contiguous batch of new pi.event frames in a single durable transaction.
     *
     * This is intentionally stricter than [applyEvent]: callers must pass only unseen events.
     * Replay/deduplication still uses [applyEvent], while high-frequency local streams can avoid
     * reopening and rewriting the durable projection for every text delta.
     */
    @Synchronized
    fun applyEventBatch(
        receivedFrames: List<ReceivedReliabilityServerFrame>,
    ): ProjectionApplyResult {
        require(receivedFrames.isNotEmpty()) { "applyEventBatch requires at least one pi.event" }
        val frames = receivedFrames.map { received ->
            val event = received.frame as? PiEventFrame
                ?: throw ProjectionException("applyEventBatch requires pi.event frames")
            PreparedPiEvent(event, received.rawBytes, sha256(received.rawBytes))
        }
        val first = frames.first().event
        if (frames.any { it.event.taskId != first.taskId }) {
            throw ProjectionException("Event batch cannot contain multiple tasks")
        }
        var snapshotRecommendation: RawSnapshotRecommendation? = null
        val committed = commit(
            taskId = first.taskId,
            staged = frames.map { StagedRawFrame(it.event.kind, it.rawBytes) },
            batchKind = first.kind,
        ) { current ->
            var state = current
            frames.forEach { prepared ->
                val event = prepared.event
                validateNewEvent(state, event)
                val admission = RawFrameRetentionPolicy.admitEvent(
                    state = state,
                    taskId = event.taskId,
                    streamId = event.streamId,
                    sequence = event.sequence,
                    rawByteCount = prepared.rawBytes.size.toLong(),
                )
                if (admission is RequireRawBudgetSnapshot) {
                    throw RawBudgetSnapshotRequiredException(admission.expectation)
                }
                admission as AcceptRawEvent
                snapshotRecommendation = admission.snapshotRecommendation ?: snapshotRecommendation
                val prior = state ?: DurableTaskProjection(taskId = event.taskId)
                val record = RawPiEventRecord(
                    sequence = event.sequence,
                    digest = prepared.digest,
                    rawBytes = prepared.rawBytes,
                    event = event.event,
                )
                state = prior.copy(
                    streamId = event.streamId,
                    throughSequence = event.sequence,
                    rawEvents = prior.rawEvents + (event.sequence to record),
                )
            }
            requireNotNull(state)
        }
        val last = frames.last().event
        return ProjectionApplyResult(
            state = committed,
            kind = last.kind,
            ack = PiEventAckFrame(last.taskId, last.streamId, committed.throughSequence),
            snapshotRecommendation = snapshotRecommendation,
        )
    }

    @Synchronized
    fun applyEvent(received: ReceivedReliabilityServerFrame): ProjectionApplyResult {
        val event = received.frame as? PiEventFrame
            ?: throw ProjectionException("applyEvent requires pi.event")
        val raw = received.rawBytes
        val digest = sha256(raw)
        val before = store.read(event.taskId)
        findCommittedDuplicate(before, event, digest)?.let { committed ->
            return ProjectionApplyResult(
                state = committed,
                kind = event.kind,
                ack = PiEventAckFrame(event.taskId, event.streamId, committed.throughSequence),
            )
        }
        validateNewEvent(before, event)
        val admission = RawFrameRetentionPolicy.admitEvent(
            state = before,
            taskId = event.taskId,
            streamId = event.streamId,
            sequence = event.sequence,
            rawByteCount = raw.size.toLong(),
        )
        if (admission is RequireRawBudgetSnapshot) {
            throw RawBudgetSnapshotRequiredException(admission.expectation)
        }
        admission as AcceptRawEvent
        val committed = commit(
            taskId = event.taskId,
            staged = listOf(StagedRawFrame(event.kind, raw)),
            batchKind = event.kind,
        ) { current ->
            val state = current ?: DurableTaskProjection(taskId = event.taskId)
            if (state.pendingResync != null) {
                throw ProjectionException("Events are blocked while resync is pending")
            }
            val streamId = state.streamId
            if (streamId != null && streamId != event.streamId) {
                throw ProjectionException("Event stream does not match durable stream")
            }
            if (streamId == null && event.sequence != 1L) {
                throw ProjectionException("First event for a task must start at sequence 1")
            }

            when {
                event.sequence == state.throughSequence + 1 -> {
                    val record = RawPiEventRecord(
                        sequence = event.sequence,
                        digest = digest,
                        rawBytes = raw,
                        event = event.event,
                    )
                    state.copy(
                        streamId = event.streamId,
                        throughSequence = event.sequence,
                        rawEvents = state.rawEvents + (event.sequence to record),
                    )
                }
                event.sequence <= state.throughSequence -> {
                    val prior = state.rawEvents[event.sequence]
                    if (prior != null && prior.digest != digest) {
                        throw ProjectionException("Duplicate event sequence has conflicting bytes")
                    }
                    state
                }
                else -> throw ProjectionException("Event sequence gap requires reconnect/resume")
            }
        }
        return ProjectionApplyResult(
            state = committed,
            kind = event.kind,
            ack = PiEventAckFrame(event.taskId, event.streamId, committed.throughSequence),
            snapshotRecommendation = admission.snapshotRecommendation,
        )
    }

    private data class PreparedPiEvent(
        val event: PiEventFrame,
        val rawBytes: ByteArray,
        val digest: String,
    )

    @Synchronized
    fun applyReplayComplete(
        received: ReceivedReliabilityServerFrame,
    ): ProjectionApplyResult {
        val replay = received.frame as? PiReplayCompleteFrame
            ?: throw ProjectionException("applyReplayComplete requires pi.replay.complete")
        val committed = commit(
            replay.taskId,
            listOf(StagedRawFrame(replay.kind, received.rawBytes)),
            replay.kind,
        ) { current ->
            val state = current ?: throw ProjectionException("Replay has no durable task")
            if (state.pendingResync != null) {
                throw ProjectionException("Replay complete is invalid during pending resync")
            }
            if (
                state.streamId != replay.streamId ||
                state.throughSequence != replay.replayedThroughSequence ||
                replay.liveFromSequence != state.throughSequence + 1
            ) {
                throw ProjectionException("Replay complete does not match durable cursor")
            }
            state
        }
        return ProjectionApplyResult(committed, replay.kind)
    }

    @Synchronized
    fun applyResyncRequired(
        received: ReceivedReliabilityServerFrame,
    ): ProjectionApplyResult {
        val resync = received.frame as? PiResyncRequiredFrame
            ?: throw ProjectionException("applyResyncRequired requires pi.resync_required")
        val committed = commit(
            resync.taskId,
            listOf(StagedRawFrame(resync.kind, received.rawBytes)),
            resync.kind,
        ) { current ->
            val state = current ?: DurableTaskProjection(taskId = resync.taskId)
            if (state.streamId != null && state.streamId != resync.requestedStreamId) {
                throw ProjectionException("Resync requestedStreamId does not match durable stream")
            }
            if (
                state.snapshotVersion != null &&
                resync.snapshotVersion < state.snapshotVersion
            ) {
                throw ProjectionException("Resync snapshotVersion must not move backwards")
            }
            state.copy(
                pendingResync = PendingResync(
                    reason = resync.reason,
                    requestedStreamId = resync.requestedStreamId,
                    currentStreamId = resync.currentStreamId,
                    snapshotVersion = resync.snapshotVersion,
                ),
            )
        }
        return ProjectionApplyResult(committed, resync.kind)
    }

    @Synchronized
    fun applyDirectSnapshot(
        received: ReceivedReliabilityServerFrame,
    ): ProjectionApplyResult {
        val snapshot = received.frame as? TaskSnapshotFrame
            ?: throw ProjectionException("applyDirectSnapshot requires task.snapshot")
        val committed = commit(
            snapshot.taskId,
            listOf(StagedRawFrame(snapshot.kind, received.rawBytes)),
            "task.snapshot.replace",
            StagedBatchMode.REPLACE,
        ) { current ->
            replaceSnapshot(
                current = current,
                taskId = snapshot.taskId,
                snapshotVersion = snapshot.snapshotVersion,
                streamId = snapshot.cursor.streamId,
                throughSequence = snapshot.cursor.highWatermarkSequence,
                windowStart = 0,
                windowEndExclusive = snapshot.pi.messages.size.toLong(),
                messages = snapshot.pi.messages,
                queue = snapshot.pi.queue,
                pendingAttention = snapshot.pendingAttention,
                deviceCalls = snapshot.deviceCalls,
                recoveryState = snapshot.recoveryState,
                runState = snapshot.runState,
                piSessionId = snapshot.piSessionId,
                isStreaming = snapshot.pi.isStreaming,
            )
        }
        return ProjectionApplyResult(
            committed,
            snapshot.kind,
            PiEventAckFrame(snapshot.taskId, committed.streamId!!, committed.throughSequence),
        )
    }

    @Synchronized
    fun applyAssembledSnapshot(assembled: AssembledSnapshot): ProjectionApplyResult {
        val begin = assembled.begin
        val staged = buildList {
            add(StagedRawFrame("task.snapshot.begin", assembled.beginRawBytes))
            assembled.pages.forEach { page ->
                add(StagedRawFrame("task.snapshot.page", page.rawBytes))
            }
            add(StagedRawFrame("task.snapshot.end", assembled.endRawBytes))
        }
        val messages = assembled.pages.flatMap { it.frame.messages }
        val stageMode = if (begin.transferMode == SnapshotTransferMode.REPLACE) {
            StagedBatchMode.REPLACE
        } else {
            StagedBatchMode.APPEND
        }
        val committed = commit(
            begin.taskId,
            staged,
            "task.snapshot.${begin.transferMode.name.lowercase()}",
            stageMode,
        ) { current ->
            when (begin.transferMode) {
                SnapshotTransferMode.REPLACE -> replaceSnapshot(
                    current = current,
                    taskId = begin.taskId,
                    snapshotVersion = begin.snapshotVersion,
                    streamId = begin.cursor.streamId,
                    throughSequence = begin.cursor.highWatermarkSequence,
                    windowStart = begin.window.messageStartIndex,
                    windowEndExclusive = begin.window.messageEndExclusive,
                    messages = messages,
                    queue = begin.queue,
                    pendingAttention = begin.pendingAttention,
                    deviceCalls = begin.deviceCalls,
                    recoveryState = begin.recoveryState,
                    runState = begin.runState,
                    piSessionId = begin.piSessionId,
                    isStreaming = begin.isStreaming,
                )
                SnapshotTransferMode.PREPEND_HISTORY -> prependHistory(current, begin, messages)
            }
        }
        val ack = if (begin.transferMode == SnapshotTransferMode.REPLACE) {
            PiEventAckFrame(begin.taskId, committed.streamId!!, committed.throughSequence)
        } else {
            null
        }
        return ProjectionApplyResult(committed, "task.snapshot", ack)
    }

    private fun findCommittedDuplicate(
        state: DurableTaskProjection?,
        event: PiEventFrame,
        digest: String,
    ): DurableTaskProjection? {
        if (state == null || event.sequence > state.throughSequence) return null
        if (state.pendingResync != null) {
            throw ProjectionException("Events are blocked while resync is pending")
        }
        if (state.streamId != event.streamId) {
            throw ProjectionException("Event stream does not match durable stream")
        }
        val prior = state.rawEvents[event.sequence]
        if (prior != null && prior.digest != digest) {
            throw ProjectionException("Duplicate event sequence has conflicting bytes")
        }
        return state
    }

    private fun validateNewEvent(state: DurableTaskProjection?, event: PiEventFrame) {
        val current = state ?: DurableTaskProjection(taskId = event.taskId)
        if (current.pendingResync != null) {
            throw ProjectionException("Events are blocked while resync is pending")
        }
        if (current.streamId != null && current.streamId != event.streamId) {
            throw ProjectionException("Event stream does not match durable stream")
        }
        if (current.streamId == null && event.sequence != 1L) {
            throw ProjectionException("First event for a task must start at sequence 1")
        }
        if (event.sequence != current.throughSequence + 1) {
            throw ProjectionException("Event sequence gap requires reconnect/resume")
        }
    }

    private fun replaceSnapshot(
        current: DurableTaskProjection?,
        taskId: String,
        snapshotVersion: Long,
        streamId: String,
        throughSequence: Long,
        windowStart: Long,
        windowEndExclusive: Long,
        messages: List<JsonElement>,
        queue: List<JsonElement>,
        pendingAttention: List<JsonElement>,
        deviceCalls: List<SnapshotDeviceCall>,
        recoveryState: RecoveryState,
        runState: TaskRunState,
        piSessionId: String,
        isStreaming: Boolean,
    ): DurableTaskProjection {
        val state = current ?: DurableTaskProjection(taskId = taskId)
        val oldVersion = state.snapshotVersion
        if (oldVersion != null && snapshotVersion < oldVersion) {
            throw ProjectionException("Snapshot version must not move backwards")
        }
        if (
            state.pendingResync == null &&
            state.streamId == streamId &&
            throughSequence < state.throughSequence
        ) {
            throw ProjectionException("Snapshot cursor must not move backwards on the current stream")
        }
        state.pendingResync?.let { pending ->
            if (pending.currentStreamId != streamId || pending.snapshotVersion != snapshotVersion) {
                throw ProjectionException("Snapshot does not satisfy pending resync")
            }
        }
        if (windowEndExclusive - windowStart != messages.size.toLong()) {
            throw ProjectionException("Snapshot messages do not match declared window")
        }
        return state.copy(
            streamId = streamId,
            throughSequence = throughSequence,
            snapshotVersion = snapshotVersion,
            windowStart = windowStart,
            windowEndExclusive = windowEndExclusive,
            messages = messages.toList(),
            queue = queue.toList(),
            pendingAttention = pendingAttention.toList(),
            deviceCalls = deviceCalls.toList(),
            recoveryState = recoveryState,
            runState = runState,
            piSessionId = piSessionId,
            isStreaming = isStreaming,
            rawEvents = emptyMap(),
            pendingResync = null,
        )
    }

    private fun prependHistory(
        current: DurableTaskProjection?,
        begin: TaskSnapshotBeginFrame,
        historyMessages: List<JsonElement>,
    ): DurableTaskProjection {
        val state = current ?: throw ProjectionException("History has no durable task")
        if (
            state.snapshotVersion != begin.snapshotVersion ||
            state.streamId != begin.cursor.streamId ||
            state.throughSequence != begin.cursor.highWatermarkSequence
        ) {
            throw ProjectionException("History task version/stream/cursor is stale")
        }
        if (begin.window.messageEndExclusive != state.windowStart) {
            throw ProjectionException("History window is not contiguous with durable projection")
        }
        if (
            begin.window.messageEndExclusive - begin.window.messageStartIndex !=
            historyMessages.size.toLong()
        ) {
            throw ProjectionException("History messages do not match declared window")
        }
        return state.copy(
            windowStart = begin.window.messageStartIndex,
            messages = historyMessages + state.messages,
        )
    }

    private fun commit(
        taskId: String,
        staged: List<StagedRawFrame>,
        batchKind: String,
        stageMode: StagedBatchMode = StagedBatchMode.APPEND,
        transform: (DurableTaskProjection?) -> DurableTaskProjection,
    ): DurableTaskProjection = store.transaction(taskId) { transaction ->
        transaction.checkpoint(ProjectionWriteStage.RAW)
        val transformed = transform(transaction.current)
        transaction.checkpoint(ProjectionWriteStage.PROJECTION)
        transaction.checkpoint(ProjectionWriteStage.CURSOR)
        val retained = RawFrameRetentionPolicy.retainBatch(
            priorBatches = transformed.stagedRawFrameBatches,
            nextBatchOrdinal = transformed.nextStageBatchOrdinal,
            batchKind = batchKind,
            currentFrames = staged,
            mode = stageMode,
        )
        val next = transformed.copy(
            nextStageBatchOrdinal = retained.nextBatchOrdinal,
            stagedRawFrameBatches = retained.batches,
        )
        transaction.replace(next)
        transaction.checkpoint(ProjectionWriteStage.FINAL)
        next
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
