package app.momoding.core.provider

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodexOAuthProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var protocol: CodexOAuthProtocol
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        protocol = CodexOAuthProtocol(
            authBaseUrl = server.url("/"),
            baseClient = OkHttpClient(),
            nowMillis = { now },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `device start uses exact endpoint and returns bounded redacted challenge`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"device_auth_id":"device-secret","user_code":"ABCD-EFGH","interval":"5"}""",
            ),
        )

        val authorization = protocol.startDeviceAuthorization()

        assertEquals("ABCD-EFGH", authorization.userCode)
        assertEquals("https://auth.openai.com/codex/device", authorization.verificationUri)
        assertEquals(5L, authorization.intervalSeconds)
        assertEquals(now + 15 * 60 * 1_000L, authorization.expiresAtMillis)
        assertFalse(authorization.toString().contains("device-secret"))
        assertFalse(authorization.toString().contains("ABCD-EFGH"))
        val request = server.takeRequest()
        assertEquals("/api/accounts/deviceauth/usercode", request.requestUrl?.encodedPath)
        assertEquals("POST", request.method)
        assertEquals(
            "app_EMoamEEZ73f0CkXaXp7hrann",
            Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                .getValue("client_id").jsonPrimitive.content,
        )
    }

    @Test
    fun `poll handles pending and slow down then exchanges code without leaking secrets`() = runBlocking {
        val authorization = authorization()
        server.enqueue(MockResponse().setResponseCode(403).setBody("private pending detail"))
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Retry-After", "12")
                .setBody("""{"error":"slow_down"}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"authorization_code":"authorization-secret","code_verifier":"verifier-secret"}""",
            ),
        )
        server.enqueue(tokenResponse("access-one", "refresh-one", 3_600))

        assertEquals(CodexDevicePollResult.Pending(5), protocol.pollDeviceAuthorization(authorization))
        assertEquals(CodexDevicePollResult.Pending(12), protocol.pollDeviceAuthorization(authorization))
        val authorized = protocol.pollDeviceAuthorization(authorization)
            as CodexDevicePollResult.Authorized

        assertEquals("account-123", authorized.credential.accountId)
        assertEquals(now + 3_600_000L, authorized.credential.expiresAtMillis)
        assertFalse(authorized.credential.toString().contains("access-one"))
        assertFalse(authorized.credential.toString().contains("refresh-one"))

        val pending = server.takeRequest()
        val slowDown = server.takeRequest()
        val ready = server.takeRequest()
        val exchange = server.takeRequest()
        assertEquals("/api/accounts/deviceauth/token", pending.requestUrl?.encodedPath)
        assertEquals("/api/accounts/deviceauth/token", slowDown.requestUrl?.encodedPath)
        assertEquals("/api/accounts/deviceauth/token", ready.requestUrl?.encodedPath)
        assertEquals("/oauth/token", exchange.requestUrl?.encodedPath)
        val form = parseForm(exchange.body.readUtf8())
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("authorization-secret", form["code"])
        assertEquals("verifier-secret", form["code_verifier"])
        assertEquals("https://auth.openai.com/deviceauth/callback", form["redirect_uri"])
    }

    @Test
    fun `refresh rotates credential and malformed or provider errors fail redacted`() = runBlocking {
        server.enqueue(tokenResponse("access-two", "refresh-two", 600))
        val refreshed = protocol.refresh("old-refresh-secret")
        assertEquals("account-123", refreshed.accountId)
        val refreshRequest = server.takeRequest()
        assertEquals("/oauth/token", refreshRequest.requestUrl?.encodedPath)
        assertEquals("refresh_token", parseForm(refreshRequest.body.readUtf8())["grant_type"])

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("private upstream old-refresh-secret detail"),
        )
        val rejected = assertThrows(CodexOAuthProtocolException::class.java) {
            runBlocking { protocol.refresh("old-refresh-secret") }
        }
        assertEquals(401, rejected.statusCode)
        assertFalse(rejected.toString().contains("old-refresh-secret"))
        assertFalse(rejected.toString().contains("private upstream"))

        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"not-a-jwt","refresh_token":"refresh-three","expires_in":600}""",
            ),
        )
        val malformed = assertThrows(CodexOAuthProtocolException::class.java) {
            runBlocking { protocol.refresh("old-refresh-secret") }
        }
        assertEquals("invalid_response", malformed.errorType)
        assertFalse(malformed.toString().contains("not-a-jwt"))
    }

    @Test
    fun `explicit device policy error fails immediately instead of polling until expiry`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"device_code_disabled","message":"private"}}"""),
        )

        val error = assertThrows(CodexOAuthProtocolException::class.java) {
            runBlocking { protocol.pollDeviceAuthorization(authorization()) }
        }

        assertEquals("device_poll_device_code_disabled", error.errorType)
        assertEquals(403, error.statusCode)
        assertFalse(error.toString().contains("private"))
    }

    @Test
    fun `expired authorization makes no request and cancellation closes in-flight start`() = runBlocking {
        val expired = authorization().copy(expiresAtMillis = now)
        val error = assertThrows(CodexOAuthProtocolException::class.java) {
            runBlocking { protocol.pollDeviceAuthorization(expired) }
        }
        assertEquals("expired", error.errorType)
        assertEquals(0, server.requestCount)

        server.enqueue(
            MockResponse()
                .setBody(
                    """{"device_auth_id":"device-secret","user_code":"ABCD-EFGH","interval":5}""",
                )
                .setBodyDelay(60, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.Default) { protocol.startDeviceAuthorization() }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `coordinator obeys interval slow down and expiry without busy polling`() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val gateway = SequenceOAuthGateway(
            listOf(
                CodexDevicePollResult.Pending(10),
                CodexDevicePollResult.Pending(10),
                CodexDevicePollResult.Authorized(credential()),
            ),
        )
        val coordinator = CodexDeviceAuthorizationCoordinator(
            gateway = gateway,
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
            },
        )

        val result = coordinator.awaitCredential(
            authorization().copy(expiresAtMillis = now + 60_000),
        )

        assertEquals("account-123", result.accountId)
        assertEquals(listOf(5_000L, 10_000L, 10_000L), sleeps)
        assertEquals(3, gateway.pollCount)
    }

    private fun authorization() = CodexDeviceAuthorization(
        deviceAuthId = "device-secret",
        userCode = "ABCD-EFGH",
        verificationUri = "https://auth.openai.com/codex/device",
        intervalSeconds = 5,
        expiresAtMillis = now + 15 * 60 * 1_000L,
    )

    private fun credential() = CodexOAuthCredential(
        accessToken = jwt("access"),
        refreshToken = "refresh-token",
        expiresAtMillis = now + 3_600_000L,
        accountId = "account-123",
    )

    private fun tokenResponse(accessSuffix: String, refresh: String, expiresIn: Int) =
        MockResponse().setBody(
            """
            {
              "access_token": "${jwt(accessSuffix)}",
              "refresh_token": "$refresh",
              "expires_in": $expiresIn,
              "ignored_forward_compatible_field": true
            }
            """.trimIndent(),
        )

    private fun jwt(suffix: String): String {
        val payload =
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"account-123"},"jti":"$suffix"}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "header.$encoded.signature"
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').associate { field ->
        val (key, value) = field.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8) to
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}

private class SequenceOAuthGateway(
    results: List<CodexDevicePollResult>,
) : CodexOAuthGateway {
    private val remaining = ArrayDeque(results)
    var pollCount = 0

    override suspend fun startDeviceAuthorization(): CodexDeviceAuthorization = error("unused")

    override suspend fun pollDeviceAuthorization(
        authorization: CodexDeviceAuthorization,
    ): CodexDevicePollResult {
        pollCount += 1
        return remaining.removeFirst()
    }

    override suspend fun refresh(refreshToken: String): CodexOAuthCredential = error("unused")
}
