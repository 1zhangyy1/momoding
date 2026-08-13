package app.momoding.core.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class PiExtensionStateCommit(
    val packageId: String,
    val packageDigest: String,
    val toolName: String,
    val artifact: PiRegisterToolArtifact,
    val state: Map<String, JsonElement>,
)

internal data class PiExtensionWorkerEpoch(
    val service: IPiExtensionWorker,
    val generation: String,
    val connection: ServiceConnection,
)

class PiExtensionWorkerClient(
    context: Context,
    /** Returns the repository-authoritative artifact, or null when the package is not executable. */
    private val authorize: suspend (
        packageId: String,
        packageDigest: String,
        toolName: String,
    ) -> PiRegisterToolArtifact?,
    /** Atomically rechecks authorization and commits state only when it returns true. */
    private val commitState: suspend (PiExtensionStateCommit) -> Boolean,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectionLock = Any()
    private val bindMutex = Mutex()
    @Volatile private var epoch: PiExtensionWorkerEpoch? = null
    private var connection: ServiceConnection? = null

    suspend fun probe(mainMarkerPath: String): PiExtensionWorkerResponse.Probe =
        transact(PiExtensionWorkerCommand.Probe(mainMarkerPath)) as? PiExtensionWorkerResponse.Probe
            ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")

    suspend fun terminateForDebugTest() {
        val active = ensureBound()
        try {
            active.service.terminateForDebugTest()
        } catch (_: RemoteException) {
            // Expected: the debug-only call kills the remote process before Binder can reply.
        } finally {
            releaseEpoch(active)
        }
    }

    suspend fun open(
        artifact: PiRegisterToolArtifact,
        request: PiRegisterToolStartRequest,
    ): Session {
        requireAuthorized(artifact, request.toolName)
        val active = ensureBound()
        val invocationId = requireWorkerIdentity(idFactory())
        return Session(
            owner = this,
            artifact = artifact,
            request = request,
            outerToolCallId = request.outerToolCallId,
            toolName = request.toolName,
            invocationId = invocationId,
            workerEpoch = active,
        )
    }

    override fun close() = releaseCurrentConnection()

    internal suspend fun requireAuthorized(
        artifact: PiRegisterToolArtifact,
        toolName: String,
    ) {
        val authoritative = try {
            authorize(artifact.packageId, artifact.packageDigest, toolName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ExtensionPackageException) {
            throw error
        } catch (_: Throwable) {
            fail("EXTENSION_PACKAGE_WORKER_UNAVAILABLE")
        }
        if (authoritative != artifact || authoritative.tools.none { it.name == toolName }) {
            fail("EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED")
        }
    }

    internal fun isCurrent(expected: PiExtensionWorkerEpoch): Boolean = epoch === expected

    private suspend fun ensureBound(): PiExtensionWorkerEpoch = bindMutex.withLock {
        epoch?.let { return@withLock it }
        releaseCurrentConnection()
        try {
            withTimeout(BIND_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val candidate = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val connected = runCatching {
                                val service = IPiExtensionWorker.Stub.asInterface(
                                    binder ?: fail("EXTENSION_PACKAGE_WORKER_UNAVAILABLE"),
                                )
                                PiExtensionWorkerEpoch(
                                    service = service,
                                    generation = requireWorkerIdentity(idFactory()),
                                    connection = this,
                                )
                            }
                            val next = connected.getOrNull()
                            val accepted = next != null && synchronized(connectionLock) {
                                if (connection !== this) return@synchronized false
                                epoch = next
                                true
                            }
                            if (!accepted || !continuation.isActive) {
                                releaseConnection(this)
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(
                                        connected.exceptionOrNull() ?: ExtensionPackageException(
                                            "EXTENSION_PACKAGE_WORKER_UNAVAILABLE",
                                        ),
                                    ))
                                }
                                return
                            }
                            continuation.resumeWith(Result.success(next))
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = bindingFailed()

                        override fun onBindingDied(name: ComponentName?) = bindingFailed()

                        override fun onNullBinding(name: ComponentName?) = bindingFailed()

                        private fun bindingFailed() {
                            releaseConnection(this)
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(
                                    ExtensionPackageException("EXTENSION_PACKAGE_WORKER_UNAVAILABLE"),
                                ))
                            }
                        }
                    }
                    synchronized(connectionLock) { connection = candidate }
                    val didBind = appContext.bindService(
                        Intent(appContext, PiExtensionWorkerService::class.java),
                        candidate,
                        Context.BIND_AUTO_CREATE,
                    )
                    if (!didBind) clearConnection(candidate)
                    if (!didBind && continuation.isActive) {
                        continuation.resumeWith(Result.failure(
                            ExtensionPackageException("EXTENSION_PACKAGE_WORKER_UNAVAILABLE"),
                        ))
                    }
                    continuation.invokeOnCancellation { releaseConnection(candidate) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            releaseCurrentConnection()
            fail("EXTENSION_PACKAGE_WORKER_UNAVAILABLE")
        }
    }

    private fun releaseCurrentConnection() {
        val current = synchronized(connectionLock) { connection }
        if (current != null) {
            releaseConnection(current)
        } else {
            epoch = null
        }
    }

    private fun releaseEpoch(expected: PiExtensionWorkerEpoch) {
        if (epoch === expected) releaseConnection(expected.connection)
    }

    private fun releaseConnection(candidate: ServiceConnection) {
        if (clearConnection(candidate)) {
            runCatching { appContext.unbindService(candidate) }
        }
    }

    private fun clearConnection(candidate: ServiceConnection): Boolean = synchronized(connectionLock) {
        if (connection !== candidate) return@synchronized false
        connection = null
        epoch = null
        true
    }

    private suspend fun transact(
        command: PiExtensionWorkerCommand,
    ): PiExtensionWorkerResponse = transact(command, ensureBound())

    private suspend fun transact(
        command: PiExtensionWorkerCommand,
        expectedEpoch: PiExtensionWorkerEpoch,
    ): PiExtensionWorkerResponse {
        val decoded = executePayload(
            protocolVersion = PiExtensionWorkerService.PROTOCOL_VERSION,
            payload = PiExtensionWorkerService.WIRE_JSON.encodeToString(command).encodeToByteArray(),
            expectedEpoch = expectedEpoch,
        )
        if (decoded is PiExtensionWorkerResponse.Error) fail(decoded.code)
        return decoded
    }

    internal suspend fun transactRawForTest(
        protocolVersion: Int,
        payload: ByteArray,
    ): PiExtensionWorkerResponse = executePayload(protocolVersion, payload, ensureBound())

    private suspend fun executePayload(
        protocolVersion: Int,
        payload: ByteArray,
        expectedEpoch: PiExtensionWorkerEpoch,
    ): PiExtensionWorkerResponse = withContext(Dispatchers.IO) {
        if (!isCurrent(expectedEpoch)) fail("EXTENSION_PACKAGE_WORKER_DIED")
        val request = requestPipe(payload)
        val response = try {
            request.use { expectedEpoch.service.execute(protocolVersion, it) }
        } catch (_: RemoteException) {
            releaseEpoch(expectedEpoch)
            fail("EXTENSION_PACKAGE_WORKER_DIED")
        }
        val responsePayload = try {
            readClientResponse(response)
        } catch (_: IOException) {
            releaseEpoch(expectedEpoch)
            fail("EXTENSION_PACKAGE_WORKER_DIED")
        }
        if (!isCurrent(expectedEpoch)) fail("EXTENSION_PACKAGE_WORKER_DIED")
        requireBoundedWireDepth(responsePayload)
        try {
            PiExtensionWorkerService.WIRE_JSON
                .decodeFromString<PiExtensionWorkerResponse>(responsePayload.decodeToString())
        } catch (_: Exception) {
            fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
        }
    }

    class Session internal constructor(
        private val owner: PiExtensionWorkerClient,
        private val artifact: PiRegisterToolArtifact,
        private val request: PiRegisterToolStartRequest,
        private val outerToolCallId: String,
        private val toolName: String,
        val invocationId: String,
        private val workerEpoch: PiExtensionWorkerEpoch,
    ) {
        private val stateLock = Any()
        private var nextSeq = 0
        private var terminal = false
        private var progressUpdates = 0
        private var hostCalls = 0
        private var possiblePartialEffects = false
        private var started = false
        private var startDispatched = false
        val generation: String get() = workerEpoch.generation

        suspend fun start(): PiRegisterToolWorkerEvent {
            synchronized(stateLock) {
                if (terminal || started) fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                started = true
            }
            if (!owner.isCurrent(workerEpoch)) {
                markTerminal()
                fail("EXTENSION_PACKAGE_WORKER_DIED")
            }
            authorizeOrCancel()
            synchronized(stateLock) {
                if (terminal) fail("EXTENSION_PACKAGE_STOPPED")
                startDispatched = true
            }
            return receive(PiExtensionWorkerCommand.Start(
                artifact = artifact,
                request = request,
                invocationId = invocationId,
                generation = generation,
            ))
        }

        suspend fun next(acknowledgedSeq: Int): PiRegisterToolWorkerEvent {
            requireActive()
            authorizeOrCancel()
            return receive(PiExtensionWorkerCommand.Next(
                invocationId,
                generation,
                acknowledgedSeq,
            ))
        }

        suspend fun resume(
            acknowledgedSeq: Int,
            response: PiRegisterToolResume,
        ): PiRegisterToolWorkerEvent {
            requireActive()
            authorizeOrCancel()
            if (response is PiRegisterToolResume.Success) {
                synchronized(stateLock) {
                    if (terminal) fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                    possiblePartialEffects = true
                }
            }
            return receive(PiExtensionWorkerCommand.Resume(
                invocationId,
                generation,
                acknowledgedSeq,
                response,
            ))
        }

        suspend fun cancel() {
            if (!markTerminal()) return
            if (hasDispatchedStart()) {
                withContext(NonCancellable) { bestEffortRemoteCancel() }
            }
        }

        private suspend fun authorizeOrCancel() {
            try {
                owner.requireAuthorized(artifact, toolName)
            } catch (error: CancellationException) {
                if (markTerminal() && hasDispatchedStart()) {
                    withContext(NonCancellable) { bestEffortRemoteCancel() }
                }
                throw error
            } catch (error: ExtensionPackageException) {
                if (markTerminal() && hasDispatchedStart()) {
                    withContext(NonCancellable) { bestEffortRemoteCancel() }
                }
                throw error
            }
        }

        private suspend fun bestEffortRemoteCancel() {
            try {
                owner.transact(
                    PiExtensionWorkerCommand.Cancel(invocationId, generation),
                    workerEpoch,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Host terminal state is authoritative; a dead/stuck Worker cannot reopen it.
            }
        }

        private suspend fun receive(
            command: PiExtensionWorkerCommand,
        ): PiRegisterToolWorkerEvent = try {
            trusted(event(owner.transact(command, workerEpoch)))
        } catch (error: CancellationException) {
            if (markTerminal() && hasDispatchedStart()) {
                withContext(NonCancellable) { bestEffortRemoteCancel() }
            }
            throw error
        } catch (error: ExtensionPackageException) {
            if (markTerminal() && hasDispatchedStart()) {
                withContext(NonCancellable) { bestEffortRemoteCancel() }
            }
            throw error
        }

        private fun requireActive() {
            synchronized(stateLock) {
                if (terminal || !started) fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            if (!owner.isCurrent(workerEpoch)) {
                markTerminal()
                fail("EXTENSION_PACKAGE_WORKER_DIED")
            }
        }

        private fun markTerminal(): Boolean = synchronized(stateLock) {
            if (terminal) return@synchronized false
            terminal = true
            true
        }

        private fun hasDispatchedStart(): Boolean = synchronized(stateLock) { startDispatched }

        private suspend fun trusted(event: PiRegisterToolWorkerEvent): PiRegisterToolWorkerEvent {
            val trusted = synchronized(stateLock) {
                if (terminal || !owner.isCurrent(workerEpoch) ||
                    event.invocationId != invocationId || event.generation != generation ||
                    event.seq != nextSeq
                ) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                nextSeq += 1
                val normalized = validateAndNormalize(event)
                if (normalized is PiRegisterToolWorkerEvent.Complete ||
                    normalized is PiRegisterToolWorkerEvent.Error
                ) {
                    terminal = true
                }
                normalized
            }
            if (trusted is PiRegisterToolWorkerEvent.Complete) commit(trusted.stateDraft)
            return trusted
        }

        private fun validateAndNormalize(
            event: PiRegisterToolWorkerEvent,
        ): PiRegisterToolWorkerEvent = when (event) {
            is PiRegisterToolWorkerEvent.Update -> event.also {
                progressUpdates += 1
                if (progressUpdates > PiRegisterToolExtensionSandbox.MAX_PROGRESS_UPDATES) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                requireHostBoundedJson(
                    it.update,
                    PiRegisterToolExtensionSandbox.MAX_PROGRESS_UPDATE_BYTES,
                )
            }
            is PiRegisterToolWorkerEvent.HostCall -> {
                hostCalls += 1
                if (hostCalls > PiRegisterToolExtensionSandbox.MAX_HOST_CALLS ||
                    artifact.hostTools.none { it.name == event.name }
                ) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                requireHostBoundedJson(
                    event.arguments,
                    PiRegisterToolExtensionSandbox.MAX_HOST_ARGUMENT_BYTES,
                )
                event.copy(childToolCallId = hostChildToolCallId(
                    outerToolCallId,
                    invocationId,
                    event.seq,
                ))
            }
            is PiRegisterToolWorkerEvent.HttpCall -> event.also {
                hostCalls += 1
                if (hostCalls > PiRegisterToolExtensionSandbox.MAX_HOST_CALLS) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                requireHostBoundedJson(
                    it.request,
                    PiRegisterToolExtensionSandbox.MAX_HTTP_REQUEST_JSON_BYTES,
                )
                try {
                    requireWorkerHttpRequest(artifact.httpPolicy, it.request)
                } catch (_: ExtensionPackageException) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
            }
            is PiRegisterToolWorkerEvent.Complete -> event.also {
                requireHostBoundedJson(
                    it.result,
                    PiRegisterToolExtensionSandbox.MAX_FINAL_RESULT_BYTES,
                )
                requireHostState(it.stateDraft)
            }
            is PiRegisterToolWorkerEvent.Error -> {
                if (!PiRegisterToolExtensionSandbox.ERROR_CODE.matches(event.code) ||
                    !event.code.startsWith("EXTENSION_")
                ) {
                    fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
                if (possiblePartialEffects && !event.partialEffects) {
                    event.copy(partialEffects = true)
                } else {
                    event
                }
            }
        }

        private suspend fun commit(state: Map<String, JsonElement>) {
            val committed = try {
                owner.commitState(PiExtensionStateCommit(
                    packageId = artifact.packageId,
                    packageDigest = artifact.packageDigest,
                    toolName = toolName,
                    artifact = artifact,
                    state = state,
                ))
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExtensionPackageException) {
                throw error
            } catch (_: Throwable) {
                fail("EXTENSION_PACKAGE_WORKER_UNAVAILABLE")
            }
            if (!committed) fail("EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED")
        }
    }

    companion object {
        private const val BIND_TIMEOUT_MILLIS = 5_000L
    }
}

private fun event(response: PiExtensionWorkerResponse): PiRegisterToolWorkerEvent =
    (response as? PiExtensionWorkerResponse.Event)?.value
        ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")

private fun requestPipe(payload: ByteArray): ParcelFileDescriptor {
    if (payload.size > PiExtensionWorkerService.MAX_REQUEST_BYTES) {
        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }
    val pipe = ParcelFileDescriptor.createPipe()
    Thread({
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(payload) }
    }, "momoding-extension-host-request").start()
    return pipe[0]
}

private fun readClientResponse(descriptor: ParcelFileDescriptor): ByteArray =
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > PiExtensionWorkerService.MAX_RESPONSE_BYTES) {
                fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

private fun requireBoundedWireDepth(payload: ByteArray) {
    var depth = 0
    var inString = false
    var escaped = false
    payload.forEach { byte ->
        val character = byte.toInt().toChar()
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_WIRE_JSON_DEPTH) {
                        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                    }
                }
                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                }
            }
        }
    }
    if (inString || escaped || depth != 0) fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
}

