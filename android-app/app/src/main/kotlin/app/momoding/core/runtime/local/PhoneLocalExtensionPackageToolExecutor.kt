package app.momoding.core.runtime.local

import app.momoding.core.extensions.ExtensionPackageException
import app.momoding.core.extensions.ExtensionPackageRepository
import app.momoding.core.extensions.ExtensionToolType
import app.momoding.core.extensions.JavaScriptExtensionSandbox
import app.momoding.core.extensions.MAX_EXTENSION_PACKAGES
import app.momoding.core.extensions.PiExtensionHostHttpExecution
import app.momoding.core.extensions.PiExtensionHostHttpException
import app.momoding.core.extensions.PiExtensionWorkerClient
import app.momoding.core.extensions.PiRegisterToolArtifact
import app.momoding.core.extensions.PiRegisterToolResume
import app.momoding.core.extensions.PiRegisterToolStartRequest
import app.momoding.core.extensions.PiRegisterToolWorkerEvent
import java.util.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class PhoneLocalExtensionPackageToolExecutor(
    private val repository: ExtensionPackageRepository,
    private val sandbox: JavaScriptExtensionSandbox = JavaScriptExtensionSandbox(Dispatchers.Default),
    private val piWorkerClient: PiExtensionWorkerClient? = null,
    private val piHttpExecute: (suspend (
        PiRegisterToolArtifact,
        JsonObject,
    ) -> PiExtensionHostHttpExecution)? = null,
) {
    private val packageExecutionLocks = List(MAX_EXTENSION_PACKAGES) { Mutex() }
    private val piSessionLock = Mutex()
    private val piSessions = mutableMapOf<String, PiSessionBinding>()
    private val deadlineWaiters = mutableMapOf<DeadlineKey, CompletableDeferred<Unit>>()
    private val cancelledDeadlines = LinkedHashSet<DeadlineKey>()

    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult {
        if (request.kind != NATIVE_KIND || request.toolName !in TOOL_NAMES) {
            throw IllegalArgumentException("PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED")
        }
        if (request.toolName == EXECUTE_PI_TOOL) return executePi(taskId, request)
        val packageId = request.arguments.requiredString("packageId")
        val packageDigest = request.arguments.requiredString("packageDigest")
        val extensionToolName = request.arguments.requiredString("extensionToolName")
        val type = request.arguments.requiredString("type")
        val description = request.arguments.requiredString("description", 512)
        val targetTool = request.arguments.optionalString("targetTool", 128)
        val prompt = request.arguments.optionalString("prompt", 16 * 1_024)
        return try {
            if (request.toolName == EXECUTE_JAVASCRIPT_TOOL) {
                if (type != ExtensionToolType.JAVASCRIPT_TOOL.wireValue) {
                    throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")
                }
                return executeJavaScript(
                    taskId = taskId,
                    packageId = packageId,
                    packageDigest = packageDigest,
                    extensionToolName = extensionToolName,
                    description = description,
                    parametersDigest = request.arguments.requiredString("parametersDigest", 64),
                    arguments = request.arguments.requiredObject("invocationArguments"),
                )
            }
            repository.authorizeTool(
                packageId = packageId,
                packageDigest = packageDigest,
                toolName = extensionToolName,
                type = type,
                description = description,
                targetTool = targetTool,
                prompt = prompt,
                parameters = request.arguments.optionalObject("parameters"),
            )
            val payload = buildJsonObject {
                put("ok", true)
                put("packageId", packageId)
                put("toolName", extensionToolName)
            }
            PiNativeAndroidToolResult(contentPayload = payload, details = payload)
        } catch (error: ExtensionPackageException) {
            val payload = buildJsonObject {
                put("ok", false)
                put("errorCode", error.code)
                put("errorMessage", publicMessage(error.code))
            }
            PiNativeAndroidToolResult(
                contentPayload = payload,
                details = payload,
                isError = true,
            )
        }
    }

    private suspend fun executePi(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult = try {
        when (val action = request.arguments.requiredString("action", 32)) {
            "start" -> startPi(taskId, request.arguments)
            "next" -> nextPi(taskId, request.arguments)
            "resume" -> resumePi(taskId, request.arguments)
            "authorize_host_call" -> authorizeHostCall(taskId, request.arguments)
            "http" -> httpPi(taskId, request.arguments)
            "await_deadline" -> awaitDeadline(taskId, request.arguments)
            "cancel_deadline" -> cancelDeadline(taskId, request.arguments)
            "cancel" -> cancelPi(taskId, request.arguments)
            else -> throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: ExtensionPackageException) {
        piFailure(error.code)
    } catch (error: PiExtensionHostHttpException) {
        piFailure(error.code)
    } catch (_: IllegalArgumentException) {
        piFailure("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }

    private suspend fun startPi(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireExactKeys(
            "action", "invocationArguments", "outerToolCallId", "packageDigest", "packageId", "toolName",
        )
        val packageId = arguments.requiredString("packageId", 76)
        val packageDigest = arguments.requiredDigest("packageDigest")
        val toolName = arguments.requiredString("toolName", 64)
        val invocation = repository.piRegisterToolInvocation(packageId, packageDigest, toolName)
        val request = PiRegisterToolStartRequest(
            outerToolCallId = arguments.requiredString("outerToolCallId", 128),
            toolName = toolName,
            arguments = arguments.requiredObject("invocationArguments", 64 * 1_024),
            state = invocation.state,
        )
        val client = piWorkerClient
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_UNAVAILABLE")
        val session = client.open(invocation.artifact, request)
        val binding = PiSessionBinding(
            taskId = taskId,
            packageId = packageId,
            packageDigest = packageDigest,
            toolName = toolName,
            artifact = invocation.artifact,
            session = session,
        )
        piSessionLock.withLock {
            if (piSessions.isNotEmpty() || piSessions.putIfAbsent(session.invocationId, binding) != null) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_BUSY")
            }
        }
        return try {
            binding.transition.withLock { acceptEvent(binding, session.start()) }
        } catch (error: Throwable) {
            removeAndCancel(binding)
            throw error
        }
    }

    private suspend fun nextPi(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireExactKeys("action", "generation", "invocationId", "seq")
        val binding = requireBinding(taskId, arguments)
        return binding.transition.withLock {
            val seq = arguments.requiredInt("seq")
            val prior = binding.lastEvent as? PiRegisterToolWorkerEvent.Update
                ?: throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            require(prior.seq == seq) { "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH" }
            acceptEvent(binding, binding.session.next(seq))
        }
    }

    private suspend fun resumePi(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireAllowedKeys(
            required = setOf("action", "generation", "invocationId", "seq"),
            optional = setOf("result", "errorCode"),
        )
        val binding = requireBinding(taskId, arguments)
        return binding.transition.withLock {
            val seq = arguments.requiredInt("seq")
            val prior = binding.lastEvent
            if (prior !is PiRegisterToolWorkerEvent.HostCall && prior !is PiRegisterToolWorkerEvent.HttpCall) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            require(prior.seq == seq) { "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH" }
            val result = arguments["result"] as? JsonObject
            val errorCode = arguments.optionalString("errorCode", 128)
            if ((result == null) == (errorCode == null)) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            val response = result?.let(PiRegisterToolResume::Success)
                ?: PiRegisterToolResume.Failure(requireNotNull(errorCode))
            acceptEvent(binding, binding.session.resume(seq, response))
        }
    }

    private suspend fun httpPi(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireExactKeys(
            "action", "generation", "invocationId", "packageDigest", "packageId", "request", "seq",
        )
        val binding = requireBinding(taskId, arguments)
        require(arguments.requiredString("packageId", 76) == binding.packageId &&
            arguments.requiredDigest("packageDigest") == binding.packageDigest) {
            "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH"
        }
        val request = arguments.requiredObject("request", 256 * 1_024)
        val seq = arguments.requiredInt("seq")
        val artifact = repository.authorizePiRegisterToolArtifact(
            binding.packageId,
            binding.packageDigest,
            binding.toolName,
        )
        require(artifact == binding.artifact) { "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED" }
        binding.transition.withLock {
            val prior = binding.lastEvent as? PiRegisterToolWorkerEvent.HttpCall
                ?: throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            require(prior.seq == seq && prior.request == request && binding.httpStartedSeq.add(seq)) {
                "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH"
            }
        }
        val execution = (piHttpExecute
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_HTTP_UNAVAILABLE"))
            .invoke(artifact, request)
        return piSuccess("execution" to execution.toJson())
    }

    private suspend fun authorizeHostCall(
        taskId: String,
        arguments: JsonObject,
    ): PiNativeAndroidToolResult {
        arguments.requireAllowedKeys(
            required = setOf(
                "action", "generation", "invocationId", "name", "packageDigest",
                "packageId", "seq", "targetTool",
            ),
            optional = setOf("capability"),
        )
        val binding = requireBinding(taskId, arguments)
        val packageId = arguments.requiredString("packageId", 76)
        val packageDigest = arguments.requiredDigest("packageDigest")
        val name = arguments.requiredString("name", 64)
        val targetTool = arguments.requiredString("targetTool", 128)
        val capability = arguments.optionalString("capability", 128)
        val seq = arguments.requiredInt("seq")
        require(packageId == binding.packageId && packageDigest == binding.packageDigest) {
            "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH"
        }
        binding.transition.withLock {
            val prior = binding.lastEvent as? PiRegisterToolWorkerEvent.HostCall
                ?: throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            require(prior.seq == seq && prior.name == name) {
                "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH"
            }
            val declared = binding.artifact.hostTools.singleOrNull { host -> host.name == name }
                ?: throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED")
            require(declared.targetTool == targetTool && declared.capability == capability) {
                "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED"
            }
            val current = repository.authorizePiRegisterToolArtifact(
                packageId,
                packageDigest,
                binding.toolName,
            )
            require(current == binding.artifact && current.hostTools.singleOrNull {
                it.name == name && it.targetTool == targetTool && it.capability == capability
            } != null) {
                "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED"
            }
        }
        return piSuccess()
    }

    private suspend fun awaitDeadline(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireExactKeys("action", "generation", "invocationId", "seq", "timeoutMillis")
        val binding = requireBinding(taskId, arguments)
        val key = DeadlineKey(binding.session.invocationId, binding.session.generation, arguments.requiredInt("seq"))
        val timeout = arguments.requiredInt("timeoutMillis").toLong()
        require(timeout in 1..MAX_DEADLINE_MILLIS) { "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH" }
        val waiter = piSessionLock.withLock {
            val prior = binding.lastEvent
            if ((prior !is PiRegisterToolWorkerEvent.HostCall && prior !is PiRegisterToolWorkerEvent.HttpCall) ||
                prior.seq != key.seq || deadlineWaiters.containsKey(key)
            ) throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            if (cancelledDeadlines.remove(key)) return@withLock null
            CompletableDeferred<Unit>().also { deadlineWaiters[key] = it }
        }
        if (waiter != null) {
            try {
                withTimeoutOrNull(timeout) { waiter.await() }
            } finally {
                piSessionLock.withLock { deadlineWaiters.remove(key, waiter) }
            }
        }
        return piSuccess()
    }

    private suspend fun cancelDeadline(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireExactKeys("action", "generation", "invocationId", "seq")
        val key = DeadlineKey(
            arguments.requiredString("invocationId"),
            arguments.requiredString("generation"),
            arguments.requiredInt("seq"),
        )
        requireBinding(taskId, arguments)
        piSessionLock.withLock {
            deadlineWaiters[key]?.complete(Unit) ?: run {
                if (cancelledDeadlines.size >= MAX_CANCELLED_DEADLINES) {
                    cancelledDeadlines.remove(cancelledDeadlines.first())
                }
                cancelledDeadlines.add(key)
            }
        }
        return piSuccess()
    }

    private suspend fun cancelPi(taskId: String, arguments: JsonObject): PiNativeAndroidToolResult {
        arguments.requireExactKeys("action", "generation", "invocationId")
        val invocationId = arguments.requiredString("invocationId")
        val generation = arguments.requiredString("generation")
        val binding = piSessionLock.withLock {
            piSessions[invocationId]?.takeIf {
                it.taskId == taskId && it.session.generation == generation
            }?.also {
                it.terminal = true
                piSessions.remove(invocationId)
                clearDeadlines(invocationId, generation)
            }
        }
        binding?.session?.cancel()
        return piSuccess()
    }

    internal suspend fun stopTask(taskId: String) {
        val bindings = piSessionLock.withLock {
            piSessions.values.filter { it.taskId == taskId }.also { owned ->
                owned.forEach { binding ->
                    binding.terminal = true
                    piSessions.remove(binding.session.invocationId, binding)
                    clearDeadlines(binding.session.invocationId, binding.session.generation)
                }
            }
        }
        withContext(NonCancellable) {
            bindings.forEach { binding -> runCatching { binding.session.cancel() } }
        }
    }

    private suspend fun requireBinding(taskId: String, arguments: JsonObject): PiSessionBinding {
        val invocationId = arguments.requiredString("invocationId")
        val generation = arguments.requiredString("generation")
        return piSessionLock.withLock {
            piSessions[invocationId]?.takeIf {
                it.taskId == taskId && it.session.generation == generation
            }
        } ?: throw ExtensionPackageException("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }

    private suspend fun acceptEvent(
        binding: PiSessionBinding,
        event: PiRegisterToolWorkerEvent,
    ): PiNativeAndroidToolResult {
        val active = piSessionLock.withLock {
            !binding.terminal && piSessions[binding.session.invocationId] === binding
        }
        if (!active) throw ExtensionPackageException("EXTENSION_PACKAGE_STOPPED")
        binding.lastEvent = event
        if (event is PiRegisterToolWorkerEvent.Complete || event is PiRegisterToolWorkerEvent.Error) {
            piSessionLock.withLock {
                binding.terminal = true
                piSessions.remove(binding.session.invocationId, binding)
                clearDeadlines(binding.session.invocationId, binding.session.generation)
            }
        }
        val serialized = app.momoding.core.extensions.PiExtensionWorkerService.WIRE_JSON
            .parseToJsonElement(
                app.momoding.core.extensions.PiExtensionWorkerService.WIRE_JSON
                    .encodeToString<PiRegisterToolWorkerEvent>(event),
            ).jsonObject
        val publicEvent = if (event is PiRegisterToolWorkerEvent.Complete) {
            JsonObject(serialized - "stateDraft")
        } else {
            serialized
        }
        return piSuccess("event" to publicEvent)
    }

    private suspend fun removeAndCancel(binding: PiSessionBinding) {
        piSessionLock.withLock {
            binding.terminal = true
            piSessions.remove(binding.session.invocationId, binding)
            clearDeadlines(binding.session.invocationId, binding.session.generation)
        }
        runCatching { binding.session.cancel() }
    }

    private fun clearDeadlines(invocationId: String, generation: String) {
        deadlineWaiters.filterKeys {
            it.invocationId == invocationId && it.generation == generation
        }.values.forEach { it.complete(Unit) }
        deadlineWaiters.keys.removeAll { it.invocationId == invocationId && it.generation == generation }
        cancelledDeadlines.removeAll { it.invocationId == invocationId && it.generation == generation }
    }

    private fun piSuccess(vararg entries: Pair<String, JsonElement>): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", true)
            entries.forEach { (key, value) -> put(key, value) }
        }
        return PiNativeAndroidToolResult(payload, payload)
    }

    private fun piFailure(code: String): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("errorCode", code)
            put("errorMessage", publicMessage(code))
        }
        return PiNativeAndroidToolResult(payload, payload, isError = true)
    }

    private suspend fun executeJavaScript(
        taskId: String,
        packageId: String,
        packageDigest: String,
        extensionToolName: String,
        description: String,
        parametersDigest: String,
        arguments: JsonObject,
    ): PiNativeAndroidToolResult = packageExecutionLocks[
        Math.floorMod(packageId.hashCode(), packageExecutionLocks.size)
    ]
        .withLock {
            val invocation = repository.javascriptInvocation(
                packageId = packageId,
                packageDigest = packageDigest,
                toolName = extensionToolName,
                description = description,
                parametersDigest = parametersDigest,
                arguments = arguments,
            )
            val execution = sandbox.execute(invocation, arguments)
            if (execution.stateChanged) {
                repository.commitJavaScriptState(packageId, packageDigest, execution.stateJson)
            }
            val details = buildJsonObject {
                put("ok", true)
                put("packageId", packageId)
                put("packageDigest", packageDigest)
                put("toolName", extensionToolName)
                put("taskId", taskId)
                if (execution.hostToolName == null) {
                    put("kind", "javascript-result")
                } else {
                    put("kind", "host-call")
                    put("hostToolName", execution.hostToolName)
                    put("arguments", requireNotNull(execution.hostArguments))
                }
            }
            val payload = buildJsonObject {
                put("ok", true)
                put("kind", details["kind"]!!)
                execution.text?.let { put("text", it) }
                execution.hostToolName?.let { put("hostToolName", it) }
            }
            val content: JsonArray = buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put(
                        "text",
                        execution.text ?: "Extension requested a declared Momoding Tool.",
                    )
                })
            }
            PiNativeAndroidToolResult(
                contentPayload = payload,
                details = details,
                content = content,
            )
        }

    private fun publicMessage(code: String): String = when (code) {
        "EXTENSION_PACKAGE_NOT_FOUND", "EXTENSION_PACKAGE_NOT_ENABLED" ->
            "This Extension is no longer enabled on this phone."
        "EXTENSION_PACKAGE_SNAPSHOT_STALE" ->
            "This Extension changed after the task loaded it. Start the next turn to refresh it."
        "EXTENSION_PACKAGE_JAVASCRIPT_TIMEOUT" ->
            "This Extension exceeded its execution time limit."
        "EXTENSION_PACKAGE_JAVASCRIPT_FAILED" ->
            "This Extension stopped inside its isolated JavaScript runtime."
        "EXTENSION_PACKAGE_HOST_TIMEOUT" ->
            "This Extension waited too long for an Android action."
        "EXTENSION_PACKAGE_HTTP_UNAVAILABLE" ->
            "Secure network access is unavailable for this Extension."
        else -> "Android rejected this Extension Tool request."
    }

    companion object {
        const val NATIVE_KIND = "android_extension_package"
        const val AUTHORIZE_TOOL = "extension_package_authorize"
        const val EXECUTE_JAVASCRIPT_TOOL = "extension_package_execute_javascript"
        const val EXECUTE_PI_TOOL = "extension_package_execute_pi"
        private const val MAX_DEADLINE_MILLIS = 60_000
        private const val MAX_CANCELLED_DEADLINES = 32
        val TOOL_NAMES = setOf(AUTHORIZE_TOOL, EXECUTE_JAVASCRIPT_TOOL, EXECUTE_PI_TOOL)
    }
}

