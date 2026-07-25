package app.momoding.wire

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

sealed interface ChunkReassemblyResult

data object ChunkTransferPending : ChunkReassemblyResult

class CompletedChunkTransfer(
    val transferId: String,
    val received: ReceivedReliabilityServerFrame,
) : ChunkReassemblyResult

class ChunkReassemblyException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Reassembles project-owned Reliability transport chunks without interpreting Pi
 * events. Remote identifiers are never used as paths; every artifact is a
 * locally generated file under the caller-provided private temp root.
 */
class ChunkReassembler(
    tempRoot: Path,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private val canonicalTempRoot = prepareTempRoot(tempRoot)
    private val active = linkedMapOf<String, ActiveTransfer>()
    private var closed = false

    @get:Synchronized
    val activeTransferCount: Int
        get() = active.size

    @Synchronized
    fun accept(received: ReceivedReliabilityServerFrame): ChunkReassemblyResult {
        if (closed) throw ChunkReassemblyException("ChunkReassembler is closed")
        return when (val frame = received.frame) {
            is TransportChunkStartFrame -> acceptStart(frame, received.rawBytes)
            is TransportChunkDataFrame -> acceptData(frame)
            is TransportChunkEndFrame -> acceptEnd(frame)
            else -> throw ChunkReassemblyException(
                "ChunkReassembler only accepts transport.chunk start/data/end frames",
            )
        }
    }

    @Synchronized
    fun expire(): List<String> {
        if (closed) return emptyList()
        val now = try {
            nowMillis()
        } catch (error: Throwable) {
            cleanupAllAndThrow("Chunk clock failed during expire", error)
        }
        val expired = active.values
            .filter { hasExpired(it, now) }
            .map { it.start.transferId }
        expired.forEach(::cleanupAndRemove)
        return expired
    }

    @Synchronized
    override fun close() {
        closed = true
        val failures = mutableListOf<Throwable>()
        active.keys.toList().forEach { transferId ->
            try {
                cleanupAndRemove(transferId)
            } catch (error: Throwable) {
                failures += error
            }
        }
        if (failures.isNotEmpty()) {
            val exception = ChunkReassemblyException("Failed to clean every chunk artifact")
            failures.forEach(exception::addSuppressed)
            throw exception
        }
    }

    private fun acceptStart(
        start: TransportChunkStartFrame,
        exactRawBytes: ByteArray,
    ): ChunkReassemblyResult {
        active[start.transferId]?.let { existing ->
            checkDeadline(existing)
            if (existing.start == start && existing.startRawBytes.contentEquals(exactRawBytes)) {
                return ChunkTransferPending
            }
            failAndCleanup(start.transferId, "Conflicting duplicate chunk start")
        }

        validateStart(start)
        val startedAt = try {
            nowMillis()
        } catch (error: Throwable) {
            throw ChunkReassemblyException("Chunk clock failed during start", error)
        }
        val artifact = createArtifact()
        try {
            val channel = FileChannel.open(
                artifact,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            active[start.transferId] = ActiveTransfer(
                start = start,
                startRawBytes = exactRawBytes.copyOf(),
                startedAtMillis = startedAt,
                artifact = artifact,
                channel = channel,
            )
        } catch (error: Throwable) {
            val failure = ChunkReassemblyException("Failed to create chunk artifact", error)
            try {
                Files.deleteIfExists(artifact)
            } catch (cleanupError: Throwable) {
                failure.addSuppressed(cleanupError)
            }
            throw failure
        }
        return ChunkTransferPending
    }

    private fun acceptData(data: TransportChunkDataFrame): ChunkReassemblyResult {
        val state = active[data.transferId]
            ?: throw ChunkReassemblyException("Unknown chunk transfer: ${data.transferId}")
        try {
            checkDeadline(state)
            if (data.data.length > ReliabilityProtocol.CHUNK_MAX_PHYSICAL_FRAME_BYTES) {
                failAndCleanup(data.transferId, "Chunk data exceeds the physical encoded budget")
            }
            val decoded = try {
                Base64.getDecoder().decode(data.data)
            } catch (error: IllegalArgumentException) {
                failAndCleanup(data.transferId, "Chunk data is not canonical base64", error)
            }
            if (Base64.getEncoder().encodeToString(decoded) != data.data) {
                failAndCleanup(data.transferId, "Chunk data is not canonical base64")
            }
            if (decoded.isEmpty()) {
                failAndCleanup(data.transferId, "Chunk data must not be empty")
            }

            if (data.chunkIndex < state.chunks.size) {
                val stored = state.chunks[data.chunkIndex]
                if (matchesStoredBytes(state, stored, decoded)) return ChunkTransferPending
                failAndCleanup(data.transferId, "Conflicting duplicate chunk data")
            }
            if (data.chunkIndex != state.chunks.size) {
                failAndCleanup(data.transferId, "Chunk data must arrive in strict index order")
            }
            if (data.chunkIndex >= state.start.chunkCount) {
                failAndCleanup(data.transferId, "Chunk index exceeds declared chunkCount")
            }
            val remaining = state.start.totalBytes - state.receivedBytes
            if (decoded.size.toLong() > remaining) {
                failAndCleanup(data.transferId, "Chunk bytes exceed declared totalBytes")
            }

            val offset = state.receivedBytes
            writeFully(state.channel, offset, decoded)
            state.chunks += StoredChunk(offset, decoded.size)
            state.receivedBytes += decoded.size
            return ChunkTransferPending
        } catch (error: ChunkReassemblyException) {
            throw error
        } catch (error: Throwable) {
            failAndCleanup(data.transferId, "Failed to append chunk data", error)
        }
    }

    private fun acceptEnd(end: TransportChunkEndFrame): ChunkReassemblyResult {
        val state = active[end.transferId]
            ?: throw ChunkReassemblyException("Unknown chunk transfer: ${end.transferId}")
        try {
            checkDeadline(state)
            if (state.chunks.size != state.start.chunkCount) {
                failAndCleanup(end.transferId, "Chunk end arrived before every declared chunk")
            }
            if (state.receivedBytes != state.start.totalBytes) {
                failAndCleanup(end.transferId, "Reassembled byte length does not match totalBytes")
            }
            state.channel.force(true)
            val actualSha = sha256(state.artifact)
            if (actualSha != state.start.sha256) {
                failAndCleanup(end.transferId, "Reassembled SHA-256 does not match chunk start")
            }

            val logicalBytes = Files.readAllBytes(state.artifact)
            val contentKind = contentKind(state.start)
            val decoded = ReliabilityContractDecoder.decodeReassembled(logicalBytes, contentKind)
            validateBinding(state.start, decoded.frame)
            cleanupAndRemove(end.transferId)
            return CompletedChunkTransfer(end.transferId, decoded)
        } catch (error: ChunkReassemblyException) {
            if (end.transferId in active) {
                failAndCleanup(
                    end.transferId,
                    error.message ?: "Failed to complete chunk transfer",
                    error,
                )
            }
            throw error
        } catch (error: Throwable) {
            failAndCleanup(end.transferId, "Failed to complete chunk transfer", error)
        }
    }

    private fun validateStart(start: TransportChunkStartFrame) {
        if (!CHUNK_UUID_PATTERN.matches(start.transferId)) {
            throw ChunkReassemblyException("Chunk transferId must be a UUID")
        }
        if (!CHUNK_UUID_PATTERN.matches(start.taskId)) {
            throw ChunkReassemblyException("Chunk taskId must be a UUID")
        }
        if (!CHUNK_SHA256_PATTERN.matches(start.sha256)) {
            throw ChunkReassemblyException("Chunk sha256 must be lowercase SHA-256 hex")
        }
        if (start.totalBytes !in 1..ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong()) {
            throw ChunkReassemblyException(
                "Chunk totalBytes must be 1-${ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES}",
            )
        }
        if (start.chunkCount !in 1..ReliabilityProtocol.CHUNK_MAX_COUNT) {
            throw ChunkReassemblyException(
                "Chunk count must be 1-${ReliabilityProtocol.CHUNK_MAX_COUNT}",
            )
        }
        val threshold = when (start) {
            is PiEventChunkStartFrame -> {
                if (start.contentKind != "pi.event") {
                    throw ChunkReassemblyException("Pi event chunk has the wrong contentKind")
                }
                if (!CHUNK_UUID_PATTERN.matches(start.streamId)) {
                    throw ChunkReassemblyException("Pi event chunk streamId must be a UUID")
                }
                if (start.sequence !in 1..ReliabilityProtocol.MAX_SAFE_INTEGER) {
                    throw ChunkReassemblyException("Pi event chunk sequence must be safe and positive")
                }
                ReliabilityProtocol.DIRECT_PI_EVENT_MAX_BYTES
            }
            is SnapshotPageChunkStartFrame -> {
                if (start.contentKind != "task.snapshot.page") {
                    throw ChunkReassemblyException("Snapshot page chunk has the wrong contentKind")
                }
                if (start.snapshotVersion !in 1..ReliabilityProtocol.MAX_SAFE_INTEGER) {
                    throw ChunkReassemblyException(
                        "Snapshot page chunk version must be safe and positive",
                    )
                }
                if (start.pageIndex !in 0 until ReliabilityProtocol.SNAPSHOT_MAX_PAGES) {
                    throw ChunkReassemblyException("Snapshot page chunk pageIndex is out of range")
                }
                ReliabilityProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES
            }
        }
        if (start.totalBytes <= threshold) {
            throw ChunkReassemblyException(
                "Chunk totalBytes must exceed the $threshold-byte direct threshold",
            )
        }
        if (start.chunkCount.toLong() > start.totalBytes) {
            throw ChunkReassemblyException("chunkCount cannot exceed totalBytes")
        }
    }

    private fun validateBinding(
        start: TransportChunkStartFrame,
        logical: ReliabilityServerFrame,
    ) {
        val matches = when {
            start is PiEventChunkStartFrame && logical is PiEventFrame ->
                start.taskId == logical.taskId &&
                    start.streamId == logical.streamId &&
                    start.sequence == logical.sequence
            start is SnapshotPageChunkStartFrame && logical is TaskSnapshotPageFrame ->
                start.taskId == logical.taskId &&
                    start.snapshotVersion == logical.snapshotVersion &&
                    start.pageIndex == logical.pageIndex
            else -> false
        }
        if (!matches) {
            throw ChunkReassemblyException("Reassembled logical frame does not match chunk binding")
        }
    }

    private fun contentKind(start: TransportChunkStartFrame): String = when (start) {
        is PiEventChunkStartFrame -> start.contentKind
        is SnapshotPageChunkStartFrame -> start.contentKind
    }

    private fun checkDeadline(state: ActiveTransfer) {
        val now = try {
            nowMillis()
        } catch (error: Throwable) {
            failAndCleanup(
                state.start.transferId,
                "Chunk clock failed while transfer was active",
                error,
            )
        }
        if (hasExpired(state, now)) {
            failAndCleanup(state.start.transferId, "Chunk transfer exceeded 30 seconds")
        }
    }

    private fun hasExpired(state: ActiveTransfer, now: Long): Boolean {
        if (now < state.startedAtMillis) return true
        val elapsed = try {
            Math.subtractExact(now, state.startedAtMillis)
        } catch (_: ArithmeticException) {
            return true
        }
        return elapsed >= ReliabilityProtocol.CHUNK_TIMEOUT_MS
    }

    private fun createArtifact(): Path {
        val artifact = Files.createTempFile(canonicalTempRoot, "reliability-chunk-", ".part")
        val realArtifact = try {
            artifact.toRealPath()
        } catch (error: Throwable) {
            val failure = ChunkReassemblyException("Failed to resolve chunk artifact", error)
            try {
                Files.deleteIfExists(artifact)
            } catch (cleanupError: Throwable) {
                failure.addSuppressed(cleanupError)
            }
            throw failure
        }
        if (realArtifact.parent != canonicalTempRoot) {
            Files.deleteIfExists(realArtifact)
            throw ChunkReassemblyException("Chunk artifact escaped the private temp root")
        }
        return realArtifact
    }

    private fun cleanupAndRemove(transferId: String) {
        val state = active[transferId] ?: return
        var closeFailure: Throwable? = null
        try {
            state.channel.close()
        } catch (error: Throwable) {
            closeFailure = error
        }
        try {
            Files.deleteIfExists(state.artifact)
        } catch (error: Throwable) {
            if (closeFailure != null) error.addSuppressed(closeFailure)
            throw ChunkReassemblyException("Failed to delete chunk artifact", error)
        }
        active.remove(transferId)
        if (closeFailure != null) {
            throw ChunkReassemblyException("Failed to close chunk artifact", closeFailure)
        }
    }

    private fun failAndCleanup(
        transferId: String,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        val failure = ChunkReassemblyException(message, cause)
        try {
            cleanupAndRemove(transferId)
        } catch (cleanupError: Throwable) {
            failure.addSuppressed(cleanupError)
        }
        throw failure
    }

    private fun cleanupAllAndThrow(message: String, cause: Throwable): Nothing {
        val failure = ChunkReassemblyException(message, cause)
        active.keys.toList().forEach { transferId ->
            try {
                cleanupAndRemove(transferId)
            } catch (cleanupError: Throwable) {
                failure.addSuppressed(cleanupError)
            }
        }
        throw failure
    }

    private fun matchesStoredBytes(
        state: ActiveTransfer,
        stored: StoredChunk,
        expected: ByteArray,
    ): Boolean {
        if (stored.byteLength != expected.size) return false
        val buffer = ByteBuffer.allocate(minOf(DEFAULT_BUFFER_SIZE, expected.size))
        var filePosition = stored.offset
        var expectedPosition = 0
        while (expectedPosition < expected.size) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity(), expected.size - expectedPosition))
            val read = state.channel.read(buffer, filePosition)
            if (read <= 0) throw IOException("Chunk artifact read made no progress")
            for (index in 0 until read) {
                if (buffer.get(index) != expected[expectedPosition + index]) return false
            }
            filePosition += read
            expectedPosition += read
        }
        return true
    }

    private data class ActiveTransfer(
        val start: TransportChunkStartFrame,
        val startRawBytes: ByteArray,
        val startedAtMillis: Long,
        val artifact: Path,
        val channel: FileChannel,
        val chunks: MutableList<StoredChunk> = mutableListOf(),
        var receivedBytes: Long = 0,
    )

    private data class StoredChunk(
        val offset: Long,
        val byteLength: Int,
    )
}

private fun prepareTempRoot(tempRoot: Path): Path {
    try {
        Files.createDirectories(tempRoot)
        val realRoot = tempRoot.toRealPath()
        if (!Files.isDirectory(realRoot)) {
            throw ChunkReassemblyException("Chunk temp root must be a directory")
        }
        return realRoot
    } catch (error: ChunkReassemblyException) {
        throw error
    } catch (error: Throwable) {
        throw ChunkReassemblyException("Failed to prepare chunk temp root", error)
    }
}

private fun writeFully(channel: FileChannel, offset: Long, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    var position = offset
    while (buffer.hasRemaining()) {
        val written = channel.write(buffer, position)
        if (written <= 0) throw IOException("Chunk artifact write made no progress")
        position += written
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private val CHUNK_UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val CHUNK_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
