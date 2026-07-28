package app.momoding.wire

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReliabilityReceiverIntegrationTest {
    private val reliabilityFixture: JsonObject by lazy {
        fixture("/pi-0.80.6/p1b-reliability-contract.json")
    }
    private val byteFixture: JsonObject by lazy {
        fixture("/pi-0.80.6/p1b-byte-domains.json")
    }

    @Test
    fun `routes exact direct event bytes to durable commit then ACK and never executes device frames`() {
        withReceiver { receiver, store, _ ->
            val source = reliabilityFixture.getValue("serverFrames").jsonObject
                .getValue("directEvent").jsonObject
            val eventZero = JsonObject(source + ("sequence" to JsonPrimitive(0)))
                .toString().encodeToByteArray()
            val rejectedZero = receiver.receive(eventZero)
            assertEquals(
                ReceiverFailureCode.DECODE_FAILED,
                assertIs<ReceiverFailure>(rejectedZero.single()).code,
            )
            assertEquals(0, store.transactionCount)

            val extendedNativeEvent = JsonObject(
                source.getValue("event").jsonObject +
                    ("futureNativeField" to JsonObject(
                        mapOf("nested" to JsonPrimitive("committed-verbatim")),
                    )),
            )
            val eventOne = JsonObject(
                source +
                    ("sequence" to JsonPrimitive(1)) +
                    ("event" to extendedNativeEvent),
            )
                .toString().encodeToByteArray()

            val first = receiver.receive(eventOne)
            assertEquals(2, first.size)
            assertEquals(1, assertIs<ProjectionCommitted>(first[0]).state.throughSequence)
            assertEquals(1, assertIs<AckReady>(first[1]).frame.throughSequence)
            assertEquals(1, store.commitCount)
            assertEquals(1, store.state(TASK_ID)?.rawEvents?.size)
            assertEquals(
                extendedNativeEvent,
                store.state(TASK_ID)?.rawEvents?.getValue(1)?.event,
            )

            val transactionCount = store.transactionCount
            val commitCount = store.commitCount
            val durableBeforeDuplicate = store.state(TASK_ID)
            val stagedBeforeDuplicate = durableBeforeDuplicate?.stagedRawFrameBatches
            store.failAtStage = ProjectionWriteStage.RAW
            store.failFinalCommit = true
            val duplicate = receiver.receive(eventOne)
            assertEquals(2, duplicate.size)
            assertEquals(1, assertIs<AckReady>(duplicate[1]).frame.throughSequence)
            assertEquals(1, store.state(TASK_ID)?.rawEvents?.size)
            assertEquals(transactionCount, store.transactionCount)
            assertEquals(commitCount, store.commitCount)
            assertEquals(durableBeforeDuplicate, store.state(TASK_ID))
            assertEquals(stagedBeforeDuplicate, store.state(TASK_ID)?.stagedRawFrameBatches)
            store.failAtStage = null
            store.failFinalCommit = false

            val device = reliabilityFixture.getValue("serverFrames").jsonObject
                .getValue("deviceRequests").jsonArray.first().toString().encodeToByteArray()
            val beforeDeviceTransactions = store.transactionCount
            val deviceActions = receiver.receive(device)
            assertEquals(1, deviceActions.size)
            val expectedDevice = device.toString(StandardCharsets.UTF_8)
            val deviceFrame = assertIs<DeviceToolRequestFrame>(
                assertIs<FrameReady>(deviceActions.single()).received.frame,
            )
            assertEquals(
                Json.parseToJsonElement(expectedDevice).jsonObject.getValue("arguments"),
                deviceFrame.arguments,
            )
            assertEquals(beforeDeviceTransactions, store.transactionCount)
        }
    }

    @Test
    fun `hard raw budget fences events until a covering authoritative snapshot commits`() {
        val retained = ByteArray(RawFrameRetentionPolicy.RAW_EVENT_HARD_MAX_BYTES.toInt())
        val store = ReferenceProjectionStore().apply {
            seed(
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    throughSequence = 1,
                    snapshotVersion = 6,
                    rawEvents = mapOf(
                        1L to RawPiEventRecord(
                            1,
                            "retained-digest",
                            retained,
                            JsonObject(mapOf("type" to JsonPrimitive("message_update"))),
                        ),
                    ),
                ),
            )
        }
        withReceiver(store) { receiver, durable, _ ->
            val source = reliabilityFixture.getValue("serverFrames").jsonObject
                .getValue("directEvent").jsonObject
            val eventTwo = JsonObject(source + ("sequence" to JsonPrimitive(2)))
                .toString().encodeToByteArray()
            val required = receiver.receive(eventTwo)
            val expectation = assertIs<SnapshotRequired>(required.single()).expectation
            assertEquals(2, expectation.rejectedSequence)
            assertEquals(0, durable.transactionCount)
            assertEquals(1, durable.state(TASK_ID)?.throughSequence)

            val eventThree = JsonObject(source + ("sequence" to JsonPrimitive(3)))
                .toString().encodeToByteArray()
            assertEquals(expectation, assertIs<SnapshotRequired>(receiver.receive(eventThree).single()).expectation)
            assertEquals(0, durable.transactionCount)

            val snapshotSource = reliabilityFixture.getValue("smallSnapshot").jsonObject
            val cursorSource = snapshotSource.getValue("cursor").jsonObject
            val wrongSnapshot = JsonObject(
                snapshotSource +
                    ("cursor" to JsonObject(
                        cursorSource + ("highWatermarkSequence" to JsonPrimitive(1)),
                    )),
            ).toString().encodeToByteArray()
            assertIs<SnapshotRequired>(receiver.receive(wrongSnapshot).single())
            assertEquals(0, durable.transactionCount)

            val matchingSnapshot = JsonObject(
                snapshotSource +
                    ("cursor" to JsonObject(
                        cursorSource + ("highWatermarkSequence" to JsonPrimitive(2)),
                    )),
            ).toString().encodeToByteArray()
            val replacement = receiver.receive(matchingSnapshot)
            assertEquals(2, replacement.size)
            assertEquals(2, assertIs<ProjectionCommitted>(replacement[0]).state.throughSequence)
            assertEquals(2, assertIs<AckReady>(replacement[1]).frame.throughSequence)
            assertEquals(1, durable.commitCount)
            assertTrue(durable.state(TASK_ID)?.rawEvents?.isEmpty() == true)
            assertEquals(listOf(1L), durable.state(TASK_ID)?.stagedRawFrameBatches?.map { it.batchOrdinal })

            val acceptedAfterReplacement = receiver.receive(eventThree)
            assertEquals(2, acceptedAfterReplacement.size)
            assertEquals(3, assertIs<AckReady>(acceptedAfterReplacement[1]).frame.throughSequence)
        }
    }

    @Test
    fun `integrates mixed chunk completed and direct pages into one authoritative snapshot commit`() {
        withReceiver { receiver, store, root ->
            val server = reliabilityFixture.getValue("serverFrames").jsonObject
            val beginSource = server.getValue("snapshotTransfer").jsonArray.first().jsonObject
            val beginWindow = beginSource.getValue("window").jsonObject
            val rawQueue = JsonObject(mapOf("futureQueueField" to JsonPrimitive("kept")))
            val rawAttention = JsonObject(mapOf("futureAttentionField" to JsonPrimitive(9)))
            val twoPageBegin = JsonObject(
                beginSource +
                    ("totalMessages" to JsonPrimitive(2)) +
                    ("queue" to JsonArray(listOf(rawQueue))) +
                    ("pendingAttention" to JsonArray(listOf(rawAttention))) +
                    ("window" to JsonObject(
                        beginWindow + ("messageEndExclusive" to JsonPrimitive(2)),
                    )),
            ).toString().encodeToByteArray()
            assertIs<AwaitingTransfer>(receiver.receive(twoPageBegin).single())

            val largePage = largeSnapshotPageBytes()
            val chunkFrames = pageChunkPhysicalFrames(largePage)
            chunkFrames.forEach { raw ->
                assertIs<AwaitingTransfer>(receiver.receive(raw).single())
            }

            val secondPage = Base64.getDecoder().decode(
                byteFixture.getValue("snapshotPages").jsonArray[1].jsonObject
                    .getValue("jsonBase64").jsonPrimitive.content,
            )
            assertIs<AwaitingTransfer>(receiver.receive(secondPage).single())

            val endSource = server.getValue("snapshotTransfer").jsonArray.last().jsonObject
            val end = JsonObject(
                endSource +
                    ("pageCount" to JsonPrimitive(2)) +
                    ("sha256" to JsonPrimitive(orderedSha(largePage, secondPage))),
            ).toString().encodeToByteArray()
            val actions = receiver.receive(end)

            assertEquals(2, actions.size)
            val committed = assertIs<ProjectionCommitted>(actions[0])
            assertEquals(2, committed.state.messages.size)
            val expectedMessages =
                (ReliabilityContractDecoder.decodeReassembled(
                    largePage,
                    "task.snapshot.page",
                ).frame as TaskSnapshotPageFrame).messages +
                    (ReliabilityContractDecoder.decode(secondPage).frame as TaskSnapshotPageFrame).messages
            assertEquals(expectedMessages, committed.state.messages)
            assertEquals(listOf(rawQueue), committed.state.queue)
            assertEquals(listOf(rawAttention), committed.state.pendingAttention)
            assertEquals(0, committed.state.windowStart)
            assertEquals(2, committed.state.windowEndExclusive)
            assertEquals(9, assertIs<AckReady>(actions[1]).frame.throughSequence)
            assertEquals(1, store.commitCount)
            assertEquals(
                listOf(
                    "task.snapshot.begin",
                    "task.snapshot.page",
                    "task.snapshot.page",
                    "task.snapshot.end",
                ),
                committed.state.stagedRawFrames.map { it.kind },
            )
            assertRootEmpty(root)
        }
    }

    @Test
    fun `registers exact history request limit and prepends without emitting ACK`() {
        val store = ReferenceProjectionStore().apply {
            seed(
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    throughSequence = 9,
                    snapshotVersion = 7,
                    windowStart = 2,
                    windowEndExclusive = 4,
                    messages = listOf(JsonPrimitive("recent-2"), JsonPrimitive("recent-3")),
                ),
            )
        }
        withReceiver(store) { receiver, _, _ ->
            receiver.registerHistoryRequest(
                requestId = "history-request-fixture",
                taskId = TASK_ID,
                snapshotVersion = 7,
                limitBytes = byteFixture.getValue("historyCountedBytes").jsonPrimitive.int.toLong(),
            )
            val history = reliabilityFixture.getValue("serverFrames").jsonObject
                .getValue("historyTransfer").jsonArray
            history.dropLast(1).forEach { frame ->
                assertIs<AwaitingTransfer>(receiver.receive(frame.toString().encodeToByteArray()).single())
            }
            val final = receiver.receive(history.last().toString().encodeToByteArray())
            assertEquals(1, final.size)
            val committed = assertIs<ProjectionCommitted>(final.single())
            assertEquals(0, committed.state.windowStart)
            assertEquals(4, committed.state.windowEndExclusive)
            assertEquals(4, committed.state.messages.size)
            val expectedHistory = history.subList(1, history.lastIndex).flatMap { source ->
                (ReliabilityContractDecoder.decode(source.toString()).frame as TaskSnapshotPageFrame)
                    .messages
            }
            assertEquals(expectedHistory, committed.state.messages.take(2))
        }
    }

    @Test
    fun `aggregate and transaction failures produce typed failure with zero commit and zero ACK`() {
        val root = Files.createTempDirectory("p1b-receiver-budget-")
        val store = ReferenceProjectionStore()
        val receiver = ReliabilityReceiver(
            tempRoot = root,
            store = store,
            snapshotLogicalLimitBytes = 1,
        )
        try {
            val server = reliabilityFixture.getValue("serverFrames").jsonObject
            val begin = server.getValue("snapshotTransfer").jsonArray.first()
            assertIs<AwaitingTransfer>(receiver.receive(begin.toString().encodeToByteArray()).single())
            val page = Base64.getDecoder().decode(
                byteFixture.getValue("snapshotPages").jsonArray.first().jsonObject
                    .getValue("jsonBase64").jsonPrimitive.content,
            )
            val failed = receiver.receive(page)
            assertEquals(1, failed.size)
            assertEquals(ReceiverFailureCode.SNAPSHOT_FAILED, assertIs<ReceiverFailure>(failed.single()).code)
            assertEquals(0, store.transactionCount)
            assertEquals(0, store.commitCount)
            assertEquals(0, failed.filterIsInstance<AckReady>().size)
        } finally {
            receiver.close()
            assertRootEmpty(root)
            Files.deleteIfExists(root)
        }

        val chunkRoot = Files.createTempDirectory("p1b-receiver-chunk-budget-")
        val chunkStore = ReferenceProjectionStore()
        val chunkReceiver = ReliabilityReceiver(
            tempRoot = chunkRoot,
            store = chunkStore,
            snapshotLogicalLimitBytes = 1,
        )
        try {
            val server = reliabilityFixture.getValue("serverFrames").jsonObject
            val begin = server.getValue("snapshotTransfer").jsonArray.first()
            assertIs<AwaitingTransfer>(
                chunkReceiver.receive(begin.toString().encodeToByteArray()).single(),
            )
            val chunkFrames = pageChunkPhysicalFrames(largeSnapshotPageBytes())
            chunkFrames.dropLast(1).forEach { raw ->
                assertIs<AwaitingTransfer>(chunkReceiver.receive(raw).single())
            }
            val failed = chunkReceiver.receive(chunkFrames.last())
            assertEquals(
                ReceiverFailureCode.SNAPSHOT_FAILED,
                assertIs<ReceiverFailure>(failed.single()).code,
            )
            assertEquals(0, chunkStore.transactionCount)
            assertEquals(0, chunkStore.commitCount)
            assertEquals(0, failed.filterIsInstance<AckReady>().size)
            assertRootEmpty(chunkRoot)
        } finally {
            chunkReceiver.close()
            assertRootEmpty(chunkRoot)
            Files.deleteIfExists(chunkRoot)
        }

        val overflowRoot = Files.createTempDirectory("p1b-receiver-overflow-")
        val overflowStore = ReferenceProjectionStore().apply {
            seed(
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    throughSequence = 8,
                    snapshotVersion = 6,
                ),
            )
        }
        val oldOverflowProjection = overflowStore.state(TASK_ID)
        val overflowReceiver = ReliabilityReceiver(
            tempRoot = overflowRoot,
            store = overflowStore,
            snapshotLogicalLimitBytes = Long.MAX_VALUE,
        )
        try {
            val server = reliabilityFixture.getValue("serverFrames").jsonObject
            val begin = server.getValue("snapshotTransfer").jsonArray.first()
                .toString().encodeToByteArray()
            assertIs<AwaitingTransfer>(overflowReceiver.receive(begin).single())
            val page = Base64.getDecoder().decode(
                byteFixture.getValue("snapshotPages").jsonArray.first().jsonObject
                    .getValue("jsonBase64").jsonPrimitive.content,
            )
            val failed = overflowReceiver.receive(page)
            val failure = assertIs<ReceiverFailure>(failed.single())
            assertEquals(ReceiverFailureCode.SNAPSHOT_FAILED, failure.code)
            assertTrue(failure.message.contains("overflow"))
            assertEquals(0, overflowStore.transactionCount)
            assertEquals(0, overflowStore.commitCount)
            assertEquals(0, failed.filterIsInstance<AckReady>().size)
            assertSame(oldOverflowProjection, overflowStore.state(TASK_ID))
            assertIs<AwaitingTransfer>(overflowReceiver.receive(begin).single())
        } finally {
            overflowReceiver.close()
            assertRootEmpty(overflowRoot)
            Files.deleteIfExists(overflowRoot)
        }

        val historyStore = ReferenceProjectionStore().apply {
            seed(
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    throughSequence = 9,
                    snapshotVersion = 7,
                    windowStart = 1,
                    windowEndExclusive = 1,
                ),
            )
        }
        withReceiver(historyStore) { historyReceiver, _, _ ->
            val historyFrames = reliabilityFixture.getValue("serverFrames").jsonObject
                .getValue("historyTransfer").jsonArray
            val firstPage = historyFrames[1].toString().encodeToByteArray()
            historyReceiver.registerHistoryRequest(
                "history-request-fixture",
                TASK_ID,
                7,
                firstPage.size.toLong() - 1,
            )
            assertIs<AwaitingTransfer>(
                historyReceiver.receive(historyFrames.first().toString().encodeToByteArray()).single(),
            )
            val failed = historyReceiver.receive(firstPage)
            assertEquals(
                ReceiverFailureCode.SNAPSHOT_FAILED,
                assertIs<ReceiverFailure>(failed.single()).code,
            )
            assertEquals(0, historyStore.transactionCount)
            assertEquals(0, failed.filterIsInstance<AckReady>().size)
        }

        val failingStore = ReferenceProjectionStore().apply { failFinalCommit = true }
        withReceiver(failingStore) { failingReceiver, _, _ ->
            val source = reliabilityFixture.getValue("serverFrames").jsonObject
                .getValue("directEvent").jsonObject
            val eventOne = JsonObject(source + ("sequence" to JsonPrimitive(1)))
            val failed = failingReceiver.receive(eventOne.toString().encodeToByteArray())
            assertEquals(1, failed.size)
            assertEquals(
                ReceiverFailureCode.PROJECTION_FAILED,
                assertIs<ReceiverFailure>(failed.single()).code,
            )
            assertEquals(0, failingStore.commitCount)
            assertNull(failingStore.state(TASK_ID))
            assertEquals(0, failed.filterIsInstance<AckReady>().size)
        }
    }

    @Test
    fun `close clears incomplete chunk and snapshot and is terminal`() {
        val root = Files.createTempDirectory("p1b-receiver-close-")
        val receiver = ReliabilityReceiver(root, ReferenceProjectionStore())
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        receiver.receive(
            server.getValue("snapshotTransfer").jsonArray.first().toString().encodeToByteArray(),
        )
        receiver.receive(
            server.getValue("snapshotPageChunk").jsonArray.first().toString().encodeToByteArray(),
        )
        assertTrue(Files.list(root).use { it.count() } == 1L)
        receiver.close()
        receiver.close()
        assertRootEmpty(root)
        assertEquals(
            ReceiverFailureCode.RECEIVER_CLOSED,
            assertIs<ReceiverFailure>(receiver.receive("{}".encodeToByteArray()).single()).code,
        )
        Files.deleteIfExists(root)
    }

    private fun pageChunkPhysicalFrames(logical: ByteArray): List<ByteArray> {
        val server = reliabilityFixture.getValue("serverFrames").jsonObject
        val chunk = server.getValue("snapshotPageChunk").jsonArray
        val firstBytes = reliabilityFixture.getValue("generatedCases").jsonObject
            .getValue("snapshotPageChunk").jsonObject.getValue("firstChunkBytes").jsonPrimitive.int
        val start = chunk.first().toString().encodeToByteArray()
        val data = listOf(
            logical.copyOfRange(0, firstBytes),
            logical.copyOfRange(firstBytes, logical.size),
        ).mapIndexed { index, bytes ->
            JsonObject(
                mapOf(
                    "protocolVersion" to JsonPrimitive(1),
                    "kind" to JsonPrimitive("transport.chunk.data"),
                    "transferId" to JsonPrimitive("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                    "chunkIndex" to JsonPrimitive(index),
                    "data" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
                ),
            ).toString().encodeToByteArray()
        }
        return listOf(start) + data + chunk.last().toString().encodeToByteArray()
    }

    private fun largeSnapshotPageBytes(): ByteArray {
        val recipe = byteFixture.getValue("physicalFrameRecipes").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "snapshotPagePhysicalPlusOne" }
        val prefix = recipe.getValue("jsonPrefix").jsonPrimitive.content
            .toByteArray(StandardCharsets.UTF_8)
        val suffix = recipe.getValue("jsonSuffix").jsonPrimitive.content
            .toByteArray(StandardCharsets.UTF_8)
        val payloadBytes = recipe.getValue("targetBytes").jsonPrimitive.int - prefix.size - suffix.size
        return prefix + patternPayload(
            payloadBytes,
            recipe.getValue("utf8Pattern").jsonPrimitive.content,
            recipe.getValue("asciiRemainderByte").jsonPrimitive.content,
        ) + suffix
    }

    private fun patternPayload(targetBytes: Int, pattern: String, remainderByte: String): ByteArray {
        val patternBytes = pattern.toByteArray(StandardCharsets.UTF_8)
        return (
            pattern.repeat(targetBytes / patternBytes.size) +
                remainderByte.repeat(targetBytes % patternBytes.size)
        ).toByteArray(StandardCharsets.UTF_8).also { assertEquals(targetBytes, it.size) }
    }

    private fun orderedSha(vararg pages: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        pages.forEach { raw ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(raw.size.toLong()).array())
            digest.update(raw)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun withReceiver(
        store: ReferenceProjectionStore = ReferenceProjectionStore(),
        block: (ReliabilityReceiver, ReferenceProjectionStore, Path) -> Unit,
    ) {
        val root = Files.createTempDirectory("p1b-receiver-")
        val receiver = ReliabilityReceiver(root, store)
        try {
            block(receiver, store, root)
        } finally {
            receiver.close()
            assertRootEmpty(root)
            Files.deleteIfExists(root)
        }
    }

    private fun assertRootEmpty(root: Path) {
        Files.list(root).use { assertEquals(0, it.count()) }
    }

    private fun fixture(path: String): JsonObject {
        val text = checkNotNull(javaClass.getResource(path)).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val STREAM_ID = "33333333-3333-4333-8333-333333333333"
    }
}
