package app.momoding.core.connector

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.net.toUri
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

internal data class PreparedConnectorAuthorization(
    val pending: PendingConnectorAuthorization,
    val metadata: ConnectorAuthorizationServerMetadata,
) {
    override fun toString(): String =
        "PreparedConnectorAuthorization(pending=$pending, metadata=$metadata)"
}

internal class ConnectorOAuthCoordinator internal constructor(
    private val vault: ConnectorCredentialVault,
    private val gateway: ConnectorOAuthGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val registrationMutex = Mutex()

    internal suspend fun prepare(
        definition: ConnectorOAuthDefinition,
    ): PreparedConnectorAuthorization {
        ConnectorOAuthPolicy.validateDefinition(definition)
        val discovery = gateway.discover(definition)
        val metadata = discovery.authorizationServer
        val clientId = definition.clientId ?: registrationMutex.withLock {
            vault.registration(definition.id, metadata.issuer, definition.redirectUri)?.clientId
                ?: gateway.registerClient(definition, metadata).also(vault::storeRegistration).clientId
        }
        val createdAt = nowMillis()
        val pending = PendingConnectorAuthorization(
            connectionId = "conn_${randomUrlToken(CONNECTION_ID_BYTES)}",
            definitionId = definition.id,
            issuer = metadata.issuer,
            resource = definition.resource,
            redirectUri = definition.redirectUri,
            clientId = clientId,
            scopes = definition.scopes,
            state = randomUrlToken(STATE_BYTES),
            codeVerifier = randomUrlToken(CODE_VERIFIER_BYTES),
            createdAtMillis = createdAt,
            expiresAtMillis = Math.addExact(createdAt, AUTHORIZATION_LIFETIME_MILLIS),
        ).also(ConnectorOAuthPolicy::validatePending)
        vault.beginAuthorization(pending)
        return PreparedConnectorAuthorization(pending, metadata)
    }

    internal suspend fun complete(
        connectionId: String,
        definition: ConnectorOAuthDefinition,
    ): ConnectorOAuthCredential {
        val current = vault.pending(connectionId) ?: throw failure(
            ConnectorOAuthFailureCode.CALLBACK_REPLAYED,
            "This authorization callback is no longer pending",
        )
        if (current.definitionId != definition.id || current.resource != definition.resource ||
            current.redirectUri != definition.redirectUri) {
            throw failure(
                ConnectorOAuthFailureCode.CALLBACK_REJECTED,
                "The authorization request no longer matches this Connector",
            )
        }
        val discovery = gateway.discover(definition)
        if (discovery.authorizationServer.issuer != current.issuer) {
            throw failure(
                ConnectorOAuthFailureCode.CALLBACK_REJECTED,
                "The authorization issuer changed before token exchange",
            )
        }
        val claimed = vault.claimCallback(connectionId, nowMillis())
        return try {
            val token = gateway.exchangeAuthorizationCode(
                claimed,
                discovery.authorizationServer,
            )
            ConnectorOAuthCredential(
                connectionId = claimed.connectionId,
                definitionId = claimed.definitionId,
                issuer = claimed.issuer,
                resource = claimed.resource,
                clientId = claimed.clientId,
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                tokenType = token.tokenType,
                scopes = token.scopes,
                expiresAtMillis = token.expiresAtMillis,
            ).also {
                ConnectorOAuthPolicy.validateCredential(it)
                vault.completeAuthorization(it)
            }
        } catch (cancellation: CancellationException) {
            vault.markExchangeOutcomeUnknown(connectionId)
            throw cancellation
        } catch (error: ConnectorOAuthException) {
            if (error.failureCode == ConnectorOAuthFailureCode.NETWORK ||
                error.failureCode == ConnectorOAuthFailureCode.TIMEOUT
            ) {
                vault.markExchangeOutcomeUnknown(connectionId)
            } else {
                vault.failExchange(connectionId)
            }
            throw error
        } catch (_: Exception) {
            vault.markExchangeOutcomeUnknown(connectionId)
            throw failure(
                ConnectorOAuthFailureCode.OUTCOME_UNKNOWN,
                "The Connector authorization outcome is unknown",
            )
        }
    }

    fun cancel(connectionId: String) = vault.cancelAuthorization(connectionId)

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private companion object {
        const val CONNECTION_ID_BYTES = 24
        const val STATE_BYTES = 32
        const val CODE_VERIFIER_BYTES = 48
        const val AUTHORIZATION_LIFETIME_MILLIS = 10L * 60L * 1_000L
    }
}

