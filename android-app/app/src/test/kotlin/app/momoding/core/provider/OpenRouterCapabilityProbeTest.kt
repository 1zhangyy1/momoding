package app.momoding.core.provider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenRouterCapabilityProbeTest {
    @Test
    fun `probe maps model metadata and caches by account and chat model`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "data": [{
                        "id": "$MODEL_ID",
                        "name": "DeepSeek V4 Pro",
                        "context_length": 131072,
                        "architecture": {
                          "input_modalities": ["text", "image"],
                          "output_modalities": ["text"]
                        },
                        "supported_parameters": ["tools"]
                      }]
                    }
                    """.trimIndent(),
                ),
            )
            val probe = OpenRouterCapabilityProbe(
                client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
                nowEpochMillis = { 1234L },
            )
            val credential = credential()

            val first = probe.resolve(credential)
            val second = probe.resolve(credential)

            assertEquals(ProviderCapabilityAvailability.AVAILABLE, first.chatStream)
            assertEquals(ProviderCapabilityAvailability.AVAILABLE, first.functionTools)
            assertEquals(ProviderCapabilityAvailability.AVAILABLE, first.imageInput)
            assertEquals(ProviderCapabilityAvailability.AVAILABLE, first.serverWebSearch)
            assertEquals(ProviderCapabilityAvailability.AVAILABLE, first.imageGeneration)
            assertEquals("openrouter_models_api", first.source)
            assertEquals(1234L, first.checkedAtEpochMillis)
            assertEquals(first, second)
            assertEquals(1, server.requestCount)
            assertNotNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `missing model is unknown rather than guessed from its id`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data": []}"""))
            val probe = OpenRouterCapabilityProbe(
                client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
                nowEpochMillis = { 99L },
            )

            val snapshot = probe.resolve(credential())

            assertEquals(ProviderCapabilityAvailability.UNKNOWN, snapshot.chatStream)
            assertEquals(ProviderCapabilityAvailability.UNKNOWN, snapshot.functionTools)
            assertEquals(ProviderCapabilityAvailability.UNKNOWN, snapshot.imageInput)
        }
    }

    private fun credential(): ProviderCredential = ProviderCredential(
        profile = ProviderProfile(
            id = "11111111-1111-4111-8111-111111111111",
            kind = ProviderKind.OPENROUTER,
            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
            modelId = MODEL_ID,
            displayName = "OpenRouter",
        ),
        apiKey = "test-key-that-never-leaves-native-client",
    )

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
    }
}
