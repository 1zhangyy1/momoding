package app.momoding.core.auth

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBindingCoordinatorTest {
    @Test
    fun responseLossRetryReusesDurableIdentity() = runTest {
        val fixture = Fixture()
        val first = fixture.coordinator.preparePair(ENDPOINT, PIN, "\ufeff Phone\u3000")
        val retry = fixture.coordinator.preparePair(ENDPOINT, PIN, "Phone renamed")

        assertEquals(first, retry)
        assertEquals("Phone", first.deviceName)
        assertNotEquals("Phone renamed", retry.deviceName)
        assertEquals(HostBindingStatus.PAIRING, fixture.state.snapshot?.status)
        assertNull(fixture.files.bytes)
    }

    @Test
    fun crashAfterVaultPublishBeforeActiveStateIsRecoveredFromAuthenticatedVault() = runTest {
        val fixture = Fixture()
        val identity = fixture.coordinator.preparePair(ENDPOINT, PIN, "Phone")
        fixture.state.failNextWrite = true

        expectIllegalState { fixture.coordinator.commitPair(identity, success()) }

        assertTrue(fixture.files.bytes != null)
        assertEquals(HostBindingStatus.PAIRING, fixture.state.snapshot?.status)
        val reconciled = fixture.coordinator.reconcileStartup() as ReconciledBinding.Active
        assertEquals(HostBindingStatus.ACTIVE, reconciled.snapshot.status)
        assertEquals(success().credentialId, reconciled.snapshot.credentialId)
        assertNull(reconciled.snapshot.profile)
    }

    @Test
    fun activeStateWithoutDecryptableVaultBecomesCredentialLostAndNeverConnects() = runTest {
        val fixture = Fixture()
        fixture.pair()
        fixture.files.bytes = null

        val reconciled = fixture.coordinator.reconcileStartup()

        assertTrue(reconciled is ReconciledBinding.CredentialLost)
        assertEquals(HostBindingStatus.CREDENTIAL_LOST, fixture.state.snapshot?.status)
        assertTrue(fixture.events.none { it == "connection.stop" })
    }

    @Test
    fun unpairUsesDurableFenceThenStopsAndDeletesInFrozenOrder() = runTest {
        val fixture = Fixture()
        fixture.pair()
        fixture.events.clear()

        fixture.coordinator.unpair()

        assertEquals(
            listOf(
                "state.write.UNPAIRING",
                "connection.stop",
                "room.erase",
                "vault.file.delete",
                "vault.key.delete",
                "state.clear",
            ),
            fixture.events,
        )
        assertNull(fixture.state.snapshot)
        assertNull(fixture.files.bytes)
        assertTrue(!fixture.aead.keyExists())
    }

    @Test
    fun everyUnpairCrashPointRemainsFencedAndStartupRetriesCleanup() = runTest {
        listOf("connection.stop", "room.erase", "vault.file.delete", "vault.key.delete", "state.clear")
            .forEach { faultPoint ->
                val fixture = Fixture()
                fixture.pair()
                fixture.faultPoint = faultPoint

                expectIllegalState { fixture.coordinator.unpair() }
                assertEquals(HostBindingStatus.UNPAIRING, fixture.state.snapshot?.status)

                fixture.faultPoint = null
                assertEquals(ReconciledBinding.Unpaired, fixture.coordinator.reconcileStartup())
                assertNull(fixture.state.snapshot)
                assertNull(fixture.files.bytes)
                assertTrue(!fixture.aead.keyExists())
            }
    }

    @Test
    fun datastoreReadCorruptionPropagatesWithoutNetworkOrDestructiveRecovery() = runTest {
        val fixture = Fixture()
        fixture.pair()
        val durable = requireNotNull(fixture.files.bytes).copyOf()
        fixture.state.failReads = true

        expectIllegalState { fixture.coordinator.reconcileStartup() }

        assertTrue(requireNotNull(fixture.files.bytes).contentEquals(durable))
        assertTrue(fixture.events.none { it == "connection.stop" || it == "room.erase" })
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val state = MemoryHostStateStore(events)
        val files = LoggingVaultFileStore(events)
        val aead = LoggingAead(events)
        var faultPoint: String? = null
        private var uuidCounter = 0
        val coordinator = HostBindingCoordinator(
            stateStore = state,
            vault = CredentialVault(aead, files),
            roomEraser = HostRoomEraser {
                hit("room.erase")
            },
            connectionStopper = HostConnectionStopper {
                hit("connection.stop")
            },
            nowMillis = { 1_000L + uuidCounter },
            uuid = {
                uuidCounter += 1
                if (uuidCounter == 1) CLIENT_ID else DEVICE_ID
            },
        )

        suspend fun pair() {
            val identity = coordinator.preparePair(ENDPOINT, PIN, "Phone")
            coordinator.commitPair(identity, success())
        }

        private fun hit(point: String) {
            events += point
            if (faultPoint == point) error("Injected $point failure")
        }

        init {
            state.fault = { point -> if (faultPoint == point) error("Injected $point failure") }
            files.fault = { point -> if (faultPoint == point) error("Injected $point failure") }
            aead.fault = { point -> if (faultPoint == point) error("Injected $point failure") }
        }
    }

    private suspend fun expectIllegalState(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        }
        assertTrue("Expected IllegalStateException but got $failure", failure is IllegalStateException)
    }

    companion object {
        private const val ENDPOINT = "https://host.example:8443"
        private const val PIN = "sha256/BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ="
        private const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        private const val CREDENTIAL_ID = "22222222-2222-4222-8222-222222222222"
        private const val CLIENT_ID = "33333333-3333-4333-8333-333333333333"
        private const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"

        private fun success(): PairingSuccess {
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
            return PairingSuccess(
                hostId = HOST_ID,
                credentialId = CREDENTIAL_ID,
                deviceCredential = "cm1.$CREDENTIAL_ID.$secret",
                profile = HostClientProfile(
                    hostAlias = "Local Host",
                    provider = "openrouter",
                    model = "model-label",
                    thinking = "default",
                    mutable = false,
                    configRevision = 1,
                ),
            )
        }
    }
}

