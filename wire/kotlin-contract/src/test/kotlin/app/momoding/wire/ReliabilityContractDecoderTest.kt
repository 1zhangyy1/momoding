package app.momoding.wire

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReliabilityContractDecoderTest {
    private val reliabilityFixture: JsonObject by lazy {
        readFixture("/pi-0.80.6/p1b-reliability-contract.json")
    }
    private val byteFixture: JsonObject by lazy {
        readFixture("/pi-0.80.6/p1b-byte-domains.json")
    }

    @Test
    fun `decodes the complete P1B server union while retaining exact raw bytes`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val extensionFrames = buildList {
            add(server.getValue("replayComplete"))
            addAll(server.getValue("resyncRequired").jsonArray)
            addAll(server.getValue("eventChunk").jsonArray)
            addAll(server.getValue("snapshotPageChunk").jsonArray)
            addAll(server.getValue("snapshotTransfer").jsonArray)
            addAll(server.getValue("historyTransfer").jsonArray)
            addAll(server.getValue("deviceRequests").jsonArray)
            add(server.getValue("deviceCancel"))
            add(server.getValue("reconcileRequest"))
            add(
                parse(
                    """
                    {
                      "protocolVersion":1,
                      "kind":"transport.chunk.data",
                      "transferId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                      "chunkIndex":0,
                      "data":"AQ=="
                    }
                    """.trimIndent(),
                ),
            )
        }

        val decoded = extensionFrames.map { source ->
            val raw = source.toString().encodeToByteArray()
            ReliabilityContractDecoder.decode(raw).also { received ->
                assertContentEquals(raw, received.rawBytes)
            }.frame
        }

        assertEquals(19, decoded.size)
        assertIs<PiReplayCompleteFrame>(decoded[0])
        assertEquals(3, decoded.filterIsInstance<PiResyncRequiredFrame>().size)
        assertEquals(1, decoded.filterIsInstance<PiEventChunkStartFrame>().size)
        assertEquals(1, decoded.filterIsInstance<SnapshotPageChunkStartFrame>().size)
        assertEquals(1, decoded.filterIsInstance<TransportChunkDataFrame>().size)
        assertEquals(2, decoded.filterIsInstance<TransportChunkEndFrame>().size)
        assertEquals(2, decoded.filterIsInstance<TaskSnapshotBeginFrame>().size)
        assertEquals(2, decoded.filterIsInstance<TaskSnapshotPageFrame>().size)
        assertEquals(2, decoded.filterIsInstance<TaskSnapshotEndFrame>().size)
        assertEquals(2, decoded.filterIsInstance<DeviceToolRequestFrame>().size)
        assertEquals(1, decoded.filterIsInstance<DeviceToolCancelFrame>().size)
        assertEquals(1, decoded.filterIsInstance<DeviceToolReconcileRequestFrame>().size)

        val logicalEvent = byteFixture.getValue("logicalEvent").jsonObject
        val eventBytes = Base64.getDecoder().decode(logicalEvent.string("jsonBase64"))
        val receivedEvent = ReliabilityContractDecoder.decode(eventBytes)
        assertContentEquals(eventBytes, receivedEvent.rawBytes)
        assertIs<PiEventFrame>(receivedEvent.frame)
        assertEquals(server.getValue("directEvent").jsonObject.getValue("event"), receivedEvent.frame.event)

        val smallSnapshot = reliabilityFixture.getValue("smallSnapshot")
        assertIs<TaskSnapshotFrame>(ReliabilityContractDecoder.decode(smallSnapshot.toString()).frame)

        val p1aFrames = listOf(
            """{"protocolVersion":1,"kind":"hello.accepted","requestId":"hello","connectionId":"connection","serverVersion":"fixture","piVersion":"0.80.6","heartbeatIntervalMs":20000,"maxFrameBytes":1048576}""",
            """{"protocolVersion":1,"kind":"response","requestId":"response","ok":true,"data":{"accepted":true}}""",
            """{"protocolVersion":1,"kind":"error","error":{"code":"BAD_REQUEST","message":"fixture","retryable":false}}""",
        ).map { ReliabilityContractDecoder.decode(it).frame }
        assertIs<HelloAcceptedFrame>(p1aFrames[0])
        assertIs<CommandResponseFrame>(p1aFrames[1])
        assertIs<WireErrorFrame>(p1aFrames[2])
    }

    @Test
    fun `encodes the exact Node locked cumulative ACK shape`() {
        val source = reliabilityFixture.getValue("clientFrames").jsonObject
            .getValue("ack").jsonObject
        val encoded = PiEventAckEncoder.encode(
            PiEventAckFrame(
                taskId = source.string("taskId"),
                streamId = source.string("streamId"),
                throughSequence = source.long("throughSequence"),
            ),
        )

        assertContentEquals(source.toString().encodeToByteArray(), encoded)
        assertFailsWith<IllegalArgumentException> {
            PiEventAckEncoder.encode(
                PiEventAckFrame(
                    taskId = source.string("taskId"),
                    streamId = source.string("streamId"),
                    throughSequence = P1bProtocol.MAX_SAFE_INTEGER + 1,
                ),
            )
        }
    }

    @Test
    fun `preserves opaque Pi message and device argument fields but rejects Wire drift`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val sourceEvent = server.getValue("directEvent").jsonObject
        val extendedNativeEvent = JsonObject(
            sourceEvent.getValue("event").jsonObject +
                ("futureNativeField" to JsonObject(mapOf("nested" to JsonPrimitive(7)))),
        )
        val extendedEventFrame = JsonObject(sourceEvent + ("event" to extendedNativeEvent))
        val decodedEvent = assertIs<PiEventFrame>(
            ReliabilityContractDecoder.decode(extendedEventFrame.toString()).frame,
        )
        assertEquals(extendedNativeEvent, decodedEvent.event)

        val sourcePage = server.getValue("historyTransfer").jsonArray[1].jsonObject
        val sourceMessage = sourcePage.getValue("messages").jsonArray.single().jsonObject
        val extendedMessage = JsonObject(
            sourceMessage + ("futureMessageField" to JsonArray(listOf(JsonPrimitive("kept")))),
        )
        val extendedPage = JsonObject(
            sourcePage + ("messages" to JsonArray(listOf(extendedMessage))),
        )
        val decodedPage = assertIs<TaskSnapshotPageFrame>(
            ReliabilityContractDecoder.decode(extendedPage.toString()).frame,
        )
        assertEquals(extendedMessage, decodedPage.messages.single())

        val sourceBegin = server.getValue("snapshotTransfer").jsonArray.first().jsonObject
        val rawQueueEntry = JsonObject(mapOf("futureQueueField" to JsonPrimitive("kept")))
        val rawAttention = JsonObject(mapOf("futureAttentionField" to JsonPrimitive(9)))
        val extendedBegin = JsonObject(
            sourceBegin +
                ("queue" to JsonArray(listOf(rawQueueEntry))) +
                ("pendingAttention" to JsonArray(listOf(rawAttention))),
        )
        val decodedBegin = assertIs<TaskSnapshotBeginFrame>(
            ReliabilityContractDecoder.decode(extendedBegin.toString()).frame,
        )
        assertEquals(rawQueueEntry, decodedBegin.queue.single())
        assertEquals(rawAttention, decodedBegin.pendingAttention.single())

        val sourceRequest = server.getValue("deviceRequests").jsonArray.first().jsonObject
        val extendedArguments = JsonObject(
            sourceRequest.getValue("arguments").jsonObject +
                ("futureArgument" to JsonObject(mapOf("opaque" to JsonPrimitive(true)))),
        )
        val decodedRequest = assertIs<DeviceToolRequestFrame>(
            ReliabilityContractDecoder.decode(
                JsonObject(sourceRequest + ("arguments" to extendedArguments)).toString(),
            ).frame,
        )
        assertEquals(extendedArguments, decodedRequest.arguments)

        assertFails {
            JsonObject(sourcePage + ("unexpectedWireField" to JsonPrimitive(true)))
        }
        assertFails {
            JsonObject(sourceRequest + ("translatedArguments" to extendedArguments))
        }
    }

    @Test
    fun `fails closed for protocol kind optional and safe-integer drift`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val resync = server.getValue("resyncRequired").jsonArray.first().jsonObject

        assertFails { JsonObject(resync + ("protocolVersion" to JsonPrimitive(2))) }
        assertFails { JsonObject(resync + ("kind" to JsonPrimitive("future.frame"))) }
        assertFails { JsonObject(resync + ("unexpected" to JsonPrimitive(true))) }
        assertFails {
            JsonObject(resync + ("snapshotVersion" to JsonPrimitive("9007199254740992")))
        }
        assertFails {
            JsonObject(resync + ("taskId" to JsonPrimitive("not-a-uuid")))
        }
        assertFails {
            JsonObject(resync + ("reason" to JsonPrimitive("guess")))
        }

        val atMaximum = JsonObject(
            resync + ("snapshotVersion" to JsonPrimitive(P1bProtocol.MAX_SAFE_INTEGER)),
        )
        assertEquals(
            P1bProtocol.MAX_SAFE_INTEGER,
            assertIs<PiResyncRequiredFrame>(
                ReliabilityContractDecoder.decode(atMaximum.toString()).frame,
            ).snapshotVersion,
        )

        val begin = server.getValue("snapshotTransfer").jsonArray.first().jsonObject
        assertFails { JsonObject(begin + ("requestId" to JsonNull)) }

        val replay = server.getValue("replayComplete").jsonObject
        assertFails {
            JsonObject(replay + ("liveFromSequence" to JsonPrimitive(11)))
        }

        assertFailsWith<SerializationException> {
            ReliabilityContractDecoder.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFailsWith<SerializationException> {
            ReliabilityContractDecoder.decode("[]")
        }
    }

    @Test
    fun `strictly validates P1A direct events before delegating to the Core decoder`() {
        val source = reliabilityFixture.getValue("serverFrames").jsonObject
            .getValue("directEvent").jsonObject

        listOf("taskId", "piSessionId", "streamId").forEach { key ->
            assertFails { JsonObject(source + (key to JsonPrimitive("not-a-uuid"))) }
        }
        assertFails {
            JsonObject(
                source +
                    ("sequence" to JsonPrimitive(P1bProtocol.MAX_SAFE_INTEGER + 1)),
            )
        }
        assertFails { JsonObject(source + ("emittedAt" to JsonPrimitive("not-rfc3339"))) }

        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(P1bProtocol.MAX_JSON_DEPTH + 2) {
            nested = JsonObject(mapOf("child" to nested))
        }
        assertFails {
            JsonObject(
                source +
                    ("event" to JsonObject(mapOf("type" to JsonPrimitive("future"), "data" to nested))),
            )
        }
    }

    @Test
    fun `strictly validates P1A direct snapshots before delegating to the Core decoder`() {
        val source = reliabilityFixture.getValue("smallSnapshot").jsonObject

        listOf("taskId", "piSessionId").forEach { key ->
            assertFails { JsonObject(source + (key to JsonPrimitive("not-a-uuid"))) }
        }
        assertFails {
            JsonObject(
                source +
                    ("snapshotVersion" to JsonPrimitive(P1bProtocol.MAX_SAFE_INTEGER + 1)),
            )
        }

        val cursor = source.getValue("cursor").jsonObject
        assertFails {
            JsonObject(
                source +
                    (
                        "cursor" to JsonObject(
                            cursor +
                                ("highWatermarkSequence" to JsonPrimitive(0)) +
                                ("oldestReplayableSequence" to JsonPrimitive(2)),
                        )
                    ),
            )
        }

        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(P1bProtocol.MAX_JSON_DEPTH + 2) {
            nested = JsonObject(mapOf("child" to nested))
        }
        val pi = source.getValue("pi").jsonObject
        assertFails {
            JsonObject(
                source +
                    ("pi" to JsonObject(pi + ("messages" to JsonArray(listOf(nested))))),
            )
        }
    }

    @Test
    fun `enforces chunk thresholds canonical base64 and exact start variants`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val eventStart = server.getValue("eventChunk").jsonArray.first().jsonObject
        val pageStart = server.getValue("snapshotPageChunk").jsonArray.first().jsonObject

        assertFails {
            JsonObject(
                eventStart +
                    ("totalBytes" to JsonPrimitive(P1bProtocol.DIRECT_PI_EVENT_MAX_BYTES)),
            )
        }
        assertFails {
            JsonObject(
                pageStart +
                    ("totalBytes" to JsonPrimitive(P1bProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES)),
            )
        }
        assertFails {
            JsonObject(
                eventStart +
                    ("totalBytes" to JsonPrimitive(P1bProtocol.CHUNK_MAX_TRANSFER_BYTES + 1)),
            )
        }
        assertFails {
            JsonObject(eventStart + ("snapshotVersion" to JsonPrimitive(7)))
        }
        assertFails {
            JsonObject(eventStart + ("contentKind" to JsonPrimitive("task.snapshot.page")))
        }

        val chunkData = parse(
            """
            {
              "protocolVersion":1,
              "kind":"transport.chunk.data",
              "transferId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              "chunkIndex":0,
              "data":"AQ"
            }
            """.trimIndent(),
        )
        assertFails { chunkData }
    }

    @Test
    fun `enforces snapshot metadata cursor and window invariants`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val begin = server.getValue("snapshotTransfer").jsonArray.first().jsonObject
        val window = begin.getValue("window").jsonObject
        val cursor = begin.getValue("cursor").jsonObject

        assertFails {
            JsonObject(
                begin +
                    ("window" to JsonObject(window + ("hasMoreBefore" to JsonPrimitive(true)))),
            )
        }
        assertFails {
            JsonObject(
                begin +
                    (
                        "window" to JsonObject(
                            window + ("messageEndExclusive" to JsonPrimitive(2)),
                        )
                    ),
            )
        }
        assertFails {
            JsonObject(
                begin +
                    (
                        "cursor" to JsonObject(
                            cursor +
                                ("highWatermarkSequence" to JsonPrimitive(9)) +
                                ("oldestReplayableSequence" to JsonPrimitive(11)),
                        )
                    ),
            )
        }

        val historyBegin = server.getValue("historyTransfer").jsonArray.first().jsonObject
        val decoded = assertIs<TaskSnapshotBeginFrame>(
            ReliabilityContractDecoder.decode(historyBegin.toString()).frame,
        )
        assertEquals(SnapshotTransferMode.PREPEND_HISTORY, decoded.transferMode)
        assertEquals(0, decoded.window.messageStartIndex)
        assertEquals(2, decoded.window.messageEndExclusive)
        assertNull(decoded.window.historyCursor)
    }

    @Test
    fun `enforces device side-effect timestamp reconciliation and JSON-depth rules`() {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val readRequest = server.getValue("deviceRequests").jsonArray[0].jsonObject
        val writeRequest = server.getValue("deviceRequests").jsonArray[1].jsonObject
        val operationId = writeRequest.getValue("operationId")

        assertFails { JsonObject(readRequest + ("operationId" to operationId)) }
        assertFails { JsonObject(writeRequest - "operationId") }
        assertFails {
            JsonObject(
                readRequest +
                    (
                        "expiresAt" to reliabilityFixture.getValue("invalidValues").jsonObject
                            .getValue("rfc3339CalendarDate")
                    ),
            )
        }

        val reconcile = server.getValue("reconcileRequest").jsonObject
        assertFails { JsonObject(reconcile + ("calls" to JsonArray(emptyList()))) }
        val call = reconcile.getValue("calls").jsonArray.single().jsonObject
        assertFails {
            JsonObject(
                reconcile +
                    (
                        "calls" to JsonArray(
                            listOf(JsonObject(call + ("unexpected" to JsonPrimitive(true)))),
                        )
                    ),
            )
        }

        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(P1bProtocol.MAX_JSON_DEPTH + 2) {
            nested = JsonObject(mapOf("child" to nested))
        }
        assertFails { JsonObject(readRequest + ("arguments" to nested)) }
    }

    @Test
    fun `enforces exact direct event page and chunk-data physical byte domains`() {
        val directRecipes = byteFixture.getValue("structuredBoundaryRecipes").jsonArray
            .map { it.jsonObject }
            .filter { it.string("domain") == "directEvent" }
        directRecipes.forEach { recipe ->
            val bytes = structuredRecipeBytes(recipe)
            if (recipe.int("targetBytes") <= P1bProtocol.DIRECT_PI_EVENT_MAX_BYTES) {
                assertIs<PiEventFrame>(ReliabilityContractDecoder.decode(bytes).frame)
            } else {
                assertFailsWith<SerializationException> {
                    ReliabilityContractDecoder.decode(bytes)
                }
            }
        }

        val pageRecipes = byteFixture.getValue("physicalFrameRecipes").jsonArray
            .map { it.jsonObject }
        pageRecipes.forEach { recipe ->
            val bytes = physicalFrameBytes(recipe)
            if (recipe.int("targetBytes") <= P1bProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES) {
                assertIs<TaskSnapshotPageFrame>(ReliabilityContractDecoder.decode(bytes).frame)
            } else {
                assertFailsWith<SerializationException> {
                    ReliabilityContractDecoder.decode(bytes)
                }
            }
        }

        val chunkRecipes = byteFixture.getValue("chunkDataFrameRecipes").jsonArray
            .map { it.jsonObject }
        chunkRecipes.forEach { recipe ->
            val bytes = chunkDataFrameBytes(recipe)
            if (recipe.int("physicalByteLength") <= P1bProtocol.CHUNK_MAX_PHYSICAL_FRAME_BYTES) {
                val frame = assertIs<TransportChunkDataFrame>(
                    ReliabilityContractDecoder.decode(bytes).frame,
                )
                assertEquals(recipe.int("decodedByteLength"), Base64.getDecoder().decode(frame.data).size)
            } else {
                assertFailsWith<SerializationException> {
                    ReliabilityContractDecoder.decode(bytes)
                }
            }
        }
    }

    private fun assertFails(frame: JsonObject) {
        assertFailsWith<SerializationException> {
            ReliabilityContractDecoder.decode(frame.toString())
        }
    }

    private fun assertFails(block: () -> JsonObject) {
        assertFails(block())
    }

    private fun readFixture(path: String): JsonObject {
        val text = checkNotNull(javaClass.getResource(path)) { "Missing fixture: $path" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun structuredRecipeBytes(recipe: JsonObject): ByteArray =
        recipe.string("jsonPrefix").toByteArray(StandardCharsets.UTF_8) +
            patternPayload(
                recipe.int("payloadBytes"),
                recipe.string("utf8Pattern"),
                recipe.string("asciiRemainderByte"),
            ) +
            recipe.string("jsonSuffix").toByteArray(StandardCharsets.UTF_8)

    private fun physicalFrameBytes(recipe: JsonObject): ByteArray {
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

    private fun chunkDataFrameBytes(recipe: JsonObject): ByteArray {
        val decoded = patternPayload(
            recipe.int("decodedByteLength"),
            recipe.string("utf8Pattern"),
            recipe.string("asciiRemainderByte"),
        )
        return (
            recipe.string("jsonPrefix") +
                Base64.getEncoder().encodeToString(decoded) +
                recipe.string("jsonSuffix")
        ).toByteArray(StandardCharsets.UTF_8)
    }

    private fun patternPayload(
        targetBytes: Int,
        pattern: String,
        remainderByte: String,
    ): ByteArray {
        val patternBytes = pattern.toByteArray(StandardCharsets.UTF_8)
        assertEquals(3, patternBytes.size)
        val payload = (
            pattern.repeat(targetBytes / patternBytes.size) +
                remainderByte.repeat(targetBytes % patternBytes.size)
        ).toByteArray(StandardCharsets.UTF_8)
        assertEquals(targetBytes, payload.size)
        return payload
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
