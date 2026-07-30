package app.momoding.core.clipboard

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

interface PhoneLocalClipboardToolHandler {
    fun handles(toolName: String): Boolean
    fun isRead(request: PiNativeToolRequest): Boolean
    fun isMutation(request: PiNativeToolRequest): Boolean
    fun mutationRequestDigest(request: PiNativeToolRequest): String
    suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): ClipboardMutationPreparation
    suspend fun executeRead(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult
    suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: ClipboardMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult
    fun stopTask(taskId: String, reason: String)
}

class PhoneLocalClipboardToolExecutor(
    private val gateway: ClipboardGateway? = null,
    private val foregroundGate: ClipboardForegroundGate = ClipboardForegroundGate { true },
    private val now: () -> Instant = Instant::now,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : PhoneLocalClipboardToolHandler {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun isRead(request: PiNativeToolRequest): Boolean =
        runCatching { ClipboardToolRequestParser.parse(request.arguments) }
            .getOrNull() is ClipboardToolRequest.Get

    override fun isMutation(request: PiNativeToolRequest): Boolean =
        runCatching { ClipboardToolRequestParser.parse(request.arguments) }
            .getOrNull()
            ?.action
            ?.isMutation == true

    override fun mutationRequestDigest(request: PiNativeToolRequest): String =
        sha256(canonicalJson(request.arguments))

    override suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): ClipboardMutationPreparation {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return ClipboardMutationPreparation.Failed(invalidArguments(null, false))
        }
        val parsed = try {
            ClipboardToolRequestParser.parse(request.arguments)
        } catch (_: ClipboardToolArgumentsException) {
            return ClipboardMutationPreparation.Failed(invalidArguments(null, true))
        }
        if (!parsed.action.isMutation) {
            return ClipboardMutationPreparation.Failed(
                invalidArguments(parsed.action.wireValue, false),
            )
        }
        if (gateway == null) {
            return ClipboardMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "PROVIDER_UNAVAILABLE",
                    "Android clipboard is not enabled in this build.",
                    false,
                ),
            )
        }
        val text = (parsed as? ClipboardToolRequest.Set)?.text
        val sensitive = text?.let { ClipboardSensitiveClassifier.classify(it) != null } ?: false
        val requestDigest = sha256(canonicalJson(request.arguments))
        val action = parsed.action
        val planDigest = sha256(
            listOf(taskId, request.toolCallId, action.wireValue, requestDigest)
                .joinToString("\u001f"),
        )
        return ClipboardMutationPreparation.Ready(
            ClipboardMutationPlan(
                taskId = taskId,
                piToolCallId = request.toolCallId,
                action = action,
                text = text,
                sensitive = sensitive,
                requestDigest = requestDigest,
                planDigest = planDigest,
                summary = when (action) {
                    ClipboardToolAction.SET -> "Copy text to the Android clipboard?"
                    ClipboardToolAction.CLEAR -> "Clear the Android clipboard?"
                    ClipboardToolAction.GET -> error("Read crossed the mutation boundary")
                },
                details = when (action) {
                    ClipboardToolAction.SET ->
                        "Writes ${text?.length ?: 0} characters and verifies the current clipboard without storing the text in approval history."
                    ClipboardToolAction.CLEAR ->
                        "Clears the current clipboard and verifies it is empty."
                    ClipboardToolAction.GET -> error("Read crossed the mutation boundary")
                },
            ),
        )
    }

    override suspend fun executeRead(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return invalidArguments(null, false)
        }
        val parsed = try {
            ClipboardToolRequestParser.parse(request.arguments)
        } catch (_: ClipboardToolArgumentsException) {
            return invalidArguments(null, true)
        }
        if (parsed !is ClipboardToolRequest.Get) {
            return invalidArguments(parsed.action.wireValue, false)
        }
        val localGateway = gateway ?: return failed(
            parsed.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android clipboard is not enabled in this build.",
            false,
        )
        if (!foregroundGate.isForeground()) return appNotForeground(parsed.action.wireValue)
        val job = currentCoroutineContext()[Job] ?: return unavailable(parsed.action.wireValue)
        if (activeJobs.putIfAbsent(taskId, job) != null) {
            return failed(
                parsed.action.wireValue,
                "CLIPBOARD_REQUEST_IN_PROGRESS",
                "Another clipboard request is already running for this task.",
                true,
            )
        }
        return try {
            val snapshot = withTimeout(timeoutMillis) { localGateway.read() }
            if (!foregroundGate.isForeground()) return appNotForeground(parsed.action.wireValue)
            when (snapshot) {
                ClipboardSnapshot.Empty -> failed(
                    parsed.action.wireValue,
                    "CLIPBOARD_EMPTY",
                    "The Android clipboard does not contain text.",
                    true,
                )
                ClipboardSnapshot.Unsupported -> failed(
                    parsed.action.wireValue,
                    "CLIPBOARD_FORMAT_UNSUPPORTED",
                    "The current clipboard item is not plain text.",
                    false,
                )
                is ClipboardSnapshot.Text -> readText(parsed, snapshot)
            }
        } catch (_: TimeoutCancellationException) {
            failed(
                parsed.action.wireValue,
                "DEVICE_TOOL_TIMEOUT",
                "Android clipboard access timed out.",
                true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            appNotForeground(parsed.action.wireValue)
        } catch (_: Exception) {
            unavailable(parsed.action.wireValue)
        } finally {
            activeJobs.remove(taskId, job)
        }
    }

    override suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: ClipboardMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult {
        if (
            taskId != plan.taskId ||
            request.toolCallId != plan.piToolCallId ||
            mutationRequestDigest(request) != plan.requestDigest
        ) {
            return failed(
                plan.action.wireValue,
                "CONFLICT",
                "The clipboard change no longer matches its approved plan.",
                false,
            )
        }
        val localGateway = gateway ?: return unavailable(plan.action.wireValue)
        if (!foregroundGate.isForeground()) return appNotForeground(plan.action.wireValue)
        val job = currentCoroutineContext()[Job] ?: return unavailable(plan.action.wireValue)
        if (activeJobs.putIfAbsent(taskId, job) != null) {
            return failed(
                plan.action.wireValue,
                "CLIPBOARD_REQUEST_IN_PROGRESS",
                "Another clipboard request is already running for this task.",
                true,
            )
        }
        currentCoroutineContext().ensureActive()
        var providerCallIssued = false
        return try {
            withContext(NonCancellable) {
                onProviderDispatch()
                providerCallIssued = true
            }
            if (!foregroundGate.isForeground()) return outcomeUnknown(plan)
            withTimeout(timeoutMillis) {
                when (plan.action) {
                    ClipboardToolAction.SET -> {
                        val text = requireNotNull(plan.text)
                        localGateway.setText(text, plan.sensitive)
                        when (val observed = localGateway.read()) {
                            is ClipboardSnapshot.Text -> if (
                                observed.value == text &&
                                (!plan.sensitive || observed.sourceMarkedSensitive)
                            ) {
                                mutationSucceeded(
                                    plan,
                                    state = "text",
                                    characterCount = text.length,
                                )
                            } else {
                                verificationFailed(plan)
                            }
                            else -> verificationFailed(plan)
                        }
                    }
                    ClipboardToolAction.CLEAR -> {
                        localGateway.clear()
                        if (localGateway.read() == ClipboardSnapshot.Empty) {
                            mutationSucceeded(plan, state = "empty", characterCount = 0)
                        } else {
                            verificationFailed(plan)
                        }
                    }
                    ClipboardToolAction.GET ->
                        error("Read crossed the mutation execution boundary")
                }
            }
        } catch (cancelled: CancellationException) {
            if (providerCallIssued) outcomeUnknown(plan) else throw cancelled
        } catch (_: Exception) {
            if (providerCallIssued) outcomeUnknown(plan) else unavailable(plan.action.wireValue)
        } finally {
            activeJobs.remove(taskId, job)
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        activeJobs[taskId]?.cancel(CancellationException(reason))
    }

    private fun readText(
        request: ClipboardToolRequest.Get,
        snapshot: ClipboardSnapshot.Text,
    ): PiNativeAndroidToolResult {
        if (snapshot.value.toByteArray(Charsets.UTF_8).size > MAX_READ_UTF8_BYTES) {
            return failed(
                request.action.wireValue,
                "CLIPBOARD_CONTENT_TOO_LARGE",
                "Clipboard text exceeds the bounded Tool result size.",
                false,
            )
        }
        if (
            ClipboardSensitiveClassifier.classify(
                snapshot.value,
                snapshot.sourceMarkedSensitive,
            ) != null
        ) {
            return failed(
                request.action.wireValue,
                "CLIPBOARD_CONTENT_RESTRICTED",
                "The clipboard appears to contain sensitive content and was not shared.",
                false,
            )
        }
        val observedAt = observedAt()
        val payload = buildJsonObject {
            put("ok", true)
            put("action", request.action.wireValue)
            put(
                "data",
                buildJsonObject {
                    put("state", "text")
                    put("text", snapshot.value)
                    put("characterCount", snapshot.value.length)
                },
            )
            put(
                "verification",
                buildJsonObject {
                    put("status", "observed")
                    put("observedAt", observedAt)
                },
            )
        }
        return PiNativeAndroidToolResult(
            contentPayload = payload,
            details = buildJsonObject {
                put("liveOnly", true)
                put("dataClass", "clipboard")
                put("contentSha256", sha256(payload.toString()))
            },
        )
    }

    private fun mutationSucceeded(
        plan: ClipboardMutationPlan,
        state: String,
        characterCount: Int,
    ) = PiNativeAndroidToolResult(
        buildJsonObject {
            put("ok", true)
            put("action", plan.action.wireValue)
            put(
                "data",
                buildJsonObject {
                    put("state", state)
                    put("characterCount", characterCount)
                    put("sensitive", plan.sensitive)
                },
            )
            put(
                "verification",
                buildJsonObject {
                    put("status", "verified")
                    put("observedAt", observedAt())
                    put("planDigest", plan.planDigest)
                },
            )
        },
    )

    private fun verificationFailed(plan: ClipboardMutationPlan) = failed(
        plan.action.wireValue,
        "VERIFICATION_FAILED",
        "Android could not verify the requested clipboard change.",
        false,
    )

    private fun outcomeUnknown(plan: ClipboardMutationPlan) = failed(
        plan.action.wireValue,
        "OUTCOME_UNKNOWN",
        "Android started the clipboard change but its final state could not be verified.",
        false,
    )

    private fun invalidArguments(action: String?, retryable: Boolean) = failed(
        action,
        "INVALID_ARGUMENTS",
        "Clipboard arguments are invalid.",
        retryable,
    )

    private fun appNotForeground(action: String) = failed(
        action,
        "APP_NOT_FOREGROUND",
        "Open Momoding before using the Android clipboard.",
        true,
    )

    private fun unavailable(action: String) = failed(
        action,
        "PROVIDER_UNAVAILABLE",
        "Android clipboard is temporarily unavailable.",
        true,
    )

    private fun failed(
        action: String?,
        code: String,
        message: String,
        retryable: Boolean,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", false)
            if (action == null) put("action", JsonNull) else put("action", action)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("retryable", retryable)
                },
            )
        },
        isError = true,
    )

    private fun observedAt(): String =
        now().truncatedTo(ChronoUnit.MILLIS).toString()

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (key, item) -> "${JsonPrimitive(key)}:${canonicalJson(item)}" }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") {
            canonicalJson(it)
        }
        else -> value.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val TOOL_NAME = "device_clipboard"
        private const val REQUEST_TIMEOUT_MILLIS = 5_000L
        private const val MAX_READ_UTF8_BYTES = 8 * 1_024

        fun create(context: Context): PhoneLocalClipboardToolExecutor {
            val application = context.applicationContext
            val activityManager = application.getSystemService(ActivityManager::class.java)
            return PhoneLocalClipboardToolExecutor(
                gateway = AndroidClipboardGateway(
                    application.getSystemService(ClipboardManager::class.java),
                ),
                foregroundGate = ClipboardForegroundGate {
                    activityManager.runningAppProcesses
                        ?.firstOrNull { it.pid == android.os.Process.myPid() }
                        ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                },
            )
        }
    }
}
