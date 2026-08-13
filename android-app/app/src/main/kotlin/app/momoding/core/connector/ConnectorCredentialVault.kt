package app.momoding.core.connector

import android.content.Context
import app.momoding.core.auth.AndroidKeystoreAead
import app.momoding.core.auth.AndroidNoBackupVaultFileStore
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConnectorCredentialVault internal constructor(
    private val aead: VaultAead,
    private val fileStore: VaultFileStore,
) {
    private val lock = PROCESS_LOCK

    fun registration(
        definitionId: String,
        issuer: String,
        redirectUri: String,
    ): ConnectorClientRegistration? = synchronized(lock) {
        readState().registrations.firstOrNull {
            it.definitionId == definitionId && it.issuer == issuer &&
                it.redirectUri == redirectUri
        }
    }

    fun storeRegistration(registration: ConnectorClientRegistration) = synchronized(lock) {
        ConnectorOAuthPolicy.validateRegistration(registration)
        updateState { state ->
            val retained = state.registrations.filterNot {
                it.definitionId == registration.definitionId && it.issuer == registration.issuer
            }
            state.copy(registrations = bounded(retained + registration, "registrations"))
        }
    }

    fun beginAuthorization(pending: PendingConnectorAuthorization) = synchronized(lock) {
        ConnectorOAuthPolicy.validatePending(pending)
        require(pending.status == PendingConnectorAuthorizationStatus.AWAITING_BROWSER) {
            "Connector OAuth authorization must begin before callback"
        }
        updateState { state ->
            require(state.credentials.none { it.connectionId == pending.connectionId }) {
                "Connector OAuth connection already exists"
            }
            val retained = state.pending.filterNot { it.connectionId == pending.connectionId }
            state.copy(pending = bounded(retained + pending, "pending authorizations"))
        }
    }

    fun pending(connectionId: String): PendingConnectorAuthorization? = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        readState().pending.firstOrNull { it.connectionId == connectionId }
    }

    fun recordCallback(
        connectionId: String,
        stateValue: String,
        issuer: String,
        actualRedirectUri: String,
        authorizationCode: String,
        nowMillis: Long,
    ): PendingConnectorAuthorization = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        ConnectorOAuthPolicy.requireSecret(
            authorizationCode,
            ConnectorOAuthPolicy.MAX_CODE_BYTES,
            "authorization code",
        )
        var recorded: PendingConnectorAuthorization? = null
        updateState { state ->
            val current = state.pending.firstOrNull { it.connectionId == connectionId }
                ?: throw failure(
                    ConnectorOAuthFailureCode.CALLBACK_REPLAYED,
                    "This authorization callback is no longer pending",
                )
            requireMatchingCallback(
                current,
                stateValue,
                issuer,
                actualRedirectUri,
                nowMillis,
            )
            recorded = current.copy(
                authorizationCode = authorizationCode,
                status = PendingConnectorAuthorizationStatus.CALLBACK_READY,
            ).also(ConnectorOAuthPolicy::validatePending)
            state.copy(
                pending = state.pending.map {
                    if (it.connectionId == connectionId) checkNotNull(recorded) else it
                },
            )
        }
        checkNotNull(recorded)
    }

    fun cancelFromAuthorizationResponse(
        connectionId: String,
        stateValue: String,
        issuer: String,
        actualRedirectUri: String,
        nowMillis: Long,
    ) = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        updateState { state ->
            val current = state.pending.firstOrNull { it.connectionId == connectionId }
                ?: throw failure(
                    ConnectorOAuthFailureCode.CALLBACK_REPLAYED,
                    "This authorization callback is no longer pending",
                )
            requireMatchingCallback(
                current,
                stateValue,
                issuer,
                actualRedirectUri,
                nowMillis,
            )
            state.copy(pending = state.pending.filterNot { it.connectionId == connectionId })
        }
    }

    fun claimCallback(
        connectionId: String,
        nowMillis: Long,
    ): PendingConnectorAuthorization = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        var claimed: PendingConnectorAuthorization? = null
        updateState { state ->
            val current = state.pending.firstOrNull { it.connectionId == connectionId }
                ?: throw failure(
                    ConnectorOAuthFailureCode.CALLBACK_REPLAYED,
                    "This authorization callback is no longer pending",
                )
            when (current.status) {
                PendingConnectorAuthorizationStatus.CALLBACK_READY -> Unit
                PendingConnectorAuthorizationStatus.EXCHANGING,
                PendingConnectorAuthorizationStatus.OUTCOME_UNKNOWN,
                -> throw failure(
                    ConnectorOAuthFailureCode.OUTCOME_UNKNOWN,
                    "The authorization exchange cannot be safely replayed",
                )
                PendingConnectorAuthorizationStatus.AWAITING_BROWSER -> throw failure(
                    ConnectorOAuthFailureCode.CALLBACK_REJECTED,
                    "The authorization callback has not completed",
                )
            }
            if (nowMillis >= current.expiresAtMillis) {
                throw failure(
                    ConnectorOAuthFailureCode.AUTHORIZATION_EXPIRED,
                    "The authorization request expired",
                )
            }
            claimed = current.copy(status = PendingConnectorAuthorizationStatus.EXCHANGING)
                .also(ConnectorOAuthPolicy::validatePending)
            state.copy(
                pending = state.pending.map {
                    if (it.connectionId == connectionId) checkNotNull(claimed) else it
                },
            )
        }
        checkNotNull(claimed)
    }

    fun markExchangeOutcomeUnknown(connectionId: String) = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        updateState { state ->
            state.copy(
                pending = state.pending.map { pending ->
                    if (pending.connectionId == connectionId &&
                        pending.status == PendingConnectorAuthorizationStatus.EXCHANGING
                    ) {
                        pending.copy(
                            status = PendingConnectorAuthorizationStatus.OUTCOME_UNKNOWN,
                        ).also(ConnectorOAuthPolicy::validatePending)
                    } else {
                        pending
                    }
                },
            )
        }
    }

    fun completeAuthorization(credential: ConnectorOAuthCredential) = synchronized(lock) {
        ConnectorOAuthPolicy.validateCredential(credential)
        updateState { state ->
            val pending = state.pending.firstOrNull {
                it.connectionId == credential.connectionId
            } ?: throw failure(
                ConnectorOAuthFailureCode.CALLBACK_REPLAYED,
                "This authorization exchange is no longer pending",
            )
            require(pending.status == PendingConnectorAuthorizationStatus.EXCHANGING &&
                pending.definitionId == credential.definitionId &&
                pending.issuer == credential.issuer &&
                pending.resource == credential.resource &&
                pending.clientId == credential.clientId) {
                "Connector OAuth credential binding is invalid"
            }
            state.copy(
                pending = state.pending.filterNot {
                    it.connectionId == credential.connectionId
                },
                credentials = bounded(
                    state.credentials.filterNot {
                        it.connectionId == credential.connectionId
                    } + credential,
                    "credentials",
                ),
            )
        }
    }

    fun failExchange(connectionId: String) = cancelAuthorization(connectionId)

    fun cancelAuthorization(connectionId: String) = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        updateState { state ->
            state.copy(pending = state.pending.filterNot { it.connectionId == connectionId })
        }
    }

    fun credential(connectionId: String): ConnectorOAuthCredential? = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        readState().credentials.firstOrNull { it.connectionId == connectionId }
    }

    fun storeCredential(credential: ConnectorOAuthCredential) = synchronized(lock) {
        ConnectorOAuthPolicy.validateCredential(credential)
        updateState { state ->
            state.copy(
                credentials = bounded(
                    state.credentials.filterNot {
                        it.connectionId == credential.connectionId
                    } + credential,
                    "credentials",
                ),
            )
        }
    }

    fun clearConnection(connectionId: String) = synchronized(lock) {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        updateState { state ->
            state.copy(
                pending = state.pending.filterNot { it.connectionId == connectionId },
                credentials = state.credentials.filterNot { it.connectionId == connectionId },
            )
        }
    }

    fun exists(): Boolean = synchronized(lock) { fileStore.read() != null }

    private fun updateState(transform: (ConnectorVaultState) -> ConnectorVaultState) {
        val updated = transform(readState()).also(::validateState)
        val plaintext = ConnectorVaultCodec.encodePlaintext(updated)
        val encrypted = try {
            aead.encrypt(ConnectorVaultCodec.AAD, plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size == ConnectorVaultCodec.IV_BYTES) {
            "Connector OAuth vault IV is invalid"
        }
        fileStore.writeAtomically(
            ConnectorVaultCodec.encodeEnvelope(encrypted.iv, encrypted.ciphertext),
        )
    }

    private fun readState(): ConnectorVaultState {
        val envelopeBytes = fileStore.read() ?: return ConnectorVaultState()
        try {
            require(aead.keyExists()) { "Connector OAuth vault key is missing" }
            val envelope = ConnectorVaultCodec.decodeEnvelope(envelopeBytes)
            val plaintext = aead.decrypt(
                envelope.iv,
                ConnectorVaultCodec.AAD,
                envelope.ciphertext,
            )
            return try {
                ConnectorVaultCodec.decodePlaintext(plaintext).also(::validateState)
            } finally {
                plaintext.fill(0)
            }
        } catch (_: ConnectorOAuthException) {
            throw failure(
                ConnectorOAuthFailureCode.VAULT_CORRUPT,
                "Connector credentials could not be read",
            )
        } catch (_: Exception) {
            throw failure(
                ConnectorOAuthFailureCode.VAULT_CORRUPT,
                "Connector credentials could not be read",
            )
        }
    }

    private fun validateState(state: ConnectorVaultState) {
        require(state.registrations.size <= MAX_RECORDS) { "Too many Connector registrations" }
        require(state.pending.size <= MAX_RECORDS) { "Too many pending Connector authorizations" }
        require(state.credentials.size <= MAX_RECORDS) { "Too many Connector credentials" }
        state.registrations.forEach(ConnectorOAuthPolicy::validateRegistration)
        state.pending.forEach(ConnectorOAuthPolicy::validatePending)
        state.credentials.forEach(ConnectorOAuthPolicy::validateCredential)
        require(state.pending.map { it.connectionId }.distinct().size == state.pending.size) {
            "Duplicate pending Connector authorization"
        }
        require(state.credentials.map { it.connectionId }.distinct().size == state.credentials.size) {
            "Duplicate Connector credential"
        }
    }

    private fun <T> bounded(values: List<T>, name: String): List<T> {
        require(values.size <= MAX_RECORDS) { "Too many Connector $name" }
        return values
    }

    private fun sameRedirect(actual: String, expected: String): Boolean {
        val actualUri = runCatching { URI(actual) }.getOrNull() ?: return false
        val expectedUri = runCatching { URI(expected) }.getOrNull() ?: return false
        return actualUri.scheme == expectedUri.scheme &&
            actualUri.rawAuthority == expectedUri.rawAuthority &&
            actualUri.rawPath == expectedUri.rawPath &&
            actualUri.rawFragment == null
    }

    private fun requireMatchingCallback(
        current: PendingConnectorAuthorization,
        stateValue: String,
        issuer: String,
        actualRedirectUri: String,
        nowMillis: Long,
    ) {
        if (current.status != PendingConnectorAuthorizationStatus.AWAITING_BROWSER) {
            throw failure(
                ConnectorOAuthFailureCode.CALLBACK_REPLAYED,
                "This authorization callback was already received",
            )
        }
        if (nowMillis !in current.createdAtMillis until current.expiresAtMillis) {
            throw failure(
                ConnectorOAuthFailureCode.AUTHORIZATION_EXPIRED,
                "The authorization request expired",
            )
        }
        if (stateValue != current.state || issuer != current.issuer ||
            !sameRedirect(actualRedirectUri, current.redirectUri)) {
            throw failure(
                ConnectorOAuthFailureCode.CALLBACK_REJECTED,
                "The authorization callback did not match the pending request",
            )
        }
    }

    companion object {
        const val KEYSTORE_ALIAS = "momoding-connector-oauth-v1"
        const val FILE_NAME = "connector-oauth.vault"
        private const val MAX_RECORDS = 16
        private val PROCESS_LOCK = Any()

        fun create(context: Context): ConnectorCredentialVault = ConnectorCredentialVault(
            aead = AndroidKeystoreAead(KEYSTORE_ALIAS),
            fileStore = AndroidNoBackupVaultFileStore(
                context = context.applicationContext,
                fileName = FILE_NAME,
                maxBytes = ConnectorVaultCodec.MAX_ENVELOPE_BYTES,
            ),
        )
    }
}

