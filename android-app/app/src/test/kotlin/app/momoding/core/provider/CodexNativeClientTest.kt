package app.momoding.core.provider

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CodexNativeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var credentials: FakeCodexCredentialSource
    private lateinit var client: CodexNativeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = FakeCodexCredentialSource()
        client = CodexNativeClient(
            endpoint = server.url("/backend-api/codex/responses"),
            credentialSource = credentials,
            baseClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams exact Codex Responses request without exposing refresh token`() = runBlocking {
        server.enqueue(successResponse("response-1"))
        val events = mutableListOf<kotlinx.serialization.json.JsonObject>()

        val result = client.stream(request(), events::add)

        assertEquals(3, result.eventCount)
        assertEquals(3, events.size)
        assertEquals("response.created", events.first()["type"]?.jsonPrimitive?.content)
        val recorded = requireNotNull(server.takeRequest())
        assertEquals("/backend-api/codex/responses", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer old-access", recorded.getHeader("Authorization"))
        assertEquals("account-123", recorded.getHeader("chatgpt-account-id"))
        assertEquals("pi", recorded.getHeader("originator"))
        assertEquals("responses=experimental", recorded.getHeader("OpenAI-Beta"))
        assertEquals("session-123", recorded.getHeader("session-id"))
        assertEquals("session-123", recorded.getHeader("x-client-request-id"))
        val body = recorded.body.readUtf8()
        assertFalse(body.contains("old-access"))
        assertFalse(body.contains("refresh-token"))
    }

    @Test
    fun `401 refreshes once before stream and retries with rotated credential`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private auth detail"))
        server.enqueue(successResponse("response-after-refresh"))

        val result = client.stream(request()) {}

        assertEquals(3, result.eventCount)
        assertEquals(1, credentials.refreshCount.get())
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `second 401 fails without refresh loop or secret echo`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("first secret"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("second secret"))

        val error = assertThrows(CodexTransportException::class.java) {
            runBlocking { client.stream(request()) {} }
        }

        assertEquals(401, error.statusCode)
        assertEquals(1, credentials.refreshCount.get())
        assertEquals(2, server.requestCount)
        assertFalse(error.toString().contains("second secret"))
        assertFalse(error.toString().contains("new-access"))
    }

    @Test
    fun `malformed or unterminated SSE fails closed without replay`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"),
        )
        val delivered = mutableListOf<kotlinx.serialization.json.JsonObject>()

        val error = assertThrows(CodexTransportException::class.java) {
            runBlocking { client.stream(request(), delivered::add) }
        }

        assertEquals(CodexTransportFailurePhase.MID_STREAM, error.phase)
        assertEquals(1, delivered.size)
        assertEquals(0, credentials.refreshCount.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `invalid request body fails before credential or network`() {
        val invalid = request().copy(
            body = request().body.toMutableMap().let { fields ->
                buildJsonObject { fields.forEach(::put); put("store", true) }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.stream(invalid) {} }
        }

        assertEquals(0, credentials.requireCount.get())
        assertEquals(0, server.requestCount)
    }

    private fun request(): CodexResponsesRequest = CodexResponsesRequest(
        modelId = "gpt-5.4",
        sessionId = "session-123",
        body = buildJsonObject {
            put("model", "gpt-5.4")
            put("store", false)
            put("stream", true)
            put(
                "input",
                buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", "hello") })
                },
            )
        },
    )

    private fun successResponse(responseId: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            """
            : keepalive

            data: {"type":"response.created","response":{"id":"$responseId"}}

            data: {"type":"response.output_text.delta","output_index":0,"delta":"hello"}

            data: {"type":"response.done","response":{"id":"$responseId","status":"completed"}}

            data: [DONE]

            """.trimIndent(),
        )
}

private class FakeCodexCredentialSource : CodexCredentialSource {
    val requireCount = AtomicInteger()
    val refreshCount = AtomicInteger()

    override suspend fun requireValidCredential(): CodexOAuthCredential {
        requireCount.incrementAndGet()
        return credential("old-access")
    }

    override suspend fun refreshAfterUnauthorized(
        rejectedAccessToken: String,
    ): CodexOAuthCredential {
        assertEquals("old-access", rejectedAccessToken)
        refreshCount.incrementAndGet()
        return credential("new-access")
    }

    private fun credential(accessToken: String) = CodexOAuthCredential(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        expiresAtMillis = 1_900_000_000_000L,
        accountId = "account-123",
    )
}
