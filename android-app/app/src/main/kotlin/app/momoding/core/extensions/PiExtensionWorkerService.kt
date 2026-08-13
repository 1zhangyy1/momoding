package app.momoding.core.extensions

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import app.momoding.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal sealed interface PiExtensionWorkerCommand {
    @Serializable
    @SerialName("start")
    data class Start(
        val artifact: PiRegisterToolArtifact,
        val request: PiRegisterToolStartRequest,
        val invocationId: String,
        val generation: String,
    ) : PiExtensionWorkerCommand

    @Serializable
    @SerialName("next")
    data class Next(val invocationId: String, val generation: String, val seq: Int) :
        PiExtensionWorkerCommand

    @Serializable
    @SerialName("resume")
    data class Resume(
        val invocationId: String,
        val generation: String,
        val seq: Int,
        val response: PiRegisterToolResume,
    ) : PiExtensionWorkerCommand

    @Serializable
    @SerialName("cancel")
    data class Cancel(val invocationId: String, val generation: String) : PiExtensionWorkerCommand

    @Serializable
    @SerialName("probe")
    data class Probe(val mainMarkerPath: String) : PiExtensionWorkerCommand
}

@Serializable
sealed interface PiExtensionWorkerResponse {
    @Serializable
    @SerialName("event")
    data class Event(val value: PiRegisterToolWorkerEvent) : PiExtensionWorkerResponse

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : PiExtensionWorkerResponse

    @Serializable
    @SerialName("probe")
    data class Probe(
        val pid: Int,
        val uid: Int,
        val activeInvocations: Int,
        val internetDenied: Boolean,
        val androidPermissionsDenied: Boolean,
        val mainMarkerDenied: Boolean,
    ) : PiExtensionWorkerResponse

    @Serializable
    @SerialName("error")
    data class Error(val code: String) : PiExtensionWorkerResponse
}

class PiExtensionWorkerService : Service() {
    private val workerDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "momoding-extension-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val sandbox = PiRegisterToolExtensionSandbox(workerDispatcher)
    private val sessions = ConcurrentHashMap<String, PiRegisterToolExtensionSandbox.Session>()
    private val cancelledBeforeStart = LinkedHashSet<String>()