class ConnectorCredentialManager internal constructor(
    private val vault: ConnectorCredentialVault,
    private val gateway: ConnectorOAuthGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun current(connectionId: String): ConnectorOAuthCredential? = vault.credential(connectionId)

    suspend fun requireValidCredential(
        connectionId: String,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthCredential = lock(connectionId).withLock {
        val current = vault.credential(connectionId) ?: throw failure(
            ConnectorOAuthFailureCode.REAUTH_REQUIRED,
            "This Connector is not authorized",
        )
        validateMetadataBinding(current, metadata)
        if (current.expiresAtMillis - nowMillis() > REFRESH_SKEW_MILLIS) return@withLock current
        refresh(current, metadata)
    }

    suspend fun refreshAfterUnauthorized(
        connectionId: String,
        rejectedAccessToken: String,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthCredential = lock(connectionId).withLock {
        val current = vault.credential(connectionId) ?: throw failure(
            ConnectorOAuthFailureCode.REAUTH_REQUIRED,
            "This Connector is not authorized",
        )
        validateMetadataBinding(current, metadata)
        if (current.accessToken != rejectedAccessToken) return@withLock current
        refresh(current, metadata)
    }

    suspend fun disconnect(
        connectionId: String,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorDisconnectResult = lock(connectionId).withLock {
        val current = vault.credential(connectionId)
        var revoked = false
        try {
            if (current != null) {
                validateMetadataBinding(current, metadata)
                gateway.revoke(current, metadata)
                revoked = metadata.revocationEndpoint != null
            }
        } catch (_: CancellationException) {
            throw CancellationException("Connector disconnect canceled after local clear")
        } catch (_: Exception) {
            revoked = false
        } finally {
            vault.clearConnection(connectionId)
        }
        ConnectorDisconnectResult(
            localCredentialCleared = true,
            remoteRevocationAttempted = current != null && metadata.revocationEndpoint != null,
            remoteRevocationConfirmed = revoked,
        )
    }

    private suspend fun refresh(
        current: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthCredential {
        val token = gateway.refresh(current, metadata)
        val rotated = current.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            tokenType = token.tokenType,
            scopes = token.scopes,
            expiresAtMillis = token.expiresAtMillis,
        ).also(ConnectorOAuthPolicy::validateCredential)
        vault.storeCredential(rotated)
        return rotated
    }

    private fun validateMetadataBinding(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ) {
        if (credential.issuer != metadata.issuer) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector authorization metadata changed issuer",
            )
        }
    }

    private fun lock(connectionId: String): Mutex {
        ConnectorOAuthPolicy.requireConnectionId(connectionId)
        return PROCESS_REFRESH_LOCKS.computeIfAbsent(connectionId) { Mutex() }
    }

    private companion object {
        const val REFRESH_SKEW_MILLIS = 60_000L
        val PROCESS_REFRESH_LOCKS = ConcurrentHashMap<String, Mutex>()
    }
}

data class ConnectorDisconnectResult(
    val localCredentialCleared: Boolean,
    val remoteRevocationAttempted: Boolean,
    val remoteRevocationConfirmed: Boolean,
)

internal class AppAuthConnectorAuthorizationLauncher(
    context: Context,
    private val vault: ConnectorCredentialVault,
) {
    private val applicationContext = context.applicationContext

    fun launch(prepared: PreparedConnectorAuthorization) {
        val pending = prepared.pending
        val request = authorizationRequest(prepared)
        val completed = callbackPendingIntent(pending.connectionId, canceled = false)
        val canceled = callbackPendingIntent(pending.connectionId, canceled = true)
        val authorizationService = AuthorizationService(applicationContext)
        try {
            authorizationService.performAuthorizationRequest(request, completed, canceled)
        } catch (_: ActivityNotFoundException) {
            vault.cancelAuthorization(pending.connectionId)
            throw failure(
                ConnectorOAuthFailureCode.BROWSER_UNAVAILABLE,
                "No supported browser is available for Connector authorization",
            )
        } finally {
            authorizationService.dispose()
        }
    }

    internal fun authorizationRequest(
        prepared: PreparedConnectorAuthorization,
    ): AuthorizationRequest {
        val metadata = prepared.metadata
        val pending = prepared.pending
        return AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(
                metadata.authorizationEndpoint.toUri(),
                metadata.tokenEndpoint.toUri(),
                metadata.registrationEndpoint?.toUri(),
            ),
            pending.clientId,
            ResponseTypeValues.CODE,
            pending.redirectUri.toUri(),
        )
            .setState(pending.state)
            .setNonce(null)
            .setCodeVerifier(pending.codeVerifier)
            .setScopes(pending.scopes.sorted())
            .setAdditionalParameters(mapOf("resource" to pending.resource))
            .build()
            .also { request ->
                check(request.codeVerifierChallengeMethod == "S256") {
                    "AppAuth did not produce a PKCE S256 challenge"
                }
            }
    }

    private fun callbackPendingIntent(connectionId: String, canceled: Boolean): PendingIntent {
        val action = if (canceled) ACTION_CANCELED else ACTION_COMPLETED
        val intent = Intent(applicationContext, ConnectorRedirectActivity::class.java)
            .setAction(action)
            .setData("momoding-internal://connector-oauth/$connectionId/$action".toUri())
            .putExtra(ConnectorRedirectActivity.EXTRA_CONNECTION_ID, connectionId)
            .putExtra(ConnectorRedirectActivity.EXTRA_CANCELED, canceled)
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getActivity(
            applicationContext,
            31 * connectionId.hashCode() + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    private companion object {
        const val ACTION_COMPLETED = "app.momoding.connector.OAUTH_COMPLETED"
        const val ACTION_CANCELED = "app.momoding.connector.OAUTH_CANCELED"
    }
}

class ConnectorRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        if (connectionId != null) {
            val vault = ConnectorCredentialVault.create(applicationContext)
            if (intent.getBooleanExtra(EXTRA_CANCELED, false)) {
                runCatching { vault.cancelAuthorization(connectionId) }
            } else {
                recordCompletion(vault, connectionId)
            }
        }
        finish()
    }

    private fun recordCompletion(vault: ConnectorCredentialVault, connectionId: String) {
        runCatching {
            val exception = AuthorizationException.fromIntent(intent)
            if (exception != null) {
                if (exception.type == AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR) {
                    val redirect = intent.data ?: return
                    val state = redirect.getQueryParameter("state") ?: return
                    val issuer = redirect.getQueryParameter("iss") ?: return
                    vault.cancelFromAuthorizationResponse(
                        connectionId = connectionId,
                        stateValue = state,
                        issuer = issuer,
                        actualRedirectUri = redirect.toString(),
                        nowMillis = System.currentTimeMillis(),
                    )
                }
                return
            }
            val response = AuthorizationResponse.fromIntent(intent)
                ?: throw IllegalArgumentException("Missing authorization response")
            val state = response.state ?: throw IllegalArgumentException("Missing state")
            val code = response.authorizationCode
                ?: throw IllegalArgumentException("Missing authorization code")
            val issuer = response.additionalParameters["iss"]
                ?: throw IllegalArgumentException("Missing authorization issuer")
            val redirect = intent.data?.toString()
                ?: throw IllegalArgumentException("Missing authorization redirect")
            vault.recordCallback(
                connectionId = connectionId,
                stateValue = state,
                issuer = issuer,
                actualRedirectUri = redirect,
                authorizationCode = code,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    companion object {
        internal const val EXTRA_CONNECTION_ID =
            "app.momoding.connector.oauth.CONNECTION_ID"
        internal const val EXTRA_CANCELED =
            "app.momoding.connector.oauth.CANCELED"
    }
}

private fun failure(code: ConnectorOAuthFailureCode, message: String) =
    ConnectorOAuthException(code, safeMessage = message)
