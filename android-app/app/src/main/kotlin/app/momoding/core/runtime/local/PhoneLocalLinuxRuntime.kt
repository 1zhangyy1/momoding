package app.momoding.core.runtime.local

import android.content.Context
import android.net.ConnectivityManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class PhoneLocalCommandRequest(
    val runId: String,
    val workspaceId: String,
    val command: String,
    val timeoutMillis: Long = 120_000,
    val outputLimitBytes: Int = 32_768,
)

data class PhoneLocalCommandResult(
    val runId: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val stopped: Boolean,
    val outputTruncated: Boolean,
    val durationMillis: Long,
)

/**
 * Phone-local Linux command runtime.
 *
 * PRoot and its loader are executable only from the APK-managed nativeLibraryDir. Alpine and each
 * task workspace are data under noBackupFilesDir. No SAF path is bound into Linux; callers must
 * import a snapshot and route every real-directory write through the Android Diff/confirm path.
 *
 * PRoot is path translation, not a hostile-code security boundary. This runtime is therefore a
 * Developer Edition execution environment for model-proposed project commands, not a container for
 * arbitrary untrusted binaries.
 */
class PhoneLocalLinuxRuntime internal constructor(
    context: Context,
    private val beforeProcessStart: suspend (String) -> Unit,
) {
    constructor(context: Context) : this(context, {})

    private val appContext = context.applicationContext
    private val runtimeHome = File(appContext.noBackupFilesDir, "phone-local-linux")
    private val rootfs = File(runtimeHome, "rootfs-$ALPINE_VERSION")
    private val workspaces = File(runtimeHome, "workspaces")
    private val activeRuns = ConcurrentHashMap<String, ActiveRun>()
    private val prepareLock = Any()

    suspend fun prepare() {
        check(prepareUntilStopped { false }) { "PHONE_LOCAL_PREPARE_STOPPED" }
    }

    private suspend fun prepareUntilStopped(stopRequested: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
        synchronized(prepareLock) {
            if (stopRequested()) return@synchronized false
            if (isPrepared()) return@synchronized true
            runtimeHome.mkdirs()
            workspaces.mkdirs()
            val archive = File(runtimeHome, ALPINE_ASSET_NAME)
            appContext.assets.open("phone-local-runtime/$ALPINE_ASSET_NAME").use { source ->
                archive.outputStream().use(source::copyTo)
            }
            check(archive.sha256() == ALPINE_SHA256) { "PHONE_LOCAL_ALPINE_DIGEST_MISMATCH" }

            rootfs.deleteRecursively()
            check(rootfs.mkdirs()) { "PHONE_LOCAL_ROOTFS_CREATE_FAILED" }
            val extractionLog = File(runtimeHome, "rootfs-extraction.log")
            val extraction = ProcessBuilder(
                "/system/bin/tar",
                "-xzf",
                archive.absolutePath,
                "-C",
                rootfs.absolutePath,
                "--restrict",
            ).redirectErrorStream(true)
                .redirectOutput(extractionLog)
                .start()
            val deadlineNanos = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(EXTRACT_TIMEOUT_SECONDS)
            while (extraction.isAlive) {
                if (stopRequested()) {
                    terminateWithinBound(extraction)
                    rootfs.deleteRecursively()
                    archive.delete()
                    extractionLog.delete()
                    return@synchronized false
                }
                val remainingNanos = deadlineNanos - System.nanoTime()
                check(remainingNanos > 0L) {
                    extraction.destroyForcibly()
                    "PHONE_LOCAL_ROOTFS_EXTRACT_TIMEOUT"
                }
                extraction.waitFor(
                    minOf(
                        STOP_POLL_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                    ),
                    TimeUnit.MILLISECONDS,
                )
            }
            check(extraction.exitValue() == 0) {
                "PHONE_LOCAL_ROOTFS_EXTRACT_FAILED: ${extractionLog.readTextOrNull().orEmpty().take(1_024)}"
            }
            // Alpine's /bin/sh is an absolute /bin/busybox symlink, which resolves only after
            // PRoot changes the guest root. Validate the real BusyBox file on the Android host.
            check(File(rootfs, "bin/busybox").isFile) { "PHONE_LOCAL_ROOTFS_SHELL_MISSING" }
            File(runtimeHome, PREPARED_MARKER).writeText(ALPINE_SHA256)
            archive.delete()
            extractionLog.delete()
            true
        }
    }

    fun workspace(workspaceId: String): File {
        requireIdentifier(workspaceId, "workspaceId")
        val workspace = File(workspaces, workspaceId)
        check(workspace.mkdirs() || workspace.isDirectory) { "PHONE_LOCAL_WORKSPACE_CREATE_FAILED" }
        return workspace
    }

    suspend fun execute(request: PhoneLocalCommandRequest): PhoneLocalCommandResult {
        requireIdentifier(request.runId, "runId")
        requireIdentifier(request.workspaceId, "workspaceId")
        require(request.command.isNotBlank()) { "command must not be blank" }
        require(request.timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "timeoutMillis is out of range" }
        require(request.outputLimitBytes in 1..MAX_OUTPUT_BYTES) { "outputLimitBytes is out of range" }
        val active = ActiveRun()
        check(activeRuns.putIfAbsent(request.runId, active) == null) {
            "PHONE_LOCAL_RUN_ALREADY_ACTIVE"
        }
        val startedAt = System.nanoTime()
        return try {
            PhoneLocalWorkspaceLocks.withLock(request.workspaceId) {
                coroutineScope {
                    if (!prepareUntilStopped(active.stopped::get) || active.stopped.get()) {
                        return@coroutineScope stoppedBeforeStartResult(request.runId, startedAt)
                    }
                    refreshDns()

                    val workspace = workspace(request.workspaceId)
                    val prootTemp = File(runtimeHome, "proot-tmp").apply {
                        check(mkdirs() || isDirectory) { "PHONE_LOCAL_PROOT_TEMP_CREATE_FAILED" }
                    }
                    val proot = nativeExecutable(PROOT_LIBRARY)
                    val loader = nativeExecutable(PROOT_LOADER_LIBRARY)
                    val prootArguments = mutableListOf(
                        proot.absolutePath,
                        "--kill-on-exit",
                        "-0",
                    )
                    if (requiresApkLinkCompatibility(request.command)) {
                        // Android app UIDs cannot create every cross-directory hardlink used by
                        // Alpine packages. Restrict this translation to a standalone apk mutation;
                        // enabling it for project commands would change normal hardlink semantics.
                        prootArguments += "--link2symlink"
                    }
                    prootArguments += listOf(
                        "-r",
                        rootfs.absolutePath,
                        "-b",
                        "/dev/null",
                        "-b",
                        "/dev/zero",
                        "-b",
                        "/dev/urandom",
                        "-b",
                        "/proc",
                        "-b",
                        "${workspace.absolutePath}:/workspace",
                        "-w",
                        "/workspace",
                        "/usr/bin/env",
                        "-i",
                        "HOME=/root",
                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                        "TMPDIR=/tmp",
                        "/bin/sh",
                        "-lc",
                        request.command,
                    )
                    val processBuilder = ProcessBuilder(prootArguments).apply {
                        environment().clear()
                        environment()["PROOT_LOADER"] = loader.absolutePath
                        environment()["PROOT_TMP_DIR"] = prootTemp.absolutePath
                        environment()["PROOT_F2FS_WORKAROUND"] = "0"
                        environment()["MOMODING_PROOT_RESET_GUEST_SIGNALS"] = "1"
                        environment()["MOMODING_PROOT_DISABLE_PIPE_SHADOW"] = "1"
                    }
                    beforeProcessStart(request.runId)
                    val process = active.startProcess(processBuilder::start)
                        ?: return@coroutineScope stoppedBeforeStartResult(
                            request.runId,
                            startedAt,
                        )

                    val budget = AtomicInteger(request.outputLimitBytes)
                    val truncated = AtomicBoolean(false)
                    val stdout = async(Dispatchers.IO) {
                        process.inputStream.readCapped(budget, truncated)
                    }
                    val stderr = async(Dispatchers.IO) {
                        process.errorStream.readCapped(budget, truncated)
                    }
                    var timedOut = false
                    try {
                        withContext(Dispatchers.IO) {
                            val deadlineNanos = System.nanoTime() +
                                TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
                            while (process.isAlive) {
                                currentCoroutineContext().ensureActive()
                                if (active.stopped.get()) {
                                    terminateWithinBound(process)
                                    break
                                }
                                val remainingNanos = deadlineNanos - System.nanoTime()
                                if (remainingNanos <= 0L) {
                                    timedOut = true
                                    terminateWithinBound(process)
                                    break
                                }
                                val waitMillis = minOf(
                                    STOP_POLL_MILLIS,
                                    TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                                )
                                process.waitFor(waitMillis, TimeUnit.MILLISECONDS)
                            }
                        }
                        val stopped = active.stopped.get()
                        PhoneLocalCommandResult(
                            runId = request.runId,
                            stdout = stdout.await(),
                            stderr = stderr.await(),
                            exitCode = if (process.isAlive) null else process.exitValue(),
                            timedOut = timedOut && !stopped,
                            stopped = stopped,
                            outputTruncated = truncated.get(),
                            durationMillis = TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt,
                            ),
                        )
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
            }
        } finally {
            activeRuns.remove(request.runId, active)
        }
    }

    fun stop(runId: String): Boolean {
        requireIdentifier(runId, "runId")
        val active = activeRuns[runId] ?: return false
        return active.requestStop()
    }

    private fun isPrepared(): Boolean =
        File(runtimeHome, PREPARED_MARKER).readTextOrNull() == ALPINE_SHA256 &&
            File(rootfs, "bin/busybox").isFile

    /**
     * Alpine's musl resolver reads this file directly. Android exposes the active network DNS
     * through ConnectivityManager rather than a stable host /etc/resolv.conf, so refresh it before
     * every command. This keeps on-demand apk installs working after Wi-Fi, cellular or VPN changes.
     */
    private fun refreshDns() {
        val currentServers = runCatching {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val network = connectivity.activeNetwork ?: return@runCatching emptyList()
            connectivity.getLinkProperties(network)
                ?.dnsServers
                .orEmpty()
                .mapNotNull { address -> address.hostAddress }
        }.getOrDefault(emptyList())
        val destination = File(rootfs, "etc/resolv.conf")
        val temporary = File(destination.parentFile, ".resolv.conf.${UUID.randomUUID()}.tmp")
        runCatching {
            check(
                destination.parentFile?.mkdirs() != false ||
                    destination.parentFile?.isDirectory == true,
            )
            temporary.writeText(renderPhoneLocalResolvConf(currentServers))
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        runCatching { Files.deleteIfExists(temporary.toPath()) }
    }

    private fun nativeExecutable(name: String): File {
        val file = File(appContext.applicationInfo.nativeLibraryDir, name)
        check(file.isFile && file.canExecute()) { "PHONE_LOCAL_NATIVE_EXECUTABLE_MISSING: $name" }
        return file
    }

    private fun requireIdentifier(value: String, label: String) {
        require(value.matches(IDENTIFIER_PATTERN) && value != "." && value != "..") {
            "$label is invalid"
        }
    }

    private fun terminateWithinBound(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return
        process.destroyForcibly()
        if (!process.waitFor(FORCE_KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            error("PHONE_LOCAL_PROCESS_FORCE_KILL_FAILED")
        }
    }

    private fun stoppedBeforeStartResult(
        runId: String,
        startedAt: Long,
    ) = PhoneLocalCommandResult(
        runId = runId,
        stdout = "",
        stderr = "",
        exitCode = null,
        timedOut = false,
        stopped = true,
        outputTruncated = false,
        durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
    )

    private class ActiveRun {
        val stopped: AtomicBoolean = AtomicBoolean(false)
        private val startLock = Any()
        private var process: Process? = null

        fun startProcess(start: () -> Process): Process? = synchronized(startLock) {
            if (stopped.get()) return@synchronized null
            start().also { process = it }
        }

        fun requestStop(): Boolean = synchronized(startLock) {
            stopped.set(true)
            process?.destroy()
            true
        }
    }

    companion object {
        const val ALPINE_VERSION = "3.23.5"
        const val ALPINE_SHA256 = "d9a77cb31f715c56afa4f0a5aa42c04cfde813b70ad74a64725902b09c29a6cc"
        private const val ALPINE_ASSET_NAME = "alpine-minirootfs-3.23.5-aarch64.tgz"
        private const val PROOT_LIBRARY = "libmomoding_proot.so"
        private const val PROOT_LOADER_LIBRARY = "libmomoding_proot_loader.so"
        private const val PREPARED_MARKER = ".prepared-sha256"
        private const val EXTRACT_TIMEOUT_SECONDS = 30L
        private const val STOP_POLL_MILLIS = 50L
        private const val STOP_GRACE_MILLIS = 1_000L
        private const val FORCE_KILL_WAIT_MILLIS = 1_000L
        private const val MAX_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        private const val MAX_OUTPUT_BYTES = 1_048_576
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

/** Serializes command execution, project import and Diff scanning for one private workspace. */
internal object PhoneLocalWorkspaceLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(workspaceId: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(workspaceId) { Mutex() }
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun java.io.InputStream.readCapped(
    remaining: AtomicInteger,
    truncated: AtomicBoolean,
): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    use { input ->
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (_: IOException) {
                // Android closes process pipes from another thread when a timed-out or stopped
                // native process is destroyed. Preserve bytes already received in that case.
                break
            }
            if (count < 0) break
            val allowed = remaining.getAndUpdate { current -> (current - count).coerceAtLeast(0) }
                .coerceAtMost(count)
            if (allowed > 0) output.write(buffer, 0, allowed)
            if (allowed < count) truncated.set(true)
        }
    }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.IGNORE)
        .onUnmappableCharacter(CodingErrorAction.IGNORE)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
}

internal fun renderPhoneLocalResolvConf(servers: List<String>): String {
    val safeServers = servers.asSequence()
        .map(String::trim)
        .filter { server ->
            server.isNotEmpty() &&
                server.length <= 64 &&
                server.all { character ->
                    character.isDigit() ||
                        character.lowercaseChar() in 'a'..'f' ||
                        character in setOf('.', ':', '%')
                }
        }
        .distinct()
        .take(MAX_DNS_SERVERS)
        .toList()
        .ifEmpty { FALLBACK_DNS_SERVERS }
    return safeServers.joinToString(separator = "\n", postfix = "\n") { server ->
        "nameserver $server"
    }
}

internal fun requiresApkLinkCompatibility(command: String): Boolean {
    val trimmed = command.trim()
    if (trimmed.any { it in APK_STANDALONE_FORBIDDEN_CHARACTERS }) return false
    val tokens = trimmed.split(Regex("\\s+"))
    if (tokens.size < 2 || tokens.first() !in setOf("apk", "/sbin/apk")) return false
    return tokens[1] in APK_MUTATION_COMMANDS
}

private const val MAX_DNS_SERVERS = 4
private val FALLBACK_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
private val APK_MUTATION_COMMANDS = setOf("add", "del", "fix", "upgrade")
private val APK_STANDALONE_FORBIDDEN_CHARACTERS =
    setOf('\n', '\r', ';', '&', '|', '<', '>', '`', '$', '(', ')')
