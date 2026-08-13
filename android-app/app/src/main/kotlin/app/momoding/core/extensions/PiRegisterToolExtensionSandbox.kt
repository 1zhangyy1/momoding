package app.momoding.core.extensions

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class PiRegisterToolArtifact(
    val packageId: String,
    val packageDigest: String,
    val entrypoint: String,
    val modules: Map<String, String>,
    val tools: List<PiRegisterToolManifestTool>,
    val hostTools: List<PiRegisterHostTool>,
    val httpPolicy: PiRegisterToolHttpPolicy = PiRegisterToolHttpPolicy(),
    val resources: Map<String, String> = emptyMap(),
)

@Serializable
data class PiRegisterToolManifestTool(
    val name: String,
    val label: String,
    val description: String,
    val parameters: JsonObject,
    val promptSnippet: String? = null,
    val promptGuidelines: List<String> = emptyList(),
    val executionMode: String = "sequential",
)

@Serializable
data class PiRegisterHostTool(
    val name: String,
    val targetTool: String,
    val capability: String? = null,
)

@Serializable
data class PiRegisterToolStartRequest(
    val outerToolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val state: Map<String, JsonElement> = emptyMap(),
)

@Serializable
sealed interface PiRegisterToolWorkerEvent {
    val invocationId: String
    val generation: String
    val seq: Int

    @Serializable
    @SerialName("update")
    data class Update(
        override val invocationId: String,
        override val generation: String,
        override val seq: Int,
        val update: JsonObject,
    ) : PiRegisterToolWorkerEvent

    @Serializable
    @SerialName("host_call")
    data class HostCall(
        override val invocationId: String,
        override val generation: String,
        override val seq: Int,
        val name: String,
        val arguments: JsonObject,
        val childToolCallId: String,
    ) : PiRegisterToolWorkerEvent

    @Serializable
    @SerialName("http_call")
    data class HttpCall(
        override val invocationId: String,
        override val generation: String,
        override val seq: Int,
        val request: JsonObject,
    ) : PiRegisterToolWorkerEvent

    @Serializable
    @SerialName("complete")
    data class Complete(
        override val invocationId: String,
        override val generation: String,
        override val seq: Int,
        val result: JsonObject,
        val stateDraft: Map<String, JsonElement>,
    ) : PiRegisterToolWorkerEvent

    @Serializable
    @SerialName("error")
    data class Error(
        override val invocationId: String,
        override val generation: String,
        override val seq: Int,
        val code: String,
        val partialEffects: Boolean,
    ) : PiRegisterToolWorkerEvent
}

@Serializable
sealed interface PiRegisterToolResume {
    @Serializable
    @SerialName("success")
    data class Success(val result: JsonObject) : PiRegisterToolResume
    @Serializable
    @SerialName("failure")
    data class Failure(val code: String) : PiRegisterToolResume
}

internal data class PiRegisterToolWorkerIdentity(
    val invocationId: String,
    val generation: String,
)

