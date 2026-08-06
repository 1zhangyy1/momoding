package app.momoding.core.provider

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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenRouterImageGenerationGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: OpenRouterImageGenerationGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = OpenRouterImageGenerationGateway(
            endpoint = server.url("/api/v1"),
            baseClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `discovers typed image models then sends one bounded dedicated image request`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [{
                    "id": "$IMAGE_MODEL_ID",
                    "name": "GPT Image 2",
                    "architecture": {
                      "input_modalities": ["text", "image"],
                      "output_modalities": ["image"]
                    },
                    "supported_parameters": {
                      "resolution": {"type": "enum", "values": ["1K", "2K"]},
                      "aspect_ratio": {"type": "enum", "values": ["1:1", "16:9"]},
                      "quality": {"type": "enum", "values": ["auto", "high"]},
                      "output_format": {"type": "enum", "values": ["png", "jpeg"]}
                    },
                    "supports_streaming": true
                  }, {
                    "id": "recraft/vector-only",
                    "name": "Vector only",
                    "architecture": {
                      "input_modalities": ["text"],
                      "output_modalities": ["image"]
                    },
                    "supported_parameters": {
                      "output_format": {"type": "enum", "values": ["svg"]}
                    },
                    "supports_streaming": false
                  }]
                }
                """.trimIndent(),
            ),
        )
        val imageBytes = PNG_HEADER + byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "created": 1748372400,
                  "data": [{
                    "b64_json": "${Base64.getEncoder().encodeToString(imageBytes)}",
                    "media_type": "image/png"
                  }],
                  "usage": {
                    "prompt_tokens": 3,
                    "completion_tokens": 40,
                    "total_tokens": 43,
                    "cost": 0.04
                  }
                }
                """.trimIndent(),
            ),
        )

        val model = gateway.listModels(API_KEY).single()
        val result = gateway.generate(
            credential(),
            OpenRouterImageGenerationRequest(
                model = model,
                prompt = " A small green robot ",
                aspectRatio = "16:9",
                quality = "high",
            ),
        )

        assertEquals(IMAGE_MODEL_ID, model.id)
        assertEquals(true, model.supportsStreaming)
        assertEquals(true, model.supports("aspect_ratio", "16:9"))
        assertArrayEquals(imageBytes, result.bytes)
        assertEquals("image/png", result.mimeType)
        assertEquals(0.04, result.usage?.costUsd ?: 0.0, 0.0)

        val modelsRequest = server.takeRequest()
        assertEquals("/api/v1/images/models", modelsRequest.requestUrl?.encodedPath)
        assertEquals("Bearer $API_KEY", modelsRequest.getHeader("Authorization"))

        val generateRequest = server.takeRequest()
        assertEquals("/api/v1/images", generateRequest.requestUrl?.encodedPath)
        assertEquals("Bearer $API_KEY", generateRequest.getHeader("Authorization"))
        val rawBody = generateRequest.body.readUtf8()
        assertFalse(rawBody.contains(API_KEY))
        val body = Json.parseToJsonElement(rawBody).jsonObject
        assertEquals(IMAGE_MODEL_ID, body.getValue("model").jsonPrimitive.content)
        assertEquals("A small green robot", body.getValue("prompt").jsonPrimitive.content)
        assertEquals("1", body.getValue("n").jsonPrimitive.content)
        assertEquals("png", body.getValue("output_format").jsonPrimitive.content)
        assertEquals("16:9", body.getValue("aspect_ratio").jsonPrimitive.content)
        assertEquals("high", body.getValue("quality").jsonPrimitive.content)
        assertEquals("1K", body.getValue("resolution").jsonPrimitive.content)
    }

    @Test
    fun `provider errors stay redacted and mismatched image bytes fail closed`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setBody("""{"error":{"message":"private upstream detail"}}"""),
        )
        val rateLimit = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { gateway.generate(credential(), request()) }
        }
        assertEquals("rate_limit_exceeded", rateLimit.errorType)
        assertEquals(30L, rateLimit.retryAfterSeconds)
        assertFalse(rateLimit.toString().contains("private upstream detail"))
        assertFalse(rateLimit.toString().contains(API_KEY))

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [{
                    "b64_json": "${Base64.getEncoder().encodeToString(PNG_HEADER)}",
                    "media_type": "image/jpeg"
                  }]
                }
                """.trimIndent(),
            ),
        )
        val invalid = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { gateway.generate(credential(), request()) }
        }
        assertEquals("invalid_response", invalid.errorType)
        assertEquals(OpenRouterFailurePhase.BEFORE_STREAM, invalid.phase)

        server.enqueue(MockResponse().setResponseCode(502).setBody("upstream private detail"))
        val unavailable = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { gateway.generate(credential(), request()) }
        }
        assertEquals("provider_unavailable", unavailable.errorType)
        assertFalse(unavailable.toString().contains("upstream private detail"))
    }

    @Test
    fun `coroutine cancellation closes the in-flight image request`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(PNG_HEADER)}","media_type":"image/png"}]}""",
                )
                .setBodyDelay(60, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.Default) {
            gateway.generate(credential(), request())
        }

        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    private fun request() = OpenRouterImageGenerationRequest(
        model = OpenRouterImageModelSummary(
            id = IMAGE_MODEL_ID,
            name = "GPT Image 2",
            inputModalities = listOf("text"),
            outputModalities = listOf("image"),
            supportedParameters = emptyMap(),
            supportsStreaming = false,
        ),
        prompt = "A small green robot",
    )

    private fun credential() = ProviderCredential(
        profile = ProviderProfile(
            id = "11111111-1111-4111-8111-111111111111",
            kind = ProviderKind.OPENROUTER,
            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
            modelId = "deepseek/deepseek-v4-pro",
            displayName = "OpenRouter",
        ),
        apiKey = API_KEY,
    )

    private companion object {
        const val API_KEY = "test-image-key-that-never-enters-the-request-body"
        const val IMAGE_MODEL_ID = "openai/gpt-image-2"
        val PNG_HEADER = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
