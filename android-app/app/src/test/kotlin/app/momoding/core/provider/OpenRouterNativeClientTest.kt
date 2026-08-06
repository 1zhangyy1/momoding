package app.momoding.core.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

class OpenRouterNativeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterNativeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenRouterNativeClient(
            endpoint = server.url("/api/v1"),
            baseClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsOfficialSseContractWithoutPuttingKeyInBody() = runBlocking {
        server.enqueue(
            sseResponse(
                """
                : OPENROUTER PROCESSING

                data: {"id":"gen-1","choices":[{"delta":{"content":"Hello"}}]}

                data: {"id":"gen-1","choices":[{"delta":{"content":" mobile"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                data: [DONE]

                """.trimIndent(),
            ).addHeader("X-Generation-Id", "gen-1"),
        )
        val chunks = mutableListOf<kotlinx.serialization.json.JsonObject>()

        val result = client.stream(credential(), request(), chunks::add)

        assertEquals("gen-1", result.generationId)
        assertEquals(2, result.chunkCount)
        assertEquals(2, chunks.size)
        assertEquals(
            "Hello",
            chunks.first()["choices"]
                ?.let { it as kotlinx.serialization.json.JsonArray }
                ?.first()
                ?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("delta")
                ?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("content")
                ?.jsonPrimitive
                ?.content,
        )
        val recorded = server.takeRequest()
        assertEquals("/api/v1/chat/completions", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer $API_KEY", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        val body = recorded.body.readUtf8()
        assertFalse(body.contains(API_KEY))
        val payload = Json.parseToJsonElement(body).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertEquals(MODEL_ID, payload["model"]?.jsonPrimitive?.content)
        assertEquals(true, payload["stream"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun combinesPiFunctionToolsWithBoundedOpenRouterWebAccess() = runBlocking {
        server.enqueue(
            sseResponse(
                """
                data: {"id":"gen-search","choices":[{"delta":{"content":"Grounded"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"server_tool_use":{"web_search_requests":1}}}

                data: [DONE]

                """.trimIndent(),
            ),
        )
        val functionTools = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject { put("name", "device_calendar") })
                },
            )
        }

        client.stream(
            credential(),
            request().copy(
                functionTools = functionTools,
                webSearch = OpenRouterWebSearchConfig(),
                webFetch = OpenRouterWebFetchConfig(),
            ),
        ) {}

        val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tools = payload.getValue("tools").jsonArray
        assertEquals(3, tools.size)
        assertEquals("function", tools[0].jsonObject.getValue("type").jsonPrimitive.content)
        val search = tools[1].jsonObject
        assertEquals("openrouter:web_search", search.getValue("type").jsonPrimitive.content)
        val parameters = search.getValue("parameters").jsonObject
        assertEquals("exa", parameters.getValue("engine").jsonPrimitive.content)
        assertEquals("3", parameters.getValue("max_uses").jsonPrimitive.content)
        assertEquals("5", parameters.getValue("max_results").jsonPrimitive.content)
        assertEquals("15", parameters.getValue("max_total_results").jsonPrimitive.content)
        assertEquals("2000", parameters.getValue("max_characters").jsonPrimitive.content)
        val fetch = tools[2].jsonObject
        assertEquals("openrouter:web_fetch", fetch.getValue("type").jsonPrimitive.content)
        val fetchParameters = fetch.getValue("parameters").jsonObject
        assertEquals("openrouter", fetchParameters.getValue("engine").jsonPrimitive.content)
        assertEquals("3", fetchParameters.getValue("max_uses").jsonPrimitive.content)
        assertEquals("20000", fetchParameters.getValue("max_content_tokens").jsonPrimitive.content)
        assertEquals("3", payload.getValue("max_tool_calls").jsonPrimitive.content)
    }

    @Test
    fun mapsHttpAuthenticationAndRateLimitWithoutEchoingRemoteBodyOrKey() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"code":401,"message":"secret remote detail","metadata":{"error_type":"authentication"}}}""",
                ),
        )

        val authentication = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { client.stream(credential(), request()) {} }
        }

        assertEquals(401, authentication.statusCode)
        assertEquals("authentication", authentication.errorType)
        assertEquals(OpenRouterFailurePhase.BEFORE_STREAM, authentication.phase)
        assertFalse(authentication.toString().contains(API_KEY))
        assertFalse(authentication.toString().contains("secret remote detail"))

        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "60")
                .setBody("""{"error":{"code":429,"message":"slow down"}}"""),
        )
        val rateLimit = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { client.stream(credential(), request()) {} }
        }
        assertEquals(429, rateLimit.statusCode)
        assertEquals("rate_limit_exceeded", rateLimit.errorType)
        assertEquals(60L, rateLimit.retryAfterSeconds)
    }

    @Test
    fun reportsMidStreamErrorAfterAlreadyDeliveredChunk() = runBlocking {
        server.enqueue(
            sseResponse(
                """
                data: {"id":"gen-2","choices":[{"delta":{"content":"partial"}}]}

                data: {"id":"gen-2","error":{"code":503,"message":"provider detail","metadata":{"error_type":"provider_overloaded"}},"choices":[{"delta":{"content":""},"finish_reason":"error"}]}

                """.trimIndent(),
            ),
        )
        val chunks = mutableListOf<kotlinx.serialization.json.JsonObject>()

        val failure = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { client.stream(credential(), request(), chunks::add) }
        }

        assertEquals(1, chunks.size)
        assertEquals(503, failure.statusCode)
        assertEquals("provider_overloaded", failure.errorType)
        assertEquals(OpenRouterFailurePhase.MID_STREAM, failure.phase)
        assertEquals("OpenRouter provider is unavailable", failure.safeTaskMessage())
        assertFalse(failure.toString().contains("provider detail"))
    }

    @Test
    fun mapsUncodedStreamFailureToSafeRetryableProviderMessage() = runBlocking {
        server.enqueue(
            sseResponse(
                """
                data: {"id":"gen-search","error":{"message":"private upstream detail","metadata":{"error_type":"provider_error"}},"choices":[{"delta":{"content":""},"finish_reason":"error"}]}

                """.trimIndent(),
            ),
        )

        val failure = assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { client.stream(credential(), request()) {} }
        }

        assertEquals(null, failure.statusCode)
        assertEquals("provider_error", failure.errorType)
        assertEquals("OpenRouter request failed", failure.safeTaskMessage())
        assertFalse(failure.toString().contains("private upstream detail"))
    }

    @Test
    fun missingDoneAndWrongContentTypeFailClosed() {
        server.enqueue(
            sseResponse(
                """
                data: {"id":"gen-3","choices":[{"delta":{"content":"partial"}}]}

                """.trimIndent(),
            ),
        )
        assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { client.stream(credential(), request()) {} }
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[]}"""),
        )
        assertThrows(OpenRouterRequestException::class.java) {
            runBlocking { client.stream(credential(), request()) {} }
        }
    }

    @Test
    fun coroutineCancellationClosesInFlightHttpCall() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
                .setBodyDelay(60, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.Default) {
            client.stream(credential(), request()) {}
        }

        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        withTimeout(5_000) {
            job.cancelAndJoin()
        }
        assertTrue(job.isCancelled)
    }

    @Test
    fun modelMismatchAndOversizedInputFailBeforeNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.stream(
                    credential(),
                    request().copy(modelId = "openai/not-the-profile-model"),
                ) {}
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.stream(
                    credential(),
                    request().copy(maxTokens = 1_000_001),
                ) {}
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.stream(
                    credential(),
                    request().copy(
                        webSearch = OpenRouterWebSearchConfig(
                            maxUses = 3,
                            maxServerToolCalls = 2,
                        ),
                    ),
                ) {}
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun listsOfficialModelCatalogWithoutPuttingKeyInUrlOrBody() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"data":[
                    {"id":"deepseek/deepseek-v4-pro","name":"DeepSeek V4 Pro","context_length":131072,"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"supported_parameters":["tools"]},
                    {"id":"openai/gpt-5","name":"GPT-5","context_length":400000,"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["reasoning","tools"]}
                    ]}""".trimIndent(),
                ),
        )

        val models = client.listModels(API_KEY)

        assertEquals(listOf(MODEL_ID, "openai/gpt-5"), models.map { it.id })
        assertEquals(listOf("text", "image"), models[1].inputModalities)
        assertEquals(listOf("text"), models[1].outputModalities)
        assertEquals(listOf("reasoning", "tools"), models[1].supportedParameters)
        assertEquals(400_000, models[1].contextLength)
        val request = requireNotNull(server.takeRequest())
        assertEquals("/api/v1/models", request.requestUrl?.encodedPath)
        assertEquals("most-popular", request.requestUrl?.queryParameter("sort"))
        assertEquals("Bearer $API_KEY", request.getHeader("Authorization"))
        assertFalse(request.requestUrl.toString().contains(API_KEY))
        assertEquals(0L, request.bodySize)
    }

    private fun sseResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    private fun request(): OpenRouterChatRequest =
        OpenRouterChatRequest(
            modelId = MODEL_ID,
            messages = buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "Hello from Android")
                    },
                )
            },
            maxTokens = 128,
        )

    private fun credential(): ProviderCredential =
        ProviderCredential(
            profile = ProviderProfile(
                id = "11111111-1111-4111-8111-111111111111",
                kind = ProviderKind.OPENROUTER,
                baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                modelId = MODEL_ID,
                displayName = "OpenRouter",
            ),
            apiKey = API_KEY,
        )

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
        const val API_KEY = "test-openrouter-key-never-log-this-value"
    }
}