    private val binder = object : IPiExtensionWorker.Stub() {
        override fun execute(
            protocolVersion: Int,
            request: ParcelFileDescriptor,
        ): ParcelFileDescriptor {
            val response = if (protocolVersion != PROTOCOL_VERSION) {
                runCatching { request.close() }
                PiExtensionWorkerResponse.Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            } else {
                runCatching {
                    val payload = readBounded(request, MAX_REQUEST_BYTES)
                    val command = WIRE_JSON.decodeFromString<PiExtensionWorkerCommand>(
                        payload.decodeToString(),
                    )
                    val maximum = when (command) {
                        is PiExtensionWorkerCommand.Start -> MAX_REQUEST_BYTES
                        is PiExtensionWorkerCommand.Resume -> MAX_HTTP_RESUME_REQUEST_BYTES
                        else -> MAX_CONTROL_REQUEST_BYTES
                    }
                    if (payload.size > maximum) {
                        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
                    }
                    runBlocking { handle(command) }
                }.getOrElse { error ->
                    PiExtensionWorkerResponse.Error(workerErrorCode(error))
                }
            }
            val encoded = WIRE_JSON.encodeToString(response).encodeToByteArray()
            val bounded = if (encoded.size <= MAX_RESPONSE_BYTES) {
                encoded
            } else {
                WIRE_JSON.encodeToString<PiExtensionWorkerResponse>(
                    PiExtensionWorkerResponse.Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH"),
                ).encodeToByteArray()
            }
            return responsePipe(bounded)
        }

        override fun terminateForDebugTest() {
            if (!BuildConfig.DEBUG) return
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessions.values.forEach(PiRegisterToolExtensionSandbox.Session::close)
        sessions.clear()
        cancelledBeforeStart.clear()
        workerDispatcher.close()
        super.onDestroy()
    }

    private suspend fun handle(command: PiExtensionWorkerCommand): PiExtensionWorkerResponse =
        when (command) {
            is PiExtensionWorkerCommand.Start -> {
                val session = synchronized(sessions) {
                    if (cancelledBeforeStart.remove(cancelKey(
                            command.invocationId,
                            command.generation,
                        ))) {
                        fail("EXTENSION_PACKAGE_STOPPED")
                    }
                    if (sessions.size >= MAX_SESSIONS || sessions.containsKey(command.invocationId)) {
                        fail("EXTENSION_PACKAGE_WORKER_UNAVAILABLE")
                    }
                    sandbox.open(
                        command.artifact,
                        command.request,
                        PiRegisterToolWorkerIdentity(command.invocationId, command.generation),
                    ).also { sessions[command.invocationId] = it }
                }
                advance(session) { session.start() }
            }
            is PiExtensionWorkerCommand.Next -> session(command.invocationId).let { session ->
                advance(session) {
                    session.next(command.invocationId, command.generation, command.seq)
                }
            }
            is PiExtensionWorkerCommand.Resume -> session(command.invocationId).let { session ->
                advance(session) {
                    session.resume(
                        command.invocationId,
                        command.generation,
                        command.seq,
                        command.response,
                    )
                }
            }
            is PiExtensionWorkerCommand.Cancel -> {
                val session = sessions[command.invocationId]
                if (session == null) {
                    rememberPreStartCancel(command.invocationId, command.generation)
                    PiExtensionWorkerResponse.Cancelled
                } else {
                    try {
                        session.cancel(command.invocationId, command.generation)
                        PiExtensionWorkerResponse.Cancelled
                    } finally {
                        if (sessions.remove(command.invocationId, session)) session.close()
                    }
                }
            }
            is PiExtensionWorkerCommand.Probe -> PiExtensionWorkerResponse.Probe(
                pid = Process.myPid(),
                uid = Process.myUid(),
                activeInvocations = sessions.size,
                internetDenied = checkSelfPermission(Manifest.permission.INTERNET) ==
                    PackageManager.PERMISSION_DENIED,
                androidPermissionsDenied = ISOLATED_PERMISSION_PROBES.all { permission ->
                    checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED
                },
                mainMarkerDenied = command.mainMarkerPath.isNotBlank() &&
                    runCatching { File(command.mainMarkerPath).readText() }.isFailure,
            )
        }

    private fun session(invocationId: String): PiRegisterToolExtensionSandbox.Session =
        sessions[invocationId] ?: fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")

    private fun rememberPreStartCancel(invocationId: String, generation: String) {
        requireWorkerWireIdentity(invocationId)
        requireWorkerWireIdentity(generation)
        synchronized(sessions) {
            val key = cancelKey(invocationId, generation)
            if (key in cancelledBeforeStart) return
            if (cancelledBeforeStart.size >= MAX_PRE_START_CANCELS) {
                // Never evict an exactly-once fence. Rebuild the isolated epoch instead.
                Process.killProcess(Process.myPid())
                fail("EXTENSION_PACKAGE_WORKER_DIED")
            }
            cancelledBeforeStart += key
        }
    }

    private fun eventResponse(
        session: PiRegisterToolExtensionSandbox.Session,
        event: PiRegisterToolWorkerEvent,
    ): PiExtensionWorkerResponse.Event {
        if (event is PiRegisterToolWorkerEvent.Complete || event is PiRegisterToolWorkerEvent.Error) {
            if (sessions.remove(session.invocationId, session)) session.close()
        }
        return PiExtensionWorkerResponse.Event(event)
    }

    private suspend fun advance(
        session: PiRegisterToolExtensionSandbox.Session,
        operation: suspend () -> PiRegisterToolWorkerEvent,
    ): PiExtensionWorkerResponse.Event = try {
        eventResponse(session, operation())
    } catch (error: Throwable) {
        if (sessions.remove(session.invocationId, session)) session.close()
        throw error
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val MAX_CONTROL_REQUEST_BYTES = 64 * 1024
        // HTTP response bodies resume over the existing read-only PFD transport, never raw Binder.
        const val MAX_HTTP_RESUME_REQUEST_BYTES = 1_600 * 1024
        // PFD events also carry a 256 KiB HTTP request after worst-case JSON escaping.
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_SESSIONS = 1
        internal const val MAX_PRE_START_CANCELS = 32
        private val ISOLATED_PERMISSION_PROBES = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        internal val WIRE_JSON = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}

private fun cancelKey(invocationId: String, generation: String): String =
    "$invocationId\u0000$generation"

private fun requireWorkerWireIdentity(value: String) {
    if (!PiRegisterToolExtensionSandbox.IDENTITY.matches(value)) {
        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }
}

private fun readBounded(descriptor: ParcelFileDescriptor, maximumBytes: Int): ByteArray =
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximumBytes) {
                fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

private fun responsePipe(payload: ByteArray): ParcelFileDescriptor {
    if (payload.size > PiExtensionWorkerService.MAX_RESPONSE_BYTES) {
        fail("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH")
    }
    val pipe = ParcelFileDescriptor.createPipe()
    Thread({
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(payload) }
    }, "momoding-extension-worker-response").start()
    return pipe[0]
}

private fun workerErrorCode(error: Throwable): String {
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
    return "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH"
}

private fun fail(code: String): Nothing = throw ExtensionPackageException(code)
