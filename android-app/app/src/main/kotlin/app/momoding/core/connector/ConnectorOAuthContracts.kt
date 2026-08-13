package app.momoding.core.connector

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ConnectorOAuthDefinition(
    val id: String,
    val resource: String,
    val protectedResourceMetadataUrl: String,
    val redirectUri: String = DEFAULT_REDIRECT_URI,
    val scopes: Set<String>,
    val allowedAuthorizationHosts: Set<String>,
    val clientId: String? = null,
    val allowDynamicClientRegistration: Boolean = true,
) {
    init {
        ConnectorOAuthPolicy.validateDefinition(this)
    }

    companion object {
        const val DEFAULT_REDIRECT_URI = "app.momoding:/oauth2redirect"
    }
}

data class McpProtectedResourceMetadata(
    val resource: String,
    val authorizationServers: List<String>,
)

data class ConnectorAuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val revocationEndpoint: String?,
    val scopesSupported: Set<String>,
    val codeChallengeMethodsSupported: Set<String>,
) {
    override fun toString(): String =
        "ConnectorAuthorizationServerMetadata(issuer=$issuer, " +
            "authorizationEndpoint=$authorizationEndpoint, tokenEndpoint=$tokenEndpoint, " +
            "registrationEndpoint=$registrationEndpoint, revocationEndpoint=$revocationEndpoint, " +
            "scopesSupported=$scopesSupported, " +
            "codeChallengeMethodsSupported=$codeChallengeMethodsSupported)"
}

data class ConnectorClientRegistration(
    val definitionId: String,
    val issuer: String,
    val redirectUri: String,
    val clientId: String,
    val registeredAtMillis: Long,
) {
    override fun toString(): String =
        "ConnectorClientRegistration(definitionId=$definitionId, issuer=$issuer, " +
            "redirectUri=$redirectUri, clientId=[REDACTED], " +
            "registeredAtMillis=$registeredAtMillis)"
}

enum class PendingConnectorAuthorizationStatus {
    AWAITING_BROWSER,
    CALLBACK_READY,
    EXCHANGING,
    OUTCOME_UNKNOWN,
}

data class PendingConnectorAuthorization(
    val connectionId: String,
    val definitionId: String,
    val issuer: String,
    val resource: String,
    val redirectUri: String,
    val clientId: String,
    val scopes: Set<String>,
    val state: String,
    val codeVerifier: String,
    val authorizationCode: String? = null,
    val status: PendingConnectorAuthorizationStatus =
        PendingConnectorAuthorizationStatus.AWAITING_BROWSER,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "PendingConnectorAuthorization(connectionId=$connectionId, " +
            "definitionId=$definitionId, issuer=$issuer, resource=$resource, " +
            "redirectUri=$redirectUri, clientId=[REDACTED], scopes=$scopes, " +
            "state=[REDACTED], codeVerifier=[REDACTED], " +
            "authorizationCode=${if (authorizationCode == null) "null" else "[REDACTED]"}, " +
            "status=$status, createdAtMillis=$createdAtMillis, " +
            "expiresAtMillis=$expiresAtMillis)"
}

data class ConnectorOAuthCredential(
    val connectionId: String,
    val definitionId: String,
    val issuer: String,
    val resource: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scopes: Set<String>,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "ConnectorOAuthCredential(connectionId=$connectionId, definitionId=$definitionId, " +
            "issuer=$issuer, resource=$resource, clientId=[REDACTED], " +
            "accessToken=[REDACTED], refreshToken=" +
            (if (refreshToken == null) "null" else "[REDACTED]") +
            ", tokenType=$tokenType, scopes=$scopes, expiresAtMillis=$expiresAtMillis)"
}

data class ConnectorOAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scopes: Set<String>,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "ConnectorOAuthTokenResponse(accessToken=[REDACTED], refreshToken=" +
            (if (refreshToken == null) "null" else "[REDACTED]") +
            ", tokenType=$tokenType, scopes=$scopes, expiresAtMillis=$expiresAtMillis)"
}

enum class ConnectorOAuthFailureCode {
    INVALID_DEFINITION,
    INVALID_METADATA,
    DISCOVERY_FAILED,
    CLIENT_REGISTRATION_FAILED,
    BROWSER_UNAVAILABLE,
    USER_CANCELED,
    CALLBACK_REJECTED,
    CALLBACK_REPLAYED,
    AUTHORIZATION_EXPIRED,
    TOKEN_EXCHANGE_FAILED,
    TOKEN_REFRESH_FAILED,
    REAUTH_REQUIRED,
    REVOCATION_FAILED,
    NETWORK,
    TIMEOUT,
    VAULT_CORRUPT,
    OUTCOME_UNKNOWN,
}

class ConnectorOAuthException(
    val failureCode: ConnectorOAuthFailureCode,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    safeMessage: String,
) : IOException(safeMessage) {
    override fun toString(): String =
        "ConnectorOAuthException(failureCode=$failureCode, httpStatus=$httpStatus, " +
            "retryable=$retryable, message=$message)"
}

internal object ConnectorOAuthPolicy {
    private val ID = Regex("^[a-z][a-z0-9_-]{0,63}$")
    private val CONNECTION_ID = Regex("^conn_[A-Za-z0-9_-]{20,96}$")
    private val SCOPE = Regex("^[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,128}$")
    private val CLIENT_ID = Regex("^[\\x21-\\x7E]{1,2048}$")
    private val STATE = Regex("^[A-Za-z0-9_-]{43,128}$")
    private val CODE_VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")

