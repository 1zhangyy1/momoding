package app.momoding.core.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.ReliabilityReceiver
import app.momoding.wire.DeviceClientFrameEncoder
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.wire.DeviceLedgerState
import app.momoding.wire.DeviceToolReconcileResultClientFrame
import app.momoding.wire.DeviceToolReconcileResultItem
import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.CommandDurabilityJournal
import app.momoding.core.data.OutboundCommandRecord
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.data.RoomProjectionTransactionStore
import app.momoding.core.data.TaskEntity
import app.momoding.core.files.DeviceMetadataToolHandler
import app.momoding.core.files.DeviceContentReadHandler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WssActorAttentionIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.momodingDao().upsertTask(task())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `capability is first after hello and ordinary request waits for exact durable success`() =
        runTest {
            val fixture = Fixture(this, database)
            try {
                val started = async { fixture.actor.start(secret()) }
                runCurrent()
                val connection = fixture.connector.connections.single()
                connection.open()
                runCurrent()
                val ordinary = async { fixture.actor.requestExact(taskListRequest(REQUEST_ID)) }
                runCurrent()
                assertEquals(listOf("hello"), connection.socket.kinds())

                fixture.acceptHello(connection)
                assertEquals(
                    listOf("hello", "device.capabilities.report"),
                    connection.socket.kinds(),
                )
                assertEquals(WssConnectionPhase.CAPABILITY_SYNCING, fixture.actor.status.value.phase)
                assertFalse(started.isCompleted)
                assertFalse(ordinary.isCompleted)

                fixture.acceptLatestCapability(connection)
                started.await()
                assertEquals(
                    listOf("hello", "device.capabilities.report", "task.list"),
                    connection.socket.kinds(),
                )
                assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
                connection.text(taskListSuccess(REQUEST_ID))
                runCurrent()
                ordinary.await()
            } finally {
                fixture.stop()
            }
        }

    @Test
    fun `device request before capability recovery performs zero attention writes`() = runTest {
        val fixture = Fixture(this, database)
        val started = supervisedAsync { fixture.actor.start(secret()) }
        try {
            runCurrent()
            val connection = fixture.connector.connections.single()
            fixture.acceptHello(connection)
            connection.text(deviceRequest(CALL_ID))
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertTrue(connection.socket.cancelled)
            assertEquals(null, RoomAttentionLedger(database).record(CALL_ID))
            assertFalse(started.isCompleted)
        } finally {
            fixture.stop()
            expectFailure(started)
        }
    }

    @Test
    fun `metadata request uses native handler and returns directly without creating attention`() =
        runTest {
            val handler = object : DeviceMetadataToolHandler {
                override fun handles(toolName: String) = toolName == "device_capabilities_get"

                override suspend fun execute(
                    frame: DeviceToolRequestFrame,
                ) = DeviceToolResultClientFrame(
                    callId = frame.callId,
                    taskId = frame.taskId,
                    deviceId = frame.deviceId,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = buildJsonObject {
                        put("capabilityVersion", 1)
                        put("grants", kotlinx.serialization.json.buildJsonArray {})
                    },
                )
            }
            val fixture = Fixture(this, database, metadataToolHandler = handler)
            try {
                val connection = fixture.startReady()

                connection.text(metadataRequest(CALL_ID))
                runCurrent()

                assertEquals("device.tool.result", connection.socket.kinds().last())
                val terminal = JSON.parseToJsonElement(connection.socket.sent.last()).jsonObject
                assertEquals("succeeded", terminal.getValue("terminal").jsonPrimitive.content)
                assertEquals(null, RoomAttentionLedger(database).record(CALL_ID))
            } finally {
                fixture.stop()
            }
        }

    @Test
    fun `content read request waits for Android approval then returns bounded content`() = runTest {
        val fixture = Fixture(
            this,
            database,
            contentReadHandler = DeviceContentReadHandler {
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("documents", buildJsonArray {
                        add(buildJsonObject {
                            put("alias", DOCUMENT_ALIAS)
                            put("mimeType", "text/plain")
                            put("byteCount", 5)
                            put("content", "hello")
                        })
                    })
                    put("totalBytes", 5)
                }
            },
        )
        try {
            val connection = fixture.startReady()
            connection.text(contentReadRequest(CALL_ID))
            runCurrent()
            assertEquals("device.tool.progress", connection.socket.kinds().last())

            val decision = supervisedAsync {
                fixture.actor.submitAttentionDecision(
                    AttentionUserDecision.AllowContentRead(CALL_ID),
                )
            }
            runCurrent()
            decision.await()
            advanceTimeBy(1_000)
            runCurrent()

            val terminal = connection.socket.sent.last { kind(it) == "device.tool.result" }
            assertTrue(terminal.contains("\"content\":\"hello\""))
            assertTrue(terminal.contains("\"terminal\":\"succeeded\""))
            assertEquals(null, RoomAttentionLedger(database).record(CALL_ID))
            assertTrue(
                database.momodingDao().terminalOperationsReadyForDelivery(DEVICE_ID)
                    .none { it.callId == CALL_ID },
            )
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `failed live content send is discarded and cannot enter reconnect resend`() = runTest {
        val fixture = Fixture(
            this,
            database,
            contentReadHandler = DeviceContentReadHandler {
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("documents", buildJsonArray {
                        add(buildJsonObject {
                            put("alias", DOCUMENT_ALIAS)
                            put("mimeType", "text/plain")
                            put("byteCount", 5)
                            put("content", "never-persist-me")
                        })
                    })
                    put("totalBytes", 5)
                }
            },
        )
        try {
            val connection = fixture.startReady()
            connection.text(contentReadRequest(CALL_ID))
            runCurrent()
            connection.socket.failNextKind = "device.tool.result"

            val decision = supervisedAsync {
                fixture.actor.submitAttentionDecision(
                    AttentionUserDecision.AllowContentRead(CALL_ID),
                )
            }
            runCurrent()

            expectFailure(decision)
            assertEquals(
                1,
                connection.socket.sent.count {
                    kind(it) == "device.tool.result" && it.contains("never-persist-me")
                },
            )
            assertEquals(null, RoomAttentionLedger(database).record(CALL_ID))
            assertTrue(
                database.momodingDao().terminalOperationsReadyForDelivery(DEVICE_ID)
                    .none { it.callId == CALL_ID },
            )
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `capability response durability failure is fatal and never opens readiness`() = runTest {
        val fixture = Fixture(this, database, failTerminalDurability = true)
        val started = supervisedAsync { fixture.actor.start(secret()) }
        try {
            runCurrent()
            val connection = fixture.connector.connections.single()
            fixture.acceptHello(connection)
            val report = connection.socket.sent.last { kind(it) == "device.capabilities.report" }
            connection.text(emptySuccess(requestId(report)))
            runCurrent()

            assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
            assertTrue(started.isCompleted)
            assertEquals(
                listOf("device.capabilities.report"),
                RoomCommandDraftJournal(database).pendingCommands().map { it.kind },
            )
        } finally {
            fixture.stop()
            expectFailure(started)
        }
    }

    @Test
    fun `capability persist failure is fatal closes the socket and sends zero capability bytes`() =
        runTest {
            val fixture = Fixture(
                this,
                database,
                failAcceptedKind = "device.capabilities.report",
            )
            val started = supervisedAsync { fixture.actor.start(secret()) }
            try {
                runCurrent()
                val connection = fixture.connector.connections.single()
                fixture.acceptHello(connection)

                assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
                assertTrue(connection.socket.cancelled)
                assertEquals(listOf("hello"), connection.socket.kinds())
                assertTrue(started.isCompleted)
            } finally {
                fixture.stop()
                expectFailure(started)
            }
        }

    @Test
    fun `native replay continues during capability sync but readiness waits for both gates`() = runTest {
        database.momodingDao().upsertTask(task().copy(streamId = STREAM_ID, throughSequence = 1))
        val fixture = Fixture(
            this,
            database,
            resumeCursors = listOf(DurableResumeCursor(TASK_ID, STREAM_ID, 1)),
        )
        val started = async { fixture.actor.start(secret()) }
        try {
            runCurrent()
            val connection = fixture.connector.connections.single()
            fixture.acceptHello(connection)
            assertEquals(WssConnectionPhase.CAPABILITY_SYNCING, fixture.actor.status.value.phase)
            connection.text(piEvent(2))
            runCurrent()
            assertEquals(
                1L,
                fixture.actor.status.value.replayProgress.getValue(TASK_ID).replayedEventCount,
            )
            connection.text(replayComplete(2, 3))
            runCurrent()
            assertEquals(WssConnectionPhase.CAPABILITY_SYNCING, fixture.actor.status.value.phase)
            assertTrue(fixture.actor.status.value.replayProgress.isEmpty())
            assertFalse(started.isCompleted)

            fixture.acceptLatestCapability(connection)
            started.await()
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `mutating request becomes durable during capability sync but physical send waits`() =
        runTest {
            val fixture = Fixture(this, database)
            val started = async { fixture.actor.start(secret()) }
            try {
                runCurrent()
                val connection = fixture.connector.connections.single()
                fixture.acceptHello(connection)
                val command = taskCreateRequest(CREATE_REQUEST_ID)
                val pending = async { fixture.actor.requestExact(command) }
                runCurrent()
                assertFalse(connection.socket.kinds().contains("task.create"))
                assertTrue(RoomCommandDraftJournal(database).pendingCommands().any {
                    it.requestId == CREATE_REQUEST_ID && it.kind == "task.create"
                })

                fixture.acceptLatestCapability(connection)
                started.await()
                assertEquals("task.create", connection.socket.kinds().last())
                connection.text(taskCreateSuccess(CREATE_REQUEST_ID))
                runCurrent()
                pending.await()
                assertTrue(RoomCommandDraftJournal(database).pendingCommands().none {
                    it.requestId == CREATE_REQUEST_ID
                })
            } finally {
                fixture.stop()
            }
        }

    @Test
    fun `typed decision sends exact terminal then snapshot from the same mailbox`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            connection.text(deviceRequest(CALL_ID))
            runCurrent()
            assertEquals("device.tool.progress", connection.socket.kinds().last())

            val decision = async {
                fixture.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()
            decision.await()
            assertFalse(connection.socket.kinds().contains("device.tool.result"))

            advanceTimeBy(1_000)
            runCurrent()
            val kinds = connection.socket.kinds()
            val resultIndex = kinds.indexOfLast { it == "device.tool.result" }
            val snapshotIndex = kinds.indexOfLast { it == "task.snapshot.request" }
            assertTrue(resultIndex > 0)
            assertTrue(snapshotIndex > resultIndex)
            val stored = RoomAttentionLedger(database).record(CALL_ID)!!.operation
            assertEquals(AttentionLedgerState.TERMINAL.name, stored.ledgerState)
            assertEquals(AttentionDeliveryState.SENT_UNCONFIRMED.name, stored.deliveryState)

            val snapshotRequest = connection.socket.sent.last {
                kind(it) == "task.snapshot.request"
            }
            val snapshotRequestId = requestId(snapshotRequest)
            connection.text(snapshotSuccess(snapshotRequestId))
            connection.text(taskSnapshot(snapshotRequestId, CALL_ID))
            runCurrent()
            assertEquals(
                AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
                RoomAttentionLedger(database).record(CALL_ID)!!.operation.deliveryState,
            )
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `cancel commits before reconcile and device frames never reach default observer`() = runTest {
        val observer = RecordingObserver()
        val fixture = Fixture(this, database, observer = observer)
        try {
            val connection = fixture.startReady()
            connection.text(deviceRequest(CALL_ID))
            connection.text(deviceCancel(CALL_ID))
            connection.text(reconcileRequest(RECONCILE_REQUEST_ID, CALL_ID))
            runCurrent()

            val reconcile = connection.socket.sent.last { kind(it) == "device.tool.reconcile.result" }
            val state = JSON.parseToJsonElement(reconcile).jsonObject
                .getValue("results").jsonArray.single().jsonObject
                .getValue("state").jsonPrimitive.content
            assertEquals("cancelled", state)
            assertEquals(
                AttentionLedgerState.CANCELLED.name,
                RoomAttentionLedger(database).record(CALL_ID)!!.operation.ledgerState,
            )
            connection.text(emptySuccess(RECONCILE_REQUEST_ID))
            runCurrent()
            assertFalse(connection.socket.kinds().contains("device.tool.result"))
            assertTrue(observer.deviceKinds.isEmpty())
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `exact duplicate reconcile reuses the current pending request and exact bytes`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            val raw = reconcileRequest(RECONCILE_REQUEST_ID, CALL_ID)
            connection.text(raw)
            runCurrent()
            val first = connection.socket.sent.last {
                kind(it) == "device.tool.reconcile.result"
            }

            connection.text(raw)
            runCurrent()
            val results = connection.socket.sent.filter {
                kind(it) == "device.tool.reconcile.result"
            }
            assertEquals(2, results.size)
            assertEquals(first, results.last())
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)

            connection.text(emptySuccess(RECONCILE_REQUEST_ID))
            runCurrent()
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `exact duplicate reconcile reopens only its durable response tombstone`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            val raw = reconcileRequest(RECONCILE_REQUEST_ID, CALL_ID)
            connection.text(raw)
            runCurrent()
            val first = connection.socket.sent.last {
                kind(it) == "device.tool.reconcile.result"
            }
            connection.text(emptySuccess(RECONCILE_REQUEST_ID))
            runCurrent()

            connection.text(raw)
            runCurrent()
            val results = connection.socket.sent.filter {
                kind(it) == "device.tool.reconcile.result"
            }
            assertEquals(2, results.size)
            assertEquals(first, results.last())
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)

            connection.text(emptySuccess(RECONCILE_REQUEST_ID))
            runCurrent()
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `generic response tombstone cannot be rebound as a reconcile command`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            val ordinary = async {
                fixture.actor.requestExact(taskListRequest(REQUEST_ID))
            }
            runCurrent()
            connection.text(taskListSuccess(REQUEST_ID))
            runCurrent()
            ordinary.await()
            assertEquals(null, RoomCommandDraftJournal(database).command(REQUEST_ID))

            connection.text(reconcileRequest(REQUEST_ID, CALL_ID))
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertTrue(connection.socket.cancelled)
            assertFalse(connection.socket.kinds().contains("device.tool.reconcile.result"))
            assertEquals(null, RoomCommandDraftJournal(database).command(REQUEST_ID))
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `reconcile persist failure is fatal and sends zero reconcile result bytes`() = runTest {
        val fixture = Fixture(
            this,
            database,
            failAcceptedKind = "device.tool.reconcile.result",
        )
        try {
            val connection = fixture.startReady()
            connection.text(reconcileRequest(RECONCILE_REQUEST_ID, CALL_ID))
            runCurrent()

            assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
            assertTrue(connection.socket.cancelled)
            assertFalse(connection.socket.kinds().contains("device.tool.reconcile.result"))
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `reconcile registration failure is fatal and completes existing pending callers`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            val pending = (0 until RequestTable.MAX_PENDING).map { index ->
                supervisedAsync {
                    fixture.actor.requestExact(taskListRequest(
                        "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                    ))
                }
            }
            runCurrent()

            connection.text(reconcileRequest(RECONCILE_REQUEST_ID, CALL_ID))
            runCurrent()

            assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
            assertTrue(connection.socket.cancelled)
            assertFalse(connection.socket.kinds().contains("device.tool.reconcile.result"))
            assertEquals(RequestTable.MAX_PENDING, pending.count { it.isCompleted })
            pending.forEach { expectFailure(it) }
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `refresh command fifteen second timeout is generation fenced and reconnects once`() =
        runTest {
            val fixture = Fixture(this, database, nowMillis = { NOW + testScheduler.currentTime })
            try {
                fixture.startReady()
                val first = fixture.connector.connections.single()
                assertEquals(1, first.socket.kinds().count { it == "device.capabilities.report" })

                advanceTimeBy(12 * 60_000L)
                runCurrent()
                assertEquals(2, first.socket.kinds().count { it == "device.capabilities.report" })

                advanceTimeBy(OutboundWireRequest.DEFAULT_TIMEOUT_MILLIS)
                runCurrent()
                assertTrue(first.socket.cancelled)
                assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
                assertEquals(1, fixture.connector.connections.size)
                advanceTimeBy(501)
                runCurrent()
                assertEquals(2, fixture.connector.connections.size)
                assertEquals(WssConnectionPhase.CONNECTING, fixture.actor.status.value.phase)
            } finally {
                fixture.stop()
            }
        }

    @Test
    fun `accepted refresh keeps online and schedules the next twelve minute report`() = runTest {
        val fixture = Fixture(this, database, nowMillis = { NOW + testScheduler.currentTime })
        try {
            val connection = fixture.startReady()
            advanceTimeBy(12 * 60_000L)
            runCurrent()
            val refresh = connection.socket.sent.last { kind(it) == "device.capabilities.report" }
            connection.text(emptySuccess(requestId(refresh)))
            runCurrent()
            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)

            advanceTimeBy(12 * 60_000L)
            runCurrent()
            assertEquals(3, connection.socket.kinds().count {
                it == "device.capabilities.report"
            })
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `same generation stale wake already in mailbox cannot touch coordinator state`() = runTest {
        val fixture = Fixture(this, database, nowMillis = { NOW + testScheduler.currentTime })
        try {
            val connection = fixture.startReady()
            connection.text(deviceRequest(CALL_ID))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val ledger = RoomAttentionLedger(database)
            val operation = ledger.record(CALL_ID)!!.operation
            assertEquals(
                1,
                database.momodingDao().updateDeviceOperation(
                    operation.copy(requestSha256 = "0".repeat(64)),
                ),
            )

            enqueueAttentionWake(
                fixture.actor,
                generation = 1,
                deadlineAtMillis = NOW + 1_000,
            )
            runCurrent()

            assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
            assertFalse(connection.socket.cancelled)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `old generation timeout cannot tear down a rehydrated capability command`() = runTest {
        val fixture = Fixture(this, database, nowMillis = { NOW + testScheduler.currentTime })
        try {
            fixture.startReady()
            val first = fixture.connector.connections.single()
            advanceTimeBy(12 * 60_000L)
            runCurrent()
            val refresh = first.socket.sent.last { kind(it) == "device.capabilities.report" }

            first.failure(fatalTls = false)
            runCurrent()
            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            advanceTimeBy(501)
            runCurrent()
            val second = fixture.connector.connections.last()
            fixture.acceptHello(second)
            assertEquals(
                refresh,
                second.socket.sent.last { kind(it) == "device.capabilities.report" },
            )
            assertEquals(WssConnectionPhase.CAPABILITY_SYNCING, fixture.actor.status.value.phase)

            advanceTimeBy(14_499)
            runCurrent()
            assertEquals(WssConnectionPhase.CAPABILITY_SYNCING, fixture.actor.status.value.phase)
            advanceTimeBy(501)
            runCurrent()
            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `process restart hydrates the exact accepted capability identity`() = runTest {
        val first = Fixture(this, database)
        val firstStart = supervisedAsync { first.actor.start(secret()) }
        lateinit var exactCapability: String
        try {
            runCurrent()
            val connection = first.connector.connections.single()
            first.acceptHello(connection)
            exactCapability = connection.socket.sent.single {
                kind(it) == "device.capabilities.report"
            }
        } finally {
            first.stop()
            expectFailure(firstStart)
        }

        val restarted = Fixture(this, database)
        val secondStart = supervisedAsync { restarted.actor.start(secret()) }
        try {
            runCurrent()
            val connection = restarted.connector.connections.single()
            restarted.acceptHello(connection)
            val hydrated = connection.socket.sent.single {
                kind(it) == "device.capabilities.report"
            }
            assertEquals(exactCapability, hydrated)
            restarted.acceptLatestCapability(connection)
            secondStart.await()
            assertEquals(WssConnectionPhase.ONLINE, restarted.actor.status.value.phase)
        } finally {
            restarted.stop()
        }
    }

    @Test
    fun `capability send failure keeps durable identity and retries it on the next generation`() =
        runTest {
            val fixture = Fixture(this, database)
            val started = supervisedAsync { fixture.actor.start(secret()) }
            try {
                runCurrent()
                val first = fixture.connector.connections.single()
                first.open()
                runCurrent()
                first.socket.failNextKind = "device.capabilities.report"
                fixture.acceptHello(first)
                val exact = first.socket.sent.last { kind(it) == "device.capabilities.report" }
                assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
                assertFalse(started.isCompleted)

                advanceTimeBy(501)
                runCurrent()
                val second = fixture.connector.connections.last()
                fixture.acceptHello(second)
                assertEquals(
                    exact,
                    second.socket.sent.last { kind(it) == "device.capabilities.report" },
                )
                fixture.acceptLatestCapability(second)
                started.await()
                assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
            } finally {
                fixture.stop()
            }
        }

    @Test
    fun `restarted rejected capability terminalizes old identity then sends fresh on same socket`() =
        runTest {
            val first = Fixture(this, database)
            val firstStart = supervisedAsync { first.actor.start(secret()) }
            lateinit var oldReport: String
            try {
                runCurrent()
                val connection = first.connector.connections.single()
                first.acceptHello(connection)
                oldReport = connection.socket.sent.last {
                    kind(it) == "device.capabilities.report"
                }
            } finally {
                first.stop()
                expectFailure(firstStart)
            }

            val restarted = Fixture(this, database)
            val restartedStart = supervisedAsync { restarted.actor.start(secret()) }
            try {
                runCurrent()
                val connection = restarted.connector.connections.single()
                restarted.acceptHello(connection)
                assertEquals(oldReport, connection.socket.sent.last {
                    kind(it) == "device.capabilities.report"
                })
                connection.text(failureResponse(requestId(oldReport)))
                runCurrent()
                val reports = connection.socket.sent.filter {
                    kind(it) == "device.capabilities.report"
                }
                assertEquals(2, reports.size)
                assertTrue(requestId(reports[0]) != requestId(reports[1]))
                assertEquals(WssConnectionPhase.CAPABILITY_SYNCING, restarted.actor.status.value.phase)
                assertFalse(restartedStart.isCompleted)

                connection.text(emptySuccess(requestId(reports[1])))
                runCurrent()
                restartedStart.await()
                assertEquals(WssConnectionPhase.ONLINE, restarted.actor.status.value.phase)
            } finally {
                restarted.stop()
            }
        }

    @Test
    fun `old reconcile probe failure settles beside current cancel and reconcile without reconnect`() =
        runTest {
            val oldRequestId = "old-reconcile-probe"
            val oldCommandId = "12121212-1212-4212-8212-121212121212"
            val oldPayload = DeviceClientFrameEncoder.encode(
                DeviceToolReconcileResultClientFrame(
                    requestId = oldRequestId,
                    commandId = oldCommandId,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    results = listOf(DeviceToolReconcileResultItem(
                        "13131313-1313-4313-8313-131313131313",
                        DeviceLedgerState.NEVER_STARTED,
                    )),
                ),
            ).decodeToString()
            RoomCommandDraftJournal(database, nowMillis = { NOW }).persistAccepted(
                oldRequestId,
                oldCommandId,
                "device.tool.reconcile.result",
                TASK_ID,
                oldPayload,
            )
            val fixture = Fixture(this, database)
            try {
                val connection = fixture.startReady()
                assertEquals(oldPayload, connection.socket.sent.last {
                    kind(it) == "device.tool.reconcile.result"
                })

                connection.text(deviceRequest(CALL_ID))
                connection.text(deviceCancel(CALL_ID))
                connection.text(reconcileRequest(RECONCILE_REQUEST_ID, CALL_ID))
                runCurrent()
                connection.text(failureResponse(oldRequestId))
                connection.text(emptySuccess(RECONCILE_REQUEST_ID))
                runCurrent()

                assertEquals(WssConnectionPhase.ONLINE, fixture.actor.status.value.phase)
                assertTrue(RoomCommandDraftJournal(database).pendingCommands().none {
                    it.kind == "device.tool.reconcile.result"
                })
                assertEquals(
                    AttentionLedgerState.CANCELLED.name,
                    RoomAttentionLedger(database).record(CALL_ID)!!.operation.ledgerState,
                )
            } finally {
                fixture.stop()
            }
        }

    @Test
    fun `terminal send failure keeps ready truth and exact resend succeeds after reconnect`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val first = fixture.startReady()
            first.text(deviceRequest(CALL_ID))
            runCurrent()
            first.socket.failNextKind = "device.tool.result"
            val decision = async {
                fixture.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()
            decision.await()
            advanceTimeBy(1_000)
            runCurrent()
            val attempted = first.socket.sent.last { kind(it) == "device.tool.result" }
            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertEquals(
                AttentionDeliveryState.READY_TO_SEND.name,
                RoomAttentionLedger(database).record(CALL_ID)!!.operation.deliveryState,
            )

            advanceTimeBy(501)
            runCurrent()
            val second = fixture.connector.connections.last()
            fixture.acceptHello(second)
            fixture.acceptLatestCapability(second)
            advanceTimeBy(1_000)
            runCurrent()
            val resent = second.socket.sent.last { kind(it) == "device.tool.result" }
            assertEquals(attempted, resent)
            assertEquals(
                AttentionDeliveryState.SENT_UNCONFIRMED.name,
                RoomAttentionLedger(database).record(CALL_ID)!!.operation.deliveryState,
            )
            assertTrue(second.socket.kinds().indexOf("task.snapshot.request") >
                second.socket.kinds().indexOf("device.tool.result"))
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `sent unconfirmed terminal is byte exact after actor restart`() = runTest {
        val first = Fixture(this, database)
        lateinit var exactTerminal: String
        try {
            val connection = first.startReady()
            connection.text(deviceRequest(CALL_ID))
            runCurrent()
            val decision = async {
                first.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()
            decision.await()
            advanceTimeBy(1_000)
            runCurrent()
            exactTerminal = connection.socket.sent.last { kind(it) == "device.tool.result" }
            assertEquals(
                AttentionDeliveryState.SENT_UNCONFIRMED.name,
                RoomAttentionLedger(database).record(CALL_ID)!!.operation.deliveryState,
            )
        } finally {
            first.stop()
        }

        val restarted = Fixture(this, database)
        try {
            val connection = restarted.startReady()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                exactTerminal,
                connection.socket.sent.last { kind(it) == "device.tool.result" },
            )
            assertTrue(connection.socket.kinds().indexOf("task.snapshot.request") >
                connection.socket.kinds().indexOf("device.tool.result"))
        } finally {
            restarted.stop()
        }
    }

    @Test
    fun `progress send failure reconnects without rolling back the durable request`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            connection.socket.failNextKind = "device.tool.progress"
            connection.text(deviceRequest(CALL_ID))
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            val record = RoomAttentionLedger(database).record(CALL_ID)!!
            assertEquals(AttentionLedgerState.AWAITING_USER.name, record.operation.ledgerState)
            assertEquals(1L, record.operation.progressSequence)
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `snapshot send failure reconnects after terminal becomes sent unconfirmed`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            connection.text(deviceRequest(CALL_ID))
            runCurrent()
            val decision = async {
                fixture.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()
            decision.await()
            connection.socket.failNextKind = "task.snapshot.request"

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertEquals(
                AttentionDeliveryState.SENT_UNCONFIRMED.name,
                RoomAttentionLedger(database).record(CALL_ID)!!.operation.deliveryState,
            )
            val kinds = connection.socket.kinds()
            assertTrue(kinds.indexOfLast { it == "task.snapshot.request" } >
                kinds.indexOfLast { it == "device.tool.result" })
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `terminal delivery commit fault reconnects and never sends snapshot early`() = runTest {
        val fixture = Fixture(this, database, failTerminalDeliveryCommit = true)
        try {
            val connection = fixture.startReady()
            connection.text(deviceRequest(CALL_ID))
            runCurrent()
            val decision = async {
                fixture.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()
            decision.await()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(WssConnectionPhase.BACKING_OFF, fixture.actor.status.value.phase)
            assertTrue(connection.socket.cancelled)
            assertFalse(connection.socket.kinds().contains("task.snapshot.request"))
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `snapshot registration failure becomes fatal and completes every pending caller`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            val pending = (0 until RequestTable.MAX_PENDING).map { index ->
                supervisedAsync {
                    fixture.actor.requestExact(taskListRequest(
                        "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                    ))
                }
            }
            runCurrent()
            assertEquals(
                RequestTable.MAX_PENDING,
                connection.socket.kinds().count { it == "task.list" },
            )

            connection.text(deviceRequest(CALL_ID))
            runCurrent()
            val decision = async {
                fixture.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()
            decision.await()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(WssConnectionPhase.FATAL, fixture.actor.status.value.phase)
            assertTrue(connection.socket.cancelled)
            assertEquals(RequestTable.MAX_PENDING, pending.count { it.isCompleted })
            pending.forEach { expectFailure(it) }
        } finally {
            fixture.stop()
        }
    }

    @Test
    fun `stop is a linearizable rejection boundary for a later attention decision`() = runTest {
        val fixture = Fixture(this, database)
        try {
            val connection = fixture.startReady()
            connection.text(deviceRequest(CALL_ID))
            runCurrent()

            val stopping = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.actor.stopAndJoin()
            }
            val decision = CoroutineScope(coroutineContext + SupervisorJob()).async(
                start = CoroutineStart.UNDISPATCHED,
            ) {
                fixture.actor.submitAttentionDecision(AttentionUserDecision.Skip(CALL_ID))
            }
            runCurrent()

            stopping.await()
            assertTrue(decision.isCompleted)
            expectFailure(decision)
            assertEquals(WssConnectionPhase.STOPPED, fixture.actor.status.value.phase)
            assertFalse(connection.socket.kinds().contains("device.tool.result"))
        } finally {
            fixture.stop()
        }
    }

    private class Fixture(
        private val scope: TestScope,
        database: MomodingDatabase,
        observer: WssActorObserver = object : WssActorObserver {},
        private val nowMillis: () -> Long = { NOW + scope.testScheduler.currentTime },
        resumeCursors: List<DurableResumeCursor> = emptyList(),
        failTerminalDurability: Boolean = false,
        failAcceptedKind: String? = null,
        failTerminalDeliveryCommit: Boolean = false,
        metadataToolHandler: DeviceMetadataToolHandler? = null,
        contentReadHandler: DeviceContentReadHandler? = null,
    ) {
        val connector = FakeConnector()
        private val roots = mutableListOf<Path>()
        private val journal = RoomCommandDraftJournal(database, nowMillis = nowMillis)
        private val attentionLedger = RoomAttentionLedger(database, nowMillis)
        private val coordinator = AttentionApplicationCoordinator(
            attentionLedger,
            journal,
            nowMillis,
            terminalSentCommit = if (failTerminalDeliveryCommit) {
                { error("injected terminal delivery commit failure") }
            } else {
                { callId -> attentionLedger.markSent(callId) }
            },
            contentReadHandler = contentReadHandler,
        )
        private val actorJournal: CommandDurabilityJournal = if (
            failTerminalDurability || failAcceptedKind != null
        ) {
            object : CommandDurabilityJournal {
                override fun persistAccepted(
                    requestId: String,
                    commandId: String?,
                    kind: String,
                    taskId: String?,
                    canonicalPayload: String,
                ): OutboundCommandRecord {
                    if (kind == failAcceptedKind) {
                        error("injected accepted durability failure for $kind")
                    }
                    return journal.persistAccepted(
                        requestId,
                        commandId,
                        kind,
                        taskId,
                        canonicalPayload,
                    )
                }

                override fun markTerminal(
                    requestId: String,
                    canonicalResponseJson: String,
                ): OutboundCommandRecord {
                    if (failTerminalDurability) {
                        error("injected terminal durability failure")
                    }
                    return journal.markTerminal(requestId, canonicalResponseJson)
                }
            }
        } else {
            journal
        }
        val actor = WssActor(
            scope = scope,
            connectorFactory = WssConnectorFactory { connector },
            receiverFactory = ReliabilityReceiverFactory {
                ReliabilityReceiver(
                    Files.createTempDirectory("wss-attention-").also(roots::add),
                    RoomProjectionTransactionStore(database),
                )
            },
            resumeCursorSource = ResumeCursorSource { resumeCursors },
            commandJournal = actorJournal,
            attentionCoordinator = coordinator,
            metadataToolExecutor = metadataToolHandler,
            reconnectPolicy = FullJitterReconnectPolicy { 0.5 },
            nowMillis = nowMillis,
        )

        fun acceptHello(connection: FakeConnection) {
            if (connection.socket.sent.isEmpty()) {
                connection.open()
                scope.runCurrent()
            }
            connection.text(helloAccepted(requestId(connection.socket.sent.first())))
            scope.runCurrent()
        }

        fun acceptLatestCapability(connection: FakeConnection) {
            val report = connection.socket.sent.last { kind(it) == "device.capabilities.report" }
            connection.text(emptySuccess(requestId(report)))
            scope.runCurrent()
        }

        suspend fun startReady(): FakeConnection {
            val started = scope.async { actor.start(secret()) }
            scope.runCurrent()
            val connection = connector.connections.single()
            acceptHello(connection)
            acceptLatestCapability(connection)
            started.await()
            return connection
        }

        suspend fun stop() {
            actor.stopAndJoin()
            roots.forEach { it.toFile().deleteRecursively() }
        }
    }

    private class FakeConnector : WssConnector {
        val connections = mutableListOf<FakeConnection>()

        override fun connect(url: String, listener: WssTransportListener): WssSocket =
            FakeConnection(listener).also(connections::add).socket
    }

    private class FakeConnection(private val listener: WssTransportListener) {
        val socket = FakeSocket()
        fun open() = listener.onOpen()
        fun text(value: String) = listener.onText(value)
        fun failure(fatalTls: Boolean) = listener.onFailure(fatalTls)
    }

    private class FakeSocket : WssSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        var failNextKind: String? = null

        override fun send(text: String): Boolean {
            sent += text
            if (kind(text) == failNextKind) {
                failNextKind = null
                return false
            }
            return true
        }

        override fun close(code: Int, reason: String): Boolean = true
        override fun cancel() {
            cancelled = true
        }

        fun kinds(): List<String> = sent.map(::kind)
    }

    private class RecordingObserver : WssActorObserver {
        val deviceKinds = mutableListOf<String>()
        override fun onReceiverAction(action: app.momoding.wire.ReceiverAction) {
            val received = (action as? app.momoding.wire.FrameReady)?.received ?: return
            val kind = received.frame::class.simpleName.orEmpty()
            if (kind.startsWith("Device")) deviceKinds += kind
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enqueueAttentionWake(
        actor: WssActor,
        generation: Long,
        deadlineAtMillis: Long,
    ) {
        val mailbox = WssActor::class.java.getDeclaredField("mailbox")
            .also { it.isAccessible = true }
            .get(actor) as Channel<Any>
        val messageClass = Class.forName(
            "app.momoding.core.transport.WssActor\$ActorMessage\$AttentionWake",
        )
        val constructor = messageClass.declaredConstructors.single().also {
            it.isAccessible = true
        }
        val message = constructor.newInstance(generation, deadlineAtMillis)
        assertTrue(mailbox.trySend(message).isSuccess)
    }

    private fun taskListRequest(requestId: String) = OutboundWireRequest(
        requestId = requestId,
        kind = "task.list",
        canonicalPayload = ClientWireCodec.encodeTaskList(requestId, null),
        mutating = false,
    )

    private fun taskCreateRequest(requestId: String) = OutboundWireRequest(
        requestId = requestId,
        commandId = CREATE_COMMAND_ID,
        kind = "task.create",
        canonicalPayload = ClientWireCodec.encodeTaskCreate(
            requestId,
            CREATE_COMMAND_ID,
            "attention-draft",
            "Integration task",
        ),
        mutating = true,
    )

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Attention integration",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "PENDING",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 1,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = NOW,
    )

    companion object {
        private val JSON = Json { explicitNulls = true }
        private val NOW = Instant.parse("2026-07-17T05:00:00.000Z").toEpochMilli()
        private const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        private const val CALL_ID = "22222222-2222-4222-8222-222222222222"
        private const val REQUEST_ID = "33333333-3333-4333-8333-333333333333"
        private const val CREATE_REQUEST_ID = "34343434-3434-4434-8434-343434343434"
        private const val CREATE_COMMAND_ID = "35353535-3535-4535-8535-353535353535"
        private const val RECONCILE_REQUEST_ID = "reconcile-integration-1"
        private const val HOST_ID = "55555555-5555-4555-8555-555555555555"
        private const val CREDENTIAL_ID = "66666666-6666-4666-8666-666666666666"
        private const val DEVICE_ID = "77777777-7777-4777-8777-777777777777"
        private const val GRANT_ID = "99999999-9999-4999-8999-999999999999"
        private const val DOCUMENT_ALIAS = "doc-0123456789abcdef01234567"
        private const val STREAM_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val PI_SESSION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

        private fun kind(json: String): String = JSON.parseToJsonElement(json).jsonObject
            .getValue("kind").jsonPrimitive.content

        private fun requestId(json: String): String = JSON.parseToJsonElement(json).jsonObject
            .getValue("requestId").jsonPrimitive.content

        private fun helloAccepted(requestId: String): String =
            """{"protocolVersion":1,"kind":"hello.accepted","requestId":"$requestId","connectionId":"88888888-8888-4888-8888-888888888888","serverVersion":"0.1.0","piVersion":"0.80.6","heartbeatIntervalMs":20000,"maxFrameBytes":1048576}"""

        private fun emptySuccess(requestId: String): String =
            """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true}"""

        private fun taskListSuccess(requestId: String): String =
            """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":{"tasks":[],"listRevision":1}}"""

        private fun taskCreateSuccess(requestId: String): String =
            """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":{"taskId":"$TASK_ID"}}"""

        private fun failureResponse(requestId: String): String =
            """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":false,"error":{"code":"BAD_REQUEST","message":"expired","retryable":false}}"""

        private fun snapshotSuccess(requestId: String): String =
            """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":{"snapshotVersion":1}}"""

        private fun taskSnapshot(requestId: String, callId: String): String =
            """{"kind":"task.snapshot","requestId":"$requestId","taskId":"$TASK_ID","snapshotVersion":1,"recoveryState":"normal","runState":"waiting","piSessionId":"$PI_SESSION_ID","pi":{"messages":[],"isStreaming":false,"queue":[]},"pendingAttention":[],"deviceCalls":[{"callId":"$callId","state":"terminal"}],"cursor":{"streamId":"$STREAM_ID","highWatermarkSequence":0,"oldestReplayableSequence":1}}"""

        private fun deviceRequest(callId: String): String =
            """{"protocolVersion":1,"kind":"device.tool.request","callId":"$callId","taskId":"$TASK_ID","piToolCallId":"pi-$callId","deviceId":"$DEVICE_ID","toolName":"request_user_question","arguments":{"question":"Choose an approach","options":[{"label":"Balanced"},{"label":"Fast"}]},"sideEffect":false,"expiresAt":"2026-07-17T05:15:00.000Z","capabilityVersion":1}"""

        private fun metadataRequest(callId: String): String =
            """{"protocolVersion":1,"kind":"device.tool.request","callId":"$callId","taskId":"$TASK_ID","piToolCallId":"pi-$callId","deviceId":"$DEVICE_ID","toolName":"device_capabilities_get","arguments":{},"sideEffect":false,"expiresAt":"2026-07-17T05:15:00.000Z","capabilityVersion":1}"""

        private fun contentReadRequest(callId: String): String =
            """{"protocolVersion":1,"kind":"device.tool.request","callId":"$callId","taskId":"$TASK_ID","piToolCallId":"pi-$callId","deviceId":"$DEVICE_ID","toolName":"device_files_read","arguments":{"grantId":"$GRANT_ID","purpose":"Read project instructions","documents":[{"alias":"$DOCUMENT_ALIAS","expectedMimeType":"text/plain","maxBytes":32}],"totalMaxBytes":32},"sideEffect":false,"expiresAt":"2026-07-17T05:15:00.000Z","capabilityVersion":1}"""

        private fun deviceCancel(callId: String): String =
            """{"protocolVersion":1,"kind":"device.tool.cancel","callId":"$callId","taskId":"$TASK_ID","reason":"tool_abort"}"""

        private fun reconcileRequest(requestId: String, callId: String): String =
            """{"protocolVersion":1,"kind":"device.tool.reconcile.request","requestId":"$requestId","taskId":"$TASK_ID","deviceId":"$DEVICE_ID","calls":[{"callId":"$callId","toolName":"request_user_question","lastKnownState":"running"}]}"""

        private fun piEvent(sequence: Long): String =
            """{"protocolVersion":1,"kind":"pi.event","taskId":"$TASK_ID","piSessionId":"$PI_SESSION_ID","piVersion":"0.80.6","streamId":"$STREAM_ID","sequence":$sequence,"emittedAt":"2026-07-17T05:00:00.000Z","event":{"type":"agent_start"}}"""

        private fun replayComplete(through: Long, liveFrom: Long): String =
            """{"protocolVersion":1,"kind":"pi.replay.complete","taskId":"$TASK_ID","streamId":"$STREAM_ID","replayedThroughSequence":$through,"liveFromSequence":$liveFrom}"""

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
                clientInstanceId = "99999999-9999-4999-8999-999999999999",
                deviceId = DEVICE_ID,
                deviceName = "Android",
            )
        }

        private suspend fun expectFailure(deferred: Deferred<*>) {
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
