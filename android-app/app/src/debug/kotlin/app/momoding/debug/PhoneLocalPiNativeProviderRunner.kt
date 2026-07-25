package app.momoding.debug

import android.content.Context
import app.momoding.core.runtime.local.PhoneLocalPiEngine
import app.momoding.core.runtime.local.PiNativeOpenRouterScenarioKind
import app.momoding.core.runtime.local.PiNativeOpenRouterScenarioStatus
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PhoneLocalPiNativeProviderProof(
    val scenarios: List<PiNativeOpenRouterScenarioStatus>,
    val providerRequestJson: List<String>,
    val cancellationCount: Int,
) {
    val allPassed: Boolean = scenarios.all(PiNativeOpenRouterScenarioStatus::expectationMet)
}

class PhoneLocalPiNativeProviderRunner(
    context: Context,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-local-pi-native-provider")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun runAll(): PhoneLocalPiNativeProviderProof = withContext(dispatcher) {
        val engine = PhoneLocalPiEngine(assets, dispatcher)
        val requestJson = mutableListOf<String>()
        var cancellationCount = 0
        try {
            engine.bootstrap()
            val scenarios = PiNativeOpenRouterScenarioKind.entries.map { kind ->
                val result = runScenario(
                    engine = engine,
                    kind = kind,
                    onRequest = { requestJson += it },
                    onCancellation = { cancellationCount += it },
                )
                check(result.expectationMet) {
                    "PI_MOBILE_NATIVE_PROVIDER_EXPECTATION_FAILED kind=${kind.wireValue} " +
                        "status=$result"
                }
                result
            }
            PhoneLocalPiNativeProviderProof(
                scenarios = scenarios,
                providerRequestJson = requestJson,
                cancellationCount = cancellationCount,
            )
        } finally {
            engine.shutdown()
        }
    }

    override fun close() {
        dispatcher.close()
    }

    private suspend fun runScenario(
        engine: PhoneLocalPiEngine,
        kind: PiNativeOpenRouterScenarioKind,
        onRequest: (String) -> Unit,
        onCancellation: (Int) -> Unit,
    ): PiNativeOpenRouterScenarioStatus {
        var status = engine.startNativeOpenRouterScenario(kind, MODEL_ID)
        var providerRequestCount = 0
        var stopIssued = false
        repeat(MAX_PUMP_ITERATIONS) {
            engine.drainNativeProviderRequests().forEach { request ->
                require(request.kind == "openrouter_chat_stream") {
                    "PI_MOBILE_UNEXPECTED_PROVIDER_REQUEST ${request.kind}"
                }
                require(request.modelId == MODEL_ID) {
                    "PI_MOBILE_UNEXPECTED_PROVIDER_MODEL ${request.modelId}"
                }
                val serialized = json.encodeToString(request)
                require(!serialized.contains("apiKey", ignoreCase = true))
                require(!serialized.contains("authorization", ignoreCase = true))
                onRequest(serialized)
                providerRequestCount += 1
                when {
                    kind == PiNativeOpenRouterScenarioKind.STOP && !stopIssued -> {
                        status = engine.abortNativeOpenRouterScenario()
                        stopIssued = true
                    }
                    kind == PiNativeOpenRouterScenarioKind.PROVIDER_ERROR -> {
                        status = engine.failNativeProviderRequest(
                            request.id,
                            "OpenRouter test provider failed",
                        )
                    }
                    kind == PiNativeOpenRouterScenarioKind.TOOL &&
                        providerRequestCount == 1 -> {
                        toolChunks().forEach { chunk ->
                            status = engine.pushNativeProviderChunk(request.id, chunk)
                        }
                        status = engine.completeNativeProviderRequest(request.id, "gen-tool")
                    }
                    else -> {
                        textChunks(
                            if (kind == PiNativeOpenRouterScenarioKind.TOOL) {
                                "Android native Provider tool complete"
                            } else {
                                "Hello from Android native Provider"
                            },
                        ).forEach { chunk ->
                            status = engine.pushNativeProviderChunk(request.id, chunk)
                        }
                        status = engine.completeNativeProviderRequest(
                            request.id,
                            "gen-${kind.wireValue}",
                        )
                    }
                }
            }
            engine.drainNativeProviderToolRequests().forEach { request ->
                require(request.kind == "mock_tool")
                require(request.toolName == "mobile_fixture_echo")
                status = engine.resolveNativeProviderToolRequest(
                    request.id,
                    buildJsonObject {
                        put("echoed", requireNotNull(request.arguments["text"]))
                        put("executor", "android-local-mock")
                    },
                )
            }
            val cancellations = engine.drainNativeProviderCancellations()
            cancellations.forEach {
                require(it.kind == "cancel_openrouter_stream")
            }
            onCancellation(cancellations.size)
            status = engine.nativeOpenRouterScenarioStatus()
            if (status.terminal) return status
            Thread.yield()
        }
        error("PI_MOBILE_NATIVE_PROVIDER_TIMEOUT kind=${kind.wireValue} status=$status")
    }

    private fun textChunks(text: String): List<JsonObject> {
        val split = minOf(12, text.length)
        return listOf(
            parseChunk(
                """{"id":"gen-text","model":"$MODEL_ID","choices":[{"delta":{"content":${jsonString(text.substring(0, split))}}}]}""",
            ),
            parseChunk(
                """{"id":"gen-text","choices":[{"delta":{"content":${jsonString(text.substring(split))}},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":6,"total_tokens":14}}""",
            ),
        )
    }

    private fun toolChunks(): List<JsonObject> = listOf(
        parseChunk(
            """{"id":"gen-tool","model":"$MODEL_ID","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-native-1","function":{"name":"mobile_fixture_echo","arguments":"{\"text\":"}}]}}]}""",
        ),
        parseChunk(
            """{"id":"gen-tool","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"native\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}""",
        ),
    )

    private fun parseChunk(value: String): JsonObject =
        json.parseToJsonElement(value) as JsonObject

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
        const val MAX_PUMP_ITERATIONS = 10_000
    }
}