private data class ConnectorVaultState(
    val registrations: List<ConnectorClientRegistration> = emptyList(),
    val pending: List<PendingConnectorAuthorization> = emptyList(),
    val credentials: List<ConnectorOAuthCredential> = emptyList(),
)

private data class ConnectorVaultEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

private object ConnectorVaultCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val MAGIC = "MMCONN01".toByteArray(StandardCharsets.US_ASCII)
    val AAD: ByteArray
        get() = MAGIC.copyOf()

    fun encodePlaintext(state: ConnectorVaultState): ByteArray {
        val dto = ConnectorVaultStateDto(
            registrations = state.registrations.map(ConnectorClientRegistration::toDto),
            pending = state.pending.map(PendingConnectorAuthorization::toDto),
            credentials = state.credentials.map(ConnectorOAuthCredential::toDto),
        )
        return json.encodeToString(dto).toByteArray(StandardCharsets.UTF_8).also {
            require(it.size in 1..MAX_PLAINTEXT_BYTES) {
                "Connector OAuth vault plaintext is too large"
            }
        }
    }

    fun decodePlaintext(bytes: ByteArray): ConnectorVaultState {
        require(bytes.size in 1..MAX_PLAINTEXT_BYTES) {
            "Connector OAuth vault plaintext size is invalid"
        }
        val dto = json.decodeFromString<ConnectorVaultStateDto>(
            bytes.toString(StandardCharsets.UTF_8),
        )
        require(dto.version == VERSION) { "Connector OAuth vault version is invalid" }
        return ConnectorVaultState(
            registrations = dto.registrations.map(ConnectorClientRegistrationDto::toDomain),
            pending = dto.pending.map(PendingConnectorAuthorizationDto::toDomain),
            credentials = dto.credentials.map(ConnectorOAuthCredentialDto::toDomain),
        )
    }

    fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "Connector OAuth vault IV is invalid" }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Connector OAuth vault ciphertext size is invalid"
        }
        return ByteArrayOutputStream().use { output ->
            output.write(AAD)
            output.write(iv.size)
            output.write(iv)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
            output.toByteArray()
        }
    }

    fun decodeEnvelope(bytes: ByteArray): ConnectorVaultEnvelope {
        require(bytes.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            "Connector OAuth vault envelope size is invalid"
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val aad = ByteArray(AAD.size).also(input::get)
        require(aad.contentEquals(AAD)) { "Connector OAuth vault header is invalid" }
        val ivLength = input.get().toInt() and 0xff
        require(ivLength == IV_BYTES && input.remaining() >= ivLength + 4) {
            "Connector OAuth vault IV is invalid"
        }
        val iv = ByteArray(ivLength).also(input::get)
        val ciphertextLength = input.int
        require(ciphertextLength in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES &&
            input.remaining() == ciphertextLength) {
            "Connector OAuth vault ciphertext size is invalid"
        }
        return ConnectorVaultEnvelope(
            iv = iv,
            ciphertext = ByteArray(ciphertextLength).also(input::get),
        )
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
    }

    const val IV_BYTES = 12
    const val MAX_ENVELOPE_BYTES = 256 * 1024
    private const val VERSION = 1
    private const val MAX_PLAINTEXT_BYTES = 220 * 1024
    private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
    private const val MIN_CIPHERTEXT_BYTES = 17
    private const val MIN_ENVELOPE_BYTES = 8 + 1 + IV_BYTES + 4 + MIN_CIPHERTEXT_BYTES
}

