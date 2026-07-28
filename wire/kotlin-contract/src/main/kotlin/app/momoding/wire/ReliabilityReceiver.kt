package app.momoding.wire

sealed interface ReceiverAction

data class FrameReady(
    val received: ReceivedP1bServerFrame,
) : ReceiverAction

data object AwaitingTransfer : ReceiverAction

data class ProjectionCommitted(
    val kind: String,
    val state: DurableTaskProjection,
) : ReceiverAction

data class AckReady(
    val frame: PiEventAckFrame,
) : ReceiverAction

data class SnapshotRecommended(
    val recommendation: RawSnapshotRecommendation,
) : ReceiverAction

data class SnapshotRequired(
    val expectation: RawBudgetSnapshotExpectation,
    val message: String,
) : ReceiverAction

data class ReceiverFailure(
    val code: ReceiverFailureCode,
    val message: String,
    val reconnectRequired: Boolean,
) : ReceiverAction

enum class ReceiverFailureCode {
    DECODE_FAILED,
    TRANSFER_FAILED,
    SNAPSHOT_FAILED,
    PROJECTION_FAILED,
    RECEIVER_CLOSED,
}

class ReliabilityReceiver private constructor(
    tempRoot: java.nio.file.Path,
    store: ProjectionTransactionStore,
    nowMillis: () -> Long,
    snapshotLogicalLimitBytes: Long,
) : AutoCloseable {
    private val chunks = ChunkReassembler(tempRoot, nowMillis)
    private val snapshots = SnapshotAssembler(snapshotLogicalLimitBytes)
    private val projection = ReliableProjection(store)
    private var rawBudgetExpectation: RawBudgetSnapshotExpectation? = null
    private var closed = false

    constructor(
        tempRoot: java.nio.file.Path,
        store: ProjectionTransactionStore,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    ) : this(
        tempRoot,
        store,
        nowMillis,
        P1bProtocol.SNAPSHOT_MAX_LOGICAL_BYTES.toLong(),
    )

    internal constructor(
        tempRoot: java.nio.file.Path,
        store: ProjectionTransactionStore,
        snapshotLogicalLimitBytes: Long,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    ) : this(tempRoot, store, nowMillis, snapshotLogicalLimitBytes)

    @Synchronized
    fun registerHistoryRequest(
        requestId: String,
        taskId: String,
        snapshotVersion: Long,
        limitBytes: Long,
    ) {
        if (closed) throw SnapshotAssemblyException("ReliabilityReceiver is closed")
        snapshots.registerHistoryRequest(
            HistoryRequestExpectation(requestId, taskId, snapshotVersion, limitBytes),
        )
    }

    @Synchronized
    fun receive(rawPhysicalBytes: ByteArray): List<ReceiverAction> {
        if (closed) {
            return listOf(
                ReceiverFailure(
                    ReceiverFailureCode.RECEIVER_CLOSED,
                    "ReliabilityReceiver is closed",
                    reconnectRequired = false,
                ),
            )
        }
        val received = try {
            ReliabilityContractDecoder.decode(rawPhysicalBytes)
        } catch (error: Throwable) {
            return failure(ReceiverFailureCode.DECODE_FAILED, error, reconnect = true)
        }
        return route(received)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            chunks.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            snapshots.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        if (failure != null) throw failure
    }

    private fun route(received: ReceivedP1bServerFrame): List<ReceiverAction> =
        when (val frame = received.frame) {
            is TransportChunkStartFrame,
            is TransportChunkDataFrame,
            is TransportChunkEndFrame,
            -> routeChunk(received)
            is TaskSnapshotBeginFrame -> try {
                snapshots.acceptBegin(received)
                listOf(AwaitingTransfer)
            } catch (error: Throwable) {
                failure(ReceiverFailureCode.SNAPSHOT_FAILED, error, reconnect = true)
            }
            is TaskSnapshotPageFrame -> try {
                snapshots.acceptPage(received)
                listOf(AwaitingTransfer)
            } catch (error: Throwable) {
                failure(ReceiverFailureCode.SNAPSHOT_FAILED, error, reconnect = true)
            }
            is TaskSnapshotEndFrame -> try {
                routeAssembledSnapshot(snapshots.acceptEnd(received))
            } catch (error: SnapshotAssemblyException) {
                failure(ReceiverFailureCode.SNAPSHOT_FAILED, error, reconnect = true)
            } catch (error: Throwable) {
                failure(ReceiverFailureCode.PROJECTION_FAILED, error, reconnect = true)
            }
            is PiEventFrame -> routeEvent(received)
            is TaskSnapshotFrame -> routeDirectSnapshot(received)
            is PiReplayCompleteFrame -> project { projection.applyReplayComplete(received) }
            is PiResyncRequiredFrame -> project { projection.applyResyncRequired(received) }
            else -> listOf(FrameReady(received))
        }

    private fun routeChunk(received: ReceivedP1bServerFrame): List<ReceiverAction> = try {
        when (val result = chunks.accept(received)) {
            ChunkTransferPending -> listOf(AwaitingTransfer)
            is CompletedChunkTransfer -> route(result.received)
        }
    } catch (error: Throwable) {
        failure(ReceiverFailureCode.TRANSFER_FAILED, error, reconnect = true)
    }

    private fun project(block: () -> ProjectionApplyResult): List<ReceiverAction> = try {
        projectionActions(block())
    } catch (error: Throwable) {
        failure(ReceiverFailureCode.PROJECTION_FAILED, error, reconnect = true)
    }

    private fun routeEvent(received: ReceivedP1bServerFrame): List<ReceiverAction> {
        rawBudgetExpectation?.let { expectation ->
            return listOf(SnapshotRequired(expectation, "Raw-event mutation is fenced"))
        }
        return try {
            projectionActions(projection.applyEvent(received))
        } catch (required: RawBudgetSnapshotRequiredException) {
            rawBudgetExpectation = required.expectation
            listOf(SnapshotRequired(required.expectation, required.message ?: "Snapshot required"))
        } catch (error: Throwable) {
            failure(ReceiverFailureCode.PROJECTION_FAILED, error, reconnect = true)
        }
    }

    private fun routeDirectSnapshot(received: ReceivedP1bServerFrame): List<ReceiverAction> {
        val snapshot = received.frame as TaskSnapshotFrame
        val expectation = rawBudgetExpectation
        if (
            expectation != null &&
            !RawFrameRetentionPolicy.snapshotSatisfies(
                expectation,
                snapshot.taskId,
                snapshot.cursor.streamId,
                snapshot.cursor.highWatermarkSequence,
            )
        ) {
            return listOf(SnapshotRequired(expectation, "Snapshot does not cover rejected event"))
        }
        return projectSnapshot(expectation) { projection.applyDirectSnapshot(received) }
    }

    private fun routeAssembledSnapshot(assembled: AssembledSnapshot): List<ReceiverAction> {
        val expectation = rawBudgetExpectation
        val begin = assembled.begin
        if (expectation != null) {
            if (
                begin.transferMode != SnapshotTransferMode.REPLACE ||
                !RawFrameRetentionPolicy.snapshotSatisfies(
                    expectation,
                    begin.taskId,
                    begin.cursor.streamId,
                    begin.cursor.highWatermarkSequence,
                )
            ) {
                return listOf(SnapshotRequired(expectation, "Snapshot does not cover rejected event"))
            }
        }
        return projectSnapshot(expectation) { projection.applyAssembledSnapshot(assembled) }
    }

    private fun projectSnapshot(
        expectation: RawBudgetSnapshotExpectation?,
        block: () -> ProjectionApplyResult,
    ): List<ReceiverAction> = try {
        val actions = projectionActions(block())
        if (expectation != null) rawBudgetExpectation = null
        actions
    } catch (error: Throwable) {
        failure(ReceiverFailureCode.PROJECTION_FAILED, error, reconnect = true)
    }

    private fun projectionActions(result: ProjectionApplyResult): List<ReceiverAction> = buildList {
        add(ProjectionCommitted(result.kind, result.state))
        result.ack?.let { add(AckReady(it)) }
        result.snapshotRecommendation?.let { add(SnapshotRecommended(it)) }
    }

    private fun failure(
        code: ReceiverFailureCode,
        error: Throwable,
        reconnect: Boolean,
    ): List<ReceiverAction> = listOf(
        ReceiverFailure(
            code = code,
            message = error.message ?: error::class.simpleName ?: "Receiver failure",
            reconnectRequired = reconnect,
        ),
    )
}
