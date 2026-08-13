package app.momoding.core.connector

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectorOAuthProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var protocol: ConnectorOAuthProtocol
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), tunnelProxy = false)
        server.start()
        protocol = ConnectorOAuthProtocol(
            baseClient = OkHttpClient.Builder()
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager,
                )
                .build(),
            nowMillis = { now },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `HTTPS discovery DCR exchange refresh and revoke preserve exact bindings`() = runBlocking {
        val definition = definition()
        enqueueDiscovery(definition)
        server.enqueue(
            jsonResponse(
                """
                {
                  "client_id":"registered-public-client",
                  "client_id_issued_at":1800000000
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(tokenResponse("access-one", "refresh-one"))
        server.enqueue(tokenResponse("access-two", "refresh-two"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val discovery = protocol.discover(definition)
        val registration = protocol.registerClient(definition, discovery.authorizationServer)
        val pending = pending(
            registration = registration,
            status = PendingConnectorAuthorizationStatus.EXCHANGING,
            code = "authorization-code-secret",
        )
        val exchanged = protocol.exchangeAuthorizationCode(
            pending,
            discovery.authorizationServer,
        )
        val credential = credential(
            registration,
            exchanged,
            pending.connectionId,
        )
        val refreshed = protocol.refresh(credential, discovery.authorizationServer)
        protocol.revoke(
            credential.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
            ),
            discovery.authorizationServer,
        )

        assertEquals("registered-public-client", registration.clientId)
        assertEquals("access-one", exchanged.accessToken)
        assertEquals("access-two", refreshed.accessToken)
        assertEquals(now + 3_600_000L, refreshed.expiresAtMillis)

        val prm = server.takeRequest()
        val asMetadata = server.takeRequest()
        val dcr = server.takeRequest()
        val exchange = server.takeRequest()
        val refresh = server.takeRequest()
        val revoke = server.takeRequest()
        assertEquals("/.well-known/oauth-protected-resource", prm.requestUrl?.encodedPath)
        assertEquals("/.well-known/oauth-authorization-server", asMetadata.requestUrl?.encodedPath)
        val registrationBody = Json.parseToJsonElement(dcr.body.readUtf8()).jsonObject
        assertEquals("native", registrationBody.getValue("application_type").jsonPrimitive.content)
        assertEquals("none", registrationBody.getValue("token_endpoint_auth_method").jsonPrimitive.content)
        assertFalse(registrationBody.containsKey("client_secret"))
        val exchangeForm = parseForm(exchange.body.readUtf8())
        assertEquals("authorization_code", exchangeForm["grant_type"])
        assertEquals("authorization-code-secret", exchangeForm["code"])
        assertEquals(pending.codeVerifier, exchangeForm["code_verifier"])
        assertEquals(definition.redirectUri, exchangeForm["redirect_uri"])
        assertEquals(definition.resource, exchangeForm["resource"])
        assertEquals("refresh_token", parseForm(refresh.body.readUtf8())["grant_type"])
        assertEquals("refresh_token", parseForm(revoke.body.readUtf8())["token_type_hint"])
    }

    @Test
    fun `OIDC discovery fallback is accepted only with exact issuer and S256`() = runBlocking {
        val definition = definition(clientId = "preconfigured-public-client")
        server.enqueue(
            jsonResponse(
                """{
                  "resource":"${definition.resource}",
                  "authorization_servers":["${issuer()}"]
                }""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(jsonResponse(metadataJson()))

        val discovery = protocol.discover(definition)

        assertEquals(issuer(), discovery.authorizationServer.issuer)
        assertEquals("/.well-known/oauth-protected-resource", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/.well-known/openid-configuration", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `OIDC fallback appends well-known path to a path based issuer`() = runBlocking {
        val definition = definition(clientId = "preconfigured-public-client")
        val pathIssuer = server.url("/tenant").toString()
        server.enqueue(
            jsonResponse(
                """{
                  "resource":"${definition.resource}",
                  "authorization_servers":["$pathIssuer"]
                }""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(jsonResponse(metadataJson(issuerValue = pathIssuer)))

        assertEquals(pathIssuer, protocol.discover(definition).authorizationServer.issuer)
        assertEquals("/.well-known/oauth-protected-resource", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/.well-known/oauth-authorization-server/tenant", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/tenant/.well-known/openid-configuration", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `wrong resource issuer endpoint drift and missing S256 fail closed`() {
        val definition = definition()
        server.enqueue(
            jsonResponse(
                """{
                  "resource":"https://wrong.example/mcp",
                  "authorization_servers":["${issuer()}"]
                }""",
            ),
        )
        assertFailure(ConnectorOAuthFailureCode.INVALID_METADATA) {
            protocol.discover(definition)
        }

        enqueueDiscovery(
            definition,
            metadata = metadataJson(tokenEndpoint = "https://different.example/token"),
        )
        assertFailure(ConnectorOAuthFailureCode.INVALID_METADATA) {
            protocol.discover(definition)
        }

        enqueueDiscovery(
            definition,
            metadata = metadataJson(challengeMethods = "[\"plain\"]"),
        )
        assertFailure(ConnectorOAuthFailureCode.INVALID_METADATA) {
            protocol.discover(definition)
        }
    }

    @Test
    fun `token resource mismatch and provider body stay redacted`() = runBlocking {
        val definition = definition(clientId = "public-client")
        enqueueDiscovery(definition)
        val metadata = protocol.discover(definition).authorizationServer
        server.enqueue(
            jsonResponse(
                """{
                  "access_token":"access-token-secret",
                  "refresh_token":"refresh-token-secret",
                  "token_type":"Bearer",
                  "expires_in":3600,
                  "scope":"read",
                  "issuer":"${issuer()}",
                  "resource":"https://wrong.example/mcp"
                }""",
            ),
        )
        val error = assertThrows(ConnectorOAuthException::class.java) {
            runBlocking {
                protocol.exchangeAuthorizationCode(
                    pending(
                        registration = registration("public-client"),
                        status = PendingConnectorAuthorizationStatus.EXCHANGING,
                        code = "authorization-code-secret",
                    ),
                    metadata,
                )
            }
        }
        assertEquals(ConnectorOAuthFailureCode.TOKEN_EXCHANGE_FAILED, error.failureCode)
        assertFalse(error.stackTraceToString().contains("access-token-secret"))
        assertFalse(error.stackTraceToString().contains("refresh-token-secret"))

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("private authorization-code-secret upstream detail"),
        )
        val rejected = assertThrows(ConnectorOAuthException::class.java) {
            runBlocking {
                protocol.exchangeAuthorizationCode(
                    pending(
                        registration = registration("public-client"),
                        status = PendingConnectorAuthorizationStatus.EXCHANGING,
                        code = "authorization-code-secret",
                    ),
                    metadata,
                )
            }
        }
        assertEquals(401, rejected.httpStatus)
        assertFalse(rejected.stackTraceToString().contains("private"))
        assertFalse(rejected.stackTraceToString().contains("authorization-code-secret"))
    }

    @Test
    fun `caller cancellation closes in-flight HTTPS discovery`() = runBlocking {
        val definition = definition()
        server.enqueue(
            jsonResponse(
                """{
                  "resource":"${definition.resource}",
                  "authorization_servers":["${issuer()}"]
                }""",
            ).setBodyDelay(60, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.Default) { protocol.discover(definition) }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    private fun enqueueDiscovery(
        definition: ConnectorOAuthDefinition,
        metadata: String = metadataJson(),
    ) {
        server.enqueue(
            jsonResponse(
                """{
                  "resource":"${definition.resource}",
                  "authorization_servers":["${issuer()}"]
                }""",
            ),
        )
        server.enqueue(jsonResponse(metadata))
    }

    private fun metadataJson(
        tokenEndpoint: String = server.url("/oauth/token").toString(),
        challengeMethods: String = "[\"S256\"]",
        issuerValue: String = issuer(),
    ) = """
        {
          "issuer":"$issuerValue",
          "authorization_endpoint":"${server.url("/oauth/authorize")}",
          "token_endpoint":"$tokenEndpoint",
          "registration_endpoint":"${server.url("/oauth/register")}",
          "revocation_endpoint":"${server.url("/oauth/revoke")}",
          "scopes_supported":["read"],
          "code_challenge_methods_supported":$challengeMethods
        }
    """.trimIndent()

    private fun tokenResponse(access: String, refresh: String) = jsonResponse(
        """
        {
          "access_token":"$access",
          "refresh_token":"$refresh",
          "token_type":"Bearer",
          "expires_in":3600,
          "scope":"read",
          "issuer":"${issuer()}",
          "resource":"${server.url("/mcp")}"
        }
        """.trimIndent(),
    )

    private fun definition(clientId: String? = null) = ConnectorOAuthDefinition(
        id = "fixture",
        resource = server.url("/mcp").toString(),
        protectedResourceMetadataUrl =
            server.url("/.well-known/oauth-protected-resource").toString(),
        scopes = setOf("read"),
        allowedAuthorizationHosts = setOf("localhost"),
        clientId = clientId,
    )

    private fun issuer() = server.url("/").toString()

    private fun registration(clientId: String) = ConnectorClientRegistration(
        definitionId = "fixture",
        issuer = issuer(),
        redirectUri = ConnectorOAuthDefinition.DEFAULT_REDIRECT_URI,
        clientId = clientId,
        registeredAtMillis = now,
    )

    private fun pending(
        registration: ConnectorClientRegistration,
        status: PendingConnectorAuthorizationStatus,
        code: String?,
    ) = PendingConnectorAuthorization(
        connectionId = "conn_${"c".repeat(24)}",
        definitionId = "fixture",
        issuer = issuer(),
        resource = server.url("/mcp").toString(),
        redirectUri = ConnectorOAuthDefinition.DEFAULT_REDIRECT_URI,
        clientId = registration.clientId,
        scopes = setOf("read"),
        state = "s".repeat(43),
        codeVerifier = "v".repeat(64),
        authorizationCode = code,
        status = status,
        createdAtMillis = now - 1_000,
        expiresAtMillis = now + 600_000,
    )

    private fun credential(
        registration: ConnectorClientRegistration,
        token: ConnectorOAuthTokenResponse,
        connectionId: String,
    ) = ConnectorOAuthCredential(
        connectionId = connectionId,
        definitionId = registration.definitionId,
        issuer = registration.issuer,
        resource = server.url("/mcp").toString(),
        clientId = registration.clientId,
        accessToken = token.accessToken,
        refreshToken = token.refreshToken,
        tokenType = token.tokenType,
        scopes = token.scopes,
        expiresAtMillis = token.expiresAtMillis,
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun parseForm(body: String): Map<String, String> = body.split('&').associate { field ->
        val (key, value) = field.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8) to
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun assertFailure(
        expected: ConnectorOAuthFailureCode,
        block: suspend () -> Unit,
    ) {
        val error = assertThrows(ConnectorOAuthException::class.java) {
            runBlocking { block() }
        }
        assertEquals(expected, error.failureCode)
    }
}
