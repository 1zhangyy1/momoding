package app.momoding.core.data

import app.momoding.wire.DurableTaskProjection
import app.momoding.wire.P1bProtocol
import app.momoding.wire.PendingResync
import app.momoding.wire.PiResyncReason
import app.momoding.wire.ProjectionTransaction
import app.momoding.wire.ProjectionTransactionStore
import app.momoding.wire.ProjectionWriteStage
import app.momoding.wire.RawFrameRetentionPolicy
import app.momoding.wire.RawPiEventRecord
import app.momoding.wire.RecoveryState
import app.momoding.wire.SnapshotDeviceCall
import app.momoding.wire.StagedRawFrame
import app.momoding.wire.StagedRawFrameBatch
import app.momoding.wire.TaskRunState
import app.momoding.core.policy.TaskApprovalMode
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ProjectionCorruptionException(message: String) : IllegalStateException(message)

/**
 * Android's durable implementation of the shared receiver transaction contract.
 *
 * The database is intentionally synchronous here: the single-owner receiver actor must invoke
 * it from its IO dispatcher. MomodingDatabase never enables main-thread queries.
 */
class RoomProjectionTransactionStore(
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ProjectionTransactionStore {
    private val dao: MomodingDao = database.momodingDao()
    private val attentionValidator = RoomAttentionLedger(database, nowMillis)

    override fun read(taskId: String): DurableTaskProjection? =
        database.runInTransaction<DurableTaskProjection?> { loadProjection(taskId) }

    override fun <T> transaction(
        taskId: String,
        block: (ProjectionTransaction) -> T,
    ): T = database.runInTransaction<T> {
        val transaction = RoomProjectionTransaction(taskId, loadProjection(taskId))
        val result = block(transaction)
        transaction.assertComplete()
        result
    }

    private inner class RoomProjectionTransaction(
        private val taskId: String,
        override val current: DurableTaskProjection?,
    ) : ProjectionTransaction {
        private val expectedStages = ProjectionWriteStage.entries
        private var nextStageIndex = 0
        private var replaced = false

        override fun checkpoint(stage: ProjectionWriteStage) {
            val expected = expectedStages.getOrNull(nextStageIndex)
                ?: throw IllegalStateException("Projection transaction has already completed")
            check(stage == expected) {
                "Projection checkpoint $stage arrived while $expected was required"
            }
            if (stage == ProjectionWriteStage.FINAL) {
                check(replaced) { "Projection transaction reached FINAL without replace" }
            }
            nextStageIndex += 1
        }

        override fun replace(next: DurableTaskProjection) {
            check(nextStageIndex == 3) {
                "Projection replace requires RAW, PROJECTION, and CURSOR checkpoints"
            }
            check(!replaced) { "Projection transaction may replace state only once" }
            check(next.taskId == taskId) { "Projection transaction cannot change taskId" }
            persistProjection(current, next)
            applyExactPiDeliveryProofs(next)
            replaced = true
        }

        fun assertComplete() {
            check(replaced && nextStageIndex == expectedStages.size) {
                "Projection transaction returned before replace and FINAL checkpoint"
            }
        }
    }

    private fun loadProjection(taskId: String): DurableTaskProjection? {
        val task = dao.task(taskId) ?: return null
        if (task.taskId != taskId) corrupt("Task lookup returned a different taskId")
        val raw = loadRawEvents(task)
        val timeline = loadTimeline(task)
        val attention = loadPendingAttention(taskId)
        val devices = loadDeviceOperations(taskId)
        val pendingResync = dao.pendingResync(taskId)?.toModel(task)
        val staged = loadStagedBatches(task)
        val queue = parseArray(task.queueJson, "Task queue")

        if (task.throughSequence < 0) corrupt("Task cursor is negative")
        if (task.windowStart < 0 || task.windowEndExclusive < task.windowStart) {
            corrupt("Task message window is invalid")
        }
        if (task.windowEndExclusive - task.windowStart != timeline.size.toLong()) {
            corrupt("Timeline rows do not match the task message window")
        }
        if (task.nextStageBatchOrdinal !in 1 until P1bProtocol.MAX_SAFE_INTEGER) {
            corrupt("Next staged batch ordinal is invalid")
        }
        if (staged.lastOrNull()?.batchOrdinal?.let { it >= task.nextStageBatchOrdinal } == true) {
            corrupt("Next staged batch ordinal does not follow retained batches")
        }

        return DurableTaskProjection(
            taskId = task.taskId,
            streamId = task.streamId,
            throughSequence = task.throughSequence,
            snapshotVersion = task.snapshotVersion,
            windowStart = task.windowStart,
            windowEndExclusive = task.windowEndExclusive,
            messages = timeline.map { parseJson(it.rawPayload, "Timeline raw payload") },
            queue = queue,
            pendingAttention = attention,
            deviceCalls = devices,
            recoveryState = task.recoveryState?.let(::parseRecoveryState),
            runState = task.runState?.let(::parseRunState),
            piSessionId = task.piSessionId,
            isStreaming = task.isStreaming,
            rawEvents = raw,
            pendingResync = pendingResync,
            nextStageBatchOrdinal = task.nextStageBatchOrdinal,
            stagedRawFrameBatches = staged,
        )
    }

    private fun loadRawEvents(task: TaskEntity): Map<Long, RawPiEventRecord> {
        val entities = dao.rawEvents(task.taskId)
        if (entities.isNotEmpty() && task.streamId == null) {
            corrupt("Raw events exist without a durable stream")
        }
        entities.zipWithNext().forEach { (left, right) ->
            if (right.sequence != left.sequence + 1) corrupt("Raw event sequence is not contiguous")
        }
        if (entities.lastOrNull()?.sequence?.let { it != task.throughSequence } == true) {
            corrupt("Raw event tail does not match the durable cursor")
        }
        return entities.associate { entity ->
            if (entity.sequence <= 0 || entity.streamId != task.streamId) {
                corrupt("Raw event identity does not match its task cursor")
            }
            val digest = sha256(entity.rawEnvelope)
            if (entity.digest != digest) corrupt("Raw event digest does not match exact bytes")
            val envelope = parseObjectBytes(entity.rawEnvelope, "Raw pi.event envelope")
            if (stringField(envelope, "kind") != "pi.event") corrupt("Raw event kind is invalid")
            if (stringField(envelope, "taskId") != task.taskId) corrupt("Raw event taskId is invalid")
            if (stringField(envelope, "streamId") != entity.streamId) corrupt("Raw event streamId is invalid")
            if (longField(envelope, "sequence") != entity.sequence) corrupt("Raw event sequence is invalid")
            val event = envelope["event"] as? JsonObject
                ?: corrupt("Raw pi.event does not contain an object event")
            val storedEvent = parseJson(entity.eventJson, "Raw event JSON") as? JsonObject
                ?: corrupt("Stored raw event JSON is not an object")
            if (event != storedEvent) corrupt("Stored raw event JSON differs from exact envelope")
            entity.sequence to RawPiEventRecord(
                sequence = entity.sequence,
                digest = entity.digest,
                rawBytes = entity.rawEnvelope,
                event = storedEvent,
            )
        }
    }

    private fun loadTimeline(task: TaskEntity): List<TimelineProjectionEntity> =
        dao.timeline(task.taskId).also { rows ->
            rows.forEachIndexed { index, row ->
                val expectedOrdinal = task.windowStart + index
                if (row.ordinal != expectedOrdinal) corrupt("Timeline ordinal is not contiguous")
                val raw = parseJson(row.rawPayload, "Timeline raw payload")
                parseJson(row.presentationJson, "Timeline presentation")
                val expectedId = stableTimelineId(row.ordinal, row.rawPayload)
                if (row.stableItemId != expectedId) corrupt("Timeline stable ID is invalid")
                if (row.kind != timelineKind(raw)) corrupt("Timeline kind is invalid")
            }
        }

    private fun loadPendingAttention(taskId: String): List<JsonElement> =
        dao.hostPendingAttention(taskId).mapIndexed { index, row ->
            if (row.ordinal != index) corrupt("Pending-attention ordinal is not contiguous")
            val raw = parseJson(row.rawPayload, "Pending-attention payload")
            val objectValue = raw as? JsonObject
            if (row.callId != objectValue.stringOrNull("callId")) corrupt("Attention callId is invalid")
            if (row.toolName != objectValue.stringOrNull("toolName")) corrupt("Attention toolName is invalid")
            if (row.state != objectValue.stringOrNull("state")) corrupt("Attention state is invalid")
            if (row.expiresAt != objectValue.stringOrNull("expiresAt")) corrupt("Attention expiry is invalid")
            if (row.originFocusKey != objectValue.stringOrNull("originFocusKey")) {
                corrupt("Attention origin focus key is invalid")
            }
            if (row.argumentsJson != objectValue.elementOrNull("arguments")) {
                corrupt("Attention arguments are invalid")
            }
            if (row.terminalResultJson != objectValue.elementOrNull("terminalResult")) {
                corrupt("Attention terminal result is invalid")
            }
            raw
        }

    private fun loadDeviceOperations(taskId: String): List<SnapshotDeviceCall> =
        dao.hostDeviceCallObservations(taskId).mapIndexed { index, row ->
            if (row.ordinal != index) corrupt("Device-operation ordinal is not contiguous")
            SnapshotDeviceCall(row.callId, row.operationId, row.hostState)
        }

    private fun loadStagedBatches(task: TaskEntity): List<StagedRawFrameBatch> {
        val rows = dao.stagedRawFrames(task.taskId)
        val batches = rows.groupBy { it.batchOrdinal }.map { (ordinal, batchRows) ->
            if (ordinal <= 0) corrupt("Staged batch ordinal is invalid")
            val batchKind = batchRows.first().batchKind
            if (batchKind.isBlank() || batchRows.any { it.batchKind != batchKind }) {
                corrupt("Staged batch kind is invalid")
            }
            val frames = batchRows.mapIndexed { index, row ->
                if (row.frameOrdinal != index) corrupt("Staged frame ordinal is not contiguous")
                if (row.kind.isBlank() || row.rawLogicalFrame.isEmpty()) {
                    corrupt("Staged raw frame is invalid")
                }
                if (row.byteCount != row.rawLogicalFrame.size.toLong()) {
                    corrupt("Staged raw frame byte count is invalid")
                }
                if (row.sha256 != sha256(row.rawLogicalFrame)) {
                    corrupt("Staged raw frame digest is invalid")
                }
                StagedRawFrame(row.kind, row.rawLogicalFrame)
            }
            StagedRawFrameBatch(ordinal, batchKind, frames)
        }
        batches.zipWithNext().forEach { (left, right) ->
            if (right.batchOrdinal <= left.batchOrdinal) corrupt("Staged batch ordering is invalid")
        }
        val totalFrames = batches.sumOf { it.frames.size }
        val totalBytes = checkedSum(batches.map { it.byteCount })
        if (totalFrames > RawFrameRetentionPolicy.MAX_STAGED_AUDIT_FRAMES) {
            corrupt("Staged audit ring exceeds its frame maximum")
        }
        if (totalBytes > RawFrameRetentionPolicy.MAX_STAGED_AUDIT_BYTES) {
            corrupt("Staged audit ring exceeds its byte maximum")
        }
        return batches
    }

    private fun persistProjection(
        current: DurableTaskProjection?,
        next: DurableTaskProjection,
    ) {
        validateProjection(next)
        val previousTask = dao.task(next.taskId)
        val draftMode = dao.draftForTaskSync(next.taskId)?.approvalMode
        val updatedAtMillis = nowMillis()
        dao.upsertTask(next.toEntity(previousTask, draftMode, updatedAtMillis))

        persistRawEvents(current, next)

        if (
            current == null ||
            current.windowStart != next.windowStart ||
            current.messages != next.messages
        ) {
            dao.deleteTimeline(next.taskId)
            next.messages.mapIndexed { index, message ->
                message.toTimelineEntity(next.taskId, next.windowStart + index)
            }.takeIf(List<TimelineProjectionEntity>::isNotEmpty)?.let(dao::insertTimeline)
        }

        validateSnapshotBindings(next)

        if (current == null || current.pendingAttention != next.pendingAttention) {
            dao.deleteHostPendingAttention(next.taskId)
            next.pendingAttention.mapIndexed { index, attention ->
                attention.toHostPendingAttentionEntity(next.taskId, index)
            }.takeIf(List<HostPendingAttentionEntity>::isNotEmpty)
                ?.let(dao::insertHostPendingAttention)
        }

        if (current == null || current.deviceCalls != next.deviceCalls) {
            dao.deleteHostDeviceCallObservations(next.taskId)
            next.deviceCalls.mapIndexed { index, call ->
                HostDeviceCallObservationEntity(
                    taskId = next.taskId,
                    callId = call.callId,
                    operationId = call.operationId,
                    toolName = null,
                    hostState = call.state,
                    terminalSummaryJson = null,
                    ordinal = index,
                )
            }.takeIf(List<HostDeviceCallObservationEntity>::isNotEmpty)
                ?.let(dao::insertHostDeviceCallObservations)
        }

        mergeHostDeviceObservations(next, updatedAtMillis)

        if (current == null || current.pendingResync != next.pendingResync) {
            dao.deletePendingResync(next.taskId)
            next.pendingResync?.toEntity(next.taskId)?.let(dao::insertPendingResync)
        }

        persistStagedRawFrames(current, next)

        if (next.runState == TaskRunState.STOPPED) {
            dao.releaseActiveStopFences(next.taskId, updatedAtMillis)
        }
    }

    private fun persistRawEvents(
        current: DurableTaskProjection?,
        next: DurableTaskProjection,
    ) {
        val appendOnly = current != null &&
            current.streamId == next.streamId &&
            current.rawEvents.size <= next.rawEvents.size &&
            current.rawEvents.all { (sequence, record) ->
                next.rawEvents[sequence]?.digest == record.digest
            }
        if (!appendOnly) {
            dao.deleteRawEvents(next.taskId)
        }
        val existingSequences = if (appendOnly) current.rawEvents.keys else emptySet()
        next.rawEvents.toSortedMap()
            .filterKeys { it !in existingSequences }
            .values
            .map { it.toEntity(next) }
            .takeIf(List<RawPiEventEntity>::isNotEmpty)
            ?.let(dao::insertRawEvents)
    }

    private fun persistStagedRawFrames(
        current: DurableTaskProjection?,
        next: DurableTaskProjection,
    ) {
        if (next.stagedRawFrameBatches.isEmpty()) {
            if (current?.stagedRawFrameBatches?.isNotEmpty() == true) {
                dao.deleteStagedRawFrames(next.taskId)
            }
            return
        }
        val currentByOrdinal = current?.stagedRawFrameBatches
            .orEmpty()
            .associateBy(StagedRawFrameBatch::batchOrdinal)
        val nextByOrdinal = next.stagedRawFrameBatches
            .associateBy(StagedRawFrameBatch::batchOrdinal)
        val firstRetainedOrdinal = next.stagedRawFrameBatches.first().batchOrdinal
        val currentLastOrdinal = current?.stagedRawFrameBatches
            ?.lastOrNull()
            ?.batchOrdinal
            ?: 0L
        val canAdvanceRing = current != null &&
            current.stagedRawFrameBatches
                .filter { it.batchOrdinal >= firstRetainedOrdinal }
                .all { currentBatch ->
                    nextByOrdinal[currentBatch.batchOrdinal]?.sameBytesAs(currentBatch) == true
                } &&
            next.stagedRawFrameBatches
                .filter { it.batchOrdinal !in currentByOrdinal }
                .all { it.batchOrdinal > currentLastOrdinal }
        if (canAdvanceRing) {
            dao.deleteStagedRawFramesBefore(
                next.taskId,
                firstRetainedOrdinal,
            )
        } else {
            dao.deleteStagedRawFrames(next.taskId)
        }
        val retainedOrdinals = if (canAdvanceRing) currentByOrdinal.keys else emptySet()
        next.stagedRawFrameBatches
            .filter { it.batchOrdinal !in retainedOrdinals }
            .flatMap { it.toEntities(next.taskId) }
            .takeIf(List<StagedRawFrameEntity>::isNotEmpty)
            ?.let(dao::insertStagedRawFrames)
    }

    private fun StagedRawFrameBatch.sameBytesAs(other: StagedRawFrameBatch): Boolean =
        batchKind == other.batchKind &&
            frames.size == other.frames.size &&
            frames.zip(other.frames).all { (left, right) ->
                left.kind == right.kind &&
                    left.sha256 == right.sha256 &&
                    left.byteCount == right.byteCount
            }

    private fun StagedRawFrameBatch.toEntities(taskId: String): List<StagedRawFrameEntity> =
        frames.mapIndexed { index, frame ->
            StagedRawFrameEntity(
                taskId = taskId,
                batchOrdinal = batchOrdinal,
                frameOrdinal = index,
                batchKind = batchKind,
                kind = frame.kind,
                rawLogicalFrame = frame.rawBytes,
                sha256 = frame.sha256,
                byteCount = frame.byteCount,
            )
        }

    private fun applyExactPiDeliveryProofs(next: DurableTaskProjection) {
        val taskOperations = dao.deviceOperations(next.taskId)
        val proofs = AttentionPiDeliveryProofVerifier.verify(
            projection = next,
            taskOperations = taskOperations,
            operationForCallId = dao::deviceOperation,
            expectationForValidatedPair = { operation ->
                val attention = dao.pendingAttention(operation.callId)
                    ?: throw ProjectionCorruptionException(
                        "Local device operation is missing its attention projection",
                    )
                try {
                    attentionValidator.validatePair(operation, attention)
                    attentionValidator.piDeliveryExpectationForValidatedPair(operation)
                } catch (error: ProjectionCorruptionException) {
                    throw error
                } catch (error: RuntimeException) {
                    throw ProjectionCorruptionException(
                        "Local attention terminal cannot validate Pi delivery proof",
                    )
                }
            },
        )
        proofs.forEach { proof ->
            try {
                attentionValidator.markPiDeliveredAfterVerifiedProof(
                    taskId = next.taskId,
                    callId = proof.callId,
                    terminalSemanticSha256 = proof.terminalSemanticSha256,
                )
            } catch (error: ProjectionCorruptionException) {
                throw error
            } catch (error: RuntimeException) {
                throw ProjectionCorruptionException(
                    "Verified Pi delivery proof cannot advance the local attention ledger",
                )
            }
        }
    }

    private fun validateProjection(next: DurableTaskProjection) {
        if (next.taskId.isBlank()) throw IllegalArgumentException("Projection taskId is blank")
        if (next.throughSequence < 0) throw IllegalArgumentException("Projection cursor is negative")
        if (next.windowStart < 0 || next.windowEndExclusive < next.windowStart) {
            throw IllegalArgumentException("Projection message window is invalid")
        }
        if (next.windowEndExclusive - next.windowStart != next.messages.size.toLong()) {
            throw IllegalArgumentException("Projection messages do not match its window")
        }
        if (next.nextStageBatchOrdinal !in 1 until P1bProtocol.MAX_SAFE_INTEGER) {
            throw IllegalArgumentException("Projection next staged batch ordinal is invalid")
        }
        if (next.rawEvents.isNotEmpty() && next.streamId == null) {
            throw IllegalArgumentException("Projection raw events require a stream")
        }
        val rawSequences = next.rawEvents.keys.sorted()
        rawSequences.zipWithNext().forEach { (left, right) ->
            if (right != left + 1) throw IllegalArgumentException("Raw event sequence is not contiguous")
        }
        if (rawSequences.lastOrNull()?.let { it != next.throughSequence } == true) {
            throw IllegalArgumentException("Raw event tail does not match cursor")
        }
        if (next.deviceCalls.map { it.callId }.toSet().size != next.deviceCalls.size) {
            throw IllegalArgumentException("Device call IDs must be unique within a task")
        }
        next.deviceCalls.forEach { call -> laterHostState(null, call.state) }
        val operationIds = next.deviceCalls.mapNotNull { it.operationId }
        if (operationIds.toSet().size != operationIds.size) {
            throw IllegalArgumentException("Device operation IDs must be unique within a task")
        }
        val attentionCallIds = next.pendingAttention.mapNotNull { attention ->
            (attention as? JsonObject).stringOrNull("callId")
        }
        if (attentionCallIds.toSet().size != attentionCallIds.size) {
            throw IllegalArgumentException("Attention call IDs must be unique within a task")
        }
        val batchOrdinals = next.stagedRawFrameBatches.map { it.batchOrdinal }
        if (batchOrdinals.zipWithNext().any { (left, right) -> right <= left }) {
            throw IllegalArgumentException("Staged batch ordinals must be strictly monotonic")
        }
        if (batchOrdinals.lastOrNull()?.let { it >= next.nextStageBatchOrdinal } == true) {
            throw IllegalArgumentException("Next staged batch ordinal must follow every retained batch")
        }
        val frameCount = next.stagedRawFrameBatches.sumOf { it.frames.size }
        val byteCount = checkedSum(next.stagedRawFrameBatches.map { it.byteCount })
        if (frameCount > RawFrameRetentionPolicy.MAX_STAGED_AUDIT_FRAMES ||
            byteCount > RawFrameRetentionPolicy.MAX_STAGED_AUDIT_BYTES
        ) {
            throw IllegalArgumentException("Staged audit ring exceeds its durable bounds")
        }
    }

    private fun DurableTaskProjection.toEntity(
        previous: TaskEntity?,
        draftMode: TaskApprovalMode?,
        updatedAtMillis: Long,
    ): TaskEntity {
        val failure = taskFailureForRunState(
            runState = runState?.name,
            messages = messages,
            previous = previous?.storedTaskFailure(),
        )
        return TaskEntity(
            taskId = taskId,
            title = previous?.title.orEmpty(),
            runState = runState?.name,
            recoveryState = recoveryState?.name,
            readState = previous?.readState ?: "UNREAD",
            attentionState = previous?.attentionState ?: "NONE",
            streamId = streamId,
            throughSequence = throughSequence,
            snapshotVersion = snapshotVersion,
            windowStart = windowStart,
            windowEndExclusive = windowEndExclusive,
            nextStageBatchOrdinal = nextStageBatchOrdinal,
            queueJson = JsonArray(queue).toString(),
            piSessionId = piSessionId,
            isStreaming = isStreaming,
            updatedAtMillis = updatedAtMillis,
            listedByHost = previous?.listedByHost ?: true,
            lastListSyncGeneration = previous?.lastListSyncGeneration,
            lastListRevision = previous?.lastListRevision,
            hostUpdatedAtMillis = previous?.hostUpdatedAtMillis,
            titleSource = previous?.titleSource ?: "LEGACY",
            pinnedAtMillis = previous?.pinnedAtMillis,
            archivedAtMillis = previous?.archivedAtMillis,
            approvalMode = previous?.approvalMode ?: draftMode ?: TaskApprovalMode.REQUEST_APPROVAL,
            failureKind = failure?.kind?.name,
            failureMessage = failure?.message,
            failureRecovery = failure?.recovery?.name,
        )
    }

    private fun RawPiEventRecord.toEntity(projection: DurableTaskProjection): RawPiEventEntity {
        val bytes = rawBytes
        if (digest != sha256(bytes)) throw IllegalArgumentException("Raw event digest is invalid")
        val envelope = parseObjectBytes(bytes, "Raw pi.event envelope")
        if (stringField(envelope, "kind") != "pi.event" ||
            stringField(envelope, "taskId") != projection.taskId ||
            stringField(envelope, "streamId") != projection.streamId ||
            longField(envelope, "sequence") != sequence ||
            envelope["event"] != event
        ) {
            throw IllegalArgumentException("Raw event model differs from exact envelope")
        }
        return RawPiEventEntity(
            taskId = projection.taskId,
            sequence = sequence,
            streamId = requireNotNull(projection.streamId),
            digest = digest,
            rawEnvelope = bytes,
            eventJson = event.toString(),
        )
    }

    private fun JsonElement.toTimelineEntity(taskId: String, ordinal: Long): TimelineProjectionEntity {
        val raw = toString()
        return TimelineProjectionEntity(
            taskId = taskId,
            stableItemId = stableTimelineId(ordinal, raw),
            ordinal = ordinal,
            kind = timelineKind(this),
            rawPayload = raw,
            presentationJson = raw,
        )
    }

    private fun validateSnapshotBindings(next: DurableTaskProjection) {
        next.pendingAttention.forEach { attention ->
            val objectValue = attention as? JsonObject ?: return@forEach
            val callId = objectValue.stringOrNull("callId") ?: return@forEach
            val local = dao.deviceOperation(callId) ?: return@forEach
            if (local.taskId != next.taskId) {
                throw ProjectionCorruptionException("Snapshot attention callId belongs to another task")
            }
            objectValue.stringOrNull("toolName")?.let { toolName ->
                if (toolName != local.toolName) {
                    throw ProjectionCorruptionException("Snapshot attention tool binding conflicts")
                }
            }
            objectValue["arguments"]?.let { arguments ->
                if (arguments != parseJson(local.argumentsCanonicalJson, "Local arguments")) {
                    throw ProjectionCorruptionException("Snapshot attention arguments conflict")
                }
            }
            objectValue.stringOrNull("expiresAt")?.let { expiresAt ->
                if (expiresAt != local.expiresAt) {
                    throw ProjectionCorruptionException("Snapshot attention expiry conflicts")
                }
            }
        }
        next.deviceCalls.forEach { call ->
            val local = dao.deviceOperation(call.callId) ?: return@forEach
            if (local.taskId != next.taskId || local.operationId != call.operationId) {
                throw ProjectionCorruptionException("Snapshot device-call immutable binding conflicts")
            }
        }
    }

    private fun mergeHostDeviceObservations(
        next: DurableTaskProjection,
        updatedAtMillis: Long,
    ) {
        next.deviceCalls.forEach { call ->
            val local = dao.deviceOperation(call.callId) ?: return@forEach
            val attention = dao.pendingAttention(call.callId)
                ?: throw ProjectionCorruptionException(
                    "Local device operation is missing its attention projection",
                )
            val merged = laterHostState(local.hostObservationState, call.state)
            val hostAlreadyTerminal = merged in setOf("terminal", "reconciled")
            val observationOnlyTerminal = hostAlreadyTerminal && local.terminalSha256 == null
            val needsObservationOnlyTransition = observationOnlyTerminal && (
                local.deliveryState != AttentionDeliveryState.HOST_TERMINAL_DURABLE.name ||
                    attention.responseState != AttentionResponseState.ALREADY_ANSWERED.name ||
                    attention.validationCode != null
                )
            val updatedOperation = local.copy(
                hostObservationState = merged,
                deliveryState = if (observationOnlyTerminal) {
                    AttentionDeliveryState.HOST_TERMINAL_DURABLE.name
                } else {
                    local.deliveryState
                },
                updatedAtMillis = if (
                    merged != local.hostObservationState || needsObservationOnlyTransition
                ) {
                    updatedAtMillis
                } else {
                    local.updatedAtMillis
                },
            )
            val updatedAttention = if (needsObservationOnlyTransition) {
                attention.copy(
                    responseState = AttentionResponseState.ALREADY_ANSWERED.name,
                    validationCode = null,
                    updatedAtMillis = updatedAtMillis,
                )
            } else {
                attention
            }
            attentionValidator.validatePair(updatedOperation, updatedAttention)
            if (updatedOperation != local) {
                check(dao.updateDeviceOperation(updatedOperation) == 1) {
                    "Local device operation disappeared during snapshot merge"
                }
            }
            if (updatedAttention != attention) {
                check(dao.updatePendingAttention(updatedAttention) == 1) {
                    "Local attention projection disappeared during snapshot merge"
                }
            }
        }
    }

    private fun JsonElement.toHostPendingAttentionEntity(
        taskId: String,
        ordinal: Int,
    ): HostPendingAttentionEntity {
        val objectValue = this as? JsonObject
        return HostPendingAttentionEntity(
            taskId = taskId,
            ordinal = ordinal,
            callId = objectValue.stringOrNull("callId"),
            toolName = objectValue.stringOrNull("toolName"),
            argumentsJson = objectValue.elementOrNull("arguments"),
            state = objectValue.stringOrNull("state"),
            expiresAt = objectValue.stringOrNull("expiresAt"),
            originFocusKey = objectValue.stringOrNull("originFocusKey"),
            terminalResultJson = objectValue.elementOrNull("terminalResult"),
            rawPayload = toString(),
        )
    }

    private fun PendingResync.toEntity(taskId: String) = PendingResyncEntity(
        taskId = taskId,
        reason = reason.name,
        requestedStreamId = requestedStreamId,
        currentStreamId = currentStreamId,
        snapshotVersion = snapshotVersion,
    )

    private fun PendingResyncEntity.toModel(task: TaskEntity): PendingResync {
        if (task.streamId != null && requestedStreamId != task.streamId) {
            corrupt("Pending resync requested stream differs from the durable stream")
        }
        return PendingResync(
            reason = try {
                PiResyncReason.valueOf(reason)
            } catch (error: IllegalArgumentException) {
                corrupt("Pending resync reason is invalid")
            },
            requestedStreamId = requestedStreamId,
            currentStreamId = currentStreamId,
            snapshotVersion = snapshotVersion,
        )
    }

    private fun parseRecoveryState(value: String): RecoveryState = try {
        RecoveryState.valueOf(value)
    } catch (error: IllegalArgumentException) {
        corrupt("Task recovery state is invalid")
    }

    private fun parseRunState(value: String): TaskRunState = try {
        TaskRunState.valueOf(value)
    } catch (error: IllegalArgumentException) {
        corrupt("Task run state is invalid")
    }

    private companion object {
        val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        fun parseJson(value: String, label: String): JsonElement = try {
            strictJson.parseToJsonElement(value)
        } catch (error: Throwable) {
            throw ProjectionCorruptionException("$label is invalid JSON")
        }

        fun parseObjectBytes(value: ByteArray, label: String): JsonObject {
            val text = try {
                value.decodeToString(throwOnInvalidSequence = true)
            } catch (error: Throwable) {
                throw ProjectionCorruptionException("$label is not valid UTF-8")
            }
            return parseJson(text, label) as? JsonObject
                ?: throw ProjectionCorruptionException("$label is not a JSON object")
        }

        fun parseArray(value: String, label: String): List<JsonElement> =
            (parseJson(value, label) as? JsonArray)?.toList()
                ?: throw ProjectionCorruptionException("$label is not a JSON array")

        fun stringField(value: JsonObject, key: String): String =
            value[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
                ?: throw ProjectionCorruptionException("JSON field $key is not a string")

        fun longField(value: JsonObject, key: String): Long =
            value[key]?.jsonPrimitive?.longOrNull
                ?: throw ProjectionCorruptionException("JSON field $key is not an integer")

        fun JsonObject?.stringOrNull(key: String): String? =
            this?.get(key)?.let { element ->
                val primitive = element as? JsonPrimitive ?: return@let null
                if (primitive.isString) primitive.content else null
            }

        fun JsonObject?.elementOrNull(key: String): String? = this?.get(key)?.toString()

        fun laterHostState(previous: String?, incoming: String): String {
            val ranks = mapOf(
                "created" to 0,
                "sent" to 1,
                "running" to 2,
                "terminal" to 3,
                "reconciled" to 4,
            )
            val incomingRank = ranks[incoming]
                ?: throw ProjectionCorruptionException("Snapshot device-call state is invalid")
            val previousRank = previous?.let { state ->
                ranks[state] ?: throw ProjectionCorruptionException(
                    "Local host observation state is invalid",
                )
            }
            return if (previousRank != null && previousRank > incomingRank) previous else incoming
        }

        fun timelineKind(element: JsonElement): String =
            (element as? JsonObject).stringOrNull("type") ?: "unknown"

        fun stableTimelineId(ordinal: Long, raw: String): String =
            "message:$ordinal:${sha256(raw.encodeToByteArray())}"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

        fun checkedSum(values: List<Long>): Long = values.fold(0L) { total, value ->
            try {
                Math.addExact(total, value)
            } catch (error: ArithmeticException) {
                throw ProjectionCorruptionException("Durable byte counter overflow")
            }
        }

        fun corrupt(message: String): Nothing = throw ProjectionCorruptionException(message)
    }
}