private data class PiSessionBinding(
    val taskId: String,
    val packageId: String,
    val packageDigest: String,
    val toolName: String,
    val artifact: PiRegisterToolArtifact,
    val session: PiExtensionWorkerClient.Session,
    val transition: Mutex = Mutex(),
    val httpStartedSeq: MutableSet<Int> = mutableSetOf(),
    var lastEvent: PiRegisterToolWorkerEvent? = null,
    var terminal: Boolean = false,
)

private data class DeadlineKey(
    val invocationId: String,
    val generation: String,
    val seq: Int,
)

private fun kotlinx.serialization.json.JsonObject.requiredString(
    key: String,
    maximum: Int = 128,
): String =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() && it.length <= maximum && '\u0000' !in it }
        ?: throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")

private fun kotlinx.serialization.json.JsonObject.optionalString(key: String, maximum: Int): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() && it.length <= maximum && '\u0000' !in it }
        ?: if (key !in this) null else throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")

private fun JsonObject.requiredObject(key: String, maximumBytes: Int = 16 * 1024): JsonObject =
    (this[key] as? JsonObject)
        ?.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= maximumBytes }
        ?: throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")

private fun JsonObject.optionalObject(key: String): JsonObject? =
    (this[key] as? JsonObject)
        ?.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024 }
        ?: if (key !in this) null else throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")

private fun JsonObject.requiredInt(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")

private fun JsonObject.requiredDigest(key: String): String =
    requiredString(key, 64).takeIf { DIGEST.matches(it) }
        ?: throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")

private fun JsonObject.requireExactKeys(vararg expected: String) {
    if (keys != expected.toSet()) {
        throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")
    }
}

private fun JsonObject.requireAllowedKeys(required: Set<String>, optional: Set<String>) {
    if (!keys.containsAll(required) || keys.any { it !in required && it !in optional }) {
        throw IllegalArgumentException("PI_MOBILE_EXTENSION_PACKAGE_GATE_INVALID")
    }
}

private val DIGEST = Regex("^[0-9a-f]{64}$")