@Serializable
private data class ConnectorVaultStateDto(
    val version: Int = 1,
    val registrations: List<ConnectorClientRegistrationDto> = emptyList(),
    val pending: List<PendingConnectorAuthorizationDto> = emptyList(),
    val credentials: List<ConnectorOAuthCredentialDto> = emptyList(),
)

@Serializable
private data class ConnectorClientRegistrationDto(
    val definitionId: String,
    val issuer: String,
    val redirectUri: String,
    val clientId: String,
    val registeredAtMillis: Long,
)

@Serializable
private data class PendingConnectorAuthorizationDto(
    val connectionId: String,
    val definitionId: String,
    val issuer: String,
    val resource: String,
    val redirectUri: String,
    val clientId: String,
    val scopes: List<String>,
    val state: String,
    val codeVerifier: String,
    val authorizationCode: String?,
    val status: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
)

@Serializable
private data class ConnectorOAuthCredentialDto(
    val connectionId: String,
    val definitionId: String,
    val issuer: String,
    val resource: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scopes: List<String>,
    val expiresAtMillis: Long,
)

private fun ConnectorClientRegistration.toDto() = ConnectorClientRegistrationDto(
    definitionId = definitionId,
    issuer = issuer,
    redirectUri = redirectUri,
    clientId = clientId,
    registeredAtMillis = registeredAtMillis,
)

