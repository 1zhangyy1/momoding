package app.momoding.wire

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class HistoryRequestExpectation(
    val requestId: String,
    val taskId: String,
    val snapshotVersion: Long,
    val limitBytes: Long,
)

class RawSnapshotPage(
    val frame: TaskSnapshotPageFrame,
    rawBytes: ByteArray,
) {
    private val retained = rawBytes.copyOf()
    val rawBytes: ByteArray get() = retained.copyOf()
    val byteLength: Int get() = retained.size
}

class AssembledSnapshot(
    val begin: TaskSnapshotBeginFrame,
    beginRawBytes: ByteArray,
    val pages: List<RawSnapshotPage>,
    endRawBytes: ByteArray,
    val receivedRawPageBytes: Long,
) {
    private val retainedBegin = beginRawBytes.copyOf()
    private val retainedEnd = endRawBytes.copyOf()
    val beginRawBytes: ByteArray get() = retainedBegin.copyOf()
    val endRawBytes: ByteArray get() = retainedEnd.copyOf()
}

class SnapshotAssemblyException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class SnapshotAssembler(
    private val snapshotLogicalLimitBytes: Long = P1bProtocol.SNAPSHOT_MAX_LOGICAL_BYTES.toLong(),
) : AutoCloseable {
    private val expectations = linkedMapOf<String, HistoryRequestExpectation>()
    private val pending = linkedMapOf<String, PendingSnapshot>()
    private var closed = false

    @get:Synchronized
    val pendingTransferCount: Int get() = pending.size

    @get:Synchronized
    val activeHistoryRequestCount: Int get() = expectations.size

    @Synchronized
    fun registerHistoryRequest(expectation: HistoryRequestExpectation) {
        ensureOpen()
        validateExpectation(expectation)
        if (expectation.requestId in expectations) {
            throw SnapshotAssemblyException("History requestId is already active")
        }
        expectations[expectation.requestId] = expectation
    }

    @Synchronized
    fun acceptBegin(received: ReceivedP1bServerFrame) {
        ensureOpen()
        val begin = received.frame as? TaskSnapshotBeginFrame
            ?: throw SnapshotAssemblyException("SnapshotAssembler requires task.snapshot.begin")
        if (pending.remove(begin.taskId) != null) {
            consumeExpectation(begin.requestId)
            throw SnapshotAssemblyException("Conflicting snapshot begin cleared the pending transfer")
        }

        val history = if (begin.transferMode == SnapshotTransferMode.PREPEND_HISTORY) {
            val requestId = begin.requestId
                ?: throw SnapshotAssemblyException("History begin requires requestId")
            val expectation = expectations.remove(requestId)
                ?: throw SnapshotAssemblyException("History begin requestId is not active")
            if (
                expectation.taskId != begin.taskId ||
                expectation.snapshotVersion != begin.snapshotVersion
            ) {
                throw SnapshotAssemblyException("History begin does not match its registered request")
            }
            expectation
        } else {
            null
        }
        pending[begin.taskId] = PendingSnapshot(begin, received.rawBytes, history)
    }

    @Synchronized
    fun acceptPage(received: ReceivedP1bServerFrame) {
        ensureOpen()
        val page = received.frame as? TaskSnapshotPageFrame
            ?: throw SnapshotAssemblyException("SnapshotAssembler requires task.snapshot.page")
        val state = pending[page.taskId]
            ?: throw SnapshotAssemblyException("Snapshot page has no pending begin")
        try {
            if (page.snapshotVersion != state.begin.snapshotVersion) {
                fail(page.taskId, "Snapshot page version does not match begin")
            }
            if (page.pageIndex != state.pages.size) {
                fail(page.taskId, "Snapshot pages must be contiguous from pageIndex 0")
            }
            if (page.messages.isEmpty()) {
                fail(page.taskId, "Snapshot page must contain at least one message")
            }
            if (page.messageStartIndex != state.nextMessageIndex) {
                fail(page.taskId, "Snapshot page message range is not contiguous")
            }
            if (page.pageIndex >= P1bProtocol.SNAPSHOT_MAX_PAGES) {
                fail(page.taskId, "Snapshot page count exceeds the maximum")
            }

            val raw = received.rawBytes
            val nextRawBytes = checkedAdd(state.receivedRawPageBytes, raw.size.toLong())
            val nextAllowance = if (state.begin.transferMode == SnapshotTransferMode.REPLACE) {
                val emptyEnvelopeBytes = canonicalEmptyPageEnvelopeBytes(page)
                if (emptyEnvelopeBytes > EMPTY_PAGE_ENVELOPE_MAX_BYTES) {
                    fail(page.taskId, "Canonical empty page envelope exceeds 1 KiB")
                }
                checkedAdd(state.emptyEnvelopeAllowanceBytes, emptyEnvelopeBytes)
            } else {
                state.emptyEnvelopeAllowanceBytes
            }

            when (state.begin.transferMode) {
                SnapshotTransferMode.REPLACE -> {
                    val maximum = checkedAdd(snapshotLogicalLimitBytes, nextAllowance)
                    if (nextRawBytes > maximum) {
                        fail(page.taskId, "Replace snapshot aggregate raw-page budget exceeded")
                    }
                }
                SnapshotTransferMode.PREPEND_HISTORY -> {
                    val limit = checkNotNull(state.history).limitBytes
                    if (nextRawBytes > limit) {
                        fail(page.taskId, "History aggregate raw-page budget exceeded")
                    }
                }
            }

            updateOrderedPageDigest(state.digest, raw)
            state.pages += RawSnapshotPage(page, raw)
            state.receivedRawPageBytes = nextRawBytes
            state.emptyEnvelopeAllowanceBytes = nextAllowance
            state.nextMessageIndex = checkedAdd(
                state.nextMessageIndex,
                page.messages.size.toLong(),
            )
            if (state.nextMessageIndex > state.begin.window.messageEndExclusive) {
                fail(page.taskId, "Snapshot page messages exceed the begin window")
            }
        } catch (error: SnapshotAssemblyException) {
            pending.remove(page.taskId)
            throw error
        } catch (error: Throwable) {
            fail(page.taskId, "Snapshot page assembly failed", error)
        }
    }

    @Synchronized
    fun acceptEnd(received: ReceivedP1bServerFrame): AssembledSnapshot {
        ensureOpen()
        val end = received.frame as? TaskSnapshotEndFrame
            ?: throw SnapshotAssemblyException("SnapshotAssembler requires task.snapshot.end")
        val state = pending[end.taskId]
            ?: throw SnapshotAssemblyException("Snapshot end has no pending begin")
        try {
            if (end.snapshotVersion != state.begin.snapshotVersion) {
                fail(end.taskId, "Snapshot end version does not match begin")
            }
            if (end.pageCount != state.pages.size || end.pageCount !in 1..P1bProtocol.SNAPSHOT_MAX_PAGES) {
                fail(end.taskId, "Snapshot end pageCount does not match received pages")
            }
            if (state.nextMessageIndex != state.begin.window.messageEndExclusive) {
                fail(end.taskId, "Snapshot page ranges do not reach the begin window end")
            }
            val actualSha = state.digest.digest().toHex()
            if (actualSha != end.sha256) {
                fail(end.taskId, "Snapshot ordered page SHA-256 does not match end")
            }
            pending.remove(end.taskId)
            return AssembledSnapshot(
                begin = state.begin,
                beginRawBytes = state.beginRawBytes,
                pages = state.pages.toList(),
                endRawBytes = received.rawBytes,
                receivedRawPageBytes = state.receivedRawPageBytes,
            )
        } catch (error: SnapshotAssemblyException) {
            throw error
        } catch (error: Throwable) {
            fail(end.taskId, "Snapshot end assembly failed", error)
        }
    }

    @Synchronized
    fun abortTask(taskId: String) {
        pending.remove(taskId)
    }

    @Synchronized
    override fun close() {
        closed = true
        pending.clear()
        expectations.clear()
    }

    private fun ensureOpen() {
        if (closed) throw SnapshotAssemblyException("SnapshotAssembler is closed")
    }

    private fun validateExpectation(expectation: HistoryRequestExpectation) {
        if (expectation.requestId.isBlank() || expectation.requestId.length > 128) {
            throw SnapshotAssemblyException("History requestId must contain 1-128 characters")
        }
        if (!SNAPSHOT_UUID_PATTERN.matches(expectation.taskId)) {
            throw SnapshotAssemblyException("History taskId must be a UUID")
        }
        if (expectation.snapshotVersion !in 1..P1bProtocol.MAX_SAFE_INTEGER) {
            throw SnapshotAssemblyException("History snapshotVersion must be safe and positive")
        }
        if (expectation.limitBytes !in 1..P1bProtocol.HISTORY_MAX_BYTES.toLong()) {
            throw SnapshotAssemblyException(
                "History limitBytes must be 1-${P1bProtocol.HISTORY_MAX_BYTES}",
            )
        }
    }

    private fun consumeExpectation(requestId: String?) {
        if (requestId != null) expectations.remove(requestId)
    }

    private fun fail(taskId: String, message: String, cause: Throwable? = null): Nothing {
        pending.remove(taskId)
        throw SnapshotAssemblyException(message, cause)
    }

    private data class PendingSnapshot(
        val begin: TaskSnapshotBeginFrame,
        val beginRawBytes: ByteArray,
        val history: HistoryRequestExpectation?,
        val pages: MutableList<RawSnapshotPage> = mutableListOf(),
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var nextMessageIndex: Long = begin.window.messageStartIndex,
        var receivedRawPageBytes: Long = 0,
        var emptyEnvelopeAllowanceBytes: Long = 0,
    )
}

private fun canonicalEmptyPageEnvelopeBytes(page: TaskSnapshotPageFrame): Long =
    buildJsonObject {
        put("protocolVersion", page.protocolVersion)
        put("kind", page.kind)
        put("taskId", page.taskId)
        put("snapshotVersion", page.snapshotVersion)
        put("pageIndex", page.pageIndex)
        put("messageStartIndex", page.messageStartIndex)
        put("messages", JsonArray(emptyList()))
    }.toString().encodeToByteArray().size.toLong()

private fun updateOrderedPageDigest(digest: MessageDigest, raw: ByteArray) {
    digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(raw.size.toLong()).array())
    digest.update(raw)
}

private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw SnapshotAssemblyException("Snapshot byte/range counter overflow", error)
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private const val EMPTY_PAGE_ENVELOPE_MAX_BYTES = 1024L
private val SNAPSHOT_UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