private fun requireHostBoundedJson(value: JsonElement, maximumBytes: Int) {
    if (value.toString().encodeToByteArray().size > maximumBytes || hostJsonDepth(value) > 12) {
        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }
}

private fun requireHostState(state: Map<String, JsonElement>) {
    if (state.size > MAX_EXTENSION_STATE_ENTRIES) {
        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }
    var totalBytes = 0
    state.forEach { (key, value) ->
        if (!PiRegisterToolExtensionSandbox.STATE_KEY.matches(key)) {
            fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
        }
        val bytes = value.toString().encodeToByteArray().size
        if (bytes > MAX_EXTENSION_STATE_VALUE_BYTES) {
            fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
        }
        totalBytes += bytes
        if (totalBytes > MAX_EXTENSION_STATE_TOTAL_BYTES || hostJsonDepth(value) > 12) {
            fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
        }
    }
}

private fun hostJsonDepth(value: JsonElement): Int = when (value) {
    is JsonArray -> 1 + (value.maxOfOrNull(::hostJsonDepth) ?: 0)
    is JsonObject -> 1 + (value.values.maxOfOrNull(::hostJsonDepth) ?: 0)
    else -> 0
}

private fun requireWorkerIdentity(value: String): String = value
    .takeIf { PiRegisterToolExtensionSandbox.IDENTITY.matches(it) }
    ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")

private fun hostChildToolCallId(outerToolCallId: String, invocationId: String, seq: Int): String =
    "ext:${"$outerToolCallId:$invocationId".sha256Utf8().take(24)}:$seq"

private fun fail(code: String): Nothing = throw ExtensionPackageException(code)

private const val MAX_WIRE_JSON_DEPTH = 32
