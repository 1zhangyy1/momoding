package app.momoding.wire

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChunkReassemblerTest {
    private val reliabilityFixture: JsonObject by lazy {
        readFixture("/pi-0.80.6/p1b-reliability-contract.json")
    }
    private val byteFixture: JsonObject by lazy {
        readFixture("/pi-0.80.6/p1b-byte-domains.json")
    }

    @Test
    fun `reassembles locked Node event and snapshot-page recipes with exact logical bytes`() {
        withAssembler { assembler, root, _ ->
            val event = eventTransfer()
            val completedEvent = consume(assembler, event)
            assertContentEquals(event.logicalBytes, completedEvent.received.rawBytes)
            val eventFrame = assertIs<PiEventFrame>(completedEvent.received.frame)
            assertEquals(10, eventFrame.sequence)
            assertEquals(event.startFrame.taskId, eventFrame.taskId)
            assertRootEmpty(root)

            val page = pageTransfer()
            val completedPage = consume(assembler, page)
            assertContentEquals(page.logicalBytes, completedPage.received.rawBytes)
            val pageFrame = assertIs<TaskSnapshotPageFrame>(completedPage.received.frame)
            assertEquals(0, pageFrame.pageIndex)
            assertEquals(page.startFrame.snapshotVersion, pageFrame.snapshotVersion)
            assertRootEmpty(root)
        }
    }

    @Test
    fun `accepts exact duplicate start and data without append or deadline extension`() {
        withAssembler { assembler, root, clock ->
            val transfer = eventTransfer()
            assertSame(ChunkTransferPending, assembler.accept(transfer.start))
            clock.now = 10_000
            assertSame(ChunkTransferPending, assembler.accept(transfer.start))
            assertSame(ChunkTransferPending, assembler.accept(transfer.data[0]))
            assertSame(ChunkTransferPending, assembler.accept(transfer.data[0]))
            assertEquals(transfer.firstChunkBytes.toLong(), Files.size(singleArtifact(root)))
            clock.now = 29_999
            assertSame(ChunkTransferPending, assembler.accept(transfer.data[1]))
            assertIs<CompletedChunkTransfer>(assembler.accept(transfer.end))
            assertRootEmpty(root)

            clock.now = 0
            assertSame(ChunkTransferPending, assembler.accept(transfer.start))
            clock.now = 29_999
            assertSame(ChunkTransferPending, assembler.accept(transfer.start))
            clock.now = 30_000
            assertFailsWith<ChunkReassemblyException> {
                assembler.accept(transfer.data[0])
            }
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)
        }
    }

    @Test
    fun `fails closed for conflicts order early end and unknown transfers`() {
        withAssembler { assembler, root, _ ->
            val transfer = eventTransfer()

            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.data[0]) }
            assertRootEmpty(root)

            assembler.accept(transfer.start)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.data[1]) }
            assertRootEmpty(root)

            assembler.accept(transfer.start)
            assembler.accept(transfer.data[0])
            val first = decodedChunkBytes(transfer.data[0]).copyOf()
            first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
            assertFailsWith<ChunkReassemblyException> {
                assembler.accept(dataFrame(transfer.transferId, 0, first))
            }
            assertRootEmpty(root)

            assembler.accept(transfer.start)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)

            assembler.accept(transfer.start)
            val conflictingStart = mutateStart(
                transfer.startSource,
                "sha256" to JsonPrimitive("0".repeat(64)),
            )
            assertFailsWith<ChunkReassemblyException> { assembler.accept(conflictingStart) }
            assertRootEmpty(root)

            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)
        }
    }

    @Test
    fun `fails closed for overflow underflow sha logical decode and binding drift`() {
        withAssembler { assembler, root, _ ->
            val transfer = eventTransfer()

            assembler.accept(transfer.start)
            assembler.accept(transfer.data[0])
            val last = decodedChunkBytes(transfer.data[1]) + byteArrayOf('x'.code.toByte())
            assertFailsWith<ChunkReassemblyException> {
                assembler.accept(dataFrame(transfer.transferId, 1, last))
            }
            assertRootEmpty(root)

            val underflowStart = mutateStart(
                transfer.startSource,
                "totalBytes" to JsonPrimitive(transfer.logicalBytes.size + 1),
            )
            assembler.accept(underflowStart)
            transfer.data.forEach(assembler::accept)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)

            val wrongShaStart = mutateStart(
                transfer.startSource,
                "sha256" to JsonPrimitive("0".repeat(64)),
            )
            assembler.accept(wrongShaStart)
            transfer.data.forEach(assembler::accept)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)

            val wrongBindingStart = mutateStart(
                transfer.startSource,
                "sequence" to JsonPrimitive(11),
            )
            assembler.accept(wrongBindingStart)
            transfer.data.forEach(assembler::accept)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)

            val invalidLogical = ByteArray(transfer.logicalBytes.size) { 'x'.code.toByte() }
            val invalidStart = receivedStart(
                transfer.startFrame.copy(
                    totalBytes = invalidLogical.size.toLong(),
                    sha256 = sha256(invalidLogical),
                ),
            )
            val invalidData = splitData(
                transfer.transferId,
                invalidLogical,
                transfer.firstChunkBytes,
            )
            assembler.accept(invalidStart)
            invalidData.forEach(assembler::accept)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)

            val nullRequestLogical = JsonObject(
                Json.parseToJsonElement(transfer.logicalBytes.decodeToString()).jsonObject +
                    ("requestId" to JsonNull),
            ).toString().encodeToByteArray()
            val nullRequestStart = receivedStart(
                transfer.startFrame.copy(
                    totalBytes = nullRequestLogical.size.toLong(),
                    sha256 = sha256(nullRequestLogical),
                ),
            )
            assembler.accept(nullRequestStart)
            splitData(transfer.transferId, nullRequestLogical, transfer.firstChunkBytes)
                .forEach(assembler::accept)
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.end) }
            assertRootEmpty(root)
        }
    }

    @Test
    fun `binds every event and snapshot-page identity field after logical decode`() {
        withAssembler { assembler, root, _ ->
            val event = eventTransfer()
            listOf(
                "taskId" to JsonPrimitive("99999999-9999-4999-8999-999999999999"),
                "streamId" to JsonPrimitive("88888888-8888-4888-8888-888888888888"),
                "sequence" to JsonPrimitive(11),
            ).forEach { change ->
                assembler.accept(mutateStart(event.startSource, change))
                event.data.forEach(assembler::accept)
                assertFailsWith<ChunkReassemblyException> { assembler.accept(event.end) }
                assertRootEmpty(root)
            }

            val page = pageTransfer()
            listOf(
                "taskId" to JsonPrimitive("99999999-9999-4999-8999-999999999999"),
                "snapshotVersion" to JsonPrimitive(8),
                "pageIndex" to JsonPrimitive(1),
            ).forEach { change ->
                assembler.accept(mutateStart(page.startSource, change))
                page.data.forEach(assembler::accept)
                assertFailsWith<ChunkReassemblyException> { assembler.accept(page.end) }
                assertRootEmpty(root)
            }
        }
    }

    @Test
    fun `expires at 30 seconds and cleans on expire and close`() {
        withAssembler { assembler, root, clock ->
            val transfer = pageTransfer()
            assembler.accept(transfer.start)
            val artifact = singleArtifact(root)
            assertTrue(artifact.fileName.toString().startsWith("p1b-chunk-"))
            assertTrue(!artifact.fileName.toString().contains(transfer.transferId))

            clock.now = 29_999
            assertEquals(emptyList(), assembler.expire())
            assertEquals(1, assembler.activeTransferCount)

            clock.now = 30_000
            assertEquals(listOf(transfer.transferId), assembler.expire())
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)

            clock.now = 40_000
            assembler.accept(transfer.start)
            assembler.accept(transfer.data[0])
            assembler.close()
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)
        }
    }

    @Test
    fun `close is terminal idempotent and safe across accept ordering`() {
        withAssembler { assembler, root, _ ->
            val transfer = eventTransfer()
            assembler.close()
            assembler.close()
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.start) }
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)
        }

        withAssembler { assembler, root, _ ->
            val transfer = eventTransfer()
            assembler.accept(transfer.start)
            assembler.accept(transfer.data[0])
            assembler.close()
            assembler.close()
            assertFailsWith<ChunkReassemblyException> { assembler.accept(transfer.data[1]) }
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)
        }

        withAssembler { assembler, root, _ ->
            val transfer = eventTransfer()
            val ready = CountDownLatch(2)
            val release = CountDownLatch(1)
            val acceptFailure = AtomicReference<Throwable?>()
            val closeFailure = AtomicReference<Throwable?>()
            val executor = Executors.newFixedThreadPool(2)
            try {
                executor.submit {
                    ready.countDown()
                    release.await()
                    try {
                        assembler.accept(transfer.start)
                    } catch (error: Throwable) {
                        acceptFailure.set(error)
                    }
                }
                executor.submit {
                    ready.countDown()
                    release.await()
                    try {
                        assembler.close()
                    } catch (error: Throwable) {
                        closeFailure.set(error)
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                release.countDown()
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            assertEquals(null, closeFailure.get())
            acceptFailure.get()?.let { assertIs<ChunkReassemblyException>(it) }
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)
        }
    }

    @Test
    fun `clock failures clean duplicate-start and expire active artifacts`() {
        withAssembler { assembler, root, clock ->
            val transfer = eventTransfer()
            assembler.accept(transfer.start)
            clock.failure = IllegalStateException("clock duplicate failure")
            val duplicateFailure = assertFailsWith<ChunkReassemblyException> {
                assembler.accept(transfer.start)
            }
            assertIs<IllegalStateException>(duplicateFailure.cause)
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)

            clock.failure = null
            assembler.accept(transfer.start)
            assembler.accept(pageTransfer().start)
            assertEquals(2, assembler.activeTransferCount)
            clock.failure = IllegalStateException("clock expire failure")
            val expireFailure = assertFailsWith<ChunkReassemblyException> {
                assembler.expire()
            }
            assertIs<IllegalStateException>(expireFailure.cause)
            assertEquals(0, assembler.activeTransferCount)
            assertRootEmpty(root)
        }
    }

    @Test
    fun `rejects invalid starts before creating artifacts and preflights the maxima`() {
        withAssembler { assembler, root, _ ->
            val source = eventTransfer().startFrame
            val invalidStarts = listOf(
                source.copy(totalBytes = P1bProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong() + 1),
                source.copy(chunkCount = P1bProtocol.CHUNK_MAX_COUNT + 1),
                source.copy(totalBytes = P1bProtocol.DIRECT_PI_EVENT_MAX_BYTES.toLong()),
                source.copy(contentKind = "task.snapshot.page"),
            )
            invalidStarts.forEach { start ->
                assertFailsWith<ChunkReassemblyException> {
                    assembler.accept(receivedStart(start))
                }
                assertRootEmpty(root)
            }

            val maximum = source.copy(
                totalBytes = P1bProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong(),
                chunkCount = P1bProtocol.CHUNK_MAX_COUNT,
            )
            assertSame(ChunkTransferPending, assembler.accept(receivedStart(maximum)))
            assertEquals(0, Files.size(singleArtifact(root)))
            assembler.close()
            assertRootEmpty(root)
        }
    }

    private fun consume(
        assembler: ChunkReassembler,
        transfer: TransferFixture<out TransportChunkStartFrame>,
    ): CompletedChunkTransfer {
        assertSame(ChunkTransferPending, assembler.accept(transfer.start))
        transfer.data.forEach { frame ->
            assertSame(ChunkTransferPending, assembler.accept(frame))
        }
        return assertIs(assembler.accept(transfer.end))
    }

    private fun eventTransfer(): TransferFixture<PiEventChunkStartFrame> {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val startSource = server.getValue("eventChunk").jsonArray.first().jsonObject
        val endSource = server.getValue("eventChunk").jsonArray.last().jsonObject
        val recipe = byteFixture.getValue("structuredBoundaryRecipes").jsonArray
            .map { it.jsonObject }
            .single { it.string("name") == "directEventPlusOne" }
        val logical = structuredRecipeBytes(recipe)
        val firstChunkBytes = reliabilityFixture.getValue("generatedCases").jsonObject
            .getValue("eventChunk").jsonObject.int("firstChunkBytes")
        val start = ReliabilityContractDecoder.decode(startSource.toString())
        val startFrame = assertIs<PiEventChunkStartFrame>(start.frame)
        assertEquals(logical.size.toLong(), startFrame.totalBytes)
        assertEquals(sha256(logical), startFrame.sha256)
        return TransferFixture(
            transferId = startFrame.transferId,
            startSource = startSource,
            start = start,
            startFrame = startFrame,
            data = splitData(startFrame.transferId, logical, firstChunkBytes),
            end = ReliabilityContractDecoder.decode(endSource.toString()),
            logicalBytes = logical,
            firstChunkBytes = firstChunkBytes,
        )
    }

    private fun pageTransfer(): TransferFixture<SnapshotPageChunkStartFrame> {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val startSource = server.getValue("snapshotPageChunk").jsonArray.first().jsonObject
        val endSource = server.getValue("snapshotPageChunk").jsonArray.last().jsonObject
        val recipe = byteFixture.getValue("physicalFrameRecipes").jsonArray
            .map { it.jsonObject }
            .single { it.string("name") == "snapshotPagePhysicalPlusOne" }
        val logical = physicalRecipeBytes(recipe)
        val firstChunkBytes = reliabilityFixture.getValue("generatedCases").jsonObject
            .getValue("snapshotPageChunk").jsonObject.int("firstChunkBytes")
        val start = ReliabilityContractDecoder.decode(startSource.toString())
        val startFrame = assertIs<SnapshotPageChunkStartFrame>(start.frame)
        assertEquals(logical.size.toLong(), startFrame.totalBytes)
        assertEquals(sha256(logical), startFrame.sha256)
        return TransferFixture(
            transferId = startFrame.transferId,
            startSource = startSource,
            start = start,
            startFrame = startFrame,
            data = splitData(startFrame.transferId, logical, firstChunkBytes),
            end = ReliabilityContractDecoder.decode(endSource.toString()),
            logicalBytes = logical,
            firstChunkBytes = firstChunkBytes,
        )
    }

    private fun splitData(
        transferId: String,
        logical: ByteArray,
        firstChunkBytes: Int,
    ): List<ReceivedP1bServerFrame> {
        val chunks = listOf(
            logical.copyOfRange(0, firstChunkBytes),
            logical.copyOfRange(firstChunkBytes, logical.size),
        )
        return chunks.mapIndexed { index, bytes -> dataFrame(transferId, index, bytes) }
    }

    private fun dataFrame(
        transferId: String,
        chunkIndex: Int,
        bytes: ByteArray,
    ): ReceivedP1bServerFrame {
        val frame = JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(1),
                "kind" to JsonPrimitive("transport.chunk.data"),
                "transferId" to JsonPrimitive(transferId),
                "chunkIndex" to JsonPrimitive(chunkIndex),
                "data" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
            ),
        )
        return ReliabilityContractDecoder.decode(frame.toString())
    }

    private fun mutateStart(
        source: JsonObject,
        vararg changes: Pair<String, JsonPrimitive>,
    ): ReceivedP1bServerFrame = ReliabilityContractDecoder.decode(
        JsonObject(source + changes.toMap()).toString(),
    )

    private fun receivedStart(frame: PiEventChunkStartFrame): ReceivedP1bServerFrame =
        ReceivedP1bServerFrame(frame, "review-constructed-start".encodeToByteArray())

    private fun decodedChunkBytes(received: ReceivedP1bServerFrame): ByteArray =
        Base64.getDecoder().decode(assertIs<TransportChunkDataFrame>(received.frame).data)

    private fun structuredRecipeBytes(recipe: JsonObject): ByteArray =
        recipe.string("jsonPrefix").toByteArray(StandardCharsets.UTF_8) +
            patternPayload(
                recipe.int("payloadBytes"),
                recipe.string("utf8Pattern"),
                recipe.string("asciiRemainderByte"),
            ) +
            recipe.string("jsonSuffix").toByteArray(StandardCharsets.UTF_8)

    private fun physicalRecipeBytes(recipe: JsonObject): ByteArray {
        val prefix = recipe.string("jsonPrefix").toByteArray(StandardCharsets.UTF_8)
        val suffix = recipe.string("jsonSuffix").toByteArray(StandardCharsets.UTF_8)
        return prefix +
            patternPayload(
                recipe.int("targetBytes") - prefix.size - suffix.size,
                recipe.string("utf8Pattern"),
                recipe.string("asciiRemainderByte"),
            ) +
            suffix
    }

    private fun patternPayload(
        targetBytes: Int,
        pattern: String,
        remainderByte: String,
    ): ByteArray {
        val patternBytes = pattern.toByteArray(StandardCharsets.UTF_8)
        val payload = (
            pattern.repeat(targetBytes / patternBytes.size) +
                remainderByte.repeat(targetBytes % patternBytes.size)
        ).toByteArray(StandardCharsets.UTF_8)
        assertEquals(targetBytes, payload.size)
        return payload
    }

    private fun readFixture(path: String): JsonObject {
        val text = checkNotNull(javaClass.getResource(path)) { "Missing fixture: $path" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun withAssembler(
        block: (ChunkReassembler, Path, MutableClock) -> Unit,
    ) {
        val root = Files.createTempDirectory("p1b-kotlin-chunks-")
        val clock = MutableClock()
        val assembler = ChunkReassembler(root, clock::read)
        try {
            block(assembler, root, clock)
        } finally {
            assembler.close()
            assertRootEmpty(root)
            Files.deleteIfExists(root)
        }
    }

    private fun singleArtifact(root: Path): Path = Files.list(root).use { paths ->
        val files = paths.toList()
        assertEquals(1, files.size)
        files.single()
    }

    private fun assertRootEmpty(root: Path) {
        Files.list(root).use { paths -> assertEquals(0, paths.count()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class TransferFixture<T : TransportChunkStartFrame>(
        val transferId: String,
        val startSource: JsonObject,
        val start: ReceivedP1bServerFrame,
        val startFrame: T,
        val data: List<ReceivedP1bServerFrame>,
        val end: ReceivedP1bServerFrame,
        val logicalBytes: ByteArray,
        val firstChunkBytes: Int,
    )

    private class MutableClock(
        var now: Long = 0,
        var failure: RuntimeException? = null,
    ) {
        fun read(): Long {
            failure?.let { throw it }
            return now
        }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