    fun validateDefinition(value: ConnectorOAuthDefinition) {
        require(ID.matches(value.id)) { "Connector OAuth definition id is invalid" }
        requireHttps(value.resource, "resource")
        requireHttps(value.protectedResourceMetadataUrl, "protected resource metadata")
        val resource = value.resource.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Connector OAuth resource is invalid")
        val metadata = value.protectedResourceMetadataUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Connector OAuth metadata URL is invalid")
        require(resource.query == null && resource.fragment == null) {
            "Connector OAuth resource is invalid"
        }
        require(metadata.query == null && metadata.fragment == null) {
            "Connector OAuth metadata URL is invalid"
        }
        require(resource.host == metadata.host) {
            "Connector OAuth metadata host differs from resource host"
        }
        requireRedirect(value.redirectUri)
        require(value.scopes.isNotEmpty() && value.scopes.size <= MAX_SCOPES) {
            "Connector OAuth scopes are invalid"
        }
        value.scopes.forEach(::requireScope)
        require(value.allowedAuthorizationHosts.isNotEmpty() &&
            value.allowedAuthorizationHosts.size <= MAX_AUTHORIZATION_HOSTS) {
            "Connector OAuth authorization host allowlist is invalid"
        }
        value.allowedAuthorizationHosts.forEach { host ->
            require(host.length in 1..253 && host == host.lowercase() &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) {
                "Connector OAuth authorization host allowlist is invalid"
            }
        }
        value.clientId?.let(::requireClientId)
        require(value.clientId != null || value.allowDynamicClientRegistration) {
            "Connector OAuth definition has no client registration strategy"
        }
    }

    fun validatePending(value: PendingConnectorAuthorization) {
        requireConnectionId(value.connectionId)
        require(ID.matches(value.definitionId)) { "Connector OAuth definition id is invalid" }
        requireHttps(value.issuer, "issuer")
        requireHttps(value.resource, "resource")
        requireRedirect(value.redirectUri)
        requireClientId(value.clientId)
        require(value.scopes.isNotEmpty() && value.scopes.size <= MAX_SCOPES) {
            "Connector OAuth scopes are invalid"
        }
        value.scopes.forEach(::requireScope)
        require(STATE.matches(value.state)) { "Connector OAuth state is invalid" }
        require(CODE_VERIFIER.matches(value.codeVerifier)) {
            "Connector OAuth code verifier is invalid"
        }
        value.authorizationCode?.let { requireSecret(it, MAX_CODE_BYTES, "authorization code") }
        require(value.createdAtMillis > 0 && value.expiresAtMillis > value.createdAtMillis) {
            "Connector OAuth pending lifetime is invalid"
        }
        when (value.status) {
            PendingConnectorAuthorizationStatus.AWAITING_BROWSER ->
                require(value.authorizationCode == null) {
                    "Connector OAuth awaiting callback contains a code"
                }
            PendingConnectorAuthorizationStatus.CALLBACK_READY,
            PendingConnectorAuthorizationStatus.EXCHANGING,
            PendingConnectorAuthorizationStatus.OUTCOME_UNKNOWN,
            -> require(value.authorizationCode != null) {
                "Connector OAuth callback state has no code"
            }
        }
    }

    fun validateCredential(value: ConnectorOAuthCredential) {
        requireConnectionId(value.connectionId)
        require(ID.matches(value.definitionId)) { "Connector OAuth definition id is invalid" }
        requireHttps(value.issuer, "issuer")
        requireHttps(value.resource, "resource")
        requireClientId(value.clientId)
        requireSecret(value.accessToken, MAX_TOKEN_BYTES, "access token")
        value.refreshToken?.let { requireSecret(it, MAX_TOKEN_BYTES, "refresh token") }
        require(value.tokenType.equals("Bearer", ignoreCase = true)) {
            "Connector OAuth token type is invalid"
        }
        require(value.scopes.size <= MAX_SCOPES) { "Connector OAuth scopes are invalid" }
        value.scopes.forEach(::requireScope)
        require(value.expiresAtMillis > 0) { "Connector OAuth expiry is invalid" }
    }

    fun validateRegistration(value: ConnectorClientRegistration) {
        require(ID.matches(value.definitionId)) { "Connector OAuth definition id is invalid" }
        requireHttps(value.issuer, "issuer")
        requireRedirect(value.redirectUri)
        requireClientId(value.clientId)
        require(value.registeredAtMillis > 0) { "Connector OAuth registration time is invalid" }
    }

    fun requireConnectionId(value: String) {
        require(CONNECTION_ID.matches(value)) { "Connector OAuth connection id is invalid" }
    }

    fun requireScope(value: String) {
        require(SCOPE.matches(value)) { "Connector OAuth scope is invalid" }
    }

    fun requireClientId(value: String) {
        require(CLIENT_ID.matches(value)) { "Connector OAuth client id is invalid" }
    }

    fun requireSecret(value: String, maxBytes: Int, name: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..maxBytes && !value.any(Char::isISOControl)) {
            "Connector OAuth $name is invalid"
        }
    }

    fun requireHttps(value: String, name: String): HttpUrl {
        val url = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Connector OAuth $name is invalid")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
            "Connector OAuth $name must use HTTPS"
        }
        return url
    }

    fun requireRedirect(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme == "app.momoding" && uri.host == null &&
            uri.path == "/oauth2redirect" && uri.rawQuery == null && uri.rawFragment == null) {
            "Connector OAuth redirect URI is invalid"
        }
    }

    const val MAX_TOKEN_BYTES = 12 * 1024
    const val MAX_CODE_BYTES = 8 * 1024
    const val MAX_SCOPES = 32
    const val MAX_AUTHORIZATION_HOSTS = 8
}
