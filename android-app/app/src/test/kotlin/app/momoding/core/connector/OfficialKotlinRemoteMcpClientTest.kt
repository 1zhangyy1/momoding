package app.momoding.core.connector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class OfficialKotlinRemoteMcpClientTest {
    @Test
    fun `official SDK initializes lists and calls through narrow boundary`() = runBlocking {
        withFixture { server, dispatcher ->
            val client = client(server, headers = { mapOf("Authorization" to "Bearer fixture") })

            val info = client.discover()
            val tools = client.listTools()
            val result = client.callTool("search_records", buildJsonObject { put("query", "alpha") })
            client.close()

            assertEquals("deterministic-mcp", info.name)
            assertEquals("1.0.0", info.version)
            assertEquals("2025-11-25", info.protocolVersion)
            assertEquals(listOf("search_records"), tools.map(RemoteMcpTool::name))
            assertEquals("object", tools.single().inputSchema["type"]?.jsonPrimitive?.content)
            assertEquals("one deterministic result", result.content.single()["text"]?.jsonPrimitive?.content)
            assertEquals("1", result.structuredContent?.get("count")?.jsonPrimitive?.content)
            assertFalse(result.isError)

            val applicationRequests = dispatcher.observedRequests.filter {
                it.method == "tools/list" || it.method == "tools/call"
            }
            assertTrue(applicationRequests.isNotEmpty())
            assertTrue(applicationRequests.all { it.protocolVersion == "2025-11-25" })
            assertTrue(dispatcher.observedRequests.all { it.authorization == "Bearer fixture" })
            assertEquals(1, dispatcher.terminatedSessions.get())
            assertEquals(listOf("Bearer fixture"), dispatcher.terminatedAuthorizations)
        }
    }

    @Test
    fun `HTTP status failures map without exposing response bodies`() = runBlocking {
        val cases = mapOf(
            401 to RemoteMcpFailureCode.UNAUTHORIZED,
            403 to RemoteMcpFailureCode.FORBIDDEN,
            404 to RemoteMcpFailureCode.NOT_FOUND,
            429 to RemoteMcpFailureCode.RATE_LIMITED,
            500 to RemoteMcpFailureCode.SERVER_ERROR,
        )
        cases.forEach { (status, expected) ->
            withFixture { server, dispatcher ->
                dispatcher.statusForMethod = "initialize" to status
                val failure = expectRemoteFailure { client(server).discover() }

                assertEquals(expected, failure.failureCode)
                assertEquals(status, failure.httpStatus)
                assertFalse(failure.stackTraceToString().contains("private upstream detail"))
            }
        }
    }

    @Test
    fun `malformed JSON and hostile schema fail closed`() = runBlocking {
        withFixture { server, dispatcher ->
            dispatcher.malformedMethod = "tools/list"
            val client = client(server)
            assertEquals(RemoteMcpFailureCode.INVALID_RESPONSE, expectRemoteFailure {
                client.listTools()
            }.failureCode)
            client.close()
        }
        withFixture { server, dispatcher ->
            dispatcher.inputSchema = DeterministicMcpDispatcher.nestedInputSchema(depth = 24)
            val client = client(server)
            assertEquals(RemoteMcpFailureCode.LIMIT_EXCEEDED, expectRemoteFailure {
                client.listTools()
            }.failureCode)
            client.close()
        }
    }

    @Test
    fun `oversized SSE and tool output are bounded`() = runBlocking {
        withFixture { server, dispatcher ->
            dispatcher.oversizedSseMethod = "initialize"
            val client = client(
                server,
                limits = OfficialKotlinRemoteMcpClient.Limits(maxInlineSseEventChars = 256),
            )
            assertEquals(RemoteMcpFailureCode.LIMIT_EXCEEDED, expectRemoteFailure {
                client.discover()
            }.failureCode)
            client.close()
        }
        withFixture { server, dispatcher ->
            dispatcher.callText = "x".repeat(2_048)
            dispatcher.chunkedMethod = "tools/call"
            val client = client(
                server,
                limits = OfficialKotlinRemoteMcpClient.Limits(maxHttpResponseBytes = 512),
            )
            assertEquals(RemoteMcpFailureCode.LIMIT_EXCEEDED, expectRemoteFailure {
                client.callTool("search_records", buildJsonObject {})
            }.failureCode)
            client.close()
        }
        withFixture { server, dispatcher ->
            dispatcher.callText = "x".repeat(4_096)
            dispatcher.gzipMethod = "tools/call"
            val client = client(
                server,
                limits = OfficialKotlinRemoteMcpClient.Limits(maxHttpResponseBytes = 512),
            )
            assertEquals(RemoteMcpFailureCode.LIMIT_EXCEEDED, expectRemoteFailure {
                client.callTool("search_records", buildJsonObject {})
            }.failureCode)
            client.close()
        }
        withFixture { server, dispatcher ->
            dispatcher.callText = "x".repeat(2_048)
            val client = client(
                server,
                limits = OfficialKotlinRemoteMcpClient.Limits(maxResultChars = 512),
            )
            assertEquals(RemoteMcpFailureCode.LIMIT_EXCEEDED, expectRemoteFailure {
                client.callTool("search_records", buildJsonObject {})
            }.failureCode)
            client.close()
        }
    }

    @Test
    fun `request timeout is stable and caller cancellation propagates`() = runBlocking {
        withFixture { server, dispatcher ->
            dispatcher.delayedMethod = "tools/call"
            val client = client(
                server,
                limits = OfficialKotlinRemoteMcpClient.Limits(requestTimeout = 150.milliseconds),
            )
            assertEquals(RemoteMcpFailureCode.TIMEOUT, expectRemoteFailure {
                client.callTool("search_records", buildJsonObject {})
            }.failureCode)
            client.close()
        }
        withFixture { server, dispatcher ->
            dispatcher.delayedMethod = "tools/call"
            val client = client(server)
            val call = async { client.callTool("search_records", buildJsonObject {}) }
            withTimeout(2_000) {
                while (dispatcher.observedRequests.none { it.method == "tools/call" }) delay(10)
            }
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertCancellation { call.await() }
            client.close()
        }
    }

    @Test
    fun `close is idempotent and prevents reuse`() = runBlocking {
        withFixture { server, _ ->
            val client = client(server)
            client.discover()
            client.close()
            client.close()

            assertEquals(RemoteMcpFailureCode.CLOSED, expectRemoteFailure {
                client.listTools()
            }.failureCode)
        }
        withFixture { server, dispatcher ->
            val client = client(server)
            client.close()
            client.close()

            assertEquals(0, dispatcher.observedRequests.size)
            assertEquals(RemoteMcpFailureCode.CLOSED, expectRemoteFailure {
                client.discover()
            }.failureCode)
        }
    }

    @Test
    fun `twenty complete lifecycles leave no reusable client session`() = runBlocking {
        withFixture { server, dispatcher ->
            repeat(20) {
                val client = client(server)
                client.discover()
                client.listTools()
                client.callTool("search_records", buildJsonObject { put("iteration", it) })
                client.close()
            }

            assertEquals(20, dispatcher.observedRequests.count { it.method == "initialize" })
            assertEquals(20, dispatcher.observedRequests.count { it.method == "tools/list" })
            assertEquals(20, dispatcher.observedRequests.count { it.method == "tools/call" })
            assertEquals(20, dispatcher.terminatedSessions.get())
        }
    }

    private fun client(
        server: MockWebServer,
        headers: () -> Map<String, String> = { emptyMap() },
        limits: OfficialKotlinRemoteMcpClient.Limits = OfficialKotlinRemoteMcpClient.Limits(),
    ): OfficialKotlinRemoteMcpClient = OfficialKotlinRemoteMcpClient.createAndroid(
        endpointUrl = server.url("/mcp").toString(),
        requestHeaders = headers,
        limits = limits,
    )

    private suspend fun expectRemoteFailure(block: suspend () -> Unit): RemoteMcpException = try {
        block()
        fail("Expected RemoteMcpException")
        error("unreachable")
    } catch (failure: RemoteMcpException) {
        failure
    }

    private suspend fun assertCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected: cancellation is never converted to an ordinary connector failure.
        }
    }

    private suspend fun withFixture(
        block: suspend (MockWebServer, DeterministicMcpDispatcher) -> Unit,
    ) {
        val dispatcher = DeterministicMcpDispatcher()
        MockWebServer().use { server ->
            server.dispatcher = dispatcher
            server.start()
            block(server, dispatcher)
        }
    }
}
