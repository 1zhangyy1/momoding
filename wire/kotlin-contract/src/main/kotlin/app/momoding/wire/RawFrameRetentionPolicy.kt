package app.momoding.wire

data class RawBudgetSnapshotExpectation(
    val taskId: String,
    val streamId: String,
    val rejectedSequence: Long,
)

data class RawSnapshotRecommendation(
    val taskId: String,
    val streamId: String,
    val throughSequence: Long,
)

sealed interface RawEventAdmission

data class AcceptRawEvent(
    val snapshotRecommendation: RawSnapshotRecommendation? = null,
) : RawEventAdmission

data class RequireRawBudgetSnapshot(
    val expectation: RawBudgetSnapshotExpectation,
) : RawEventAdmission

enum class StagedBatchMode {
    APPEND,
    REPLACE,
}

data class StagedRetentionResult(
    val nextBatchOrdinal: Long,
    val batches: List<StagedRawFrameBatch>,
)

class RetentionPolicyException(message: String) : IllegalStateException(message)

/**
 * The one shared policy for raw-event progress and durable raw-frame audit retention.
 *
 * Android persists the returned state verbatim. It must not implement a second set of
 * byte, frame, batch, or snapshot-progress rules.
 */
object RawFrameRetentionPolicy {
    const val RAW_EVENT_SOFT_MAX_EVENTS: Int = 3_584
    const val RAW_EVENT_SOFT_MAX_BYTES: Long = 7L * 1024 * 1024
    const val RAW_EVENT_HARD_MAX_EVENTS: Int = 4_096
    const val RAW_EVENT_HARD_MAX_BYTES: Long = 8L * 1024 * 1024
    const val MAX_STAGED_AUDIT_FRAMES: Int = 4_096
    const val MAX_STAGED_AUDIT_BYTES: Long =
        ReliabilityProtocol.SNAPSHOT_MAX_LOGICAL_BYTES.toLong() +
            ReliabilityProtocol.SNAPSHOT_MAX_PAGES * 1024L +
            2L * CoreProtocol.MAX_FRAME_BYTES

    fun admitEvent(
        state: DurableTaskProjection?,
        taskId: String,
        streamId: String,
        sequence: Long,
        rawByteCount: Long,
    ): RawEventAdmission {
        if (rawByteCount !in 1..ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong()) {
            throw RetentionPolicyException(
                "Logical pi.event bytes must be 1-${ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES}",
            )
        }
        val records = state?.rawEvents.orEmpty().values
        if (records.size > RAW_EVENT_HARD_MAX_EVENTS) {
            throw RetentionPolicyException("Durable raw-event count exceeds the hard maximum")
        }
        val retainedBytes = records.fold(0L) { total, record ->
            if (record.byteCount !in 1..ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong()) {
                throw RetentionPolicyException("Durable raw-event byte count is invalid")
            }
            checkedAdd(total, record.byteCount)
        }
        if (
            retainedBytes > RAW_EVENT_HARD_MAX_BYTES &&
            (records.size != 1 || retainedBytes > ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES)
        ) {
            throw RetentionPolicyException("Durable raw-event generation exceeds its hard budget")
        }

        val nextCount = records.size + 1
        val nextBytes = checkedAdd(retainedBytes, rawByteCount)
        val effectiveByteLimit = if (records.isEmpty() && rawByteCount > RAW_EVENT_HARD_MAX_BYTES) {
            rawByteCount
        } else {
            RAW_EVENT_HARD_MAX_BYTES
        }
        if (nextCount > RAW_EVENT_HARD_MAX_EVENTS || nextBytes > effectiveByteLimit) {
            return RequireRawBudgetSnapshot(
                RawBudgetSnapshotExpectation(taskId, streamId, sequence),
            )
        }

        val recommendSnapshot =
            nextCount >= RAW_EVENT_SOFT_MAX_EVENTS ||
                nextBytes >= RAW_EVENT_SOFT_MAX_BYTES ||
                rawByteCount > RAW_EVENT_HARD_MAX_BYTES
        return AcceptRawEvent(
            if (recommendSnapshot) {
                RawSnapshotRecommendation(taskId, streamId, sequence)
            } else {
                null
            },
        )
    }

