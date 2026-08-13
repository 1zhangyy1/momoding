package app.momoding.core.connector

import app.momoding.core.auth.AeadCiphertext
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorCredentialVaultTest {
    @Test
    fun `callback binding survives restart and rejects mismatch and replay`() {
        val fixture = vaultFixture()
        val pending = pending()
        fixture.vault.beginAuthorization(pending)

        val wrongState = assertThrows(ConnectorOAuthException::class.java) {
            fixture.vault.recordCallback(
                connectionId = pending.connectionId,
                stateValue = "x".repeat(43),
                issuer = pending.issuer,
                actualRedirectUri = "app.momoding:/oauth2redirect?code=hidden",
                authorizationCode = "authorization-code-secret",
                nowMillis = NOW,
            )
        }
        assertEquals(ConnectorOAuthFailureCode.CALLBACK_REJECTED, wrongState.failureCode)
        assertEquals(
            PendingConnectorAuthorizationStatus.AWAITING_BROWSER,
            fixture.vault.pending(pending.connectionId)?.status,
        )

        listOf(
            pending.copy(issuer = "https://wrong.example/") to
                "app.momoding:/oauth2redirect?code=hidden",
            pending to "app.momoding:/wrong-path?code=hidden",
        ).forEach { (callback, redirect) ->
            val mismatch = assertThrows(ConnectorOAuthException::class.java) {
                fixture.vault.recordCallback(
                    connectionId = pending.connectionId,
                    stateValue = pending.state,
                    issuer = callback.issuer,
                    actualRedirectUri = redirect,
                    authorizationCode = "authorization-code-secret",
                    nowMillis = NOW,
                )
            }
            assertEquals(ConnectorOAuthFailureCode.CALLBACK_REJECTED, mismatch.failureCode)
            assertEquals(
                PendingConnectorAuthorizationStatus.AWAITING_BROWSER,
                fixture.vault.pending(pending.connectionId)?.status,
            )
        }

        val restarted = ConnectorCredentialVault(fixture.aead, fixture.file)
        val callback = restarted.recordCallback(
            connectionId = pending.connectionId,
            stateValue = pending.state,
            issuer = pending.issuer,
            actualRedirectUri =
                "app.momoding:/oauth2redirect?code=authorization-code-secret&state=${pending.state}",
            authorizationCode = "authorization-code-secret",
            nowMillis = NOW,
        )
        assertEquals(PendingConnectorAuthorizationStatus.CALLBACK_READY, callback.status)

        val replay = assertThrows(ConnectorOAuthException::class.java) {
            restarted.recordCallback(
                connectionId = pending.connectionId,
                stateValue = pending.state,
                issuer = pending.issuer,
                actualRedirectUri = "app.momoding:/oauth2redirect?code=replayed",
                authorizationCode = "replayed-code-secret",
                nowMillis = NOW,
            )
        }
        assertEquals(ConnectorOAuthFailureCode.CALLBACK_REPLAYED, replay.failureCode)

        val claimed = restarted.claimCallback(pending.connectionId, NOW)
        assertEquals(PendingConnectorAuthorizationStatus.EXCHANGING, claimed.status)
        val credential = credential(connectionId = pending.connectionId)
        restarted.completeAuthorization(credential)

        assertEquals(credential, ConnectorCredentialVault(fixture.aead, fixture.file).credential(
            pending.connectionId,
        ))
        assertNull(restarted.pending(pending.connectionId))
        val raw = String(requireNotNull(fixture.file.bytes), Charsets.ISO_8859_1)
        listOf(
            pending.state,
            pending.codeVerifier,
            "authorization-code-secret",
            credential.accessToken,
            checkNotNull(credential.refreshToken),
        ).forEach { assertFalse(raw.contains(it)) }
        assertFalse(pending.toString().contains(pending.state))
        assertFalse(credential.toString().contains(credential.accessToken))
    }

    @Test
    fun `registration is issuer bound and tampering fails with stable vault error`() {
        val fixture = vaultFixture()
        val registration = registration()
        fixture.vault.storeRegistration(registration)

        assertEquals(
            registration,
            fixture.vault.registration(
                registration.definitionId,
                registration.issuer,
                registration.redirectUri,
            ),
        )
        assertNull(
            fixture.vault.registration(
                registration.definitionId,
                "https://other.example/",
                registration.redirectUri,
            ),
        )
        fixture.file.bytes = requireNotNull(fixture.file.bytes).copyOf().also {
            it[it.lastIndex] = (it.last() + 1).toByte()
        }
        val error = assertThrows(ConnectorOAuthException::class.java) {
            fixture.vault.registration(
                registration.definitionId,
                registration.issuer,
                registration.redirectUri,
            )
        }
        assertEquals(ConnectorOAuthFailureCode.VAULT_CORRUPT, error.failureCode)
        assertFalse(error.stackTraceToString().contains(registration.clientId))
    }

    @Test
    fun `coordinator reuses issuer registration and resumes callback after restart`() = runBlocking {
        val fixture = vaultFixture()
        val gateway = FakeConnectorOAuthGateway()
        val definition = definition()
        val first = ConnectorOAuthCoordinator(
            fixture.vault,
            gateway,
            nowMillis = { NOW },
        ).prepare(definition)
        val second = ConnectorOAuthCoordinator(
            ConnectorCredentialVault(fixture.aead, fixture.file),
            gateway,
            nowMillis = { NOW },
        ).prepare(definition)

        assertNotEquals(first.pending.connectionId, second.pending.connectionId)
        assertEquals(first.pending.clientId, second.pending.clientId)
        assertEquals(1, gateway.registerCount.get())
        assertTrue(first.pending.state.length >= 43)
        assertTrue(first.pending.codeVerifier.length in 43..128)

        fixture.vault.recordCallback(
            connectionId = first.pending.connectionId,
            stateValue = first.pending.state,
            issuer = first.pending.issuer,
            actualRedirectUri = "app.momoding:/oauth2redirect?code=callback-code",
            authorizationCode = "callback-code",
            nowMillis = NOW,
        )
        val restartedCoordinator = ConnectorOAuthCoordinator(
            ConnectorCredentialVault(fixture.aead, fixture.file),
            gateway,
            nowMillis = { NOW },
        )
        val completed = restartedCoordinator.complete(first.pending.connectionId, definition)

        assertEquals(first.pending.connectionId, completed.connectionId)
        assertEquals(1, gateway.exchangeCount.get())
        assertEquals("access-token-rotated", completed.accessToken)
        assertNull(fixture.vault.pending(first.pending.connectionId))
    }

    @Test
    fun `credential manager single flights refresh and clears locally when revoke fails`() = runBlocking {
        val fixture = vaultFixture()
        val gateway = FakeConnectorOAuthGateway().apply { failRevoke = true }
        val managers = listOf(
            ConnectorCredentialManager(fixture.vault, gateway) { NOW },
            ConnectorCredentialManager(
                ConnectorCredentialVault(fixture.aead, fixture.file),
                gateway,
            ) { NOW },
        )
        val expired = credential(expiresAtMillis = NOW - 1)
        fixture.vault.storeCredential(expired)

        val refreshed = List(12) { index ->
            async {
                managers[index % managers.size].requireValidCredential(
                    expired.connectionId,
                    metadata(),
                )
            }
        }.awaitAll()

        assertEquals(1, gateway.refreshCount.get())
        assertEquals(1, refreshed.map { it.accessToken }.distinct().size)
        val disconnected = managers.first().disconnect(expired.connectionId, metadata())
        assertTrue(disconnected.localCredentialCleared)
        assertTrue(disconnected.remoteRevocationAttempted)
        assertFalse(disconnected.remoteRevocationConfirmed)
        assertNull(managers.last().current(expired.connectionId))
        assertEquals(1, gateway.revokeCount.get())
    }

    @Test
    fun `unknown exchange outcome cannot replay authorization code after restart`() {
        val fixture = vaultFixture()
        val pending = pending()
        fixture.vault.beginAuthorization(pending)
        fixture.vault.recordCallback(
            connectionId = pending.connectionId,
            stateValue = pending.state,
            issuer = pending.issuer,
            actualRedirectUri = "app.momoding:/oauth2redirect?code=authorization-code-secret",
            authorizationCode = "authorization-code-secret",
            nowMillis = NOW,
        )
        fixture.vault.claimCallback(pending.connectionId, NOW)
        fixture.vault.markExchangeOutcomeUnknown(pending.connectionId)

        val restarted = ConnectorCredentialVault(fixture.aead, fixture.file)
        val error = assertThrows(ConnectorOAuthException::class.java) {
            restarted.claimCallback(pending.connectionId, NOW)
        }

        assertEquals(ConnectorOAuthFailureCode.OUTCOME_UNKNOWN, error.failureCode)
        assertEquals(
            PendingConnectorAuthorizationStatus.OUTCOME_UNKNOWN,
            restarted.pending(pending.connectionId)?.status,
        )
    }

    @Test
    fun `OAuth error cancellation requires the same callback bindings`() {
        val fixture = vaultFixture()
        val pending = pending()
        fixture.vault.beginAuthorization(pending)

        val rejected = assertThrows(ConnectorOAuthException::class.java) {
            ConnectorCredentialVault(fixture.aead, fixture.file).cancelFromAuthorizationResponse(
                connectionId = pending.connectionId,
                stateValue = "x".repeat(43),
                issuer = pending.issuer,
                actualRedirectUri = "app.momoding:/oauth2redirect?error=access_denied",
                nowMillis = NOW,
            )
        }
        assertEquals(ConnectorOAuthFailureCode.CALLBACK_REJECTED, rejected.failureCode)
        assertEquals(
            PendingConnectorAuthorizationStatus.AWAITING_BROWSER,
            fixture.vault.pending(pending.connectionId)?.status,
        )

        ConnectorCredentialVault(fixture.aead, fixture.file).cancelFromAuthorizationResponse(
            connectionId = pending.connectionId,
            stateValue = pending.state,
            issuer = pending.issuer,
            actualRedirectUri =
                "app.momoding:/oauth2redirect?error=access_denied&state=${pending.state}",
            nowMillis = NOW,
        )
        assertNull(fixture.vault.pending(pending.connectionId))
    }

    @Test
    fun `separate vault instances serialize callback and code claim`() = runBlocking {
        val fixture = vaultFixture()
        val pending = pending()
        fixture.vault.beginAuthorization(pending)
        val vaults = List(8) { ConnectorCredentialVault(fixture.aead, fixture.file) }
        val callbackGate = CompletableDeferred<Unit>()
        val callbackOutcomes = vaults.map { vault ->
            async(Dispatchers.Default) {
                callbackGate.await()
                runCatching {
                    vault.recordCallback(
                        connectionId = pending.connectionId,
                        stateValue = pending.state,
                        issuer = pending.issuer,
                        actualRedirectUri =
                            "app.momoding:/oauth2redirect?code=authorization-code-secret",
                        authorizationCode = "authorization-code-secret",
                        nowMillis = NOW,
                    )
                }
            }
        }.also { callbackGate.complete(Unit) }.awaitAll()

        assertEquals(1, callbackOutcomes.count { it.isSuccess })
        assertTrue(
            callbackOutcomes.filter(Result<*>::isFailure).all {
                (it.exceptionOrNull() as ConnectorOAuthException).failureCode ==
                    ConnectorOAuthFailureCode.CALLBACK_REPLAYED
            },
        )

        val claimGate = CompletableDeferred<Unit>()
        val claimOutcomes = vaults.map { vault ->
            async(Dispatchers.Default) {
                claimGate.await()
                runCatching { vault.claimCallback(pending.connectionId, NOW) }
            }
        }.also { claimGate.complete(Unit) }.awaitAll()

        assertEquals(1, claimOutcomes.count { it.isSuccess })
        assertTrue(
            claimOutcomes.filter(Result<*>::isFailure).all {
                (it.exceptionOrNull() as ConnectorOAuthException).failureCode ==
                    ConnectorOAuthFailureCode.OUTCOME_UNKNOWN
            },
        )
        assertEquals(
            PendingConnectorAuthorizationStatus.EXCHANGING,
            ConnectorCredentialVault(fixture.aead, fixture.file)
                .pending(pending.connectionId)?.status,
        )
    }

    private fun vaultFixture(): VaultFixture {
        val aead = TestConnectorAead()
        val file = MemoryConnectorVaultFileStore()
        return VaultFixture(aead, file, ConnectorCredentialVault(aead, file))
    }

    private fun definition() = ConnectorOAuthDefinition(
        id = "fixture",
        resource = "https://auth.example/mcp",
        protectedResourceMetadataUrl =
            "https://auth.example/.well-known/oauth-protected-resource",
        scopes = setOf("read"),
        allowedAuthorizationHosts = setOf("auth.example"),
    )

    private fun metadata() = ConnectorAuthorizationServerMetadata(
        issuer = "https://auth.example/",
        authorizationEndpoint = "https://auth.example/authorize",
        tokenEndpoint = "https://auth.example/token",
        registrationEndpoint = "https://auth.example/register",
        revocationEndpoint = "https://auth.example/revoke",
        scopesSupported = setOf("read"),
        codeChallengeMethodsSupported = setOf("S256"),
    )

    private fun pending() = PendingConnectorAuthorization(
        connectionId = "conn_${"a".repeat(24)}",
        definitionId = "fixture",
        issuer = "https://auth.example/",
        resource = "https://auth.example/mcp",
        redirectUri = ConnectorOAuthDefinition.DEFAULT_REDIRECT_URI,
        clientId = "public-client-id",
        scopes = setOf("read"),
        state = "s".repeat(43),
        codeVerifier = "v".repeat(64),
        createdAtMillis = NOW - 1_000,
        expiresAtMillis = NOW + 600_000,
    )

    private fun registration() = ConnectorClientRegistration(
        definitionId = "fixture",
        issuer = "https://auth.example/",
        redirectUri = ConnectorOAuthDefinition.DEFAULT_REDIRECT_URI,
        clientId = "public-client-id",
        registeredAtMillis = NOW,
    )

    private fun credential(
        connectionId: String = "conn_${"b".repeat(24)}",
        expiresAtMillis: Long = NOW + 3_600_000,
    ) = ConnectorOAuthCredential(
        connectionId = connectionId,
        definitionId = "fixture",
        issuer = "https://auth.example/",
        resource = "https://auth.example/mcp",
        clientId = "public-client-id",
        accessToken = "access-token-secret",
        refreshToken = "refresh-token-secret",
        tokenType = "Bearer",
        scopes = setOf("read"),
        expiresAtMillis = expiresAtMillis,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

private data class VaultFixture(
    val aead: TestConnectorAead,
    val file: MemoryConnectorVaultFileStore,
    val vault: ConnectorCredentialVault,
)

private class MemoryConnectorVaultFileStore : VaultFileStore {
    var bytes: ByteArray? = null

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun writeAtomically(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }

    override fun delete() {
        bytes = null
    }
}

private class TestConnectorAead : VaultAead {
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
    private var exists = true
    private var counter = 0

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext {
        val iv = ByteArray(12) { index -> (index + ++counter).toByte() }
        return AeadCiphertext(iv, crypt(Cipher.ENCRYPT_MODE, iv, aad, plaintext))
    }

    override fun decrypt(
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = crypt(Cipher.DECRYPT_MODE, iv, aad, ciphertext)

    override fun keyExists(): Boolean = exists

    override fun deleteKey() {
        exists = false
    }

    private fun crypt(mode: Int, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        check(exists)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(input)
        }
    }
}

private class FakeConnectorOAuthGateway : ConnectorOAuthGateway {
    val registerCount = AtomicInteger()
    val exchangeCount = AtomicInteger()
    val refreshCount = AtomicInteger()
    val revokeCount = AtomicInteger()
    var failRevoke = false

    override suspend fun discover(
        definition: ConnectorOAuthDefinition,
    ) = ConnectorOAuthDiscovery(
        McpProtectedResourceMetadata(definition.resource, listOf(metadata().issuer)),
        metadata(),
    )

    override suspend fun registerClient(
        definition: ConnectorOAuthDefinition,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorClientRegistration {
        registerCount.incrementAndGet()
        return ConnectorClientRegistration(
            definition.id,
            metadata.issuer,
            definition.redirectUri,
            "registered-public-client",
            1_800_000_000_000L,
        )
    }

    override suspend fun exchangeAuthorizationCode(
        pending: PendingConnectorAuthorization,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthTokenResponse {
        exchangeCount.incrementAndGet()
        return token("access-token-rotated")
    }

    override suspend fun refresh(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthTokenResponse {
        refreshCount.incrementAndGet()
        delay(20)
        return token("access-token-refreshed")
    }

    override suspend fun revoke(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ) {
        revokeCount.incrementAndGet()
        if (failRevoke) throw ConnectorOAuthException(
            ConnectorOAuthFailureCode.REVOCATION_FAILED,
            safeMessage = "Fixture revocation failed",
        )
    }

    private fun metadata() = ConnectorAuthorizationServerMetadata(
        issuer = "https://auth.example/",
        authorizationEndpoint = "https://auth.example/authorize",
        tokenEndpoint = "https://auth.example/token",
        registrationEndpoint = "https://auth.example/register",
        revocationEndpoint = "https://auth.example/revoke",
        scopesSupported = setOf("read"),
        codeChallengeMethodsSupported = setOf("S256"),
    )

    private fun token(accessToken: String) = ConnectorOAuthTokenResponse(
        accessToken = accessToken,
        refreshToken = "refresh-token-rotated",
        tokenType = "Bearer",
        scopes = setOf("read"),
        expiresAtMillis = 1_800_003_600_000L,
    )
}