private fun ConnectorClientRegistrationDto.toDomain() = ConnectorClientRegistration(
    definitionId = definitionId,
    issuer = issuer,
    redirectUri = redirectUri,
    clientId = clientId,
    registeredAtMillis = registeredAtMillis,
)

private fun PendingConnectorAuthorization.toDto() = PendingConnectorAuthorizationDto(
    connectionId = connectionId,
    definitionId = definitionId,
    issuer = issuer,
    resource = resource,
    redirectUri = redirectUri,
    clientId = clientId,
    scopes = scopes.sorted(),
    state = state,
    codeVerifier = codeVerifier,
    authorizationCode = authorizationCode,
    status = status.name,
    createdAtMillis = createdAtMillis,
    expiresAtMillis = expiresAtMillis,
)

private fun PendingConnectorAuthorizationDto.toDomain() = PendingConnectorAuthorization(
    connectionId = connectionId,
    definitionId = definitionId,
    issuer = issuer,
    resource = resource,
    redirectUri = redirectUri,
    clientId = clientId,
    scopes = scopes.toSet(),
    state = state,
    codeVerifier = codeVerifier,
    authorizationCode = authorizationCode,
    status = PendingConnectorAuthorizationStatus.valueOf(status),
    createdAtMillis = createdAtMillis,
    expiresAtMillis = expiresAtMillis,
)

private fun ConnectorOAuthCredential.toDto() = ConnectorOAuthCredentialDto(
    connectionId = connectionId,
    definitionId = definitionId,
    issuer = issuer,
    resource = resource,
    clientId = clientId,
    accessToken = accessToken,
    refreshToken = refreshToken,
    tokenType = tokenType,
    scopes = scopes.sorted(),
    expiresAtMillis = expiresAtMillis,
)

private fun ConnectorOAuthCredentialDto.toDomain() = ConnectorOAuthCredential(
    connectionId = connectionId,
    definitionId = definitionId,
    issuer = issuer,
    resource = resource,
    clientId = clientId,
    accessToken = accessToken,
    refreshToken = refreshToken,
    tokenType = tokenType,
    scopes = scopes.toSet(),
    expiresAtMillis = expiresAtMillis,
)

private fun failure(code: ConnectorOAuthFailureCode, message: String) =
    ConnectorOAuthException(code, safeMessage = message)