class PiRegisterToolExtensionHost(
    dispatcher: CoroutineDispatcher,
    onAbortListenersDispatched: (Int) -> Unit = {},
    hostCallTimeoutMillis: Long = PiRegisterToolExtensionSandbox.HOST_CALL_TIMEOUT_MILLIS,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val generation = requireIdentity(idFactory())
    private val worker = PiRegisterToolExtensionSandbox(
        dispatcher = dispatcher,
        onAbortListenersDispatched = onAbortListenersDispatched,
        hostCallTimeoutMillis = hostCallTimeoutMillis,
    )

    fun open(
        artifact: PiRegisterToolArtifact,
        request: PiRegisterToolStartRequest,
    ): Session {
        val identity = PiRegisterToolWorkerIdentity(
            invocationId = requireIdentity(idFactory()),
            generation = generation,
        )
        return Session(
            worker = worker.open(artifact, request, identity),
            outerToolCallId = request.outerToolCallId,
            identity = identity,
        )
    }

    class Session internal constructor(
        private val worker: PiRegisterToolExtensionSandbox.Session,
        private val outerToolCallId: String,
        private val identity: PiRegisterToolWorkerIdentity,
    ) : AutoCloseable {
        val invocationId: String get() = identity.invocationId
        val generation: String get() = identity.generation
        private var nextSeq = 0

        suspend fun start(): PiRegisterToolWorkerEvent = trusted(worker.start())

        suspend fun next(
            expectedInvocationId: String,
            expectedGeneration: String,
            acknowledgedSeq: Int,
        ): PiRegisterToolWorkerEvent = trusted(worker.next(
            expectedInvocationId,
            expectedGeneration,
            acknowledgedSeq,
        ))

        suspend fun resume(
            expectedInvocationId: String,
            expectedGeneration: String,
            acknowledgedSeq: Int,
            response: PiRegisterToolResume,
        ): PiRegisterToolWorkerEvent = trusted(worker.resume(
            expectedInvocationId,
            expectedGeneration,
            acknowledgedSeq,
            response,
        ))

        suspend fun cancel(
            expectedInvocationId: String = invocationId,
            expectedGeneration: String = generation,
        ) = worker.cancel(expectedInvocationId, expectedGeneration)

        override fun close() = worker.close()

        private fun trusted(event: PiRegisterToolWorkerEvent): PiRegisterToolWorkerEvent {
            if (event.invocationId != invocationId || event.generation != generation ||
                event.seq != nextSeq
            ) {
                fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            nextSeq += 1
            return if (event is PiRegisterToolWorkerEvent.HostCall) {
                event.copy(
                    childToolCallId = childToolCallId(
                        outerToolCallId,
                        invocationId,
                        event.seq,
                    ),
                )
            } else {
                event
            }
        }
    }
}

class PiRegisterToolExtensionSandbox(
    private val dispatcher: CoroutineDispatcher,
    private val onAbortListenersDispatched: (Int) -> Unit = {},
    private val hostCallTimeoutMillis: Long = HOST_CALL_TIMEOUT_MILLIS,
) {
    init {
        if (hostCallTimeoutMillis !in 1L..HOST_CALL_TIMEOUT_MILLIS) {
            fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
        }
    }

    internal fun open(
        artifact: PiRegisterToolArtifact,
        request: PiRegisterToolStartRequest,
        identity: PiRegisterToolWorkerIdentity,
    ): Session = Session(
        dispatcher = dispatcher,
        artifact = requireArtifact(artifact),
        request = requireRequest(artifact, request),
        invocationId = identity.invocationId,
        generation = identity.generation,
        onAbortListenersDispatched = onAbortListenersDispatched,
        hostCallTimeoutMillis = hostCallTimeoutMillis,
    )

    class Session internal constructor(
        private val dispatcher: CoroutineDispatcher,
        private val artifact: PiRegisterToolArtifact,
        private val request: PiRegisterToolStartRequest,
        val invocationId: String,
        val generation: String,
        private val onAbortListenersDispatched: (Int) -> Unit,
        private val hostCallTimeoutMillis: Long,
    ) : AutoCloseable {
        private enum class State { NEW, RUNNING, WAITING_NEXT, WAITING_HOST, TERMINAL }
        private enum class PendingRpc { TOOL, HTTP }

        private val lock = Any()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val events = Channel<PiRegisterToolWorkerEvent>(MAX_BUFFERED_EVENTS)
        private val aborted = AtomicBoolean(false)
        private val stateDraft = request.state.toMutableMap()
        private var state = State.NEW
        private var nextSeq = 0
        private var producedSeq = 0
        private var pendingHost: CompletableDeferred<PiRegisterToolResume>? = null
        private var pendingHostSeq: Int? = null
        private var pendingRpc: PendingRpc? = null
        private var evaluation: Job? = null
        private var executionStarted = false
        @Volatile private var hostResultResumed = false
        private var sliceStartedNanos = 0L
        private var cumulativeSliceNanos = 0L
        @Volatile private var reportedHostError: String? = null
        @Volatile private var quickJs: QuickJs? = null

        suspend fun start(): PiRegisterToolWorkerEvent {
            synchronized(lock) {
                requireState(State.NEW)
                state = State.RUNNING
                evaluation = scope.launch { runExtension() }
            }
            return awaitEvent()
        }

        suspend fun next(
            expectedInvocationId: String,
            expectedGeneration: String,
            acknowledgedSeq: Int,
        ): PiRegisterToolWorkerEvent {
            synchronized(lock) {
                requireIdentity(expectedInvocationId, expectedGeneration)
                requireState(State.WAITING_NEXT)
                if (acknowledgedSeq != nextSeq - 1) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                state = State.RUNNING
            }
            return awaitEvent()
        }

        suspend fun resume(
            expectedInvocationId: String,
            expectedGeneration: String,
            acknowledgedSeq: Int,
            response: PiRegisterToolResume,
        ): PiRegisterToolWorkerEvent {
            val kind = synchronized(lock) {
                requireIdentity(expectedInvocationId, expectedGeneration)
                requireState(State.WAITING_HOST)
                if (acknowledgedSeq != pendingHostSeq || acknowledgedSeq != nextSeq - 1) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                pendingRpc ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            val normalizedResponse = requireResume(response, kind == PendingRpc.HTTP)
            val deferred = synchronized(lock) {
                requireIdentity(expectedInvocationId, expectedGeneration)
                requireState(State.WAITING_HOST)
                if (acknowledgedSeq != pendingHostSeq || acknowledgedSeq != nextSeq - 1) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                state = State.RUNNING
                pendingHostSeq = null
                pendingRpc = null
                pendingHost.also { pendingHost = null }
                    ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            hostResultResumed = true
            reportedHostError = (normalizedResponse as? PiRegisterToolResume.Failure)?.code
            if (!deferred.complete(normalizedResponse)) {
                fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            return awaitEvent()
        }

        suspend fun cancel(
            expectedInvocationId: String = invocationId,
            expectedGeneration: String = generation,
        ) {
            val pending: CompletableDeferred<PiRegisterToolResume>?
            val job: Job?
            synchronized(lock) {
                requireIdentity(expectedInvocationId, expectedGeneration)
                if (state == State.TERMINAL) return
                state = State.TERMINAL
                aborted.set(true)
                pending = pendingHost
                pendingHost = null
                pendingHostSeq = null
                pendingRpc = null
                job = evaluation
            }
            pending?.complete(PiRegisterToolResume.Failure("EXTENSION_PACKAGE_STOPPED"))
            if (pending !== null && job != null) {
                runCatching { withTimeout(100L) { joinAll(job) } }
            }
            if (job?.isCompleted != true) {
                quickJs?.interruptEvaluation()
                job?.cancel()
            }
            events.close()
        }

        override fun close() {
            synchronized(lock) {
                state = State.TERMINAL
                aborted.set(true)
                pendingHost?.cancel()
                pendingHost = null
                pendingHostSeq = null
                pendingRpc = null
            }
            quickJs?.interruptEvaluation()
            scope.cancel()
            events.close()
        }

        private suspend fun awaitEvent(): PiRegisterToolWorkerEvent {
            val event = try {
                events.receive()
            } catch (_: ClosedReceiveChannelException) {
                fail(
                    if (aborted.get()) "EXTENSION_PACKAGE_STOPPED"
                    else "EXTENSION_PACKAGE_WORKER_DIED",
                )
            }
            synchronized(lock) {
                if (state == State.TERMINAL) fail("EXTENSION_PACKAGE_STOPPED")
                if (event.invocationId != invocationId || event.generation != generation ||
                    event.seq != nextSeq
                ) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                nextSeq += 1
                state = when (event) {
                    is PiRegisterToolWorkerEvent.Update -> State.WAITING_NEXT
                    is PiRegisterToolWorkerEvent.HostCall,
                    is PiRegisterToolWorkerEvent.HttpCall,
                    -> State.WAITING_HOST
                    is PiRegisterToolWorkerEvent.Complete,
                    is PiRegisterToolWorkerEvent.Error,
                    -> State.TERMINAL
                }
            }
            return event
        }

        private suspend fun runExtension() {
            val runtime = QuickJs.create(dispatcher).apply {
                memoryLimit = SANDBOX_MEMORY_LIMIT_BYTES
                maxStackSize = SANDBOX_STACK_LIMIT_BYTES
                evaluationTimeoutMillis = FACTORY_TIMEOUT_MILLIS
            }
            quickJs = runtime
            try {
                installHostBindings(runtime)
                runtime.evaluate<Any?>(piExtensionWorkerPrelude(expectedRegistrationsJson()), PRELUDE_FILE)
                runtime.addModule(MOBILE_SDK_MODULE, MOBILE_SDK_SOURCE)
                artifact.modules.forEach(runtime::addModule)
                try {
                    withTimeout(FACTORY_TIMEOUT_MILLIS) {
                        runtime.evaluate<Any?>(
                            factoryBootstrap(artifact.entrypoint),
                            FACTORY_BOOTSTRAP_FILE,
                            asModule = true,
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    fail("EXTENSION_PACKAGE_JAVASCRIPT_CPU_TIMEOUT")
                }
                executionStarted = true
                runtime.evaluationTimeoutMillis = EXECUTION_CPU_TIMEOUT_MILLIS
                beginExecutionSlice()
                val resultJson = withTimeout(INVOCATION_TIMEOUT_MILLIS) {
                    runtime.evaluate<String>(
                        "await globalThis.__momodingPiWorker.executeJson(" +
                            "${JsonPrimitive(request.toolName)}," +
                            "${JsonPrimitive(request.outerToolCallId)}," +
                            "${JsonPrimitive(request.arguments.toString())})",
                        EXECUTION_FILE,
                    )
                }
                checkpointExecutionSlice()
                val result = parseObject(resultJson, "EXTENSION_PACKAGE_RESULT_INVALID")
                requireBoundedJson(result, MAX_FINAL_RESULT_BYTES, "EXTENSION_PACKAGE_RESULT_TOO_LARGE")
                emitTerminal { seq -> PiRegisterToolWorkerEvent.Complete(
                    invocationId = invocationId,
                    generation = generation,
                    seq = seq,
                    result = result,
                    stateDraft = stateDraft.toMap(),
                ) }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                emitFailure("EXTENSION_PACKAGE_WORKER_UNAVAILABLE")
            } catch (error: QuickJsInterruptedException) {
                if (!aborted.get()) emitFailure("EXTENSION_PACKAGE_JAVASCRIPT_CPU_TIMEOUT")
            } catch (error: ExtensionPackageException) {
                emitFailure(error.message ?: "EXTENSION_PACKAGE_FAILED")
            } catch (error: QuickJsException) {
                if (!aborted.get()) emitFailure(
                    reportedHostError
                        ?: trustedExtensionErrorCode(error, "EXTENSION_PACKAGE_JAVASCRIPT_FAILED"),
                )
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!aborted.get()) emitFailure(
                    trustedExtensionErrorCode(error, "EXTENSION_PACKAGE_FAILED"),
                )
            } finally {
                quickJs = null
                runtime.close()
                events.close()
            }
        }

        private fun installHostBindings(runtime: QuickJs) {
            var updateCount = 0
            var hostCallCount = 0
            runtime.define("__momodingWorkerHost") {
                function("isAborted") { _ -> aborted.get() }
                function("checkpointExecution") { _ ->
                    requireExecutionStarted()
                    checkpointExecutionSlice()
                    Unit
                }
                function("recordAbortListenersDispatched") { args ->
                    val count = (args.singleOrNull() as? Number)?.toInt()
                        ?.takeIf { it in 0..MAX_ABORT_LISTENERS }
                        ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                    onAbortListenersDispatched(count)
                    Unit
                }
                function("emitUpdateJson") { args ->
                    requireExecutionStarted()
                    if (aborted.get()) fail("EXTENSION_PACKAGE_STOPPED")
                    if (++updateCount > MAX_PROGRESS_UPDATES) {
                        fail("EXTENSION_PACKAGE_PROGRESS_LIMIT_EXCEEDED")
                    }
                    val update = parseObject(singleString(args), "EXTENSION_PACKAGE_UPDATE_INVALID")
                    requireBoundedJson(update, MAX_PROGRESS_UPDATE_BYTES, "EXTENSION_PACKAGE_UPDATE_TOO_LARGE")
                    emit(create = { seq -> PiRegisterToolWorkerEvent.Update(
                        invocationId,
                        generation,
                        seq,
                        update,
                    ) })
                    Unit
                }
                function("readResource") { args ->
                    requireExecutionStarted()
                    val path = singleString(args)
                    requireValidExtensionPackagePath(path)
                    artifact.resources[path]
                        ?: fail("EXTENSION_PACKAGE_RESOURCE_NOT_FOUND")
                }
                function("getStateJson") { args ->
                    requireExecutionStarted()
                    val key = requireStateKey(singleString(args))
                    (stateDraft[key] ?: JsonNull).toString()
                }
                function("setStateJson") { args ->
                    requireExecutionStarted()
                    val key = requireStateKey(args.getOrNull(0) as? String)
                    val value = parseElement(
                        args.getOrNull(1) as? String,
                        "EXTENSION_PACKAGE_STATE_VALUE_INVALID",
                    )
                    val next = stateDraft.toMutableMap().apply { put(key, value) }
                    requireState(next)
                    stateDraft.clear()
                    stateDraft.putAll(next)
                    Unit
                }
                function("deleteState") { args ->
                    requireExecutionStarted()
                    stateDraft.remove(requireStateKey(singleString(args)))
                    Unit
                }
                asyncFunction("callToolJson") { args ->
                    requireExecutionStarted()
                    checkpointExecutionSlice()
                    if (aborted.get()) fail("EXTENSION_PACKAGE_STOPPED")
                    if (++hostCallCount > MAX_HOST_CALLS) {
                        fail("EXTENSION_PACKAGE_HOST_CALL_LIMIT_EXCEEDED")
                    }
                    val name = args.getOrNull(0) as? String
                        ?: fail("EXTENSION_PACKAGE_HOST_CALL_INVALID")
                    if (artifact.hostTools.none { it.name == name }) {
                        fail("EXTENSION_PACKAGE_HOST_CALL_NOT_DECLARED")
                    }
                    val arguments = parseObject(
                        args.getOrNull(1) as? String,
                        "EXTENSION_PACKAGE_HOST_CALL_INVALID",
                    )
                    requireBoundedJson(
                        arguments,
                        MAX_HOST_ARGUMENT_BYTES,
                        "EXTENSION_PACKAGE_HOST_CALL_TOO_LARGE",
                    )
                    val deferred = CompletableDeferred<PiRegisterToolResume>()
                    val seq = emit(
                        create = { eventSeq -> PiRegisterToolWorkerEvent.HostCall(
                            invocationId = invocationId,
                            generation = generation,
                            seq = eventSeq,
                            name = name,
                            arguments = arguments,
                            // This field crosses the Worker boundary as untrusted data.
                            // PiRegisterToolExtensionHost always overwrites it from Host-owned identity.
                            childToolCallId = "worker-untrusted",
                        ) },
                        beforeSend = { eventSeq ->
                            synchronized(lock) {
                                if (pendingHost !== null) {
                                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                                }
                                pendingHost = deferred
                                pendingHostSeq = eventSeq
                                pendingRpc = PendingRpc.TOOL
                            }
                        },
                    )
                    runtime.evaluationTimeoutMillis = 0L
                    val response = try {
                        withTimeout(hostCallTimeoutMillis) { deferred.await() }
                    } catch (_: TimeoutCancellationException) {
                        PiRegisterToolResume.Failure("EXTENSION_PACKAGE_HOST_TIMEOUT").also {
                            reportedHostError = it.code
                        }
                    } finally {
                        runtime.evaluationTimeoutMillis = EXECUTION_CPU_TIMEOUT_MILLIS
                        beginExecutionSlice()
                    }
                    when (response) {
                        is PiRegisterToolResume.Success -> buildJsonObject {
                            put("ok", true)
                            put("result", response.result)
                        }.toString()
                        is PiRegisterToolResume.Failure -> buildJsonObject {
                            put("ok", false)
                            put("errorCode", response.code)
                        }.toString()
                    }
                }
                asyncFunction("fetchJson") { args ->
                    requireExecutionStarted()
                    checkpointExecutionSlice()
                    if (aborted.get()) fail("EXTENSION_PACKAGE_STOPPED")
                    if (++hostCallCount > MAX_HOST_CALLS) {
                        fail("EXTENSION_PACKAGE_HOST_CALL_LIMIT_EXCEEDED")
                    }
                    val request = parseObject(
                        singleString(args),
                        "EXTENSION_PACKAGE_HTTP_REQUEST_INVALID",
                    )
                    requireWorkerHttpRequest(artifact.httpPolicy, request)
                    val deferred = CompletableDeferred<PiRegisterToolResume>()
                    emit(
                        create = { eventSeq -> PiRegisterToolWorkerEvent.HttpCall(
                            invocationId = invocationId,
                            generation = generation,
                            seq = eventSeq,
                            request = request,
                        ) },
                        beforeSend = { eventSeq ->
                            synchronized(lock) {
                                if (pendingHost !== null) {
                                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                                }
                                pendingHost = deferred
                                pendingHostSeq = eventSeq
                                pendingRpc = PendingRpc.HTTP
                            }
                        },
                    )
                    runtime.evaluationTimeoutMillis = 0L
                    val response = try {
                        withTimeout(hostCallTimeoutMillis) { deferred.await() }
                    } catch (_: TimeoutCancellationException) {
                        PiRegisterToolResume.Failure("EXTENSION_PACKAGE_HOST_TIMEOUT").also {
                            reportedHostError = it.code
                        }
                    } finally {
                        runtime.evaluationTimeoutMillis = EXECUTION_CPU_TIMEOUT_MILLIS
                        beginExecutionSlice()
                    }
                    when (response) {
                        is PiRegisterToolResume.Success -> buildJsonObject {
                            put("ok", true)
                            put("result", response.result)
                        }.toString()
                        is PiRegisterToolResume.Failure -> buildJsonObject {
                            put("ok", false)
                            put("errorCode", response.code)
                        }.toString()
                    }
                }
            }
        }

        private fun emit(
            create: (Int) -> PiRegisterToolWorkerEvent,
            beforeSend: (Int) -> Unit = {},
        ): Int {
            if (aborted.get()) fail("EXTENSION_PACKAGE_STOPPED")
            val seq = synchronized(lock) { producedSeq++ }
            beforeSend(seq)
            if (!events.trySend(create(seq)).isSuccess) {
                fail("EXTENSION_PACKAGE_PROGRESS_LIMIT_EXCEEDED")
            }
            return seq
        }

        private fun emitTerminal(create: (Int) -> PiRegisterToolWorkerEvent) {
            if (!aborted.get()) emit(create)
        }

        private fun emitFailure(code: String) {
            if (aborted.get()) return
            runCatching {
                emitTerminal { seq -> PiRegisterToolWorkerEvent.Error(
                    invocationId,
                    generation,
                    seq,
                    stableErrorCode(code),
                    partialEffects = hostResultResumed,
                ) }
            }
        }

        private fun expectedRegistrationsJson(): String = buildJsonArray {
            artifact.tools.forEach { tool -> add(buildJsonObject {
                put("name", tool.name)
                put("label", tool.label)
                put("description", tool.description)
                put("parameters", tool.parameters)
                put("promptSnippet", tool.promptSnippet?.let(::JsonPrimitive) ?: JsonNull)
                put("promptGuidelines", buildJsonArray {
                    tool.promptGuidelines.forEach { add(JsonPrimitive(it)) }
                })
                put("executionMode", tool.executionMode)
            }) }
        }.toString()

        private fun requireState(values: Map<String, JsonElement>) {
            if (values.size > MAX_EXTENSION_STATE_ENTRIES) {
                fail("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
            }
            var totalBytes = 0
            values.forEach { (key, value) ->
                requireStateKey(key)
                val bytes = value.toString().toByteArray().size
                if (bytes > MAX_EXTENSION_STATE_VALUE_BYTES) {
                    fail("EXTENSION_PACKAGE_STATE_VALUE_TOO_LARGE")
                }
                totalBytes += bytes
                if (totalBytes > MAX_EXTENSION_STATE_TOTAL_BYTES) {
                    fail("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
                }
            }
        }

        private fun requireStateKey(value: String?): String = value
            ?.takeIf { STATE_KEY.matches(it) }
            ?: fail("EXTENSION_PACKAGE_STATE_KEY_INVALID")

        private fun requireExecutionStarted() {
            if (!executionStarted) fail("EXTENSION_PACKAGE_FACTORY_SIDE_EFFECT_UNSUPPORTED")
        }

        private fun beginExecutionSlice() {
            sliceStartedNanos = System.nanoTime()
        }

        private fun checkpointExecutionSlice() {
            if (sliceStartedNanos == 0L) return
            val elapsed = (System.nanoTime() - sliceStartedNanos).coerceAtLeast(0L)
            if (elapsed > EXECUTION_CPU_TIMEOUT_MILLIS * 1_000_000L) {
                fail("EXTENSION_PACKAGE_JAVASCRIPT_CPU_TIMEOUT")
            }
            cumulativeSliceNanos += elapsed
            if (cumulativeSliceNanos > EXECUTION_TOTAL_CPU_MILLIS * 1_000_000L) {
                fail("EXTENSION_PACKAGE_JAVASCRIPT_CPU_TIMEOUT")
            }
            sliceStartedNanos = 0L
        }

        private fun requireIdentity(expectedInvocationId: String, expectedGeneration: String) {
            if (expectedInvocationId != invocationId || expectedGeneration != generation) {
                fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
        }

        private fun requireState(expected: State) {
            if (state != expected) fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
        }
    }

    internal companion object {
        val STRICT_JSON = Json { ignoreUnknownKeys = false }
        val IDENTITY = Regex("^[A-Za-z0-9._:-]{1,128}$")
        val PACKAGE_ID = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$")
        val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
        val STATE_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$")
        val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{2,127}$")
        const val SANDBOX_MEMORY_LIMIT_BYTES = 16L * 1024L * 1024L
        const val SANDBOX_STACK_LIMIT_BYTES = 1024L * 1024L
        const val FACTORY_TIMEOUT_MILLIS = 1_000L
        const val EXECUTION_CPU_TIMEOUT_MILLIS = 2_000L
        const val EXECUTION_TOTAL_CPU_MILLIS = 5_000L
        const val INVOCATION_TIMEOUT_MILLIS = 10L * 60L * 1_000L
        const val HOST_CALL_TIMEOUT_MILLIS = 60_000L
        const val MAX_HOST_CALLS = 8
        const val MAX_PROGRESS_UPDATES = 20
        const val MAX_PROGRESS_UPDATE_BYTES = 8 * 1024
        const val MAX_HOST_ARGUMENT_BYTES = 16 * 1024
        const val MAX_HTTP_REQUEST_JSON_BYTES = 1_600 * 1024
        const val MAX_FINAL_RESULT_BYTES = 32 * 1024
        const val MAX_BUFFERED_EVENTS = MAX_PROGRESS_UPDATES + 2
        const val MAX_ABORT_LISTENERS = 32
        const val PRELUDE_FILE = "momoding-pi-worker-prelude.js"
        const val FACTORY_BOOTSTRAP_FILE = "momoding-pi-worker-factory.js"
        const val EXECUTION_FILE = "momoding-pi-worker-execute.js"
        const val MOBILE_SDK_MODULE = "@momoding/sdk"
        val MOBILE_SDK_SOURCE = """
            const host = globalThis.__momodingWorkerHost;
            export const readResource = (path) => host.readResource(path);
            export const getState = (key) => JSON.parse(host.getStateJson(key));
            export const setState = (key, value) => {
              const encoded = JSON.stringify(value);
              if (encoded === undefined) throw new Error("MOMODING_EXTENSION_STATE_INVALID");
              host.setStateJson(key, encoded);
            };
            export const deleteState = (key) => host.deleteState(key);
            export const callTool = async (name, args = {}) => {
              const envelope = JSON.parse(await host.callToolJson(name, JSON.stringify(args)));
              if (envelope.ok !== true) {
                if (envelope.errorCode === "EXTENSION_PACKAGE_STOPPED" ||
                    envelope.errorCode === "EXTENSION_PACKAGE_HOST_TIMEOUT") {
                  globalThis.__momodingPiWorker.abort();
                }
                throw new Error(envelope.errorCode || "EXTENSION_PACKAGE_HOST_CALL_FAILED");
              }
              return envelope.result;
            };
        """.trimIndent()
    }
}

private fun factoryBootstrap(entrypoint: String): String = """
    import factory from ${JsonPrimitive(entrypoint)};
    await globalThis.__momodingPiWorker.loadFactory(factory);
""".trimIndent()

private fun requireArtifact(value: PiRegisterToolArtifact): PiRegisterToolArtifact {
    if (!PiRegisterToolExtensionSandbox.PACKAGE_ID.matches(value.packageId) || value.packageId.length > 76 ||
        !Regex("^[0-9a-f]{64}$").matches(value.packageDigest) ||
        value.entrypoint !in value.modules || value.modules.isEmpty() || value.modules.size > 32 ||
        value.tools.isEmpty() || value.tools.size > 16 ||
        value.tools.map { it.name }.distinct().size != value.tools.size ||
        value.hostTools.map { it.name }.distinct().size != value.hostTools.size ||
        value.modules.size + value.resources.size > MAX_EXTENSION_PACKAGE_FILES ||
        value.modules.keys.any(value.resources::containsKey)
    ) {
        fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
    }
    value.modules.forEach { (path, source) ->
        requireValidExtensionPackagePath(path)
        if (!path.endsWith(".js") || source.toByteArray().size > MAX_EXTENSION_JAVASCRIPT_MODULE_BYTES) {
            fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
        }
    }
    if (value.modules.values.sumOf { it.toByteArray().size } > MAX_EXTENSION_JAVASCRIPT_TOTAL_BYTES) {
        fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
    }
    value.resources.forEach { (path, content) ->
        requireValidExtensionPackagePath(path)
        if (path == EXTENSION_MANIFEST_PATH || path.startsWith("dist/") ||
            content.toByteArray().size > MAX_PI_REGISTER_RESOURCE_BYTES
        ) {
            fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
        }
    }
    val packageBytes = value.modules.values.sumOf { it.toByteArray().size.toLong() } +
        value.resources.values.sumOf { it.toByteArray().size.toLong() }
    if (packageBytes > MAX_EXTENSION_PACKAGE_TOTAL_BYTES) {
        fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
    }
    value.tools.forEach { tool ->
        if (!PiRegisterToolExtensionSandbox.TOOL_NAME.matches(tool.name) ||
            tool.label.isBlank() || tool.label.length > 80 ||
            tool.description.isBlank() || tool.description.length > 512 ||
            tool.parameters["type"]?.jsonPrimitive?.contentOrNull != "object" ||
            tool.parameters["additionalProperties"]?.jsonPrimitive?.contentOrNull != "false" ||
            tool.executionMode != "sequential" || tool.promptGuidelines.size > 16
        ) {
            fail("EXTENSION_PACKAGE_TOOL_INVALID")
        }
    }
    value.hostTools.forEach { host ->
        if (!PiRegisterToolExtensionSandbox.TOOL_NAME.matches(host.name) ||
            !PiRegisterToolExtensionSandbox.TOOL_NAME.matches(host.targetTool)
        ) {
            fail("EXTENSION_PACKAGE_HOST_TOOL_INVALID")
        }
    }
    requirePiExtensionHttpPolicy(value.httpPolicy)
    return value
}

private fun requireRequest(
    artifact: PiRegisterToolArtifact,
    request: PiRegisterToolStartRequest,
): PiRegisterToolStartRequest {
    requireIdentity(request.outerToolCallId)
    if (artifact.tools.none { it.name == request.toolName }) fail("EXTENSION_PACKAGE_TOOL_MISSING")
    requireBoundedJson(request.arguments, 64 * 1024, "EXTENSION_PACKAGE_ARGUMENTS_TOO_LARGE")
    if (request.state.size > MAX_EXTENSION_STATE_ENTRIES ||
        request.state.values.sumOf { it.toString().toByteArray().size } > MAX_EXTENSION_STATE_TOTAL_BYTES ||
        request.state.any { (key, value) ->
            !PiRegisterToolExtensionSandbox.STATE_KEY.matches(key) ||
                value.toString().toByteArray().size > MAX_EXTENSION_STATE_VALUE_BYTES
        }
    ) {
        fail("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
    }
    return request
}

private fun requireResume(
    value: PiRegisterToolResume,
    allowHttpResponse: Boolean,
): PiRegisterToolResume = when (value) {
    is PiRegisterToolResume.Success -> value.also {
        requireBoundedJson(
            it.result,
            if (allowHttpResponse) MAX_HTTP_RESUME_BYTES else 64 * 1024,
            "EXTENSION_PACKAGE_HOST_RESULT_TOO_LARGE",
        )
    }
    is PiRegisterToolResume.Failure -> value.copy(code = stableErrorCode(value.code))
}

private fun parseObject(value: String?, code: String): JsonObject =
    parseElement(value, code) as? JsonObject ?: fail(code)

private fun parseElement(value: String?, code: String): JsonElement = try {
    value?.let(PiRegisterToolExtensionSandbox.STRICT_JSON::parseToJsonElement) ?: fail(code)
} catch (_: Exception) {
    fail(code)
}

private fun singleString(args: Array<out Any?>): String =
    args.singleOrNull() as? String ?: fail("EXTENSION_PACKAGE_ARGUMENT_INVALID")

private fun requireBoundedJson(value: JsonElement, maximumBytes: Int, code: String) {
    if (value.toString().toByteArray().size > maximumBytes || jsonDepth(value) > 12) fail(code)
}

private fun jsonDepth(value: JsonElement): Int = when (value) {
    is JsonArray -> 1 + (value.maxOfOrNull(::jsonDepth) ?: 0)
    is JsonObject -> 1 + (value.values.maxOfOrNull(::jsonDepth) ?: 0)
    else -> 0
}

private fun childToolCallId(outerToolCallId: String, invocationId: String, seq: Int): String =
    "ext:${"$outerToolCallId:$invocationId".sha256Utf8().take(24)}:$seq"

private fun requireIdentity(value: String): String = value
    .takeIf(PiRegisterToolExtensionSandbox.IDENTITY::matches)
    ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")

private fun stableErrorCode(value: String): String = value
    .takeIf(PiRegisterToolExtensionSandbox.ERROR_CODE::matches)
    ?: "EXTENSION_PACKAGE_FAILED"

private fun trustedExtensionErrorCode(error: Throwable, fallback: String): String {
    var current: Throwable? = error
    repeat(8) {
        val code = (current as? ExtensionPackageException)?.message
        if (code != null && PiRegisterToolExtensionSandbox.ERROR_CODE.matches(code) &&
            code.startsWith("EXTENSION_")
        ) {
            return code
        }
        current = current?.cause
    }
    return fallback
}

private fun fail(code: String): Nothing = throw ExtensionPackageException(code)

private const val MAX_PI_REGISTER_RESOURCE_BYTES = 64 * 1024
