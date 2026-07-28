package app.momoding.core.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.DeviceHostCallState
import app.momoding.wire.DeviceToolCancelFrame
import app.momoding.wire.DeviceToolCancelReason
import app.momoding.wire.DeviceToolReconcileCall
import app.momoding.wire.DeviceToolReconcileRequestFrame
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.ReceivedP1bServerFrame
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.JournalConflictException
import app.momoding.core.data.MIGRATION_1_2
import app.momoding.core.data.MIGRATION_2_3
import app.momoding.core.data.MIGRATION_3_4
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.data.TaskContentGrantEntity
import app.momoding.core.data.TaskEntity
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttentionApplicationCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var ledger: RoomAttentionLedger
    private lateinit var journal: RoomCommandDraftJournal
    private lateinit var coordinator: AttentionApplicationCoordinator
    private var now = NOW
    private lateinit var ids: ArrayDeque<String>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = Room.databaseBuilder(context, MomodingDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        database.p2Dao().upsertTask(task())
        ledger = RoomAttentionLedger(database) { now }
        journal = RoomCommandDraftJournal(database, nowMillis = { now })
        ids = ArrayDeque((100..180).map(::uuid))
        coordinator = AttentionApplicationCoordinator(
            ledger = ledger,
            journal = journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `generation persists capability before exact success opens device gate`() {
        val entering = coordinator.beginGeneration(1, DEVICE_ID)
        assertFalse(coordinator.isCapabilityReady)
        assertEquals(1, entering.commands.size)
        val capability = entering.commands.single()
        assertEquals(AttentionCommandPurpose.CAPABILITY, capability.purpose)
        assertTrue(capability.register)
        assertEquals("device.capabilities.report", capability.request.kind)
        val payload = JSON.parseToJsonElement(capability.request.canonicalPayload).jsonObject
        assertEquals(DEVICE_ID, payload.getValue("deviceId").jsonPrimitive.content)
        assertEquals(
            Instant.ofEpochMilli(NOW + 14 * 60_000L).toString(),
            payload.getValue("expiresAt").jsonPrimitive.content,
        )
        assertEquals(
            "device_capabilities_get",
            payload.getValue("manifest").jsonObject
                .getValue("tools").jsonArray[0].jsonPrimitive.content,
        )

        persist(capability)
        val accepted = complete(capability)
        assertTrue(coordinator.isCapabilityReady)
        assertEquals(true, accepted.capabilityReady)
        assertTrue(requireNotNull(accepted.nextWakeAtMillis) > now)

        val early = coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        assertEquals(1, early.oneWayFrames.size)
        assertEquals("device.tool.progress", early.oneWayFrames.single().kind)
        assertEquals(AttentionLedgerState.AWAITING_USER.name, ledger.record(CALL_ID)!!.operation.ledgerState)
    }

    @Test
    fun `duplicate request announces once and option decision becomes exact terminal after grace`() {
        ready()
        val first = coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        val duplicate = coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        assertEquals(1, first.oneWayFrames.size)
        assertTrue(duplicate.oneWayFrames.isEmpty())

        val decision = coordinator.submitDecision(AttentionUserDecision.Option(CALL_ID, 1))
        assertTrue(decision.terminalCandidates.isEmpty())
        val stored = ledger.record(CALL_ID)!!
        assertTrue(requireNotNull(stored.operation.terminalFrameCanonicalJson)
            .contains("Fast approach"))
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name, stored.operation.deliveryState)

        now += 1_000
        val wake = coordinator.onWake()
        assertEquals(listOf(CALL_ID), wake.terminalCandidates.map { it.callId })
        coordinator.markTerminalSent(CALL_ID)
        assertEquals(
            AttentionDeliveryState.SENT_UNCONFIRMED.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
    }

    @Test
    fun `remote delivery only drains its device when phone local terminal shares the database`() = runBlocking {
        ready()
        val remoteCallId = uuid(181)
        coordinator.handleDeviceRequest(questionFrame(remoteCallId))
        coordinator.submitDecision(AttentionUserDecision.Option(remoteCallId, 0))

        val localCallId = uuid(182)
        val localBridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { now },
            idFactory = { localCallId },
        )
        localBridge.accept(
            TASK_ID,
            PiNativeToolRequest(
                id = "native-local-coexistence",
                kind = "android_attention",
                toolCallId = "pi-local-coexistence",
                toolName = "request_user_question",
                arguments = buildJsonObject { put("question", "Keep this local?") },
            ),
        )
        localBridge.submitDecision(AttentionUserDecision.Skip(localCallId))

        now += 1_000
        val remoteWake = coordinator.onWake()
        assertEquals(listOf(remoteCallId), remoteWake.terminalCandidates.map { it.callId })
        assertEquals(
            listOf(remoteCallId),
            ledger.terminalOperationsReadyForDelivery(DEVICE_ID).map { it.callId },
        )
        assertEquals(
            listOf(localCallId),
            ledger.terminalOperationsReadyForDelivery("phone-local-android").map { it.callId },
        )
    }

    @Test
    fun `content read is actionable only through explicit allow or deny decisions`() = runBlocking {
        val contentCoordinator = AttentionApplicationCoordinator(
            ledger = ledger,
            journal = journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
            contentReadHandler = {
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
        ready(contentCoordinator)

        val allowed = uuid(30)
        val accepted = contentCoordinator.handleDeviceRequest(contentReadFrame(allowed))
        assertEquals("device.tool.progress", accepted.oneWayFrames.single().kind)
        assertTrue(accepted.oneWayFrames.single().canonicalPayload.contains("file content approval"))
        database.p2Dao().insertTaskContentGrant(
            TaskContentGrantEntity(
                callId = allowed,
                taskId = TASK_ID,
                grantId = GRANT_ID,
                documentAliasesJson = """["$DOCUMENT_ALIAS"]""",
                mimeTypesJson = """["text/plain"]""",
                perFileByteBudgetsJson = "[32]",
                totalByteBudget = 32,
                consumedBytes = 5,
                expiresAtMillis = now + 60_000,
                revokedAtMillis = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        val allowedPlan = contentCoordinator.submitContentReadDecision(
            AttentionUserDecision.AllowContentRead(allowed),
        )
        val allowedTerminal = allowedPlan.oneWayFrames.single()
        assertTrue(allowedTerminal.discardLiveContentAfterAttempt)
        assertTrue(allowedTerminal.canonicalPayload.contains("\"content\":\"hello\""))
        assertTrue(allowedTerminal.canonicalPayload.contains("\"terminal\":\"succeeded\""))
        val beforeDiscard = requireNotNull(ledger.record(allowed))
        assertNull(beforeDiscard.operation.terminalFrameCanonicalJson)
        assertNull(beforeDiscard.operation.terminalSha256)
        assertNull(beforeDiscard.attention.terminalDisplayJson)
        contentCoordinator.discardLiveContentRead(TASK_ID, allowed)
        assertNull(ledger.record(allowed))
        assertNull(database.p2Dao().taskContentGrant(allowed))
        assertTrue(ledger.terminalOperationsReadyForDelivery(DEVICE_ID).none { it.callId == allowed })

        val denied = uuid(31)
        contentCoordinator.handleDeviceRequest(contentReadFrame(denied))
        contentCoordinator.submitContentReadDecision(
            AttentionUserDecision.DenyContentRead(denied),
        )
        val deniedTerminal = requireNotNull(ledger.record(denied)?.operation?.terminalFrameCanonicalJson)
        assertTrue(deniedTerminal.contains("\"terminal\":\"rejected\""))
        assertTrue(deniedTerminal.contains("\"code\":\"CONTENT_READ_DECLINED\""))

        val expired = uuid(32)
        contentCoordinator.handleDeviceRequest(
            contentReadFrame(
                callId = expired,
                expiresAt = Instant.ofEpochMilli(now + 1).toString(),
            ),
        )
        now += 2
        contentCoordinator.onWake()
        val expiredTerminal = requireNotNull(
            ledger.record(expired)?.operation?.terminalFrameCanonicalJson,
        )
        assertTrue(expiredTerminal.contains("\"terminal\":\"timed_out\""))
        assertTrue(expiredTerminal.contains("\"code\":\"CONTENT_READ_EXPIRED\""))
    }

    @Test
    fun `reconcile is deterministic bounded and maps local durable states`() {
        ready()
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        coordinator.submitDecision(AttentionUserDecision.Skip(CALL_ID))
        val unknownCall = uuid(22)
        val raw = reconcileRaw(
            requestId = RECONCILE_REQUEST_ID,
            calls = listOf(
                DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                ),
                DeviceToolReconcileCall(
                    unknownCall,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.SENT,
                ),
            ),
        )
        val first = coordinator.handleReconcileRequest(raw).commands.single()
        val firstPayload = JSON.parseToJsonElement(first.request.canonicalPayload).jsonObject
        val results = firstPayload.getValue("results").jsonArray
        assertEquals("succeeded", results[0].jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("never_started", results[1].jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(RECONCILE_REQUEST_ID, first.request.requestId)
        assertEquals(8, UUID.fromString(requireNotNull(first.request.commandId)).version())

        val secondCoordinator = AttentionApplicationCoordinator(
            ledger,
            journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )
        ready(secondCoordinator, generation = 2)
        val second = secondCoordinator.handleReconcileRequest(raw).commands.single()
        assertEquals(first.request.commandId, second.request.commandId)

        val changed = reconcileRaw(
            requestId = RECONCILE_REQUEST_ID,
            calls = listOf(DeviceToolReconcileCall(
                unknownCall,
                toolName = "request_user_question",
                lastKnownState = DeviceHostCallState.RUNNING,
            )),
        )
        assertNotEquals(
            first.request.commandId,
            secondCoordinator.handleReconcileRequest(changed).commands.single().request.commandId,
        )
    }

    @Test
    fun `duplicate reconcile callId is rejected before journal or ledger write`() {
        ready()
        val repeated = uuid(29)
        val before = journal.pendingCommands().size
        val plan = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(
                    DeviceToolReconcileCall(
                        repeated,
                        toolName = "request_user_question",
                        lastKnownState = DeviceHostCallState.SENT,
                    ),
                    DeviceToolReconcileCall(
                        repeated,
                        toolName = "request_user_question",
                        lastKnownState = DeviceHostCallState.RUNNING,
                    ),
                ),
            ),
        )
        assertEquals("Reconcile request repeats a callId", plan.protocolRecoveryReason)
        assertEquals(before, journal.pendingCommands().size)
        assertNull(ledger.record(repeated))
    }

    @Test
    fun `expiry cancel and snapshot proof preserve local authority`() {
        ready()
        val earlierExpiry = NOW + 60_000
        coordinator.handleDeviceRequest(
            questionFrame(CALL_ID, Instant.ofEpochMilli(earlierExpiry).toString()),
        )
        coordinator.handleDeviceCancel(
            DeviceToolCancelFrame(
                protocolVersion = 1,
                kind = "device.tool.cancel",
                callId = uuid(77),
                taskId = TASK_ID,
                reason = DeviceToolCancelReason.TOOL_ABORT,
            ),
        )
        assertNull(ledger.record(uuid(77)))

        now = earlierExpiry
        val expired = coordinator.onWake()
        assertEquals(listOf(CALL_ID), expired.terminalCandidates.map { it.callId })
        assertEquals(AttentionLedgerState.TIMED_OUT.name, ledger.record(CALL_ID)!!.operation.ledgerState)

        database.p2Dao().updateDeviceOperation(
            ledger.record(CALL_ID)!!.operation.copy(hostObservationState = "terminal"),
        )
        coordinator.onProjectionCommitted(TASK_ID)
        assertEquals(
            AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
    }

    @Test
    fun `generation restart repairs a committed host terminal proof before resend`() {
        ready()
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        coordinator.submitDecision(AttentionUserDecision.Skip(CALL_ID))
        ledger.markSent(CALL_ID)
        database.p2Dao().updateDeviceOperation(
            ledger.record(CALL_ID)!!.operation.copy(hostObservationState = "terminal"),
        )
        val restarted = AttentionApplicationCoordinator(
            ledger,
            journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )

        restarted.beginGeneration(2, DEVICE_ID)

        assertEquals(
            AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
    }

    @Test
    fun `resumed capability rejection terminalizes old command then emits fresh identity`() {
        val first = coordinator.beginGeneration(1, DEVICE_ID).commands.single()
        persist(first)
        val restarted = AttentionApplicationCoordinator(
            ledger,
            journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )
        val resumed = restarted.beginGeneration(2, DEVICE_ID).commands.single()
        assertEquals(first.request.requestId, resumed.request.requestId)
        assertTrue(resumed.register)

        val failureJson = """{"protocolVersion":1,"kind":"response","requestId":"${resumed.request.requestId}","ok":false,"error":{"code":"BAD_REQUEST","message":"expired","retryable":false}}"""
        journal.markTerminal(resumed.request.requestId, failureJson)
        val response = ReceivedP1bServerFrame(
            CommandResponseFrame(
                protocolVersion = 1,
                kind = "response",
                requestId = resumed.request.requestId,
                ok = false,
                error = app.momoding.wire.WireErrorBody(
                    app.momoding.wire.WireErrorCode.BAD_REQUEST,
                    "expired",
                    false,
                ),
            ),
            failureJson.encodeToByteArray(),
        )
        val plan = restarted.onCommandResponse(pending(resumed), response)
        assertEquals(1, plan.commands.size)
        assertNotEquals(resumed.request.requestId, plan.commands.single().request.requestId)
        assertFalse(restarted.isCapabilityReady)
    }

    @Test
    fun `refresh and hard fence honor exact fourteen minute capability boundaries`() {
        ready()
        now = NOW + 12 * 60_000L - 1L
        val beforeRefresh = coordinator.onWake()
        assertTrue(beforeRefresh.commands.isEmpty())
        assertTrue(coordinator.isCapabilityReady)
        assertEquals(NOW + 12 * 60_000L, beforeRefresh.nextWakeAtMillis)

        now += 1L
        val refresh = coordinator.onWake()
        assertEquals(1, refresh.commands.size)
        assertEquals(AttentionCommandPurpose.CAPABILITY, refresh.commands.single().purpose)
        assertTrue(coordinator.isCapabilityReady)
        assertEquals(NOW + 14 * 60_000L, refresh.nextWakeAtMillis)

        now = NOW + 14 * 60_000L - 1L
        val beforeExpiry = coordinator.onWake()
        assertTrue(beforeExpiry.commands.isEmpty())
        assertTrue(coordinator.isCapabilityReady)
        assertEquals(NOW + 14 * 60_000L, beforeExpiry.nextWakeAtMillis)

        now += 1L
        val expired = coordinator.onWake()
        assertEquals(
            "Attention capability expired before refresh completed",
            expired.protocolRecoveryReason,
        )
        assertFalse(coordinator.isCapabilityReady)
    }

    @Test
    fun `successful refresh replaces the capability deadline without a past timer loop`() {
        ready()
        now = NOW + 12 * 60_000L
        val refresh = coordinator.onWake().commands.single()
        persist(refresh)

        val accepted = complete(refresh)
        assertTrue(coordinator.isCapabilityReady)
        assertEquals(now + 1_000L, accepted.nextWakeAtMillis)
        now += 1_000L
        assertEquals(NOW + 24 * 60_000L, coordinator.onWake().nextWakeAtMillis)
    }

    @Test
    fun `live refresh rejection fails closed without minting a replacement identity`() {
        ready()
        now = NOW + 12 * 60_000L
        val refresh = coordinator.onWake().commands.single()
        persist(refresh)
        val failureJson = """{"protocolVersion":1,"kind":"response","requestId":"${refresh.request.requestId}","ok":false,"error":{"code":"BAD_REQUEST","message":"rejected","retryable":false}}"""
        journal.markTerminal(refresh.request.requestId, failureJson)

        val rejected = coordinator.onCommandResponse(
            pending(refresh),
            ReceivedP1bServerFrame(
                CommandResponseFrame(
                    protocolVersion = 1,
                    kind = "response",
                    requestId = refresh.request.requestId,
                    ok = false,
                    error = app.momoding.wire.WireErrorBody(
                        app.momoding.wire.WireErrorCode.BAD_REQUEST,
                        "rejected",
                        false,
                    ),
                ),
                failureJson.encodeToByteArray(),
            ),
        )

        assertEquals("Capability report was rejected", rejected.protocolRecoveryReason)
        assertTrue(rejected.commands.isEmpty())
        assertFalse(coordinator.isCapabilityReady)
    }

    @Test
    fun `device frames before readiness or outside current binding produce zero ledger writes`() {
        assertThrows(IllegalStateException::class.java) {
            coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        }
        assertNull(ledger.record(CALL_ID))

        ready()
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.handleDeviceRequest(questionFrame(CALL_ID).copy(deviceId = "wrong-device"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.handleDeviceRequest(questionFrame(CALL_ID).copy(capabilityVersion = 2))
        }
        assertThrows(IllegalStateException::class.java) {
            coordinator.handleDeviceRequest(questionFrame(CALL_ID).copy(taskId = uuid(91)))
        }
        assertNull(ledger.record(CALL_ID))
    }

    @Test
    fun `injected reconcile identity collisions fail closed at both durable conflict boundaries`() {
        val collisionCoordinator = AttentionApplicationCoordinator(
            ledger,
            journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
            reconcileDigest = { ByteArray(32) { 0x5a } },
        )
        ready(collisionCoordinator)
        val firstRaw = reconcileRaw(
            requestId = "collision-request-a",
            calls = listOf(DeviceToolReconcileCall(
                uuid(31),
                toolName = "request_user_question",
                lastKnownState = DeviceHostCallState.SENT,
            )),
        )
        val exact = collisionCoordinator.handleReconcileRequest(firstRaw).commands.single()
        persist(exact)
        val exactDuplicate = collisionCoordinator.handleReconcileRequest(firstRaw).commands.single()
        assertEquals(exact.request.commandId, exactDuplicate.request.commandId)
        assertEquals(exact.request.canonicalPayload, exactDuplicate.request.canonicalPayload)
        persist(exactDuplicate)

        val sameRequestChangedPayload = collisionCoordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "collision-request-a",
                calls = listOf(DeviceToolReconcileCall(
                    uuid(32),
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.RUNNING,
                )),
            ),
        ).commands.single()
        assertThrows(JournalConflictException::class.java) {
            persist(sameRequestChangedPayload)
        }

        val differentRequestSameCommand = collisionCoordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "collision-request-b",
                calls = listOf(DeviceToolReconcileCall(
                    uuid(33),
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.CREATED,
                )),
            ),
        ).commands.single()
        assertEquals(exact.request.commandId, differentRequestSameCommand.request.commandId)
        assertThrows(JournalConflictException::class.java) {
            persist(differentRequestSameCommand)
        }
    }

    @Test
    fun `reconcile success releases only terminal calls covered by that batch`() {
        ready()
        val uncoveredCall = uuid(41)
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        coordinator.handleDeviceRequest(questionFrame(uncoveredCall))
        coordinator.submitDecision(AttentionUserDecision.Skip(CALL_ID))
        coordinator.submitDecision(AttentionUserDecision.Skip(uncoveredCall))

        val reconcile = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        persist(reconcile)
        val completed = complete(reconcile)

        assertEquals(listOf(CALL_ID), completed.terminalCandidates.map { it.callId })
        assertEquals(
            AttentionDeliveryState.READY_TO_SEND.name,
            ledger.record(uncoveredCall)!!.operation.deliveryState,
        )
    }

    @Test
    fun `refresh success does not reclassify current reconcile as a stale probe`() {
        ready()
        val reconcile = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(DeviceToolReconcileCall(
                    uuid(42),
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.RUNNING,
                )),
            ),
        ).commands.single()
        persist(reconcile)

        now = NOW + 12 * 60_000L
        val refresh = coordinator.onWake().commands.single()
        persist(refresh)
        assertTrue(complete(refresh).commands.isEmpty())

        val failureJson = """{"protocolVersion":1,"kind":"response","requestId":"${reconcile.request.requestId}","ok":false,"error":{"code":"BAD_REQUEST","message":"current failure","retryable":false}}"""
        journal.markTerminal(reconcile.request.requestId, failureJson)
        val failed = coordinator.onCommandResponse(
            pending(reconcile),
            ReceivedP1bServerFrame(
                CommandResponseFrame(
                    protocolVersion = 1,
                    kind = "response",
                    requestId = reconcile.request.requestId,
                    ok = false,
                    error = app.momoding.wire.WireErrorBody(
                        app.momoding.wire.WireErrorCode.BAD_REQUEST,
                        "current failure",
                        false,
                    ),
                ),
                failureJson.encodeToByteArray(),
            ),
        )
        assertEquals("Current reconcile result was rejected", failed.protocolRecoveryReason)
    }

    @Test
    fun `capability success with data does not open the device gate`() {
        val capability = coordinator.beginGeneration(1, DEVICE_ID).commands.single()
        persist(capability)
        val responseJson = """{"protocolVersion":1,"kind":"response","requestId":"${capability.request.requestId}","ok":true,"data":{}}"""
        journal.markTerminal(capability.request.requestId, responseJson)
        val result = coordinator.onCommandResponse(
            pending(capability),
            ReceivedP1bServerFrame(
                CommandResponseFrame(
                    protocolVersion = 1,
                    kind = "response",
                    requestId = capability.request.requestId,
                    ok = true,
                    data = buildJsonObject {},
                ),
                responseJson.encodeToByteArray(),
            ),
        )

        assertEquals("Capability report was rejected", result.protocolRecoveryReason)
        assertFalse(coordinator.isCapabilityReady)
    }

    @Test
    fun `host cancel commits before reconcile and never becomes an outbound terminal`() {
        ready()
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        val cancel = DeviceToolCancelFrame(
            protocolVersion = 1,
            kind = "device.tool.cancel",
            callId = CALL_ID,
            taskId = TASK_ID,
            reason = DeviceToolCancelReason.TOOL_ABORT,
        )
        assertTrue(coordinator.handleDeviceCancel(cancel).terminalCandidates.isEmpty())
        assertEquals(AttentionLedgerState.CANCELLED.name, ledger.record(CALL_ID)!!.operation.ledgerState)
        assertEquals(
            AttentionDeliveryState.NOT_READY.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
        coordinator.handleDeviceCancel(cancel)
        assertThrows(IllegalStateException::class.java) {
            coordinator.handleDeviceCancel(cancel.copy(reason = DeviceToolCancelReason.TIMEOUT))
        }

        val reconcile = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.RUNNING,
                )),
            ),
        ).commands.single()
        val result = JSON.parseToJsonElement(reconcile.request.canonicalPayload).jsonObject
            .getValue("results").jsonArray.single().jsonObject
        assertEquals("cancelled", result.getValue("state").jsonPrimitive.content)
        assertEquals("ATTENTION_CANCELLED", result.getValue("error").jsonObject
            .getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `capability response cannot open the gate before exact response durability`() {
        val capability = coordinator.beginGeneration(1, DEVICE_ID).commands.single()
        persist(capability)
        val responseJson = successResponseJson(capability.request.requestId)
        val acceptedOnly = coordinator.onCommandResponse(
            pending(capability),
            ReceivedP1bServerFrame(
                CommandResponseFrame(1, "response", capability.request.requestId, true),
                responseJson.encodeToByteArray(),
            ),
        )
        assertEquals(
            "Device command response is not exact durable truth",
            acceptedOnly.protocolRecoveryReason,
        )
        assertFalse(coordinator.isCapabilityReady)

        val failureJson = """{"protocolVersion":1,"kind":"response","requestId":"${capability.request.requestId}","ok":false,"error":{"code":"BAD_REQUEST","message":"rejected","retryable":false}}"""
        journal.markTerminal(capability.request.requestId, failureJson)
        val typedRawMismatch = coordinator.onCommandResponse(
            pending(capability),
            ReceivedP1bServerFrame(
                CommandResponseFrame(1, "response", capability.request.requestId, true),
                failureJson.encodeToByteArray(),
            ),
        )
        assertEquals(
            "Device command response is not exact durable truth",
            typedRawMismatch.protocolRecoveryReason,
        )
        assertFalse(coordinator.isCapabilityReady)
    }

    @Test
    fun `reconcile response cannot drain before durability or from mismatched raw bytes`() {
        ready()
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        coordinator.submitDecision(AttentionUserDecision.Skip(CALL_ID))
        val reconcile = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        persist(reconcile)
        val responseJson = successResponseJson(reconcile.request.requestId)
        val acceptedOnly = coordinator.onCommandResponse(
            pending(reconcile),
            ReceivedP1bServerFrame(
                CommandResponseFrame(1, "response", reconcile.request.requestId, true),
                responseJson.encodeToByteArray(),
            ),
        )
        assertTrue(acceptedOnly.terminalCandidates.isEmpty())
        assertEquals(
            "Device command response is not exact durable truth",
            acceptedOnly.protocolRecoveryReason,
        )

        val restarted = AttentionApplicationCoordinator(
            ledger,
            journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )
        val capability = restarted.beginGeneration(2, DEVICE_ID).commands.single()
        persist(capability)
        complete(capability, restarted)
        journal.markTerminal(reconcile.request.requestId, responseJson)
        val rawMismatch = restarted.onCommandResponse(
            pending(reconcile),
            ReceivedP1bServerFrame(
                CommandResponseFrame(1, "response", reconcile.request.requestId, true),
                "$responseJson ".encodeToByteArray(),
            ),
        )
        assertTrue(rawMismatch.terminalCandidates.isEmpty())
        assertEquals(
            "Device command response is not exact durable truth",
            rawMismatch.protocolRecoveryReason,
        )
    }

    @Test
    fun `unknown or wrong reconcile task fails before durable reply`() {
        ready()
        val unknownTask = uuid(91)
        val before = journal.pendingCommands().size
        val unknown = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "unknown-task-reconcile",
                taskId = unknownTask,
                calls = listOf(DeviceToolReconcileCall(
                    uuid(92),
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.CREATED,
                )),
            ),
        )
        assertEquals("Reconcile task binding is not durable", unknown.protocolRecoveryReason)
        assertTrue(unknown.commands.isEmpty())
        assertEquals(before, journal.pendingCommands().size)

        val restarted = AttentionApplicationCoordinator(
            ledger,
            journal,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )
        ready(restarted, generation = 2)
        restarted.handleDeviceRequest(questionFrame(CALL_ID))
        database.p2Dao().upsertTask(task(unknownTask))
        val wrong = restarted.handleReconcileRequest(
            reconcileRaw(
                requestId = "wrong-task-reconcile",
                taskId = unknownTask,
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.RUNNING,
                )),
            ),
        )
        assertEquals("Reconcile task binding is not durable", wrong.protocolRecoveryReason)
        assertTrue(wrong.commands.isEmpty())
        assertEquals(before, journal.pendingCommands().size)
    }

    @Test
    fun `stale non-empty success terminalizes probe without releasing covered terminal`() {
        ready()
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        coordinator.submitDecision(AttentionUserDecision.Skip(CALL_ID))
        val reconcile = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        persist(reconcile)

        val nextCapability = coordinator.beginGeneration(2, DEVICE_ID).commands.single()
        persist(nextCapability)
        assertEquals(
            listOf(RECONCILE_REQUEST_ID),
            complete(nextCapability).commands.map { it.request.requestId },
        )
        val responseJson = """{"protocolVersion":1,"kind":"response","requestId":"${reconcile.request.requestId}","ok":true,"data":{}}"""
        journal.markTerminal(reconcile.request.requestId, responseJson)
        val settled = coordinator.onCommandResponse(
            pending(reconcile),
            ReceivedP1bServerFrame(
                CommandResponseFrame(
                    protocolVersion = 1,
                    kind = "response",
                    requestId = reconcile.request.requestId,
                    ok = true,
                    data = buildJsonObject {},
                ),
                responseJson.encodeToByteArray(),
            ),
        )
        assertNull(settled.protocolRecoveryReason)
        assertTrue(settled.terminalCandidates.isEmpty())
        assertEquals(
            AttentionDeliveryState.READY_TO_SEND.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
    }

    @Test
    fun `stale exact success drains its batch while stale failure only settles`() {
        ready()
        val succeededCall = uuid(45)
        val failedProbeCall = uuid(46)
        coordinator.handleDeviceRequest(questionFrame(succeededCall))
        coordinator.handleDeviceRequest(questionFrame(failedProbeCall))
        coordinator.submitDecision(AttentionUserDecision.Skip(succeededCall))
        coordinator.submitDecision(AttentionUserDecision.Skip(failedProbeCall))
        val successProbe = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "stale-success-probe",
                calls = listOf(DeviceToolReconcileCall(
                    succeededCall,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        val failedProbe = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "stale-failed-probe",
                calls = listOf(DeviceToolReconcileCall(
                    failedProbeCall,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        persist(successProbe)
        persist(failedProbe)

        val capability = coordinator.beginGeneration(2, DEVICE_ID).commands.single()
        persist(capability)
        assertEquals(2, complete(capability).commands.size)
        val failureJson = """{"protocolVersion":1,"kind":"response","requestId":"${failedProbe.request.requestId}","ok":false,"error":{"code":"BAD_REQUEST","message":"old connection","retryable":false}}"""
        journal.markTerminal(failedProbe.request.requestId, failureJson)
        val failed = coordinator.onCommandResponse(
            pending(failedProbe),
            ReceivedP1bServerFrame(
                CommandResponseFrame(
                    protocolVersion = 1,
                    kind = "response",
                    requestId = failedProbe.request.requestId,
                    ok = false,
                    error = app.momoding.wire.WireErrorBody(
                        app.momoding.wire.WireErrorCode.BAD_REQUEST,
                        "old connection",
                        false,
                    ),
                ),
                failureJson.encodeToByteArray(),
            ),
        )
        assertNull(failed.protocolRecoveryReason)
        assertTrue(failed.terminalCandidates.isEmpty())

        persist(successProbe)
        val succeeded = complete(successProbe)
        assertEquals(listOf(succeededCall), succeeded.terminalCandidates.map { it.callId })
    }

    @Test
    fun `eight item reconcile maps every local authority class in host order`() {
        ready()
        val running = uuid(51)
        val succeeded = uuid(52)
        val failedClosed = uuid(53)
        val cancelled = uuid(54)
        val timedOut = uuid(55)
        val rejected = uuid(56)
        val operationMismatch = uuid(57)
        val neverStarted = uuid(58)
        coordinator.handleDeviceRequest(questionFrame(running))
        coordinator.handleDeviceRequest(questionFrame(succeeded))
        coordinator.submitDecision(AttentionUserDecision.Skip(succeeded))
        coordinator.handleDeviceRequest(
            questionFrame(failedClosed).copy(
                toolName = "device_files_read",
                sideEffect = true,
            ),
        )
        coordinator.handleDeviceRequest(questionFrame(cancelled))
        coordinator.handleDeviceCancel(DeviceToolCancelFrame(
            protocolVersion = 1,
            kind = "device.tool.cancel",
            callId = cancelled,
            taskId = TASK_ID,
            reason = DeviceToolCancelReason.TOOL_ABORT,
        ))
        coordinator.handleDeviceRequest(
            questionFrame(timedOut, Instant.ofEpochMilli(NOW).toString()),
        )
        coordinator.handleDeviceRequest(confirmationFrame(rejected))
        coordinator.submitDecision(AttentionUserDecision.Decline(rejected))
        coordinator.handleDeviceRequest(questionFrame(operationMismatch))

        val result = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = RECONCILE_REQUEST_ID,
                calls = listOf(
                    DeviceToolReconcileCall(running, null, "request_user_question", DeviceHostCallState.RUNNING),
                    DeviceToolReconcileCall(succeeded, null, "request_user_question", DeviceHostCallState.TERMINAL),
                    DeviceToolReconcileCall(failedClosed, null, "device_files_read", DeviceHostCallState.TERMINAL),
                    DeviceToolReconcileCall(cancelled, null, "request_user_question", DeviceHostCallState.TERMINAL),
                    DeviceToolReconcileCall(timedOut, null, "request_user_question", DeviceHostCallState.TERMINAL),
                    DeviceToolReconcileCall(rejected, null, "request_user_confirmation", DeviceHostCallState.TERMINAL),
                    DeviceToolReconcileCall(operationMismatch, uuid(571), "request_user_question", DeviceHostCallState.RUNNING),
                    DeviceToolReconcileCall(neverStarted, null, "request_user_question", DeviceHostCallState.CREATED),
                ),
            ),
        ).commands.single()
        val encodedResults = JSON.parseToJsonElement(result.request.canonicalPayload).jsonObject
            .getValue("results").jsonArray
        val states = encodedResults.map {
                it.jsonObject.getValue("state").jsonPrimitive.content
            }
        assertEquals(
            listOf("running", "succeeded", "failed", "cancelled", "failed", "failed", "unknown", "never_started"),
            states,
        )
        assertEquals(
            uuid(571),
            encodedResults[6].jsonObject.getValue("operationId").jsonPrimitive.content,
        )
    }

    @Test
    fun `typed custom confirm decline and double tap derive only durable exact terminals`() {
        ready()
        val custom = uuid(61)
        val confirmed = uuid(62)
        val declined = uuid(63)
        coordinator.handleDeviceRequest(questionFrame(custom))
        coordinator.handleDeviceRequest(confirmationFrame(confirmed))
        coordinator.handleDeviceRequest(confirmationFrame(declined))

        coordinator.submitDecision(AttentionUserDecision.Custom(custom, "Use the safe path"))
        val firstCustom = ledger.record(custom)!!.operation.terminalSha256
        coordinator.submitDecision(AttentionUserDecision.Custom(custom, "changed by double tap"))
        assertEquals(firstCustom, ledger.record(custom)!!.operation.terminalSha256)
        assertTrue(ledger.record(custom)!!.operation.terminalFrameCanonicalJson!!
            .contains("Use the safe path"))
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.submitDecision(AttentionUserDecision.Option(confirmed, 0))
        }
        coordinator.submitDecision(AttentionUserDecision.Confirm(confirmed))
        coordinator.submitDecision(AttentionUserDecision.Decline(declined))
        assertTrue(ledger.record(confirmed)!!.operation.terminalFrameCanonicalJson!!
            .contains("confirmed"))
        assertEquals("rejected", ledger.record(declined)!!.operation.terminalKind)
    }

    @Test
    fun `at rest request and terminal corruption becomes unknown and blocks sender`() {
        ready()
        coordinator.handleDeviceRequest(questionFrame(CALL_ID))
        coordinator.submitDecision(AttentionUserDecision.Skip(CALL_ID))
        val original = ledger.record(CALL_ID)!!.operation
        database.p2Dao().updateDeviceOperation(
            original.copy(requestSha256 = "0".repeat(64)),
        )
        val requestCorrupt = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "request-corrupt-reconcile",
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        assertEquals(
            "unknown",
            JSON.parseToJsonElement(requestCorrupt.request.canonicalPayload).jsonObject
                .getValue("results").jsonArray.single().jsonObject
                .getValue("state").jsonPrimitive.content,
        )

        database.p2Dao().updateDeviceOperation(original.copy(terminalKind = "failed"))
        val terminalCorrupt = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "terminal-corrupt-reconcile",
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        assertEquals(
            "unknown",
            JSON.parseToJsonElement(terminalCorrupt.request.canonicalPayload).jsonObject
                .getValue("results").jsonArray.single().jsonObject
                .getValue("state").jsonPrimitive.content,
        )

        val corruptFrame = original.terminalFrameCanonicalJson!!
            .replace("\"deviceId\":\"$DEVICE_ID\"", "\"deviceId\":\"wrong-device\"")
        database.p2Dao().updateDeviceOperation(original.copy(
            terminalFrameCanonicalJson = corruptFrame,
            terminalSha256 = sha256(corruptFrame.encodeToByteArray()),
        ))
        val bindingCorrupt = coordinator.handleReconcileRequest(
            reconcileRaw(
                requestId = "binding-corrupt-reconcile",
                calls = listOf(DeviceToolReconcileCall(
                    CALL_ID,
                    toolName = "request_user_question",
                    lastKnownState = DeviceHostCallState.TERMINAL,
                )),
            ),
        ).commands.single()
        assertEquals(
            "unknown",
            JSON.parseToJsonElement(bindingCorrupt.request.canonicalPayload).jsonObject
                .getValue("results").jsonArray.single().jsonObject
                .getValue("state").jsonPrimitive.content,
        )
        now += 1_000L
        assertThrows(IllegalArgumentException::class.java) { coordinator.onWake() }
    }

    private fun ready(
        target: AttentionApplicationCoordinator = coordinator,
        generation: Long = 1,
    ) {
        val command = target.beginGeneration(generation, DEVICE_ID).commands.single()
        persist(command)
        val plan = complete(command, target)
        assertEquals(true, plan.capabilityReady)
    }

    private fun persist(command: AttentionCoordinatorCommand) {
        journal.persistAccepted(
            command.request.requestId,
            command.request.commandId,
            command.request.kind,
            command.request.taskId,
            command.request.canonicalPayload,
        )
    }

    private fun complete(
        command: AttentionCoordinatorCommand,
        target: AttentionApplicationCoordinator = coordinator,
    ): AttentionCoordinatorPlan {
        val json = successResponseJson(command.request.requestId)
        journal.markTerminal(command.request.requestId, json)
        return target.onCommandResponse(
            pending(command),
            ReceivedP1bServerFrame(
                CommandResponseFrame(1, "response", command.request.requestId, true),
                json.encodeToByteArray(),
            ),
        )
    }

    private fun pending(command: AttentionCoordinatorCommand) = PendingWireRequest(
        requestId = command.request.requestId,
        kind = command.request.kind,
        canonicalPayload = command.request.canonicalPayload,
        mutating = command.request.mutating,
        commandId = command.request.commandId,
        taskId = command.request.taskId,
        deadlineAtMillis = now + 15_000,
        completion = CompletableDeferred(),
        generation = 1,
    )

    private fun questionFrame(
        callId: String,
        expiresAt: String = EXPIRES_AT_TEXT,
    ) = DeviceToolRequestFrame(
        protocolVersion = 1,
        kind = "device.tool.request",
        callId = callId,
        taskId = TASK_ID,
        piToolCallId = "pi-$callId",
        deviceId = DEVICE_ID,
        toolName = "request_user_question",
        arguments = buildJsonObject {
            put("question", "Choose an approach")
            put("options", buildJsonArray {
                add(buildJsonObject { put("label", "Balanced approach") })
                add(buildJsonObject { put("label", "Fast approach") })
            })
        },
        sideEffect = false,
        operationId = null,
        expiresAt = expiresAt,
        capabilityVersion = 1,
    )

    private fun confirmationFrame(callId: String) = questionFrame(callId).copy(
        toolName = "request_user_confirmation",
        arguments = buildJsonObject {
            put("summary", "Apply this change?")
            put("details", "The action is limited to the current task.")
        },
    )

    private fun contentReadFrame(
        callId: String,
        expiresAt: String = EXPIRES_AT_TEXT,
    ) = questionFrame(callId, expiresAt).copy(
        toolName = "device_files_read",
        arguments = buildJsonObject {
            put("grantId", GRANT_ID)
            put("purpose", "Read the project instructions")
            put("documents", buildJsonArray {
                add(buildJsonObject {
                    put("alias", DOCUMENT_ALIAS)
                    put("expectedMimeType", "text/plain")
                    put("maxBytes", 32)
                })
            })
            put("totalMaxBytes", 32)
        },
    )

    private fun reconcileRaw(
        requestId: String,
        taskId: String = TASK_ID,
        calls: List<DeviceToolReconcileCall>,
    ): ReceivedP1bServerFrame {
        val frame = DeviceToolReconcileRequestFrame(
            protocolVersion = 1,
            kind = "device.tool.reconcile.request",
            requestId = requestId,
            taskId = taskId,
            deviceId = DEVICE_ID,
            calls = calls,
        )
        val raw = buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "device.tool.reconcile.request")
            put("requestId", requestId)
            put("taskId", taskId)
            put("deviceId", DEVICE_ID)
            put("calls", buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("callId", call.callId)
                        call.operationId?.let { put("operationId", it) }
                        put("toolName", call.toolName)
                        put("lastKnownState", when (call.lastKnownState) {
                            DeviceHostCallState.CREATED -> "created"
                            DeviceHostCallState.SENT -> "sent"
                            DeviceHostCallState.RUNNING -> "running"
                            DeviceHostCallState.TERMINAL -> "terminal"
                            DeviceHostCallState.RECONCILED -> "reconciled"
                        })
                    })
                }
            })
        }.toString().encodeToByteArray()
        return ReceivedP1bServerFrame(frame, raw)
    }

    private fun task(taskId: String = TASK_ID) = TaskEntity(
        taskId = taskId,
        title = "Coordinator core",
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

    private fun successResponseJson(requestId: String): String =
        """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true}"""

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private companion object {
        const val DATABASE_NAME = "p2-7d-attention-coordinator-test.db"
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CALL_ID = "22222222-2222-4222-8222-222222222221"
        const val DEVICE_ID = "android-p2-7-device"
        const val GRANT_ID = "33333333-3333-4333-8333-333333333333"
        const val DOCUMENT_ALIAS = "doc-0123456789abcdef01234567"
        const val RECONCILE_REQUEST_ID = "reconcile-request-p2-7"
        val NOW = Instant.parse("2026-07-17T01:00:00.000Z").toEpochMilli()
        val EXPIRES_AT = Instant.parse("2026-07-17T01:15:00.000Z").toEpochMilli()
        const val EXPIRES_AT_TEXT = "2026-07-17T01:15:00.000Z"
        val JSON = Json { explicitNulls = true }

        fun uuid(value: Int): String =
            "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    }
}
