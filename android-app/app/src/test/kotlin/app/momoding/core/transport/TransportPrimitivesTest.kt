package app.momoding.core.transport

import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPrimitivesTest {
    @Test
    fun helloIsDeviceCredentialOnlyAndNeverStringifiesSecretObjects() {
        val secret = secret()
        val hello = ClientWireCodec.encodeHello(
            ClientHelloPayload(
                requestId = REQUEST_ID,
                clientVersion = "0.1.0-dev",
                secret = secret,
                resume = listOf(DurableResumeCursor(TASK_ID, STREAM_ID, 42)),
            ),
        )

        assertTrue(hello.contains("\"scheme\":\"device-credential\""))
        assertTrue(hello.contains("\"lastAckedSequence\":42"))
        assertFalse(hello.contains("dev-token"))
        assertFalse(secret.toString().contains(secret.deviceCredential))
        assertThrows(IllegalArgumentException::class.java) {
            ClientWireCodec.encodeHello(
                ClientHelloPayload(
                    REQUEST_ID,
                    "0.1.0",
                    secret,
                    List(129) { DurableResumeCursor(uuidFor(it), STREAM_ID, 0) },
                ),
            )
        }
    }

    @Test
    fun reconnectCapsAndFullJitterAreExactForAllEightAttempts() {
        val zero = FullJitterReconnectPolicy { 0.0 }
        val almostOne = FullJitterReconnectPolicy { Math.nextDown(1.0) }
        val caps = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)

        caps.forEachIndexed { index, cap ->
            assertEquals(0, zero.delayMillis(index + 1))
            assertEquals(cap, almostOne.delayMillis(index + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { zero.delayMillis(0) }
        assertThrows(IllegalArgumentException::class.java) { zero.delayMillis(9) }
    }

    @Test
    fun snapshotRequestEncoderKeepsExactReadOnlyShapeAndSafeVersionBound() {
        assertEquals(
            """{"protocolVersion":1,"kind":"task.snapshot.request","requestId":"$REQUEST_ID","taskId":"$TASK_ID"}""",
            ClientWireCodec.encodeTaskSnapshotRequest(REQUEST_ID, TASK_ID),
        )
        assertEquals(
            """{"protocolVersion":1,"kind":"task.snapshot.request","requestId":"$REQUEST_ID","taskId":"$TASK_ID","knownSnapshotVersion":7}""",
            ClientWireCodec.encodeTaskSnapshotRequest(REQUEST_ID, TASK_ID, 7),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ClientWireCodec.encodeTaskSnapshotRequest(REQUEST_ID, TASK_ID, -1)
        }
    }

    @Test
    fun requestTableFencesOldGenerationLateUnknownAndExpiredTombstones() {
        var now = 1_000L
        val table = RequestTable { now }
        val pending = request(1, deadline = 5_000)
        table.register(pending)

        assertEquals(ResponseLookup.StaleGeneration, table.lookup(REQUEST_ID, 2))
        assertTrue(table.lookup(REQUEST_ID, 1) is ResponseLookup.Pending)
        table.complete(REQUEST_ID)
        assertEquals(ResponseLookup.Tombstoned, table.lookup(REQUEST_ID, 1))
        assertEquals(ResponseLookup.Unknown, table.lookup(OTHER_REQUEST_ID, 1))

        now += RequestTable.TOMBSTONE_TTL_MILLIS
        assertEquals(ResponseLookup.Unknown, table.lookup(REQUEST_ID, 1))
    }

    @Test
    fun durableAttentionReplayCanReplaceOnlyAnExistingMutatingResponseTombstone() {
        val table = RequestTable { 1_000 }
        val first = request(
            generation = 1,
            deadline = 5_000,
            mutating = true,
            kind = "device.tool.reconcile.result",
        )
        table.register(first)
        table.complete(REQUEST_ID)
        val replay = request(
            generation = 2,
            deadline = 6_000,
            mutating = true,
            kind = "device.tool.reconcile.result",
        )

        table.registerDurableReconcileReplay(replay)

        val lookup = table.lookup(REQUEST_ID, 2)
        assertTrue(lookup is ResponseLookup.Pending)
        assertEquals(replay, (lookup as ResponseLookup.Pending).request)
        assertThrows(IllegalArgumentException::class.java) {
            table.registerDurableReconcileReplay(request(
                2,
                OTHER_REQUEST_ID,
                6_000,
                true,
                "device.tool.reconcile.result",
            ))
        }
        val generic = RequestTable { 1_000 }
        val genericRequest = request(1, deadline = 5_000)
        generic.register(genericRequest)
        generic.complete(REQUEST_ID)
        assertThrows(IllegalArgumentException::class.java) {
            generic.registerDurableReconcileReplay(request(
                generation = 2,
                deadline = 6_000,
                mutating = true,
                kind = "device.tool.reconcile.result",
                commandId = "22222222-2222-4222-8222-222222222222",
                taskId = TASK_ID,
            ))
        }
        assertFalse(generic.hasCompatibleReconcileProvenance(REQUEST_ID))
    }

    @Test
    fun attentionWakeIdentityRejectsOldGenerationAndSameGenerationOldDeadline() {
        val current = AttentionWakeIdentity(generation = 7, deadlineAtMillis = 2_000)

        assertTrue(current.matches(7, 2_000))
        assertFalse(current.matches(6, 2_000))
        assertFalse(current.matches(7, 1_000))
    }

    @Test
    fun requestTableIsBoundedAndReconnectUpdatesGenerationWithoutChangingPayload() {
        val table = RequestTable { 1_000 }
        repeat(RequestTable.MAX_PENDING) { index ->
            val id = uuidFor(index)
            table.register(request(1, requestId = id, deadline = 5_000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            table.register(request(1, requestId = OTHER_REQUEST_ID, deadline = 5_000))
        }

        val resent = table.updateGenerationAndPendingPayloads(9)

        assertEquals(RequestTable.MAX_PENDING, resent.size)
        assertTrue(resent.all { it.generation == 9L && it.canonicalPayload.contains(it.requestId) })
    }

    private fun request(
        generation: Long,
        requestId: String = REQUEST_ID,
        deadline: Long,
        mutating: Boolean = false,
        kind: String = "task.list",
        commandId: String? = null,
        taskId: String? = null,
    ): PendingWireRequest = PendingWireRequest(
        requestId = requestId,
        kind = kind,
        canonicalPayload = "{\"requestId\":\"$requestId\"}",
        mutating = mutating,
        commandId = commandId,
        taskId = taskId,
        deadlineAtMillis = deadline,
        completion = CompletableDeferred(),
        generation = generation,
    )

    private fun secret(): VaultSecret {
        val credentialSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
        return VaultSecret(
            header = VaultHeader(
                endpoint = "https://host.example:8443",
                spkiPin = "sha256/${Base64.getEncoder().encodeToString(ByteArray(32) { 4 })}",
                hostId = HOST_ID,
                credentialId = CREDENTIAL_ID,
            ),
            deviceCredential = "cm1.$CREDENTIAL_ID.$credentialSecret",
            clientInstanceId = CLIENT_ID,
            deviceId = DEVICE_ID,
            deviceName = "Android",
        )
    }

    companion object {
        private const val REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val OTHER_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        private const val CREDENTIAL_ID = "22222222-2222-4222-8222-222222222222"
        private const val CLIENT_ID = "33333333-3333-4333-8333-333333333333"
        private const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        private const val TASK_ID = "55555555-5555-4555-8555-555555555555"
        private const val STREAM_ID = "66666666-6666-4666-8666-666666666666"

        private fun uuidFor(index: Int): String =
            "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}"
    }
}
