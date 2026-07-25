package app.momoding.core.auth

import app.momoding.core.transport.PinnedHostEndpoint
import app.momoding.core.transport.SpkiPin
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PairingIdentity(
    val endpoint: String,
    val spkiPin: String,
    val clientInstanceId: String,
    val deviceId: String,
    val deviceName: String,
)

data class PairingSuccess(
    val hostId: String,
    val credentialId: String,
    val deviceCredential: String,
    val profile: HostClientProfile,
) {
    override fun toString(): String =
        "PairingSuccess(hostId=$hostId, credentialId=$credentialId, " +
            "deviceCredential=[REDACTED], profile=$profile)"
}

sealed interface ReconciledBinding {
    data object Unpaired : ReconciledBinding
    data class Pairing(val identity: PairingIdentity) : ReconciledBinding
    data class Active(val snapshot: HostBindingSnapshot, val secret: VaultSecret) : ReconciledBinding
    data class CredentialLost(val snapshot: HostBindingSnapshot) : ReconciledBinding
}

fun interface HostRoomEraser {
    suspend fun eraseAllHostData()
}

fun interface HostConnectionStopper {
    suspend fun stopAndJoin()
}

class HostBindingCoordinator(
    private val stateStore: HostStateStore,
    private val vault: CredentialVault,
    private val roomEraser: HostRoomEraser,
    private val connectionStopper: HostConnectionStopper,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    suspend fun preparePair(
        endpointValue: String,
        spkiPinValue: String,
        deviceName: String,
    ): PairingIdentity = mutex.withLock {
        val endpoint = PinnedHostEndpoint.parse(endpointValue).canonicalHttpsUrl
        val pin = SpkiPin.parse(spkiPinValue).value
        val existing = stateStore.read()
        if (existing != null) {
            require(
                existing.status == HostBindingStatus.PAIRING &&
                    existing.endpoint == endpoint && existing.spkiPin == pin,
            ) { "A Host binding is already present" }
            return@withLock existing.toPairingIdentity()
        }
        require(!vault.exists()) { "A credential vault already exists" }
        val canonicalDeviceName = DeviceNamePolicy.canonicalize(deviceName)
        HostBindingSnapshot(
            status = HostBindingStatus.PAIRING,
            endpoint = endpoint,
            spkiPin = pin,
            clientInstanceId = uuid(),
            deviceId = uuid(),
            deviceName = canonicalDeviceName,
            updatedAtMillis = nowMillis(),
        ).also { stateStore.write(it) }.toPairingIdentity()
    }

    suspend fun commitPair(identity: PairingIdentity, success: PairingSuccess): ReconciledBinding.Active =
        mutex.withLock {
            val current = requireNotNull(stateStore.read()) { "Pairing state is missing" }
            require(current.status == HostBindingStatus.PAIRING && current.toPairingIdentity() == identity) {
                "Pairing identity changed"
            }
            val header = VaultHeader(
                endpoint = identity.endpoint,
                spkiPin = identity.spkiPin,
                hostId = success.hostId,
                credentialId = success.credentialId,
            )
            val secret = VaultSecret(
                header = header,
                deviceCredential = success.deviceCredential,
                clientInstanceId = identity.clientInstanceId,
                deviceId = identity.deviceId,
                deviceName = identity.deviceName,
            ).also(VaultEnvelopeCodec::validateSecret)
            require(success.profile.configRevision > 0) { "Client profile revision is invalid" }

            vault.store(secret)
            val active = current.copy(
                status = HostBindingStatus.ACTIVE,
                hostId = success.hostId,
                credentialId = success.credentialId,
                profile = success.profile,
                updatedAtMillis = nowMillis(),
            ).validate()
            stateStore.write(active)
            ReconciledBinding.Active(active, secret)
        }

    suspend fun cancelPreparedPair(identity: PairingIdentity): Boolean = mutex.withLock {
        val current = stateStore.read() ?: return@withLock false
        if (current.status != HostBindingStatus.PAIRING || current.toPairingIdentity() != identity) {
            return@withLock false
        }
        require(!vault.exists()) { "Prepared pairing already published a credential" }
        stateStore.clear()
        true
    }

    suspend fun reconcileStartup(): ReconciledBinding = mutex.withLock {
        val current = stateStore.read()
        if (current?.status == HostBindingStatus.UNPAIRING) {
            finishUnpairLocked()
            return@withLock ReconciledBinding.Unpaired
        }
        if (current?.status == HostBindingStatus.CREDENTIAL_LOST) {
            return@withLock ReconciledBinding.CredentialLost(current)
        }
        val secret = try {
            vault.load()
        } catch (error: Exception) {
            if (current == null) {
                throw IllegalStateException("An unbound credential vault requires recovery", error)
            }
            if (current.status == HostBindingStatus.PAIRING) {
                return@withLock ReconciledBinding.Pairing(current.toPairingIdentity())
            }
            val lost = current.copy(
                status = HostBindingStatus.CREDENTIAL_LOST,
                updatedAtMillis = nowMillis(),
            ).also { stateStore.write(it) }
            return@withLock ReconciledBinding.CredentialLost(lost)
        }
        if (secret == null) {
            if (current == null) return@withLock ReconciledBinding.Unpaired
            if (current.status == HostBindingStatus.PAIRING) {
                return@withLock ReconciledBinding.Pairing(current.toPairingIdentity())
            }
            val lost = current.copy(
                status = HostBindingStatus.CREDENTIAL_LOST,
                updatedAtMillis = nowMillis(),
            ).also { stateStore.write(it) }
            return@withLock ReconciledBinding.CredentialLost(lost)
        }

        if (current == null) {
            val restored = secret.toActiveSnapshot(profile = null, updatedAtMillis = nowMillis())
            stateStore.write(restored)
            return@withLock ReconciledBinding.Active(restored, secret)
        }
        val matches = current.endpoint == secret.header.endpoint &&
            current.spkiPin == secret.header.spkiPin &&
            current.clientInstanceId == secret.clientInstanceId &&
            current.deviceId == secret.deviceId &&
            (current.hostId == null || current.hostId == secret.header.hostId) &&
            (current.credentialId == null || current.credentialId == secret.header.credentialId)
        if (!matches) {
            val lostBase = if (current.status == HostBindingStatus.PAIRING) {
                secret.toActiveSnapshot(profile = null, updatedAtMillis = nowMillis())
            } else {
                current
            }
            val lost = lostBase.copy(
                status = HostBindingStatus.CREDENTIAL_LOST,
                updatedAtMillis = nowMillis(),
            ).also { stateStore.write(it) }
            return@withLock ReconciledBinding.CredentialLost(lost)
        }
        val active = current.copy(
            status = HostBindingStatus.ACTIVE,
            hostId = secret.header.hostId,
            credentialId = secret.header.credentialId,
            updatedAtMillis = nowMillis(),
        ).also { stateStore.write(it) }
        ReconciledBinding.Active(active, secret)
    }

    suspend fun unpair() = mutex.withLock {
        val current = stateStore.read()
        if (current == null) {
            vault.deleteFile()
            vault.deleteKey()
            return@withLock
        }
        if (current.status != HostBindingStatus.UNPAIRING) {
            require(current.status != HostBindingStatus.PAIRING) { "Cancel pairing before unpairing" }
            stateStore.write(
                current.copy(
                    status = HostBindingStatus.UNPAIRING,
                    updatedAtMillis = nowMillis(),
                ),
            )
        }
        finishUnpairLocked()
    }

    private suspend fun finishUnpairLocked() {
        connectionStopper.stopAndJoin()
        roomEraser.eraseAllHostData()
        vault.deleteFile()
        vault.deleteKey()
        stateStore.clear()
    }

    private fun HostBindingSnapshot.toPairingIdentity(): PairingIdentity = PairingIdentity(
        endpoint = endpoint,
        spkiPin = spkiPin,
        clientInstanceId = clientInstanceId,
        deviceId = deviceId,
        deviceName = deviceName,
    )

    private fun VaultSecret.toActiveSnapshot(
        profile: HostClientProfile?,
        updatedAtMillis: Long,
    ): HostBindingSnapshot = HostBindingSnapshot(
        status = HostBindingStatus.ACTIVE,
        endpoint = header.endpoint,
        spkiPin = header.spkiPin,
        clientInstanceId = clientInstanceId,
        deviceId = deviceId,
        deviceName = deviceName,
        hostId = header.hostId,
        credentialId = header.credentialId,
        profile = profile,
        updatedAtMillis = updatedAtMillis,
    ).validate()
}
