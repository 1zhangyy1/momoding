package app.momoding.core.connector

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class ConnectorOAuthDiscovery(
    val protectedResource: McpProtectedResourceMetadata,
    val authorizationServer: ConnectorAuthorizationServerMetadata,
)

interface ConnectorOAuthGateway {
    suspend fun discover(definition: ConnectorOAuthDefinition): ConnectorOAuthDiscovery

    suspend fun registerClient(
        definition: ConnectorOAuthDefinition,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorClientRegistration

    suspend fun exchangeAuthorizationCode(
        pending: PendingConnectorAuthorization,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthTokenResponse

    suspend fun refresh(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthTokenResponse

    suspend fun revoke(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    )
}

class ConnectorOAuthProtocol internal constructor(
    baseClient: OkHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ConnectorOAuthGateway {
    constructor() : this(OkHttpClient())

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun discover(
        definition: ConnectorOAuthDefinition,
    ): ConnectorOAuthDiscovery {
        ConnectorOAuthPolicy.validateDefinition(definition)
        val protectedResource = fetchProtectedResourceMetadata(definition)
        val issuers = protectedResource.authorizationServers.distinct()
        if (issuers.size != 1) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector authorization metadata is ambiguous",
            )
        }
        val issuer = requireAllowedAuthorizationUrl(
            issuers.single(),
            definition,
            "issuer",
        )
        val metadata = fetchAuthorizationServerMetadata(definition, issuer)
        validateMetadata(definition, metadata)
        return ConnectorOAuthDiscovery(protectedResource, metadata)
    }

    override suspend fun registerClient(
        definition: ConnectorOAuthDefinition,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorClientRegistration {
        ConnectorOAuthPolicy.validateDefinition(definition)
        validateMetadata(definition, metadata)
        if (!definition.allowDynamicClientRegistration) {
            throw failure(
                ConnectorOAuthFailureCode.CLIENT_REGISTRATION_FAILED,
                "This Connector does not permit dynamic client registration",
            )
        }
        val endpoint = metadata.registrationEndpoint ?: throw failure(
            ConnectorOAuthFailureCode.CLIENT_REGISTRATION_FAILED,
            "The authorization server does not support public client registration",
        )
        val requestBody = buildJsonObject {
            put("client_name", "Momoding")
            put("application_type", "native")
            put("token_endpoint_auth_method", "none")
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(definition.redirectUri)) })
            put(
                "grant_types",
                buildJsonArray {
                    add(JsonPrimitive("authorization_code"))
                    add(JsonPrimitive("refresh_token"))
                },
            )
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(requireAllowedAuthorizationUrl(endpoint, definition, "registration endpoint"))
            .post(requestBody)
            .header("Accept", "application/json")
            .build()
        return executeJson(request, "client registration") { root ->
            if (root.containsKey("client_secret")) {
                throw failure(
                    ConnectorOAuthFailureCode.CLIENT_REGISTRATION_FAILED,
                    "The authorization server returned a confidential client",
                )
            }
            ConnectorClientRegistration(
                definitionId = definition.id,
                issuer = metadata.issuer,
                redirectUri = definition.redirectUri,
                clientId = root.requiredString("client_id", MAX_CLIENT_ID_CHARS),
                registeredAtMillis = nowMillis(),
            ).also(ConnectorOAuthPolicy::validateRegistration)
        }
    }

    override suspend fun exchangeAuthorizationCode(
        pending: PendingConnectorAuthorization,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthTokenResponse {
        ConnectorOAuthPolicy.validatePending(pending)
        require(pending.status == PendingConnectorAuthorizationStatus.EXCHANGING) {
            "Connector OAuth authorization code was not claimed"
        }
        validateBoundMetadata(metadata, pending.issuer)
        val code = checkNotNull(pending.authorizationCode) {
            "Connector OAuth authorization code is missing"
        }
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", pending.clientId)
            .add("code", code)
            .add("code_verifier", pending.codeVerifier)
            .add("redirect_uri", pending.redirectUri)
            .add("resource", pending.resource)
            .build()
        return tokenRequest(
            endpoint = metadata.tokenEndpoint,
            issuer = pending.issuer,
            resource = pending.resource,
            expectedScopes = pending.scopes,
            body = body,
            operation = "authorization code exchange",
            failureCode = ConnectorOAuthFailureCode.TOKEN_EXCHANGE_FAILED,
        )
    }

    override suspend fun refresh(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ): ConnectorOAuthTokenResponse {
        ConnectorOAuthPolicy.validateCredential(credential)
        validateBoundMetadata(metadata, credential.issuer)
        val refreshToken = credential.refreshToken ?: throw failure(
            ConnectorOAuthFailureCode.REAUTH_REQUIRED,
            "This Connector must be authorized again",
        )
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", credential.clientId)
            .add("refresh_token", refreshToken)
            .add("resource", credential.resource)
            .build()
        val response = tokenRequest(
            endpoint = metadata.tokenEndpoint,
            issuer = credential.issuer,
            resource = credential.resource,
            expectedScopes = credential.scopes,
            body = body,
            operation = "token refresh",
            failureCode = ConnectorOAuthFailureCode.TOKEN_REFRESH_FAILED,
        )
        return if (response.refreshToken == null) {
            response.copy(refreshToken = refreshToken)
        } else {
            response
        }
    }

    override suspend fun revoke(
        credential: ConnectorOAuthCredential,
        metadata: ConnectorAuthorizationServerMetadata,
    ) {
        ConnectorOAuthPolicy.validateCredential(credential)
        validateBoundMetadata(metadata, credential.issuer)
        val endpoint = metadata.revocationEndpoint ?: return
        val token = credential.refreshToken ?: credential.accessToken
        val hint = if (credential.refreshToken == null) "access_token" else "refresh_token"
        val body = FormBody.Builder()
            .add("token", token)
            .add("token_type_hint", hint)
            .add("client_id", credential.clientId)
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Accept", "application/json")
            .build()
        execute(request, "token revocation") { response ->
            if (response.code !in 200..299) {
                throw httpFailure(
                    response,
                    ConnectorOAuthFailureCode.REVOCATION_FAILED,
                    "Connector token revocation failed",
                )
            }
        }
    }

    private suspend fun fetchProtectedResourceMetadata(
        definition: ConnectorOAuthDefinition,
    ): McpProtectedResourceMetadata {
        val request = Request.Builder()
            .url(definition.protectedResourceMetadataUrl)
            .get()
            .header("Accept", "application/json")
            .build()
        return executeJson(request, "protected resource discovery") { root ->
            val resource = root.requiredString("resource", MAX_URL_CHARS)
            if (resource != definition.resource) {
                throw failure(
                    ConnectorOAuthFailureCode.INVALID_METADATA,
                    "Connector resource metadata did not match the configured resource",
                )
            }
            val authorizationServers = root.requiredStringList(
                "authorization_servers",
                MAX_AUTHORIZATION_SERVERS,
                MAX_URL_CHARS,
            )
            authorizationServers.forEach {
                requireAllowedAuthorizationUrl(it, definition, "authorization server")
            }
            McpProtectedResourceMetadata(resource, authorizationServers)
        }
    }

    private suspend fun fetchAuthorizationServerMetadata(
        definition: ConnectorOAuthDefinition,
        issuer: HttpUrl,
    ): ConnectorAuthorizationServerMetadata {
        val oauthUrl = oauthAuthorizationServerMetadataUrl(issuer)
        val oauth = fetchOptionalMetadata(oauthUrl, "authorization server discovery")
        val root = oauth ?: fetchOptionalMetadata(
            openIdConfigurationUrl(issuer),
            "OpenID discovery",
        ) ?: throw failure(
            ConnectorOAuthFailureCode.DISCOVERY_FAILED,
            "Connector authorization metadata was not found",
        )
        return parseAuthorizationServerMetadata(root).also {
            if (runCatching { it.issuer.toHttpUrl() }.getOrNull() != issuer) {
                throw failure(
                    ConnectorOAuthFailureCode.INVALID_METADATA,
                    "Connector authorization issuer did not match discovery",
                )
            }
            validateMetadata(definition, it)
        }
    }

    private suspend fun fetchOptionalMetadata(
        url: HttpUrl,
        operation: String,
    ): JsonObject? {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        return execute(request, operation) { response ->
            if (response.code == 404) return@execute null
            if (!response.isSuccessful) {
                throw httpFailure(
                    response,
                    ConnectorOAuthFailureCode.DISCOVERY_FAILED,
                    "Connector authorization discovery failed",
                )
            }
            readBoundedJson(response)
        }
    }

    private fun parseAuthorizationServerMetadata(
        root: JsonObject,
    ): ConnectorAuthorizationServerMetadata = ConnectorAuthorizationServerMetadata(
        issuer = root.requiredString("issuer", MAX_URL_CHARS),
        authorizationEndpoint = root.requiredString("authorization_endpoint", MAX_URL_CHARS),
        tokenEndpoint = root.requiredString("token_endpoint", MAX_URL_CHARS),
        registrationEndpoint = root.optionalString("registration_endpoint", MAX_URL_CHARS),
        revocationEndpoint = root.optionalString("revocation_endpoint", MAX_URL_CHARS),
        scopesSupported = root.optionalStringList(
            "scopes_supported",
            ConnectorOAuthPolicy.MAX_SCOPES,
            MAX_SCOPE_CHARS,
        ).toSet(),
        codeChallengeMethodsSupported = root.requiredStringList(
            "code_challenge_methods_supported",
            MAX_CHALLENGE_METHODS,
            MAX_CHALLENGE_METHOD_CHARS,
        ).toSet(),
    )

    private fun validateMetadata(
        definition: ConnectorOAuthDefinition,
        metadata: ConnectorAuthorizationServerMetadata,
    ) {
        val issuer = requireAllowedAuthorizationUrl(metadata.issuer, definition, "issuer")
        listOf(
            "authorization endpoint" to metadata.authorizationEndpoint,
            "token endpoint" to metadata.tokenEndpoint,
        ).plus(
            listOfNotNull(
                metadata.registrationEndpoint?.let { "registration endpoint" to it },
                metadata.revocationEndpoint?.let { "revocation endpoint" to it },
            ),
        ).forEach { (name, value) ->
            val endpoint = requireAllowedAuthorizationUrl(value, definition, name)
            if (endpoint.scheme != issuer.scheme || endpoint.host != issuer.host ||
                endpoint.port != issuer.port) {
                throw failure(
                    ConnectorOAuthFailureCode.INVALID_METADATA,
                    "Connector $name drifted from the authorization issuer",
                )
            }
        }
        if ("S256" !in metadata.codeChallengeMethodsSupported) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector authorization server does not support PKCE S256",
            )
        }
        if (metadata.scopesSupported.isNotEmpty() &&
            !metadata.scopesSupported.containsAll(definition.scopes)) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector authorization server does not support the required scopes",
            )
        }
    }

    private fun validateBoundMetadata(
        metadata: ConnectorAuthorizationServerMetadata,
        expectedIssuer: String,
    ) {
        val issuer = ConnectorOAuthPolicy.requireHttps(expectedIssuer, "issuer")
        val returnedIssuer = ConnectorOAuthPolicy.requireHttps(metadata.issuer, "issuer")
        require(returnedIssuer == issuer) {
            "Connector OAuth issuer differs from stored credential"
        }
        listOfNotNull(
            metadata.authorizationEndpoint,
            metadata.tokenEndpoint,
            metadata.registrationEndpoint,
            metadata.revocationEndpoint,
        ).forEach { endpoint ->
            val url = ConnectorOAuthPolicy.requireHttps(endpoint, "endpoint")
            require(url.scheme == issuer.scheme && url.host == issuer.host &&
                url.port == issuer.port && url.query == null && url.fragment == null) {
                "Connector OAuth endpoint drifted from stored issuer"
            }
        }
        require("S256" in metadata.codeChallengeMethodsSupported) {
            "Connector OAuth metadata no longer supports PKCE S256"
        }
    }

    private suspend fun tokenRequest(
        endpoint: String,
        issuer: String,
        resource: String,
        expectedScopes: Set<String>,
        body: FormBody,
        operation: String,
        failureCode: ConnectorOAuthFailureCode,
    ): ConnectorOAuthTokenResponse {
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Accept", "application/json")
            .build()
        return execute(request, operation) { response ->
            if (!response.isSuccessful) {
                throw httpFailure(
                    response,
                    failureCode,
                    "Connector $operation failed",
                )
            }
            val root = readBoundedJson(response)
            root.optionalString("issuer", MAX_URL_CHARS)?.let { returnedIssuer ->
                if (returnedIssuer != issuer) {
                    throw failure(failureCode, "Connector token issuer did not match")
                }
            }
            root.optionalString("resource", MAX_URL_CHARS)?.let { returnedResource ->
                if (returnedResource != resource) {
                    throw failure(failureCode, "Connector token resource did not match")
                }
            }
            val returnedScopes = root.optionalString("scope", MAX_SCOPE_TEXT_CHARS)
                ?.split(' ')
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: expectedScopes
            returnedScopes.forEach(ConnectorOAuthPolicy::requireScope)
            if (!expectedScopes.containsAll(returnedScopes)) {
                throw failure(failureCode, "Connector token scope was broader than requested")
            }
            val expiresIn = root.requiredLong("expires_in", 1L..MAX_TOKEN_LIFETIME_SECONDS)
            ConnectorOAuthTokenResponse(
                accessToken = root.requiredString("access_token", ConnectorOAuthPolicy.MAX_TOKEN_BYTES),
                refreshToken = root.optionalString(
                    "refresh_token",
                    ConnectorOAuthPolicy.MAX_TOKEN_BYTES,
                ),
                tokenType = root.requiredString("token_type", MAX_TOKEN_TYPE_CHARS).also {
                    if (!it.equals("Bearer", ignoreCase = true)) {
                        throw failure(failureCode, "Connector token type is unsupported")
                    }
                },
                scopes = returnedScopes,
                expiresAtMillis = safeFutureMillis(expiresIn),
            )
        }
    }

    private suspend fun <T> executeJson(
        request: Request,
        operation: String,
        parse: (JsonObject) -> T,
    ): T = execute(request, operation) { response ->
        if (!response.isSuccessful) {
            throw httpFailure(
                response,
                operation.failureCode(),
                "Connector $operation failed",
            )
        }
        parse(readBoundedJson(response))
    }

    private suspend fun <T> execute(
        request: Request,
        operation: String,
        parse: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled || call.isCanceled()) return
                    val timeout = e is SocketTimeoutException
                    val error = failure(
                        if (timeout) ConnectorOAuthFailureCode.TIMEOUT
                        else ConnectorOAuthFailureCode.NETWORK,
                        if (timeout) "Connector $operation timed out"
                        else "Connector $operation failed",
                        retryable = true,
                    )
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = try {
                        Result.success(response.use(parse))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: ConnectorOAuthException) {
                        Result.failure(error)
                    } catch (_: Exception) {
                        Result.failure(
                            failure(
                                operation.failureCode(),
                                "Connector $operation returned an invalid response",
                            ),
                        )
                    }
                    outcome.fold(
                        onSuccess = { if (continuation.isActive) continuation.resume(it) },
                        onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                    )
                }
            },
        )
    }

    private fun readBoundedJson(response: Response): JsonObject {
        val source = response.body.source()
        source.request(MAX_RESPONSE_BYTES.toLong() + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector OAuth response was too large",
            )
        }
        return json.parseToJsonElement(source.readUtf8()).jsonObject
    }

    private fun httpFailure(
        response: Response,
        code: ConnectorOAuthFailureCode,
        message: String,
    ) = ConnectorOAuthException(
        failureCode = code,
        httpStatus = response.code,
        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
        safeMessage = message,
    )

    private fun requireAllowedAuthorizationUrl(
        value: String,
        definition: ConnectorOAuthDefinition,
        name: String,
    ): HttpUrl {
        val url = ConnectorOAuthPolicy.requireHttps(value, name)
        if (url.host !in definition.allowedAuthorizationHosts) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector $name host is not allowed",
            )
        }
        if (url.query != null || url.fragment != null) {
            throw failure(
                ConnectorOAuthFailureCode.INVALID_METADATA,
                "Connector $name URL is invalid",
            )
        }
        return url
    }

    private fun oauthAuthorizationServerMetadataUrl(issuer: HttpUrl): HttpUrl {
        val issuerPath = issuer.encodedPath.trim('/')
        val suffix = ".well-known/oauth-authorization-server"
        val path = if (issuerPath.isEmpty()) suffix else "$suffix/$issuerPath"
        return issuer.newBuilder()
            .encodedPath("/$path")
            .query(null)
            .fragment(null)
            .build()
    }

    private fun openIdConfigurationUrl(issuer: HttpUrl): HttpUrl {
        val issuerPath = issuer.encodedPath.trimEnd('/')
        return issuer.newBuilder()
            .encodedPath("$issuerPath/.well-known/openid-configuration")
            .query(null)
            .fragment(null)
            .build()
    }

    private fun safeFutureMillis(seconds: Long): Long =
        Math.addExact(nowMillis(), Math.multiplyExact(seconds, 1_000L))

    private fun JsonObject.requiredString(name: String, maxChars: Int): String {
        val primitive = getValue(name).jsonPrimitive
        if (!primitive.isString) throw IllegalArgumentException("OAuth field is invalid")
        return primitive.content.also { value ->
            ConnectorOAuthPolicy.requireSecret(value, maxChars, "response field")
        }
    }

    private fun JsonObject.optionalString(name: String, maxChars: Int): String? {
        val value = get(name) ?: return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("OAuth field is invalid")
        if (!primitive.isString) throw IllegalArgumentException("OAuth field is invalid")
        return primitive.content.also {
            ConnectorOAuthPolicy.requireSecret(it, maxChars, "response field")
        }
    }

    private fun JsonObject.requiredLong(name: String, range: LongRange): Long {
        val primitive = getValue(name).jsonPrimitive
        return primitive.longOrNull?.takeIf(range::contains)
            ?: throw IllegalArgumentException("OAuth numeric field is invalid")
    }

    private fun JsonObject.requiredStringList(
        name: String,
        maxItems: Int,
        maxChars: Int,
    ): List<String> = (getValue(name) as? JsonArray)
        ?.takeIf { it.size in 1..maxItems }
        ?.map { element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("OAuth list field is invalid")
            if (!primitive.isString) throw IllegalArgumentException("OAuth list field is invalid")
            primitive.content.also {
                ConnectorOAuthPolicy.requireSecret(it, maxChars, "response field")
            }
        }
        ?: throw IllegalArgumentException("OAuth list field is invalid")

    private fun JsonObject.optionalStringList(
        name: String,
        maxItems: Int,
        maxChars: Int,
    ): List<String> = get(name)?.let { value ->
        (value as? JsonArray)
            ?.takeIf { it.size <= maxItems }
            ?.map { element ->
                val primitive = element as? JsonPrimitive
                    ?: throw IllegalArgumentException("OAuth list field is invalid")
                if (!primitive.isString) throw IllegalArgumentException("OAuth list field is invalid")
                primitive.content.also {
                    ConnectorOAuthPolicy.requireSecret(it, maxChars, "response field")
                }
            }
            ?: throw IllegalArgumentException("OAuth list field is invalid")
    } ?: emptyList()

    private fun String.failureCode(): ConnectorOAuthFailureCode = when {
        contains("registration") -> ConnectorOAuthFailureCode.CLIENT_REGISTRATION_FAILED
        contains("discovery") -> ConnectorOAuthFailureCode.DISCOVERY_FAILED
        contains("exchange") -> ConnectorOAuthFailureCode.TOKEN_EXCHANGE_FAILED
        contains("refresh") -> ConnectorOAuthFailureCode.TOKEN_REFRESH_FAILED
        contains("revocation") -> ConnectorOAuthFailureCode.REVOCATION_FAILED
        else -> ConnectorOAuthFailureCode.INVALID_METADATA
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val WRITE_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_URL_CHARS = 4 * 1024
        const val MAX_CLIENT_ID_CHARS = 2 * 1024
        const val MAX_SCOPE_CHARS = 128
        const val MAX_SCOPE_TEXT_CHARS = 4 * 1024
        const val MAX_TOKEN_TYPE_CHARS = 32
        const val MAX_AUTHORIZATION_SERVERS = 3
        const val MAX_CHALLENGE_METHODS = 8
        const val MAX_CHALLENGE_METHOD_CHARS = 32
        const val MAX_TOKEN_LIFETIME_SECONDS = 30L * 24L * 60L * 60L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun failure(
    code: ConnectorOAuthFailureCode,
    message: String,
    retryable: Boolean = false,
) = ConnectorOAuthException(code, retryable = retryable, safeMessage = message)
