package app.momoding.core.runtime.local

import app.momoding.core.provider.CodexNativeClient
import app.momoding.core.provider.CodexResponsesRequest
import app.momoding.core.provider.CodexTransportException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Moves token-free Codex requests/events between the one Pi AgentHarness and Android HTTPS. */
internal class PhoneLocalPiCodexRequestPump(
    private val engine: PhoneLocalPiEngine,
    private val client: CodexNativeClient,
    private val networkScope: CoroutineScope,
) {
    private val networkEvents = ConcurrentLinkedQueue<CodexNetworkEvent>()
    private val jobs = mutableMapOf<String, Job>()
    private val cancelledRequestIds = mutableSetOf<String>()

    suspend fun pump(): PiNativeOpenRouterScenarioStatus {
        drainCancellations()
        drainNetworkEvents()
        startProviderRequests()
        drainNetworkEvents()
        return engine.nativeOpenRouterScenarioStatus()
    }

    suspend fun close() {
        jobs.values.toList().forEach { it.cancelAndJoin() }
        jobs.clear()
        networkEvents.clear()
    }

    private suspend fun drainCancellations() {
        engine.drainNativeProviderCancellations().forEach { cancellation ->
            require(cancellation.kind == "cancel_codex_responses_stream") {
                "PI_MOBILE_CODEX_CANCELLATION_KIND_INVALID"
            }
            cancelledRequestIds += cancellation.id
            jobs.remove(cancellation.id)?.cancel()
            networkEvents.removeIf { it.requestId == cancellation.id }
        }
    }

    private suspend fun drainNetworkEvents() {
        while (true) {
            val event = networkEvents.poll() ?: return
            if (event.requestId in cancelledRequestIds) continue
            when (event) {
                is CodexNetworkEvent.Event ->
                    engine.pushNativeProviderChunk(event.requestId, event.value)
                is CodexNetworkEvent.Completed -> {
                    jobs.remove(event.requestId)
                    engine.completeNativeProviderRequest(event.requestId, null)
                }
                is CodexNetworkEvent.Failed -> {
                    jobs.remove(event.requestId)
                    engine.failNativeProviderRequest(event.requestId, event.safeMessage)
                }
            }
        }
    }

    private suspend fun startProviderRequests() {
        engine.drainNativeProviderRequests().forEach { request ->
            require(request.kind == "codex_responses_stream") {
                "PI_MOBILE_CODEX_REQUEST_KIND_INVALID"
            }
            require(request.id !in jobs) { "PI_MOBILE_CODEX_REQUEST_DUPLICATED" }
            val body = requireNotNull(request.body) { "PI_MOBILE_CODEX_BODY_MISSING" }
            jobs[request.id] = networkScope.launch {
                try {
                    client.stream(
                        CodexResponsesRequest(
                            modelId = request.modelId,
                            body = body,
                            sessionId = request.sessionId,
                        ),
                    ) { value ->
                        networkEvents += CodexNetworkEvent.Event(request.id, value)
                    }
                    networkEvents += CodexNetworkEvent.Completed(request.id)
                } catch (_: CancellationException) {
                    // Pi Stop owns the terminal state and has already removed this request.
                } catch (error: Throwable) {
                    networkEvents += CodexNetworkEvent.Failed(
                        request.id,
                        safeCodexMessage(error),
                    )
                }
            }
        }
    }
}

private sealed interface CodexNetworkEvent {
    val requestId: String

    data class Event(
        override val requestId: String,
        val value: JsonObject,
    ) : CodexNetworkEvent

    data class Completed(override val requestId: String) : CodexNetworkEvent

    data class Failed(
        override val requestId: String,
        val safeMessage: String,
    ) : CodexNetworkEvent
}

private fun safeCodexMessage(error: Throwable): String = when (error) {
    is CodexTransportException -> error.message ?: "Codex request failed"
    else -> "Codex request failed"
}.take(160)
