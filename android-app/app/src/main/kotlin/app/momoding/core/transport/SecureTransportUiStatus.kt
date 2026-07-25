package app.momoding.core.transport

import app.momoding.core.auth.HostClientProfile

enum class SecureTransportUiPhase {
    STARTING,
    UNPAIRED,
    PAIRING,
    CONNECTING,
    AUTHENTICATING,
    SYNCHRONIZING,
    READY,
    RECONNECTING,
    OFFLINE,
    VERSION_MISMATCH,
    HOST_REVOKED,
    CREDENTIAL_LOST,
    ERROR,
}

data class SecureTransportUiStatus(
    val phase: SecureTransportUiPhase = SecureTransportUiPhase.STARTING,
    val hostAlias: String? = null,
    val profile: HostClientProfile? = null,
    val configRevision: Long? = null,
    val pairingRecovery: Boolean = false,
)
