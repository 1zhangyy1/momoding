package app.momoding.core.runtime.local

import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.files.AuthorizedFileMutation
import app.momoding.core.files.DeviceFileChangeExecutor
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface PhoneLocalProjectToolHandler {
    fun handles(toolName: String): Boolean
    suspend fun execute(taskId: String, request: PiNativeToolRequest): JsonObject
    suspend fun stopTask(taskId: String): Boolean
}

/** Executes the two bounded Pi project tools against the App-private task workspace. */
class PhoneLocalProjectToolExecutor internal constructor(
    private val executeCommand: suspend (PhoneLocalCommandRequest) -> PhoneLocalCommandResult,
    private val stopCommand: (String) -> Boolean,
    private val projects: PhoneLocalProjectWorkspace,
    private val fileChanges: DeviceFileChangeExecutor,
    private val nowMillis: () -> Long,
    private val newPreparedId: () -> String,
    private val afterRunRegistered: suspend (String) -> Unit = {},
) : PhoneLocalProjectToolHandler {
    constructor(
        linux: PhoneLocalLinuxRuntime,
        projects: PhoneLocalProjectWorkspace,
        fileChanges: DeviceFileChangeExecutor,
        nowMillis: () -> Long = System::currentTimeMillis,
        newPreparedId: () -> String = { UUID.randomUUID().toString() },
    ) : this(
        executeCommand = linux::execute,
        stopCommand = linux::stop,
        projects = projects,
        fileChanges = fileChanges,
        nowMillis = nowMillis,
        newPreparedId = newPreparedId,
    )

    private val activeRuns = ConcurrentHashMap<String, ActiveProjectRun>()
    private val runGate = Any()
    private val stoppingTasks = mutableMapOf<String, Int>()

    override fun handles(toolName: String): Boolean = toolName in SUPPORTED_TOOLS

    override suspend fun execute(taskId: String, request: PiNativeToolRequest): JsonObject {
        if (!handles(request.toolName)) return failure("PROJECT_TOOL_UNSUPPORTED", "Project tool is unavailable")
        return try {
            val arguments = parseArguments(request.toolName, request.arguments)
            val runId = "project-${sha256("$taskId\u0000${request.id}").take(32)}"
            val activeRun = ActiveProjectRun(runId)
            when (registerRun(taskId, activeRun)) {
                RunRegistration.BUSY -> return failure(
                    "PROJECT_COMMAND_ALREADY_RUNNING",
                    "Another project command is still running",
                )
                RunRegistration.STOPPING -> return stoppedBeforeCommand(request.toolName, runId)
                RunRegistration.REGISTERED -> Unit
            }
            try {
                afterRunRegistered(runId)
                if (activeRun.stopRequested.get()) {
                    return stoppedBeforeCommand(request.toolName, runId)
                }
                if (!projects.hasImportedTask(taskId)) projects.importApprovedTask(taskId)
                if (activeRun.stopRequested.get()) {
                    return discardInterruptedResult(
                        taskId = taskId,
                        toolName = request.toolName,
                        command = stoppedCommandResult(runId),
                        stopRequested = true,
                    )
                }
                val commandResult = executeCommand(
                    PhoneLocalCommandRequest(
                        runId = runId,
                        workspaceId = taskId,
                        command = arguments.command,
                        timeoutMillis = arguments.timeoutMillis,
                        outputLimitBytes = arguments.outputLimitBytes,
                    ),
                )
                if (
                    commandResult.stopped ||
                    commandResult.timedOut ||
                    activeRun.stopRequested.get()
                ) {
                    return discardInterruptedResult(
                        taskId = taskId,
                        toolName = request.toolName,
                        command = commandResult,
                        stopRequested = activeRun.stopRequested.get(),
                    )
                }
                val detected = projects.detectChanges(taskId)
                if (activeRun.stopRequested.get()) {
                    return discardInterruptedResult(
                        taskId = taskId,
                        toolName = request.toolName,
                        command = commandResult,
                        stopRequested = true,
                    )
                }
                if (detected.unsupported.isNotEmpty()) {
                    return buildResult(
                        toolName = request.toolName,
                        command = commandResult,
                        ok = false,
                        errorCode = "PROJECT_CHANGES_UNSUPPORTED",
                        errorMessage = "The command produced changes Android cannot safely review",
                        changes = buildJsonObject {
                            put("state", "blocked")
                            put("snapshotSha256", detected.snapshotSha256)
                            put("unsupported", buildJsonArray {
                                detected.unsupported.forEach { change ->
                                    add(
                                        buildJsonObject {
                                            put("relativePath", change.relativePath)
                                            put("reason", change.reason)
                                        },
                                    )
                                }
                            })
                        },
                    )
                }
                val preparedChanges = if (detected.operations.isEmpty()) {
                    buildJsonObject {
                        put("state", "none")
                        put("snapshotSha256", detected.snapshotSha256)
                    }
                } else {
                    prepareChanges(taskId, request, detected)
                }
                if (activeRun.stopRequested.get()) {
                    return discardInterruptedResult(
                        taskId = taskId,
                        toolName = request.toolName,
                        command = commandResult,
                        stopRequested = true,
                    )
                }
                val preparationFailed = preparedChanges["state"]?.jsonPrimitive?.content == "failed"
                val commandOk = commandResult.exitCode == 0 &&
                    !commandResult.timedOut &&
                    !commandResult.stopped
                buildResult(
                    toolName = request.toolName,
                    command = commandResult,
                    ok = commandOk && !preparationFailed,
                    errorCode = when {
                        commandResult.stopped -> "PROJECT_COMMAND_STOPPED"
                        commandResult.timedOut -> "PROJECT_COMMAND_TIMED_OUT"
                        commandResult.exitCode != 0 -> "PROJECT_COMMAND_FAILED"
                        preparationFailed -> "PROJECT_CHANGE_PREPARE_FAILED"
                        else -> null
                    },
                    errorMessage = when {
                        commandResult.stopped -> "Project command was stopped"
                        commandResult.timedOut -> "Project command timed out"
                        commandResult.exitCode != 0 -> "Project command exited with a failure"
                        preparationFailed -> "Project changes could not be prepared for review"
                        else -> null
                    },
                    changes = preparedChanges,
                )
            } finally {
                synchronized(runGate) {
                    activeRuns.remove(taskId, activeRun)
                }
                activeRun.settled.complete(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            failure("PROJECT_FOLDER_REQUIRED", "Choose and authorize a project folder for this task")
        } catch (_: IllegalArgumentException) {
            failure("PROJECT_TOOL_ARGUMENTS_INVALID", "Project command arguments are invalid")
        } catch (_: IllegalStateException) {
            failure("PROJECT_WORKSPACE_UNAVAILABLE", "The private project workspace is unavailable")
        }
    }

    override suspend fun stopTask(taskId: String): Boolean {
        val activeRun = synchronized(runGate) {
            stoppingTasks[taskId] = stoppingTasks.getOrDefault(taskId, 0) + 1
            activeRuns[taskId]?.also { it.stopRequested.set(true) }
        }
        return try {
            val processStopRequested = activeRun?.let { stopCommand(it.runId) } == true
            if (activeRun != null) {
                val settled = withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MILLIS) {
                    activeRun.settled.await()
                    true
                } == true
                if (!settled) return processStopRequested
            }
            val cancelled = fileChanges.cancelUnboundPreparedForTask(
                taskId,
                "PROJECT_COMMAND_STOPPED",
            )
            projects.discardImportedTask(taskId)
            processStopRequested || activeRun != null || cancelled > 0
        } finally {
            synchronized(runGate) {
                val remaining = stoppingTasks.getValue(taskId) - 1
                if (remaining == 0) stoppingTasks.remove(taskId) else stoppingTasks[taskId] = remaining
            }
        }
    }

    private fun registerRun(taskId: String, activeRun: ActiveProjectRun): RunRegistration =
        synchronized(runGate) {
            when {
                stoppingTasks.getOrDefault(taskId, 0) > 0 -> RunRegistration.STOPPING
                activeRuns.putIfAbsent(taskId, activeRun) != null -> RunRegistration.BUSY
                else -> RunRegistration.REGISTERED
            }
        }

    private fun stoppedBeforeCommand(toolName: String, runId: String): JsonObject = buildResult(
        toolName = toolName,
        command = stoppedCommandResult(runId),
        ok = false,
        errorCode = "PROJECT_COMMAND_STOPPED",
        errorMessage = "Project command was stopped",
        changes = buildJsonObject {
            put("state", "discarded")
            put("reason", "stopped")
        },
    )

    private fun stoppedCommandResult(runId: String) = PhoneLocalCommandResult(
        runId = runId,
        stdout = "",
        stderr = "",
        exitCode = null,
        timedOut = false,
        stopped = true,
        outputTruncated = false,
        durationMillis = 0,
    )

    private suspend fun discardInterruptedResult(
        taskId: String,
        toolName: String,
        command: PhoneLocalCommandResult,
        stopRequested: Boolean,
    ): JsonObject {
        fileChanges.cancelUnboundPreparedForTask(taskId, "PROJECT_COMMAND_STOPPED")
        projects.discardImportedTask(taskId)
        val effectiveCommand = if (stopRequested && !command.stopped) {
            command.copy(stopped = true)
        } else {
            command
        }
        return buildResult(
            toolName = toolName,
            command = effectiveCommand,
            ok = false,
            errorCode = if (effectiveCommand.stopped) {
                "PROJECT_COMMAND_STOPPED"
            } else {
                "PROJECT_COMMAND_TIMED_OUT"
            },
            errorMessage = if (effectiveCommand.stopped) {
                "Project command was stopped"
            } else {
                "Project command timed out"
            },
            changes = buildJsonObject {
                put("state", "discarded")
                put("reason", if (effectiveCommand.stopped) "stopped" else "timed_out")
            },
        )
    }

    private suspend fun prepareChanges(
        taskId: String,
        request: PiNativeToolRequest,
        detected: PhoneLocalProjectChangeSet,
    ): JsonObject {
        val preparedId = newPreparedId()
        val frame = DeviceToolRequestFrame(
            protocolVersion = 1,
            kind = "device.tool.request",
            callId = preparedId,
            taskId = taskId,
            piToolCallId = request.toolCallId,
            deviceId = "phone-local-android",
            toolName = "device_files_prepare_changes",
            arguments = buildJsonObject {
                put("grantId", detected.grantId)
                put(
                    "purpose",
                    if (request.toolName == RUN_TESTS_TOOL) {
                        "Review file changes produced while running project tests"
                    } else {
                        "Review file changes produced by a project command"
                    },
                )
                put("operations", buildJsonArray {
                    detected.operations.forEach { add(it.toJson()) }
                })
            },
            sideEffect = false,
            operationId = null,
            expiresAt = Instant.ofEpochMilli(nowMillis() + PREPARE_TTL_MILLIS).toString(),
            capabilityVersion = 1,
        )
        val prepared = fileChanges.prepare(frame)
        if (prepared.terminal != DeviceToolTerminalKind.SUCCEEDED) {
            return buildJsonObject {
                put("state", "failed")
                put("snapshotSha256", detected.snapshotSha256)
                put("code", prepared.error?.code ?: "PROJECT_CHANGE_PREPARE_FAILED")
            }
        }
        val result = requireNotNull(prepared.result).jsonObject
        return buildJsonObject {
            put("state", "prepared")
            put("snapshotSha256", detected.snapshotSha256)
            put("preparedId", result.getValue("preparedId"))
            put("planDigest", result.getValue("planDigest"))
            put("operationCount", result.getValue("operationCount"))
            put("createdPaths", buildJsonArray {
                detected.createdPaths.forEach { add(JsonPrimitive(it)) }
            })
            put("modifiedPaths", buildJsonArray {
                detected.modifiedPaths.forEach { add(JsonPrimitive(it)) }
            })
            put("deletedPaths", buildJsonArray {
                detected.deletedPaths.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun buildResult(
        toolName: String,
        command: PhoneLocalCommandResult,
        ok: Boolean,
        errorCode: String?,
        errorMessage: String?,
        changes: JsonObject,
    ): JsonObject = buildJsonObject {
        put("ok", ok)
        put("kind", if (toolName == RUN_TESTS_TOOL) "test" else "terminal")
        put("stdout", command.stdout)
        put("stderr", command.stderr)
        if (command.exitCode == null) {
            put("exitCode", JsonNull)
        } else {
            put("exitCode", command.exitCode)
        }
        put("timedOut", command.timedOut)
        put("stopped", command.stopped)
        put("outputTruncated", command.outputTruncated)
        put("durationMillis", command.durationMillis)
        put("fileChanges", changes)
        errorCode?.let { put("errorCode", it) }
        errorMessage?.let { put("errorMessage", it) }
    }

    private fun failure(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("errorCode", code)
        put("errorMessage", message)
    }

    private fun parseArguments(toolName: String, value: JsonObject): CommandArguments {
        require(value.keys.all { it in setOf("command", "timeoutMillis", "outputLimitBytes") })
        require("command" in value)
        val command = value.getValue("command").jsonPrimitive.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= MAX_COMMAND_CHARS && '\u0000' !in it }
            ?: throw IllegalArgumentException("command is invalid")
        val timeout = value["timeoutMillis"]?.jsonPrimitive?.longOrNull
            ?: if (toolName == RUN_TESTS_TOOL) DEFAULT_TEST_TIMEOUT_MILLIS else DEFAULT_TIMEOUT_MILLIS
        val outputLimit = value["outputLimitBytes"]?.jsonPrimitive?.longOrNull
            ?: DEFAULT_OUTPUT_LIMIT_BYTES.toLong()
        require(timeout in 1..MAX_TIMEOUT_MILLIS)
        require(outputLimit in 1..MAX_OUTPUT_LIMIT_BYTES.toLong())
        return CommandArguments(command, timeout, outputLimit.toInt())
    }

    private data class CommandArguments(
        val command: String,
        val timeoutMillis: Long,
        val outputLimitBytes: Int,
    )

    private data class ActiveProjectRun(
        val runId: String,
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
        val settled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private enum class RunRegistration {
        REGISTERED,
        BUSY,
        STOPPING,
    }

    private companion object {
        const val RUN_COMMAND_TOOL = "run_command"
        const val RUN_TESTS_TOOL = "run_tests"
        val SUPPORTED_TOOLS = setOf(RUN_COMMAND_TOOL, RUN_TESTS_TOOL)
        const val MAX_COMMAND_CHARS = 8_192
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val DEFAULT_TEST_TIMEOUT_MILLIS = 300_000L
        const val MAX_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        const val DEFAULT_OUTPUT_LIMIT_BYTES = 32_768
        const val MAX_OUTPUT_LIMIT_BYTES = 1_048_576
        const val PREPARE_TTL_MILLIS = 30 * 60_000L
        const val STOP_SETTLE_TIMEOUT_MILLIS = 5_000L

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

        fun AuthorizedFileMutation.toJson(): JsonObject = when (this) {
            is AuthorizedFileMutation.CreateFile -> buildJsonObject {
                put("operationId", operationId)
                put("kind", "create_file")
                put("parentAlias", parentAlias)
                put("displayName", displayName)
                put("mimeType", mimeType)
                put("content", content)
            }
            is AuthorizedFileMutation.WriteFile -> buildJsonObject {
                put("operationId", operationId)
                put("kind", "write_file")
                put("sourceAlias", sourceAlias)
                put("mimeType", mimeType)
                put("content", content)
                put("expected", expected.toJson())
            }
            is AuthorizedFileMutation.DeleteFile -> buildJsonObject {
                put("operationId", operationId)
                put("kind", "delete_file")
                put("sourceAlias", sourceAlias)
                put("expected", expected.toJson())
            }
            else -> error("Project workspace generated an unsupported mutation")
        }

        fun app.momoding.core.files.AuthorizedFilePrecondition.toJson(): JsonObject =
            buildJsonObject {
                put("displayName", displayName)
                put("mimeType", mimeType)
                put("byteCount", byteCount?.let(::JsonPrimitive) ?: JsonNull)
                put("lastModifiedMillis", lastModifiedMillis?.let(::JsonPrimitive) ?: JsonNull)
            }
    }
}
