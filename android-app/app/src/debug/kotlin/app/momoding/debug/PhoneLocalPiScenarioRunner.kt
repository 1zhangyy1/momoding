package app.momoding.debug

import android.content.Context
import app.momoding.core.runtime.local.PiFakeScenarioKind
import app.momoding.core.runtime.local.PiFakeScenarioStatus
import app.momoding.core.runtime.local.PhoneLocalPiEngine
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PhoneLocalPiScenarioProof(
    val scenarios: List<PiFakeScenarioStatus>,
) {
    val nativeEventCount: Int = scenarios.sumOf { it.events.size }
    val allPassed: Boolean = scenarios.all(PiFakeScenarioStatus::expectationMet)
}

class PhoneLocalPiScenarioRunner(
    context: Context,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-local-pi-scenario")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun runAll(): PhoneLocalPiScenarioProof = withContext(dispatcher) {
        val engine = PhoneLocalPiEngine(assets, dispatcher)
        try {
            engine.bootstrap()
            PhoneLocalPiScenarioProof(
                scenarios = PiFakeScenarioKind.entries.map { kind ->
                    runScenario(engine, kind)
                },
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
        kind: PiFakeScenarioKind,
    ): PiFakeScenarioStatus {
        var status = engine.startScenario(kind)
        var stopIssued = false
        repeat(MAX_PUMP_ITERATIONS) {
            if (
                kind == PiFakeScenarioKind.STOP_BEFORE_TOOL &&
                status.phase == "provider_waiting" &&
                !stopIssued
            ) {
                status = engine.abortScenario()
                stopIssued = true
            }
            engine.drainNativeRequests().forEach { request ->
                require(request.kind == "mock_tool") {
                    "PI_MOBILE_UNEXPECTED_NATIVE_REQUEST ${request.kind}"
                }
                require(request.toolName == "mobile_fixture_echo") {
                    "PI_MOBILE_UNEXPECTED_TOOL ${request.toolName}"
                }
                status = if (kind == PiFakeScenarioKind.TOOL_ERROR) {
                    engine.rejectNativeRequest(
                        request.id,
                        "Android mock tool rejected the request",
                    )
                } else {
                    engine.resolveNativeRequest(
                        request.id,
                        buildJsonObject {
                            put("echoed", requireNotNull(request.arguments["text"]))
                            put("executor", "android-local-mock")
                        },
                    )
                }
            }
            status = engine.scenarioStatus()
            if (status.terminal) {
                check(status.expectationMet) {
                    "PI_MOBILE_SCENARIO_EXPECTATION_FAILED kind=${kind.wireValue} status=$status"
                }
                return status
            }
            Thread.yield()
        }
        error("PI_MOBILE_SCENARIO_TIMEOUT kind=${kind.wireValue} status=$status")
    }

    private companion object {
        const val MAX_PUMP_ITERATIONS = 5_000
    }
}