    fun snapshotSatisfies(
        expectation: RawBudgetSnapshotExpectation,
        taskId: String,
        streamId: String,
        highWatermarkSequence: Long,
    ): Boolean =
        expectation.taskId == taskId &&
            expectation.streamId == streamId &&
            highWatermarkSequence >= expectation.rejectedSequence

    fun retainBatch(
        priorBatches: List<StagedRawFrameBatch>,
        nextBatchOrdinal: Long,
        batchKind: String,
        currentFrames: List<StagedRawFrame>,
        mode: StagedBatchMode,
    ): StagedRetentionResult {
        validatePriorBatches(priorBatches, nextBatchOrdinal)
        if (batchKind.isBlank()) throw RetentionPolicyException("Staged batch kind is blank")
        if (currentFrames.isEmpty()) throw RetentionPolicyException("Staged batch is empty")
        if (currentFrames.size > MAX_STAGED_AUDIT_FRAMES) {
            throw RetentionPolicyException("Current staged batch exceeds the frame maximum")
        }
        val currentBytes = batchBytes(currentFrames)
        if (currentBytes > MAX_STAGED_AUDIT_BYTES) {
            throw RetentionPolicyException("Current staged batch exceeds the byte maximum")
        }
        if (nextBatchOrdinal !in 1 until ReliabilityProtocol.MAX_SAFE_INTEGER) {
            throw RetentionPolicyException("Next staged batch ordinal is exhausted")
        }

        val current = StagedRawFrameBatch(nextBatchOrdinal, batchKind, currentFrames.toList())
        val retained = when (mode) {
            StagedBatchMode.REPLACE -> mutableListOf(current)
            StagedBatchMode.APPEND -> (priorBatches + current).toMutableList()
        }
        while (
            retained.size > 1 &&
            (ringFrameCount(retained) > MAX_STAGED_AUDIT_FRAMES ||
                ringByteCount(retained) > MAX_STAGED_AUDIT_BYTES)
        ) {
            retained.removeAt(0)
        }
        if (
            ringFrameCount(retained) > MAX_STAGED_AUDIT_FRAMES ||
            ringByteCount(retained) > MAX_STAGED_AUDIT_BYTES
        ) {
            throw RetentionPolicyException("Staged audit ring cannot retain the current batch")
        }
        return StagedRetentionResult(nextBatchOrdinal + 1, retained.toList())
    }

    private fun validatePriorBatches(
        batches: List<StagedRawFrameBatch>,
        nextBatchOrdinal: Long,
    ) {
        var priorOrdinal = 0L
        batches.forEach { batch ->
            if (batch.batchOrdinal <= priorOrdinal || batch.batchOrdinal >= nextBatchOrdinal) {
                throw RetentionPolicyException("Staged batch ordinals are not strictly monotonic")
            }
            if (batch.batchKind.isBlank() || batch.frames.isEmpty()) {
                throw RetentionPolicyException("Durable staged batch is invalid")
            }
            if (batch.frames.size > MAX_STAGED_AUDIT_FRAMES) {
                throw RetentionPolicyException("Durable staged batch exceeds the frame maximum")
            }
            if (batchBytes(batch.frames) > MAX_STAGED_AUDIT_BYTES) {
                throw RetentionPolicyException("Durable staged batch exceeds the byte maximum")
            }
            priorOrdinal = batch.batchOrdinal
        }
    }

    private fun ringFrameCount(batches: List<StagedRawFrameBatch>): Int =
        batches.fold(0) { total, batch ->
            try {
                Math.addExact(total, batch.frames.size)
            } catch (error: ArithmeticException) {
                throw RetentionPolicyException("Retention frame counter overflow")
            }
        }

    private fun ringByteCount(batches: List<StagedRawFrameBatch>): Long =
        batches.fold(0L) { total, batch -> checkedAdd(total, batch.byteCount) }

    private fun batchBytes(frames: List<StagedRawFrame>): Long =
        frames.fold(0L) { total, frame ->
            if (frame.byteCount <= 0) {
                throw RetentionPolicyException("Staged raw frame byte count must be positive")
            }
            checkedAdd(total, frame.byteCount)
        }

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw RetentionPolicyException("Retention byte counter overflow")
    }
}
