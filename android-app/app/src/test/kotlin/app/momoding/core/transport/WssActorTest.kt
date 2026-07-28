package app.momoding.core.transport

import app.momoding.wire.DurableTaskProjection
import app.momoding.wire.ProjectionTransaction
import app.momoding.wire.ProjectionTransactionStore
import app.momoding.wire.ReceiverAction
import app.momoding.wire.ReceiverFailure
import app.momoding.wire.ReceiverFailureCode
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.ReliabilityReceiver
import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import app.momoding.core.data.CommandDurabilityJournal
import app.momoding.core.data.OutboundCommandRecord
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WssActorTest {
    @Test
    fun helloGateQueuesRequestThenCompletesOnlyMatchingResponse() = runTest {
        val fixture = Fixture(this)
        try {
            val started = async { fixture.actor.start(secret()) }
            runCurrent()
            val connection = fixture.connector.connections.single()
            connection.open()
            runCurrent()
            val hello = connection.socket.sent.single()
            assertTrue(hello.contains("\"kind\":\"hello\""))

            val pending = async { fixture.actor.request(taskListRequest(REQUEST_ID)) }
            runCurrent()
            assertEquals(1, connection.socket.sent.size)

            fixture.acceptHello(connection)
            assertTrue(started.isCompleted)
            assertEquals(2, connection.socket.sent.size)
            connection.text(successResponse(REQUEST_ID))
            runCurrent()

            assertEquals(REQUEST_ID, (pending.await().frame as app.momoding.wire.CommandResponseFrame).requestId)
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun sameStreamReplayKeepsTaskHistoricalUntilExactReplayComplete() = runTest {
        val projectionStore = MemoryProjectionStore(
            DurableTaskProjection(
                taskId = TASK_ID,
                streamId = STREAM_ID,
                throughSequence = 1,
            ),
        )
        val fixture = Fixture(
            scope = this,
            projectionStore = projectionStore,
            resumeCursors = listOf(DurableResumeCursor(TASK_ID, STREAM_ID, 1)),
        )
        try {
            val started = async { fixture.actor.start(secret()) }
            runCurrent()
            val connection = fixture.connector.connections.single()
            fixture.acceptHello(connection)
            started.await()

            assertEquals(WssConnectionPhase.REPLAYING, fixture.actor.status.value.phase)
            assertEquals(0, fixture.actor.status.value.replayProgress.getValue(TASK_ID).replayedEventCount)

            connection.text(piEvent(sequence = 2, streamId = STREAM_ID))
            runCurrent()
            assertEquals(WssConnectionPhase.REPLAYING, fixture.actor.status.value.phase)
            assertEquals(1, fixture.actor.status.value.replayProgress.getValue(TASK_ID).replayedEventCount)

            connection.text(replayComplete(through = 2, liveFrom = 3))
            runCurrent()
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
            assertTrue(fixture.actor.status.value.replayProgress.isEmpty())
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun replayCompleteReleasesOnlyItsTaskWhileAnotherResumeRemainsPending() = runTest {
        val fixture = Fixture(
            scope = this,
            projectionStore = MemoryProjectionStore(
                DurableTaskProjection(taskId = TASK_ID, streamId = STREAM_ID, throughSequence = 1),
            ),
            resumeCursors = listOf(
                DurableResumeCursor(TASK_ID, STREAM_ID, 1),
                DurableResumeCursor(OTHER_TASK_ID, OTHER_STREAM_ID, 2),
            ),
        )
        try {
            val started = async { fixture.actor.start(secret()) }
            runCurrent()
            val connection = fixture.connector.connections.single()
            fixture.acceptHello(connection)
            started.await()

            connection.text(replayComplete(through = 1, liveFrom = 2))
            runCurrent()

            assertEquals(WssConnectionPhase.REPLAYING, fixture.actor.status.value.phase)
            assertEquals(setOf(OTHER_TASK_ID), fixture.actor.status.value.replayProgress.keys)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun resyncSnapshotReleasesOnlyItsTaskWhileAnotherResumeRemainsPending() = runTest {
        val fixture = Fixture(
            scope = this,
            projectionStore = MemoryProjectionStore(
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    throughSequence = 1,
                    snapshotVersion = 1,
                ),
            ),
            resumeCursors = listOf(
                DurableResumeCursor(TASK_ID, STREAM_ID, 1),
                DurableResumeCursor(OTHER_TASK_ID, OTHER_STREAM_ID, 2),
            ),
        )
        try {
            val started = async { fixture.actor.start(secret()) }
            runCurrent()
            val connection = fixture.connector.connections.single()
            fixture.acceptHello(connection)
            started.await()

            connection.text(resyncRequired(snapshotVersion = 2))
            runCurrent()
            assertTrue(TASK_ID in fixture.actor.status.value.replayProgress)
            connection.text(taskSnapshot(snapshotVersion = 2, highWatermark = 4))
            runCurrent()

            assertEquals(WssConnectionPhase.REPLAYING, fixture.actor.status.value.phase)
            assertEquals(setOf(OTHER_TASK_ID), fixture.actor.status.value.replayProgress.keys)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun oldGenerationAndLateTombstonedResponsesAreDropped() = runTest {
        val fixture = Fixture(this, jitter = { 0.0 })
        try {
            val first = fixture.startOnline()
            val pending = async { fixture.actor.request(taskListRequest(REQUEST_ID)) }
            runCurrent()
            first.failure(fatalTls = false)
            runCurrent()
            val second = fixture.connector.connections.last()

            first.text(successResponse(REQUEST_ID))
            runCurrent()
            assertFalse(pending.isCompleted)

            fixture.acceptHello(second)
            second.text(successResponse(REQUEST_ID))
            runCurrent()
            pending.await()
            second.text(successResponse(REQUEST_ID))
            runCurrent()

            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
            assertEquals(2, fixture.connector.connections.size)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun receiverFailureDiagnosticSurvivesZeroDelayAndReplacesOnNextGeneration() = runTest {
        val socketEvidence = mutableListOf<String>()
        val fixture = Fixture(this, jitter = { 0.0 }, socketEvents = socketEvidence)
        val sensitivePath = "/private/synthetic/receiver-secret.txt"
        val sensitiveToken = "synthetic-receiver-token"
        val sensitiveUri = "content://synthetic.receiver/private"
        val decodeFrame =
            """{"protocolVersion":1,"kind":"synthetic.invalid","path":"$sensitivePath","token":"$sensitiveToken","uri":"$sensitiveUri"}"""
        try {
            val first = fixture.startOnline()
            assertEquals(1L, fixture.actor.status.value.connectionGeneration)
            assertEquals(null, fixture.actor.status.value.lastReceiverFailure)

            val decodeMessage = runCatching {
                ReliabilityContractDecoder.decode(decodeFrame.toByteArray(Charsets.UTF_8))
            }.exceptionOrNull()?.message ?: "Decode failure"
            val capturedConsole = captureConsole {
                first.text(decodeFrame)
                runCurrent()
            }

            val afterDecode = fixture.actor.status.value
            assertEquals(WssConnectionPhase.CONNECTING, afterDecode.phase)
            assertEquals(2L, afterDecode.connectionGeneration)
            assertEquals(
                WssReceiverFailureDiagnostic(
                    code = ReceiverFailureCode.DECODE_FAILED,
                    messageSha256 = sha256(decodeMessage),
                    failureGeneration = 1,
                    failureOrdinal = 1,
                ),
                afterDecode.lastReceiverFailure,
            )
            listOf(sensitivePath, sensitiveToken, sensitiveUri).forEach { raw ->
                assertFalse(afterDecode.toString().contains(raw))
                assertFalse(afterDecode.reason.orEmpty().contains(raw))
                assertFalse(socketEvidence.joinToString().contains(raw))
                assertFalse(capturedConsole.contains(raw))
                assertFalse(fixture.privateEvidenceText().contains(raw))
            }

            val second = fixture.connector.connections.last()
            fixture.acceptHello(second)
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
            assertEquals(afterDecode.lastReceiverFailure, fixture.actor.status.value.lastReceiverFailure)

            second.text(piEvent())
            runCurrent()

            val afterProjection = fixture.actor.status.value
            assertEquals(WssConnectionPhase.CONNECTING, afterProjection.phase)
            assertEquals(3L, afterProjection.connectionGeneration)
            assertEquals(
                WssReceiverFailureDiagnostic(
                    code = ReceiverFailureCode.PROJECTION_FAILED,
                    messageSha256 = sha256("Projection transaction was not expected"),
                    failureGeneration = 2,
                    failureOrdinal = 2,
                ),
                afterProjection.lastReceiverFailure,
            )
        } finally {
            fixture.stop()
        }
        assertEquals(WssConnectionPhase.STOPPED, fixture.actor.status.value.phase)
        assertEquals(null, fixture.actor.status.value.lastReceiverFailure)
    }

    @Test
    fun receiverFailureKeepsLegacyBackoffDelayCloseAndReceiverReplacement() = runTest {
        val socketEvidence = mutableListOf<String>()
        val fixture = Fixture(this, jitter = { 0.5 }, socketEvents = socketEvidence)
        val sensitivePath = "/private/synthetic/backoff-secret.txt"
        val sensitiveToken = "synthetic-backoff-token"
        val sensitiveUri = "content://synthetic.receiver/backoff"
        try {
            val first = fixture.startOnline()
            first.text(
                piEvent().replace(
                    "\"event\":{\"type\":\"agent_start\"}",
                    "\"event\":{\"type\":\"agent_start\",\"path\":\"$sensitivePath\"," +
                        "\"token\":\"$sensitiveToken\",\"uri\":\"$sensitiveUri\"}",
                ),
            )
            runCurrent()

            val backoff = fixture.actor.status.value
            val diagnostic = requireNotNull(backoff.lastReceiverFailure)
            assertEquals(ReceiverFailureCode.PROJECTION_FAILED, diagnostic.code)
            assertEquals(WssConnectionPhase.BACKING_OFF, backoff.phase)
            assertEquals(1, backoff.reconnectAttempt)
            assertEquals(500L, backoff.nextDelayMillis)
            assertEquals("Reliability receiver failure", backoff.reason)
            assertEquals(1L, diagnostic.failureGeneration)
            assertEquals(1L, diagnostic.failureOrdinal)
            assertTrue(first.socket.cancelled)
            assertEquals(1, first.socket.closeCalls.size)
            assertEquals("", first.socket.closeCalls.single().second)
            assertReceiverClosed(fixture.receiverInstances.single())
            assertEquals(1, fixture.connector.connections.size)
            assertEquals(1, fixture.receiverInstances.size)
            listOf(sensitivePath, sensitiveToken, sensitiveUri).forEach { raw ->
                assertFalse(backoff.toString().contains(raw))
                assertFalse(socketEvidence.joinToString().contains(raw))
                assertFalse(fixture.privateEvidenceText().contains(raw))
            }

            advanceTimeBy(499)
            runCurrent()
            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertEquals(1, fixture.connector.connections.size)
            assertEquals(1, fixture.receiverInstances.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
            assertEquals(2L, fixture.actor.status.value.connectionGeneration)
            assertEquals(diagnostic, fixture.actor.status.value.lastReceiverFailure)
            assertEquals(2, fixture.connector.connections.size)
            assertEquals(2, fixture.receiverInstances.size)
            assertTrue(fixture.connector.connections.last() !== first)
            assertTrue(fixture.receiverInstances[1] !== fixture.receiverInstances[0])
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun nonReceiverRecoveryNeverCreatesOrOverwritesReceiverDiagnostic() = runTest {
        val withoutReceiverFailure = Fixture(this)
        try {
            val connection = withoutReceiverFailure.startOnline()
            connection.binary(1)
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, withoutReceiverFailure.actor.status.value.phase)
            assertEquals(null, withoutReceiverFailure.actor.status.value.lastReceiverFailure)
            assertEquals(
                "Binary WebSocket frames are forbidden",
                withoutReceiverFailure.actor.status.value.reason,
            )
        } finally {
            withoutReceiverFailure.stop()
        }

        val withReceiverFailure = Fixture(this)
        try {
            val first = withReceiverFailure.startOnline()
            first.text("""{"protocolVersion":1,"kind":"synthetic.invalid"}""")
            runCurrent()
            val receiverDiagnostic =
                requireNotNull(withReceiverFailure.actor.status.value.lastReceiverFailure)
            assertEquals("Reliability receiver failure", withReceiverFailure.actor.status.value.reason)

            withReceiverFailure.actor.retryNow()
            runCurrent()
            val second = withReceiverFailure.connector.connections.last()
            withReceiverFailure.acceptHello(second)
            second.binary(1)
            runCurrent()

            val afterBinary = withReceiverFailure.actor.status.value
            assertEquals(WssConnectionPhase.BACKING_OFF, afterBinary.phase)
            assertEquals("Binary WebSocket frames are forbidden", afterBinary.reason)
            assertEquals(receiverDiagnostic, afterBinary.lastReceiverFailure)
        } finally {
            withReceiverFailure.stop()
        }
    }

    @Test
    fun nonReceiverFailureMatrixNeverCreatesDiagnostic() = runTest {
        RecoverableNonReceiverFailure.entries.forEach { failure ->
            assertRecoverableNonReceiverFailure(failure, seedSticky = false)
        }
        assertFatalTlsFailure(seedSticky = false)
        assertRetryExhaustion(seedSticky = false)
    }

    @Test
    fun nonReceiverFailureMatrixNeverOverwritesStickyDiagnostic() = runTest {
        RecoverableNonReceiverFailure.entries.forEach { failure ->
            assertRecoverableNonReceiverFailure(failure, seedSticky = true)
        }
        assertFatalTlsFailure(seedSticky = true)
        assertRetryExhaustion(seedSticky = true)
    }

    @Test
    fun mutatingIntentAndTerminalResponseAreDurableBeforeWireAndCaller() = runTest {
        val events = mutableListOf<String>()
        val journal = RecordingJournal(events)
        val fixture = Fixture(this, journal = journal, socketEvents = events)
        try {
            val connection = fixture.startOnline()
            val request = OutboundWireRequest(
                requestId = REQUEST_ID,
                kind = "task.create",
                canonicalPayload =
                    """{"protocolVersion":1,"kind":"task.create","requestId":"$REQUEST_ID","commandId":"$COMMAND_ID","draftId":"draft-1"}""",
                mutating = true,
                commandId = COMMAND_ID,
            )
            val pending = async { fixture.actor.request(request) }
            pending.invokeOnCompletion { events += "caller.completed" }
            runCurrent()

            assertTrue(events.indexOf("journal.accepted") < events.indexOf("socket.send.task.create"))
            connection.text(successResponse(REQUEST_ID, "{\"taskId\":\"$TASK_ID\"}"))
            runCurrent()
            pending.await()

            assertTrue(events.indexOf("journal.terminal") < events.indexOf("caller.completed"))
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun binaryOversizeDuplicateJsonAndUnknownResponseCloseAndRecover() = runTest {
        suspend fun runRecoverable(inject: (FakeConnection) -> Unit) {
            val fixture = Fixture(this)
            try {
                val connection = fixture.startOnline()
                inject(connection)
                runCurrent()
                assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
                assertTrue(connection.socket.cancelled)
                fixture.actor.retryNow()
                runCurrent()
                assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
                assertEquals(2, fixture.connector.connections.size)
            } finally {
                fixture.stop()
            }
        }

        runRecoverable { it.binary(1) }
        runRecoverable { it.text("x".repeat(app.momoding.wire.P1aProtocol.MAX_FRAME_BYTES + 1)) }
        runRecoverable { it.text(successResponse(OTHER_REQUEST_ID)) }

        val burstFixture = Fixture(this)
        try {
            val connection = burstFixture.startOnline()
            connection.binary(1)
            connection.binary(1)
            runCurrent()
            assertEquals(WssConnectionPhase.BACKING_OFF, burstFixture.actor.status.value.phase)
            assertEquals(1, burstFixture.actor.status.value.reconnectAttempt)
        } finally {
            burstFixture.stop()
        }

        val duplicateFixture = Fixture(this)
        try {
            val started = supervisedAsync { duplicateFixture.actor.start(secret()) }
            runCurrent()
            val connection = duplicateFixture.connector.connections.single()
            connection.open()
            runCurrent()
            val helloId = requestIdFrom(connection.socket.sent.single())
            connection.text(
                helloAccepted(helloId).replace(
                    "\"kind\":\"hello.accepted\"",
                    "\"kind\":\"hello.accepted\",\"kind\":\"hello.accepted\"",
                ),
            )
            runCurrent()
            assertEquals(WssConnectionPhase.BACKING_OFF, duplicateFixture.actor.status.value.phase)
            assertFalse(started.isCompleted)
        } finally {
            duplicateFixture.stop()
        }
    }

    @Test
    fun preHelloUnknownFramesRecoverButCredentialAndVersionErrorsStayFatal() = runTest {
        suspend fun runRecoverable(frame: (String) -> String) {
            val fixture = Fixture(this)
            try {
                val started = supervisedAsync { fixture.actor.start(secret()) }
                runCurrent()
                val first = fixture.connector.connections.single()
                first.open()
                runCurrent()
                val helloRequestId = requestIdFrom(first.socket.sent.single())

                first.text(frame(helloRequestId))
                runCurrent()
                assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
                assertTrue(first.socket.cancelled)
                assertFalse(started.isCompleted)

                fixture.actor.retryNow()
                runCurrent()
                val second = fixture.connector.connections.last()
                fixture.acceptHello(second)
                started.await()
                assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
            } finally {
                fixture.stop()
            }
        }

        runRecoverable { successResponse(OTHER_REQUEST_ID) }
        runRecoverable { helloAccepted(OTHER_REQUEST_ID) }

        val projectionStore = CountingProjectionStore()
        val eventFixture = Fixture(this, projectionStore = projectionStore)
        try {
            val started = supervisedAsync { eventFixture.actor.start(secret()) }
            runCurrent()
            val connection = eventFixture.connector.connections.single()
            connection.open()
            runCurrent()
            connection.text(piEvent())
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, eventFixture.actor.status.value.phase)
            assertEquals(0, projectionStore.transactionCalls)
            assertFalse(started.isCompleted)
        } finally {
            eventFixture.stop()
        }

        listOf("UNAUTHORIZED", "PROTOCOL_MISMATCH").forEach { code ->
            val fixture = Fixture(this)
            try {
                val started = supervisedAsync { fixture.actor.start(secret()) }
                runCurrent()
                val connection = fixture.connector.connections.single()
                connection.open()
                runCurrent()
                connection.text(wireError(code))
                runCurrent()

                assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
                expectDeferredFailure(started)
            } finally {
                fixture.stop()
            }
        }
    }

    @Test
    fun helloTimesOutAtFiveSecondsAndFatalTlsNeverReconnects() = runTest {
        val timeoutFixture = Fixture(this, jitter = { 0.5 })
        try {
            val started = supervisedAsync { timeoutFixture.actor.start(secret()) }
            runCurrent()
            timeoutFixture.connector.connections.single().open()
            runCurrent()
            advanceTimeBy(WssActor.HELLO_TIMEOUT_MILLIS)
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, timeoutFixture.actor.status.value.phase)
            assertEquals(1, timeoutFixture.actor.status.value.reconnectAttempt)
            timeoutFixture.stop()
            expectDeferredFailure(started)
        } finally {
            if (timeoutFixture.actor.status.value.phase != WssConnectionPhase.STOPPED) timeoutFixture.stop()
        }

        val tlsFixture = Fixture(this)
        try {
            val started = supervisedAsync { tlsFixture.actor.start(secret()) }
            runCurrent()
            tlsFixture.connector.connections.single().failure(fatalTls = true)
            runCurrent()

            assertEquals(WssConnectionPhase.FATAL, tlsFixture.actor.status.value.phase)
            assertEquals(1, tlsFixture.connector.connections.size)
            expectDeferredFailure(started)
        } finally {
            tlsFixture.stop()
        }
    }

    @Test
    fun eightAutomaticRetriesThenOfflineAndExplicitRetryResetsBudget() = runTest {
        val fixture = Fixture(this, jitter = { 0.0 })
        try {
            val started = supervisedAsync { fixture.actor.start(secret()) }
            runCurrent()
            repeat(9) {
                fixture.connector.connections.last().failure(fatalTls = false)
                runCurrent()
            }

            assertEquals(WssConnectionPhase.OFFLINE, fixture.actor.status.value.phase)
            assertEquals(9, fixture.connector.connections.size)
            expectDeferredFailure(started)

            fixture.actor.retryNow()
            runCurrent()
            assertEquals(10, fixture.connector.connections.size)
            assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun boundedMailboxOverflowCancelsSocketAndEntersRecovery() = runTest {
        val fixture = Fixture(this)
        try {
            val connection = fixture.startOnline()
            val pending = async { fixture.actor.request(taskListRequest(REQUEST_ID)) }
            runCurrent()
            val response = successResponse(REQUEST_ID)
            connection.text(response)
            runCurrent()
            pending.await()

            repeat(WssActor.MAILBOX_CAPACITY + 2) { connection.text(response) }
            runCurrent()

            assertTrue(connection.socket.cancelled)
            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun concurrentAndRepeatedStopCallsAreIdempotent() = runTest {
        val fixture = Fixture(this)
        fixture.startOnline()

        val first = async { fixture.actor.stopAndJoin() }
        val second = async { fixture.actor.stopAndJoin() }
        runCurrent()
        first.await()
        second.await()
        fixture.actor.stopAndJoin()

        assertEquals(WssConnectionPhase.STOPPED, fixture.actor.status.value.phase)
    }

    private class Fixture(
        private val scope: TestScope,
        jitter: () -> Double = { 0.5 },
        journal: CommandDurabilityJournal? = null,
        socketEvents: MutableList<String>? = null,
        projectionStore: ProjectionTransactionStore = NoopProjectionStore,
        resumeCursors: List<DurableResumeCursor> = emptyList(),
        receiverReceive: (ReliabilityReceiver, ByteArray) -> List<ReceiverAction> =
            ReliabilityReceiver::receive,
    ) {
        val connector = FakeConnector(socketEvents)
        private val tempRoots = mutableListOf<Path>()
        val receiverInstances = mutableListOf<ReliabilityReceiver>()
        val actor = WssActor(
            scope = scope,
            connectorFactory = WssConnectorFactory { connector },
            receiverFactory = ReliabilityReceiverFactory {
                ReliabilityReceiver(
                    Files.createTempDirectory("wss-actor-test-").also(tempRoots::add),
                    projectionStore,
                ).also(receiverInstances::add)
            },
            resumeCursorSource = ResumeCursorSource { resumeCursors },
            commandJournal = journal,
            reconnectPolicy = FullJitterReconnectPolicy(jitter),
            receiverReceive = receiverReceive,
        )

        suspend fun startOnline(): FakeConnection {
            val started = scope.async { actor.start(secret()) }
            scope.runCurrent()
            val connection = connector.connections.last()
            acceptHello(connection)
            started.await()
            return connection
        }

        fun acceptHello(connection: FakeConnection) {
            if (connection.socket.sent.isEmpty()) {
                connection.open()
                scope.runCurrent()
            }
            connection.text(helloAccepted(requestIdFrom(connection.socket.sent.first())))
            scope.runCurrent()
        }

        fun privateEvidenceText(): String = tempRoots
            .asSequence()
            .flatMap { root ->
                root.toFile().walkTopDown().filter { file -> file.isFile }.asSequence()
            }
            .joinToString(separator = "\n") { file ->
                file.readBytes().toString(Charsets.UTF_8)
            }

        suspend fun stop() {
            actor.stopAndJoin()
            tempRoots.forEach { it.toFile().deleteRecursively() }
        }
    }

    private suspend fun TestScope.seedReceiverDiagnostic(
        fixture: Fixture,
    ): WssReceiverFailureDiagnostic {
        val first = fixture.startOnline()
        first.text("""{"protocolVersion":1,"kind":"synthetic.invalid"}""")
        runCurrent()
        val sticky = requireNotNull(fixture.actor.status.value.lastReceiverFailure)
        fixture.actor.retryNow()
        runCurrent()
        fixture.acceptHello(fixture.connector.connections.last())
        assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
        assertEquals(sticky, fixture.actor.status.value.lastReceiverFailure)
        return sticky
    }

    private suspend fun TestScope.assertRecoverableNonReceiverFailure(
        failure: RecoverableNonReceiverFailure,
        seedSticky: Boolean,
    ) {
        var throwReceiver = false
        val fixture = Fixture(
            scope = this,
            jitter = { 0.5 },
            projectionStore = if (failure == RecoverableNonReceiverFailure.ACK_SEND) {
                MemoryProjectionStore(
                    DurableTaskProjection(TASK_ID, STREAM_ID, throughSequence = 0),
                )
            } else {
                NoopProjectionStore
            },
            receiverReceive = { receiver, bytes ->
                if (throwReceiver) error("synthetic receiver throw")
                receiver.receive(bytes)
            },
        )
        try {
            val sticky = if (seedSticky) {
                seedReceiverDiagnostic(fixture)
            } else {
                fixture.startOnline()
                null
            }
            val connection = fixture.connector.connections.last()
            val receiver = fixture.receiverInstances.last()
            val connectionCount = fixture.connector.connections.size
            val receiverCount = fixture.receiverInstances.size
            val pending = async { fixture.actor.request(taskListRequest(REQUEST_ID)) }
            runCurrent()
            assertFalse(pending.isCompleted)

            when (failure) {
                RecoverableNonReceiverFailure.RECEIVER_THROW -> {
                    throwReceiver = true
                    connection.text(piEvent())
                }
                RecoverableNonReceiverFailure.ACK_SEND -> {
                    connection.socket.failNextSendKind = "pi.event.ack"
                    connection.text(piEvent())
                }
                RecoverableNonReceiverFailure.BINARY_PROTOCOL -> connection.binary(1)
            }
            runCurrent()
            throwReceiver = false

            val backoff = fixture.actor.status.value
            assertEquals(WssConnectionPhase.BACKING_OFF, backoff.phase)
            assertEquals(1, backoff.reconnectAttempt)
            assertEquals(500L, backoff.nextDelayMillis)
            assertEquals(failure.reason, backoff.reason)
            assertEquals(sticky, backoff.lastReceiverFailure)
            assertTrue(connection.socket.cancelled)
            assertEquals(1, connection.socket.closeCalls.size)
            assertEquals("", connection.socket.closeCalls.single().second)
            assertReceiverClosed(receiver)
            assertEquals(connectionCount, fixture.connector.connections.size)
            assertEquals(receiverCount, fixture.receiverInstances.size)
            assertFalse(pending.isCompleted)

            advanceTimeBy(499)
            runCurrent()
            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertEquals(connectionCount, fixture.connector.connections.size)
            assertEquals(receiverCount, fixture.receiverInstances.size)
            assertFalse(pending.isCompleted)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
            assertEquals(2L + if (seedSticky) 1L else 0L,
                fixture.actor.status.value.connectionGeneration)
            assertEquals(sticky, fixture.actor.status.value.lastReceiverFailure)
            assertEquals(connectionCount + 1, fixture.connector.connections.size)
            assertEquals(receiverCount + 1, fixture.receiverInstances.size)
            assertTrue(fixture.receiverInstances.last() !== receiver)
            assertFalse(pending.isCompleted)

            val replacement = fixture.connector.connections.last()
            fixture.acceptHello(replacement)
            assertTrue(replacement.socket.sent.any { it.contains("\"kind\":\"task.list\"") })
            assertFalse(pending.isCompleted)
            replacement.text(successResponse(REQUEST_ID))
            runCurrent()
            pending.await()
        } finally {
            fixture.stop()
        }
    }

    private suspend fun TestScope.assertFatalTlsFailure(seedSticky: Boolean) {
        val fixture = Fixture(this, jitter = { 0.5 })
        try {
            val sticky = if (seedSticky) {
                seedReceiverDiagnostic(fixture)
            } else {
                fixture.startOnline()
                null
            }
            val connection = fixture.connector.connections.last()
            val receiver = fixture.receiverInstances.last()
            val connectionCount = fixture.connector.connections.size
            val receiverCount = fixture.receiverInstances.size
            val pending = CoroutineScope(coroutineContext + SupervisorJob()).async {
                fixture.actor.request(taskListRequest(REQUEST_ID))
            }
            runCurrent()
            assertFalse(pending.isCompleted)

            connection.failure(fatalTls = true)
            runCurrent()

            val fatal = fixture.actor.status.value
            assertEquals(WssConnectionPhase.FATAL, fatal.phase)
            assertEquals(0, fatal.reconnectAttempt)
            assertEquals(null, fatal.nextDelayMillis)
            assertEquals("TLS validation failed", fatal.reason)
            assertEquals(WssTerminalCause.TLS_VALIDATION, fatal.terminalCause)
            assertEquals(sticky, fatal.lastReceiverFailure)
            assertTrue(connection.socket.cancelled)
            assertEquals(1, connection.socket.closeCalls.size)
            assertEquals("", connection.socket.closeCalls.single().second)
            assertReceiverClosed(receiver)
            expectDeferredFailure(pending)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
            assertEquals(connectionCount, fixture.connector.connections.size)
            assertEquals(receiverCount, fixture.receiverInstances.size)
        } finally {
            fixture.stop()
        }
    }

    private suspend fun TestScope.assertRetryExhaustion(seedSticky: Boolean) {
        val expectedDelays = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L)
        val fixture = Fixture(this, jitter = { 0.5 })
        try {
            val sticky = if (seedSticky) {
                seedReceiverDiagnostic(fixture)
            } else {
                fixture.startOnline()
                null
            }
            val initialConnectionCount = fixture.connector.connections.size
            val initialReceiverCount = fixture.receiverInstances.size
            val pending = async {
                fixture.actor.request(
                    taskListRequest(REQUEST_ID).copy(
                        timeoutMillis = WssActor.MAX_REQUEST_TIMEOUT_MILLIS,
                    ),
                )
            }
            runCurrent()
            assertFalse(pending.isCompleted)

            expectedDelays.forEachIndexed { index, delayMillis ->
                val connection = fixture.connector.connections.last()
                val receiver = fixture.receiverInstances.last()
                connection.failure(fatalTls = false)
                runCurrent()

                val backoff = fixture.actor.status.value
                assertEquals(WssConnectionPhase.BACKING_OFF, backoff.phase)
                assertEquals(index + 1, backoff.reconnectAttempt)
                assertEquals(delayMillis, backoff.nextDelayMillis)
                assertEquals("WebSocket transport failed", backoff.reason)
                assertEquals(sticky, backoff.lastReceiverFailure)
                assertTrue(connection.socket.cancelled)
                assertEquals(1, connection.socket.closeCalls.size)
                assertReceiverClosed(receiver)
                assertEquals(initialConnectionCount + index, fixture.connector.connections.size)
                assertEquals(initialReceiverCount + index, fixture.receiverInstances.size)
                assertFalse(pending.isCompleted)

                advanceTimeBy(delayMillis - 1)
                runCurrent()
                assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
                assertEquals(initialConnectionCount + index, fixture.connector.connections.size)
                assertEquals(initialReceiverCount + index, fixture.receiverInstances.size)

                advanceTimeBy(1)
                runCurrent()
                assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
                assertEquals(initialConnectionCount + index + 1, fixture.connector.connections.size)
                assertEquals(initialReceiverCount + index + 1, fixture.receiverInstances.size)
                assertTrue(fixture.receiverInstances.last() !== receiver)
                assertFalse(pending.isCompleted)
            }

            val exhaustedConnection = fixture.connector.connections.last()
            val exhaustedReceiver = fixture.receiverInstances.last()
            exhaustedConnection.failure(fatalTls = false)
            runCurrent()

            val offline = fixture.actor.status.value
            assertEquals(WssConnectionPhase.OFFLINE, offline.phase)
            assertEquals(8, offline.reconnectAttempt)
            assertEquals(null, offline.nextDelayMillis)
            assertEquals("WebSocket transport failed", offline.reason)
            assertEquals(sticky, offline.lastReceiverFailure)
            assertTrue(exhaustedConnection.socket.cancelled)
            assertEquals(1, exhaustedConnection.socket.closeCalls.size)
            assertReceiverClosed(exhaustedReceiver)
            assertFalse(pending.isCompleted)

            val exhaustedConnectionCount = fixture.connector.connections.size
            val exhaustedReceiverCount = fixture.receiverInstances.size
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(WssConnectionPhase.OFFLINE, fixture.actor.status.value.phase)
            assertEquals(exhaustedConnectionCount, fixture.connector.connections.size)
            assertEquals(exhaustedReceiverCount, fixture.receiverInstances.size)
            assertFalse(pending.isCompleted)

            fixture.actor.retryNow()
            runCurrent()
            assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
            assertEquals(0, fixture.actor.status.value.reconnectAttempt)
            assertEquals(exhaustedConnectionCount + 1, fixture.connector.connections.size)
            assertEquals(exhaustedReceiverCount + 1, fixture.receiverInstances.size)
            val replacement = fixture.connector.connections.last()
            fixture.acceptHello(replacement)
            assertTrue(replacement.socket.sent.any { it.contains("\"kind\":\"task.list\"") })
            assertFalse(pending.isCompleted)
            replacement.text(successResponse(REQUEST_ID))
            runCurrent()
            pending.await()
        } finally {
            fixture.stop()
        }
    }

    private fun assertReceiverClosed(receiver: ReliabilityReceiver) {
        val action = receiver.receive(piEvent().toByteArray(Charsets.UTF_8)).single()
        assertTrue(action is ReceiverFailure)
        assertEquals(
            ReceiverFailureCode.RECEIVER_CLOSED,
            (action as ReceiverFailure).code,
        )
    }

    private fun captureConsole(block: () -> Unit): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        return try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8.name()))
            block()
            stdout.toString(Charsets.UTF_8.name()) + stderr.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
    }

    private enum class RecoverableNonReceiverFailure(val reason: String) {
        RECEIVER_THROW("Reliability receiver threw"),
        ACK_SEND("ACK send failed"),
        BINARY_PROTOCOL("Binary WebSocket frames are forbidden"),
    }

    private class FakeConnector(
        private val events: MutableList<String>?,
    ) : WssConnector {
        val connections = mutableListOf<FakeConnection>()

        override fun connect(url: String, listener: WssTransportListener): WssSocket {
            assertTrue(url.startsWith("wss://"))
            return FakeConnection(listener, events).also(connections::add).socket
        }
    }

    private class FakeConnection(
        private val listener: WssTransportListener,
        events: MutableList<String>?,
    ) {
        val socket = FakeSocket(events)
        fun open() = listener.onOpen()
        fun text(value: String) = listener.onText(value)
        fun binary(byteCount: Long) = listener.onBinary(byteCount)
        fun failure(fatalTls: Boolean) = listener.onFailure(fatalTls)
    }

    private class FakeSocket(
        private val events: MutableList<String>?,
    ) : WssSocket {
        val sent = mutableListOf<String>()
        val closeCalls = mutableListOf<Pair<Int, String>>()
        var cancelled = false
        var failNextSendKind: String? = null

        override fun send(text: String): Boolean {
            sent += text
            val kind = Regex("\"kind\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "unknown"
            events?.add("socket.send.$kind")
            if (kind == failNextSendKind) {
                failNextSendKind = null
                return false
            }
            return true
        }

        override fun close(code: Int, reason: String): Boolean {
            closeCalls += code to reason
            return true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class RecordingJournal(
        private val events: MutableList<String>,
    ) : CommandDurabilityJournal {
        override fun persistAccepted(
            requestId: String,
            commandId: String?,
            kind: String,
            taskId: String?,
            canonicalPayload: String,
        ): OutboundCommandRecord {
            events += "journal.accepted"
            return record(requestId, commandId, kind, taskId, canonicalPayload)
        }

        override fun markTerminal(
            requestId: String,
            canonicalResponseJson: String,
        ): OutboundCommandRecord {
            events += "journal.terminal"
            return record(requestId, COMMAND_ID, "task.create", null, "{}")
        }

        private fun record(
            requestId: String,
            commandId: String?,
            kind: String,
            taskId: String?,
            payload: String,
        ) = OutboundCommandRecord(
            requestId,
            commandId,
            kind,
            taskId,
            payload,
            app.momoding.core.data.OutboundCommandState.ACCEPTED,
            null,
            null,
            1,
            1,
        )
    }

    private object NoopProjectionStore : ProjectionTransactionStore {
        override fun read(taskId: String): DurableTaskProjection? = null

        override fun <T> transaction(taskId: String, block: (ProjectionTransaction) -> T): T =
            error("Projection transaction was not expected")
    }

    private class CountingProjectionStore : ProjectionTransactionStore {
        var transactionCalls = 0

        override fun read(taskId: String): DurableTaskProjection? = null

        override fun <T> transaction(taskId: String, block: (ProjectionTransaction) -> T): T {
            transactionCalls += 1
            error("Pre-hello frame must not reach projection")
        }
    }

    private class MemoryProjectionStore(initial: DurableTaskProjection) : ProjectionTransactionStore {
        private var current = initial

        override fun read(taskId: String): DurableTaskProjection? = current.takeIf { it.taskId == taskId }

        override fun <T> transaction(taskId: String, block: (ProjectionTransaction) -> T): T {
            require(taskId == current.taskId)
            val transaction = object : ProjectionTransaction {
                override val current: DurableTaskProjection
                    get() = this@MemoryProjectionStore.current

                override fun checkpoint(stage: app.momoding.wire.ProjectionWriteStage) = Unit

                override fun replace(next: DurableTaskProjection) {
                    this@MemoryProjectionStore.current = next
                }
            }
            return block(transaction)
        }
    }

    companion object {
        private const val REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val OTHER_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val COMMAND_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        private const val TASK_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        private const val OTHER_TASK_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        private const val STREAM_ID = "33333333-3333-4333-8333-333333333333"
        private const val OTHER_STREAM_ID = "55555555-5555-4555-8555-555555555555"
        private const val PI_SESSION_ID = "66666666-6666-4666-8666-666666666666"
        private const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        private const val CREDENTIAL_ID = "22222222-2222-4222-8222-222222222222"

        private fun taskListRequest(requestId: String): OutboundWireRequest = OutboundWireRequest(
            requestId = requestId,
            kind = "task.list",
            canonicalPayload = ClientWireCodec.encodeTaskList(requestId, null),
            mutating = false,
        )

        private fun successResponse(requestId: String, data: String = "{\"tasks\":[],\"listRevision\":1}"): String =
            """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":$data}"""

        private fun helloAccepted(requestId: String): String =
            """{"protocolVersion":1,"kind":"hello.accepted","requestId":"$requestId","connectionId":"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee","serverVersion":"0.1.0","piVersion":"0.80.6","heartbeatIntervalMs":20000,"maxFrameBytes":1048576}"""

        private fun wireError(code: String): String =
            """{"protocolVersion":1,"kind":"error","error":{"code":"$code","message":"fixture","retryable":false}}"""

        private fun piEvent(sequence: Long = 1, streamId: String = STREAM_ID): String =
            """{"protocolVersion":1,"kind":"pi.event","taskId":"$TASK_ID","piSessionId":"22222222-2222-4222-8222-222222222222","piVersion":"0.80.6","streamId":"$streamId","sequence":$sequence,"emittedAt":"2026-07-15T00:00:00.000Z","event":{"type":"agent_start"}}"""

        private fun replayComplete(through: Long, liveFrom: Long): String =
            """{"protocolVersion":1,"kind":"pi.replay.complete","taskId":"$TASK_ID","streamId":"$STREAM_ID","replayedThroughSequence":$through,"liveFromSequence":$liveFrom}"""

        private fun resyncRequired(snapshotVersion: Long): String =
            """{"protocolVersion":1,"kind":"pi.resync_required","taskId":"$TASK_ID","reason":"cursor_expired","requestedStreamId":"$STREAM_ID","currentStreamId":"$STREAM_ID","snapshotVersion":$snapshotVersion}"""

        private fun taskSnapshot(snapshotVersion: Long, highWatermark: Long): String =
            """{"kind":"task.snapshot","taskId":"$TASK_ID","snapshotVersion":$snapshotVersion,"recoveryState":"normal","runState":"running","piSessionId":"$PI_SESSION_ID","pi":{"messages":[],"isStreaming":true,"queue":[]},"pendingAttention":[],"deviceCalls":[],"cursor":{"streamId":"$STREAM_ID","highWatermarkSequence":$highWatermark,"oldestReplayableSequence":1}}"""

        private fun requestIdFrom(json: String): String =
            Regex("\"requestId\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                ?: error("requestId missing")

        private fun secret(): VaultSecret {
            val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
            return VaultSecret(
                header = VaultHeader(
                    "https://host.example:8443",
                    "sha256/${Base64.getEncoder().encodeToString(ByteArray(32) { 4 })}",
                    HOST_ID,
                    CREDENTIAL_ID,
                ),
                deviceCredential = "cm1.$CREDENTIAL_ID.$raw",
                clientInstanceId = "33333333-3333-4333-8333-333333333333",
                deviceId = "44444444-4444-4444-8444-444444444444",
                deviceName = "Android",
            )
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }

        private suspend fun expectDeferredFailure(deferred: Deferred<*>) {
            var failed = false
            try {
                deferred.await()
            } catch (_: Exception) {
                failed = true
            }
            assertTrue(failed)
        }

        private fun TestScope.supervisedAsync(block: suspend () -> Unit): Deferred<Unit> =
            CoroutineScope(coroutineContext + SupervisorJob()).async { block() }
    }
}