private class MemoryHostStateStore(
    private val events: MutableList<String>,
) : HostStateStore {
    var snapshot: HostBindingSnapshot? = null
    var failReads = false
    var failNextWrite = false
    var fault: (String) -> Unit = {}

    override suspend fun read(): HostBindingSnapshot? {
        if (failReads) error("Corrupt DataStore")
        return snapshot
    }

    override suspend fun write(snapshot: HostBindingSnapshot) {
        events += "state.write.${snapshot.status}"
        if (failNextWrite) {
            failNextWrite = false
            error("Injected state write failure")
        }
        fault("state.write.${snapshot.status}")
        this.snapshot = snapshot
    }

    override suspend fun clear() {
        events += "state.clear"
        fault("state.clear")
        snapshot = null
    }
}

private class LoggingVaultFileStore(
    private val events: MutableList<String>,
) : VaultFileStore {
    var bytes: ByteArray? = null
    var fault: (String) -> Unit = {}

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun writeAtomically(bytes: ByteArray) {
        events += "vault.file.write"
        fault("vault.file.write")
        this.bytes = bytes.copyOf()
    }

    override fun delete() {
        events += "vault.file.delete"
        fault("vault.file.delete")
        bytes = null
    }
}

private class LoggingAead(
    private val events: MutableList<String>,
) : VaultAead {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private var exists = true
    var fault: (String) -> Unit = {}

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext {
        val iv = ByteArray(VaultEnvelopeCodec.IV_BYTES) { 5 }
        return AeadCiphertext(iv, crypt(Cipher.ENCRYPT_MODE, iv, aad, plaintext))
    }

    override fun decrypt(iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        crypt(Cipher.DECRYPT_MODE, iv, aad, ciphertext)

    override fun keyExists(): Boolean = exists

    override fun deleteKey() {
        events += "vault.key.delete"
        fault("vault.key.delete")
        exists = false
    }

    private fun crypt(mode: Int, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        check(exists) { "Vault key missing" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(input)
        }
    }
}
