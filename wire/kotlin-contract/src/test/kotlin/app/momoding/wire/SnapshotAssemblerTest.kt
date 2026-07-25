package app.momoding.wire

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotAssemblerTest {
    private val reliabilityFixture: JsonObject by lazy {
        fixture("/pi-0.80.6/reliability-contract.json")
    }
    private val byteFixture: JsonObject by lazy {
        fixture("/pi-0.80.6/byte-domains.json")
    }

    @Test
    fun `assembles locked replace and registered history transfers from exact page bytes`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val replaceFrames = server.getValue("snapshotTransfer").jsonArray
        val replaceBegin = decode(replaceFrames.first())
        val replacePageBytes = largeSnapshotPageBytes()
        val replacePage = ReliabilityContractDecoder.decodeReassembled(
            replacePageBytes,
            "task.snapshot.page",
        )
        val replaceEnd = decode(replaceFrames.last())

        SnapshotAssembler().use { assembler ->
            assembler.acceptBegin(replaceBegin)
            assembler.acceptPage(replacePage)
            val assembled = assembler.acceptEnd(replaceEnd)

            assertEquals(SnapshotTransferMode.REPLACE, assembled.begin.transferMode)
            assertEquals(1, assembled.pages.size)
            assertEquals(replacePageBytes.size.toLong(), assembled.receivedRawPageBytes)
            assertContentEquals(replacePageBytes, assembled.pages.single().rawBytes)
            assertEquals(0, assembler.pendingTransferCount)
        }

        val historyFrames = server.getValue("historyTransfer").jsonArray
        val historyReceived = historyFrames.map(::decode)
        val historyPageBytes = historyReceived.subList(1, historyReceived.lastIndex)
            .sumOf { it.byteLength.toLong() }
        SnapshotAssembler().use { assembler ->
            assembler.registerHistoryRequest(
                HistoryRequestExpectation(
                    requestId = "history-request-fixture",
                    taskId = TASK_ID,
                    snapshotVersion = 7,
                    limitBytes = historyPageBytes,
                ),
            )
            assembler.acceptBegin(historyReceived.first())
            historyReceived.subList(1, historyReceived.lastIndex).forEach(assembler::acceptPage)
            val assembled = assembler.acceptEnd(historyReceived.last())

            assertEquals(SnapshotTransferMode.PREPEND_HISTORY, assembled.begin.transferMode)
            assertEquals(2, assembled.pages.size)
            assertEquals(historyPageBytes, assembled.receivedRawPageBytes)
            assertEquals(0, assembler.activeHistoryRequestCount)
            assertEquals(0, assembler.pendingTransferCount)
        }
    }

    @Test
    fun `fails closed and clears pending state for conflicts ordering ranges versions and digest`() {
        val begin = replaceBegin(windowEnd = 1)
        val page = page(index = 0, start = 0)

        SnapshotAssembler().use { assembler ->
            assembler.acceptBegin(received(begin, "begin"))
            assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptBegin(received(begin.copy(requestId = "other"), "conflict"))
            }
            assertEquals(0, assembler.pendingTransferCount)

            assembler.acceptBegin(received(begin, "begin"))
            assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptPage(received(page.copy(pageIndex = 1), "page"))
            }
            assertEquals(0, assembler.pendingTransferCount)

            assembler.acceptBegin(received(begin, "begin"))
            assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptPage(received(page.copy(messageStartIndex = 1), "page"))
            }
            assertEquals(0, assembler.pendingTransferCount)

            assembler.acceptBegin(received(begin, "begin"))
            assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptPage(received(page.copy(snapshotVersion = 8), "page"))
            }
            assertEquals(0, assembler.pendingTransferCount)

            assembler.acceptBegin(received(begin, "begin"))
            val raw = "exact-page".encodeToByteArray()
            assembler.acceptPage(received(page, raw))
            assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptEnd(received(end(sha = "0".repeat(64)), "end"))
            }
            assertEquals(0, assembler.pendingTransferCount)
        }
    }

    @Test
    fun `enforces replace allowance at limit minus one exact limit and limit plus one`() {
        val logicalLimit = 2_048L
        val allowance = emptyEnvelopeBytes(
            validPage(targetBytes = 1_024, index = 0, start = 0).frame as TaskSnapshotPageFrame,
        )
        val maximum = logicalLimit + allowance

        listOf(maximum - 1, maximum).forEach { rawSize ->
            SnapshotAssembler(logicalLimit).use { assembler ->
                val receivedPage = validPage(rawSize.toInt(), index = 0, start = 0)
                val raw = receivedPage.rawBytes
                assembler.acceptBegin(replaceFixtureBegin())
                assembler.acceptPage(receivedPage)
                val assembled = assembler.acceptEnd(strictEnd(listOf(raw)))
                assertEquals(rawSize, assembled.receivedRawPageBytes)
                assertEquals(0, assembler.pendingTransferCount)
            }
        }

        SnapshotAssembler(logicalLimit).use { assembler ->
            val receivedPage = validPage((maximum + 1).toInt(), index = 0, start = 0)
            val validEnd = strictEnd(listOf(receivedPage.rawBytes))
            assertEquals(orderedSha(receivedPage.rawBytes), (validEnd.frame as TaskSnapshotEndFrame).sha256)
            assembler.acceptBegin(replaceFixtureBegin())
            val error = assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptPage(receivedPage)
            }
            assertTrue(error.message!!.contains("aggregate raw-page budget"))
            assertEquals(0, assembler.pendingTransferCount)
        }
        assertEquals(64 * 1024 * 1024, ReliabilityProtocol.SNAPSHOT_MAX_LOGICAL_BYTES)
        assertTrue(allowance <= 1024)
    }

    @Test
    fun `enforces the original registered history limit and the global maximum`() {
        val limit = 1_200L
        listOf(limit - 1, limit).forEachIndexed { index, rawSize ->
            SnapshotAssembler().use { assembler ->
                val requestId = "history-$index"
                val receivedPage = validPage(rawSize.toInt(), index = 0, start = 0)
                assembler.registerHistoryRequest(
                    HistoryRequestExpectation(requestId, TASK_ID, 7, limit),
                )
                assembler.acceptBegin(strictHistoryBegin(requestId, windowEnd = 1))
                assembler.acceptPage(receivedPage)
                assertEquals(
                    rawSize,
                    assembler.acceptEnd(strictEnd(listOf(receivedPage.rawBytes)))
                        .receivedRawPageBytes,
                )
            }
        }

        SnapshotAssembler().use { assembler ->
            assembler.registerHistoryRequest(
                HistoryRequestExpectation("history-over", TASK_ID, 7, limit),
            )
            val receivedPage = validPage((limit + 1).toInt(), index = 0, start = 0)
            val validEnd = strictEnd(listOf(receivedPage.rawBytes))
            assertEquals(1, (validEnd.frame as TaskSnapshotEndFrame).pageCount)
            assembler.acceptBegin(strictHistoryBegin("history-over", windowEnd = 1))
            val error = assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptPage(receivedPage)
            }
            assertTrue(error.message!!.contains("History aggregate"))
            assertEquals(0, assembler.pendingTransferCount)
        }

        val half = ReliabilityProtocol.HISTORY_MAX_BYTES / 2
        listOf(-1, 0, 1).forEach { delta ->
            SnapshotAssembler().use { assembler ->
                val requestId = "history-global-${delta + 1}"
                val pages = listOf(
                    validPage(half, index = 0, start = 0),
                    validPage(half + delta, index = 1, start = 1),
                )
                val rawPages = pages.map { it.rawBytes }
                val validEnd = strictEnd(rawPages)
                assertEquals(2, (validEnd.frame as TaskSnapshotEndFrame).pageCount)
                assembler.registerHistoryRequest(
                    HistoryRequestExpectation(
                        requestId,
                        TASK_ID,
                        7,
                        ReliabilityProtocol.HISTORY_MAX_BYTES.toLong(),
                    ),
                )
                assembler.acceptBegin(strictHistoryBegin(requestId, windowEnd = 2))
                assembler.acceptPage(pages[0])
                if (delta <= 0) {
                    assembler.acceptPage(pages[1])
                    val assembled = assembler.acceptEnd(validEnd)
                    assertEquals(
                        ReliabilityProtocol.HISTORY_MAX_BYTES.toLong() + delta,
                        assembled.receivedRawPageBytes,
                    )
                } else {
                    val error = assertFailsWith<SnapshotAssemblyException> {
                        assembler.acceptPage(pages[1])
                    }
                    assertTrue(error.message!!.contains("History aggregate"))
                    assertEquals(0, assembler.pendingTransferCount)
                }
            }
        }

        SnapshotAssembler().use { assembler ->
            assertFailsWith<SnapshotAssemblyException> {
                assembler.registerHistoryRequest(
                    HistoryRequestExpectation(
                        "history-too-large",
                        TASK_ID,
                        7,
                        ReliabilityProtocol.HISTORY_MAX_BYTES.toLong() + 1,
                    ),
                )
            }
        }
    }

    @Test
    fun `checked aggregate overflow clears pending and permits a fresh begin`() {
        val assembler = SnapshotAssembler(Long.MAX_VALUE)
        val receivedPage = validPage(targetBytes = 1_024, index = 0, start = 0)
        assembler.acceptBegin(replaceFixtureBegin())
        val error = assertFailsWith<SnapshotAssemblyException> {
            assembler.acceptPage(receivedPage)
        }
        assertTrue(error.message!!.contains("overflow"))
        assertEquals(0, assembler.pendingTransferCount)
        assembler.acceptBegin(replaceFixtureBegin())
        assertEquals(1, assembler.pendingTransferCount)
        assembler.close()
    }

    @Test
    fun `history expectations are one shot and close clears all pending state`() {
        SnapshotAssembler().use { assembler ->
            val expectation = HistoryRequestExpectation("history-once", TASK_ID, 7, 1024)
            assembler.registerHistoryRequest(expectation)
            assertFailsWith<SnapshotAssemblyException> {
                assembler.registerHistoryRequest(expectation)
            }
            assertFailsWith<SnapshotAssemblyException> {
                assembler.acceptBegin(
                    received(historyBegin("history-once", 0, 1).copy(snapshotVersion = 8), "begin"),
                )
            }
            assertEquals(0, assembler.activeHistoryRequestCount)
        }

        val assembler = SnapshotAssembler()
        assembler.registerHistoryRequest(
            HistoryRequestExpectation("history-close", TASK_ID, 7, 1024),
        )
        assembler.acceptBegin(received(replaceBegin(windowEnd = 1), "begin"))
        assembler.close()
        assertEquals(0, assembler.pendingTransferCount)
        assertEquals(0, assembler.activeHistoryRequestCount)
        assertFailsWith<SnapshotAssemblyException> {
            assembler.acceptBegin(received(replaceBegin(windowEnd = 1), "begin"))
        }
    }

    private fun replaceBegin(windowEnd: Long) = TaskSnapshotBeginFrame(
        protocolVersion = 1,
        kind = "task.snapshot.begin",
        requestId = "snapshot-request",
        transferMode = SnapshotTransferMode.REPLACE,
        taskId = TASK_ID,
        snapshotVersion = 7,
        recoveryState = RecoveryState.NORMAL,
        runState = TaskRunState.IDLE,
        piSessionId = SESSION_ID,
        isStreaming = false,
        queue = emptyList(),
        pendingAttention = emptyList(),
        deviceCalls = emptyList(),
        cursor = StreamCursor(STREAM_ID, 9, 1),
        totalMessages = windowEnd,
        window = SnapshotWindow(0, windowEnd, false),
    )

    private fun historyBegin(requestId: String, windowStart: Long, windowEnd: Long) =
        replaceBegin(windowEnd).copy(
            requestId = requestId,
            transferMode = SnapshotTransferMode.PREPEND_HISTORY,
            totalMessages = windowEnd,
            window = SnapshotWindow(windowStart, windowEnd, false),
        )

    private fun page(index: Int, start: Long) = TaskSnapshotPageFrame(
        protocolVersion = 1,
        kind = "task.snapshot.page",
        taskId = TASK_ID,
        snapshotVersion = 7,
        pageIndex = index,
        messageStartIndex = start,
        messages = listOf(JsonPrimitive("message-$index")),
    )

    private fun end(sha: String, pageCount: Int = 1) = TaskSnapshotEndFrame(
        protocolVersion = 1,
        kind = "task.snapshot.end",
        taskId = TASK_ID,
        snapshotVersion = 7,
        pageCount = pageCount,
        sha256 = sha,
    )

    private fun decode(element: kotlinx.serialization.json.JsonElement): ReceivedReliabilityServerFrame =
        ReliabilityContractDecoder.decode(element.toString().encodeToByteArray())

    private fun received(frame: ReliabilityServerFrame, raw: String): ReceivedReliabilityServerFrame =
        received(frame, raw.encodeToByteArray())

    private fun received(frame: ReliabilityServerFrame, raw: ByteArray): ReceivedReliabilityServerFrame =
        ReceivedReliabilityServerFrame(frame, raw)

    private fun emptyEnvelopeBytes(page: TaskSnapshotPageFrame): Long = buildJsonObject {
        put("protocolVersion", page.protocolVersion)
        put("kind", page.kind)
        put("taskId", page.taskId)
        put("snapshotVersion", page.snapshotVersion)
        put("pageIndex", page.pageIndex)
        put("messageStartIndex", page.messageStartIndex)
        put("messages", JsonArray(emptyList()))
    }.toString().encodeToByteArray().size.toLong()

    private fun replaceFixtureBegin(): ReceivedReliabilityServerFrame = decode(
        reliabilityFixture.getValue("serverFrames").jsonObject
            .getValue("snapshotTransfer").jsonArray.first(),
    )

    private fun strictHistoryBegin(requestId: String, windowEnd: Long): ReceivedReliabilityServerFrame {
        val source = reliabilityFixture.getValue("serverFrames").jsonObject
            .getValue("historyTransfer").jsonArray.first().jsonObject
        val window = source.getValue("window").jsonObject
        val raw = JsonObject(
            source +
                ("requestId" to JsonPrimitive(requestId)) +
                ("totalMessages" to JsonPrimitive(windowEnd)) +
                ("window" to JsonObject(
                    window +
                        ("messageStartIndex" to JsonPrimitive(0)) +
                        ("messageEndExclusive" to JsonPrimitive(windowEnd)),
                )),
        ).toString().encodeToByteArray()
        return ReliabilityContractDecoder.decode(raw)
    }

    private fun strictEnd(rawPages: List<ByteArray>): ReceivedReliabilityServerFrame {
        val raw = buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "task.snapshot.end")
            put("taskId", TASK_ID)
            put("snapshotVersion", 7)
            put("pageCount", rawPages.size)
            put("sha256", orderedSha(*rawPages.toTypedArray()))
        }.toString().encodeToByteArray()
        return ReliabilityContractDecoder.decode(raw)
    }

    private fun validPage(
        targetBytes: Int,
        index: Int,
        start: Long,
    ): ReceivedReliabilityServerFrame {
        fun raw(padding: String): ByteArray = buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "task.snapshot.page")
            put("taskId", TASK_ID)
            put("snapshotVersion", 7)
            put("pageIndex", index)
            put("messageStartIndex", start)
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("futureNativeField", "kept")
                            put("padding", padding)
                        },
                    ),
                ),
            )
        }.toString().encodeToByteArray()

        val empty = raw("")
        require(targetBytes >= empty.size) { "target $targetBytes is below valid page minimum" }
        val exact = raw("a".repeat(targetBytes - empty.size))
        assertEquals(targetBytes, exact.size)
        val received = if (exact.size > ReliabilityProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES) {
            ReliabilityContractDecoder.decodeReassembled(exact, "task.snapshot.page")
        } else {
            ReliabilityContractDecoder.decode(exact)
        }
        val frame = received.frame as TaskSnapshotPageFrame
        assertEquals(index, frame.pageIndex)
        assertEquals(start, frame.messageStartIndex)
        assertEquals("kept", frame.messages.single().jsonObject.getValue("futureNativeField").jsonPrimitive.content)
        return received
    }

    private fun largeSnapshotPageBytes(): ByteArray {
        val recipe = byteFixture.getValue("physicalFrameRecipes").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "snapshotPagePhysicalPlusOne" }
        val prefix = recipe.getValue("jsonPrefix").jsonPrimitive.content
            .toByteArray(StandardCharsets.UTF_8)
        val suffix = recipe.getValue("jsonSuffix").jsonPrimitive.content
            .toByteArray(StandardCharsets.UTF_8)
        val targetBytes = recipe.getValue("targetBytes").jsonPrimitive.content.toInt()
        val pattern = recipe.getValue("utf8Pattern").jsonPrimitive.content
        val remainder = recipe.getValue("asciiRemainderByte").jsonPrimitive.content
        val payloadBytes = targetBytes - prefix.size - suffix.size
        val patternBytes = pattern.toByteArray(StandardCharsets.UTF_8)
        val payload = (
            pattern.repeat(payloadBytes / patternBytes.size) +
                remainder.repeat(payloadBytes % patternBytes.size)
        ).toByteArray(StandardCharsets.UTF_8)
        return prefix + payload + suffix
    }

    private fun orderedSha(vararg pages: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        pages.forEach { raw ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(raw.size.toLong()).array())
            digest.update(raw)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fixture(path: String): JsonObject {
        val text = checkNotNull(javaClass.getResource(path)).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val SESSION_ID = "22222222-2222-4222-8222-222222222222"
        const val STREAM_ID = "33333333-3333-4333-8333-333333333333"
    }
}
