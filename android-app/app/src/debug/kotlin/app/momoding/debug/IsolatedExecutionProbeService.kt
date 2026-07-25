package app.momoding.debug

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Debug-only probe for Isolated execution. It verifies Android's isolated-process boundary before a project
 * runtime is selected. The production executor must not use Android's host shell.
 */
class IsolatedExecutionProbeService : Service() {
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { request ->
            if (request.what != REQUEST_PROBE) return@Handler false
            val replyTo = request.replyTo
            val mainMarkerPath = request.data.getString(KEY_MAIN_MARKER_PATH).orEmpty()
            Thread {
                Log.i(TAG, "probe-start")
                val responseData = runCatching {
                    runProbe(mainMarkerPath)
                }.getOrElse { error ->
                    Log.e(TAG, "probe-failed", error)
                    Bundle().apply { putString(KEY_PROBE_ERROR, error.stackTraceToString()) }
                }
                Log.i(TAG, "probe-reply")
                runCatching {
                    replyTo.send(
                        Message.obtain(null, RESPONSE_PROBE).apply { data = responseData },
                    )
                }.onFailure { error -> Log.e(TAG, "probe-reply-failed", error) }
            }.start()
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun runProbe(mainMarkerPath: String): Bundle {
        val workspace = File(cacheDir, "isolated-execution-execution-probe")
        val workspaceCreated = workspace.mkdirs() || workspace.isDirectory
        val workspaceMarker = File(workspace, "workspace.txt")
        val workspaceWritable = runCatching {
            workspaceMarker.writeText("isolated-workspace")
            workspaceMarker.readText() == "isolated-workspace"
        }.getOrDefault(false)

        val command = if (workspaceWritable) {
            runCatching {
                val process = ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    "pwd; printf 'probe-out'; printf 'probe-err' >&2; exit 7",
                )
                    .directory(workspace)
                    .start()
                val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                ProbeCommand(
                    started = true,
                    finished = finished,
                    stdout = process.inputStream.bufferedReader().readText(),
                    stderr = process.errorStream.bufferedReader().readText(),
                    exitCode = if (finished) process.exitValue() else null,
                )
            }.getOrElse { error ->
                ProbeCommand(started = false, error = error.javaClass.simpleName)
            }
        } else {
            ProbeCommand(started = false, error = "WORKSPACE_UNAVAILABLE")
        }

        return Bundle().apply {
            putInt(KEY_UID, Process.myUid())
            putInt(KEY_PID, Process.myPid())
            putBoolean(
                KEY_INTERNET_DENIED,
                checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED,
            )
            putBoolean(
                KEY_MAIN_MARKER_DENIED,
                mainMarkerPath.isNotBlank() && !runCatching { File(mainMarkerPath).readText() }.isSuccess,
            )
            putString(KEY_CACHE_PATH, cacheDir.absolutePath)
            putBoolean(KEY_WORKSPACE_CREATED, workspaceCreated)
            putBoolean(KEY_WORKSPACE_WRITABLE, workspaceWritable)
            putBoolean(KEY_COMMAND_STARTED, command.started)
            putBoolean(KEY_COMMAND_FINISHED, command.finished)
            putString(KEY_COMMAND_STDOUT, command.stdout)
            putString(KEY_COMMAND_STDERR, command.stderr)
            command.exitCode?.let { putInt(KEY_COMMAND_EXIT_CODE, it) }
            putString(KEY_COMMAND_ERROR, command.error)
        }
    }

    private data class ProbeCommand(
        val started: Boolean,
        val finished: Boolean = false,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val error: String? = null,
    )

    companion object {
        const val REQUEST_PROBE = 1
        const val RESPONSE_PROBE = 2
        const val KEY_MAIN_MARKER_PATH = "mainMarkerPath"
        const val KEY_UID = "uid"
        const val KEY_PID = "pid"
        const val KEY_INTERNET_DENIED = "internetDenied"
        const val KEY_MAIN_MARKER_DENIED = "mainMarkerDenied"
        const val KEY_CACHE_PATH = "cachePath"
        const val KEY_WORKSPACE_CREATED = "workspaceCreated"
        const val KEY_WORKSPACE_WRITABLE = "workspaceWritable"
        const val KEY_COMMAND_STARTED = "commandStarted"
        const val KEY_COMMAND_FINISHED = "commandFinished"
        const val KEY_COMMAND_STDOUT = "commandStdout"
        const val KEY_COMMAND_STDERR = "commandStderr"
        const val KEY_COMMAND_EXIT_CODE = "commandExitCode"
        const val KEY_COMMAND_ERROR = "commandError"
        const val KEY_PROBE_ERROR = "probeError"
        private const val COMMAND_TIMEOUT_SECONDS = 5L
        private const val TAG = "IsolatedExecutionProbe"
    }
}
