package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAIStreamIdleTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setChunkedBody(": keepalive\n\n".repeat(1_000), 16)
            .throttleBody(16, 20, TimeUnit.MILLISECONDS))
    }

    @After fun stop() = server.shutdown()

    private fun stream() = OpenAIProvider(
        apiKey = "test-only",
        model = LLMModel(id = "test-stream", displayName = "Test", provider = "OpenAI"),
        basePath = server.url("/").toString().trimEnd('/'),
        useResponsesAPI = false,
    ).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello")), null, 32)

    @Test(timeout = 8_000) fun `keepalive without model output times out and closes the reader`() = runBlocking {
        val chunks = mutableListOf<LLMStreamChunk>()
        val began = System.nanoTime()
        try {
            withTimeout(5_000) {
                stream().withIdleTimeout(200, startAfterFirst = true) {
                    throw LLMError.TransientError("idle-probe")
                }.toList(chunks)
            }
            throw AssertionError("keepalive is not model output")
        } catch (e: LLMError.TransientError) {
            assertTrue(e.message.orEmpty().contains("idle-probe"))
            assertTrue(e.isRetryable)
        }
        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 4_000)
    }

    @Test(timeout = 8_000) fun `cancelling after Started does not wait for the socket read timeout`() = runBlocking {
        val began = System.nanoTime()
        val chunks = withTimeout(5_000) { stream().take(1).toList() }
        assertEquals(1, chunks.size)
        assertTrue(chunks.single() is LLMStreamChunk.Started)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 4_000)
    }
}
