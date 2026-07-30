package app.momoding.core.runtime.local

import app.momoding.core.accessibility.ScreenCapturePerformer
import app.momoding.core.accessibility.ScreenCaptureResult
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface PhoneLocalScreenCaptureToolHandler {
    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult

    fun handles(toolName: String): Boolean = toolName == PhoneLocalScreenCaptureToolExecutor.TOOL_NAME

    fun stopTask(taskId: String, reason: String) = Unit
}

class PhoneLocalScreenCaptureToolExecutor(
    private val coordinator: ScreenCapturePerformer,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PhoneLocalScreenCaptureToolHandler {
    private val sessions = ConcurrentHashMap<String, CaptureBudget>()

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        require(handles(request.toolName)) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
        val purpose = request.arguments["purpose"]?.jsonPrimitive?.contentOrNull
        require(!purpose.isNullOrBlank() && purpose.length <= 512) {
            "PI_MOBILE_SCREEN_CAPTURE_PURPOSE_INVALID"
        }
        val targetPackage = when (val value = request.arguments["targetPackage"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
            else -> null
        }
        require(
            request.arguments.keys.all { it == "purpose" || it == "targetPackage" } &&
                (targetPackage == null || targetPackage.length <= 255)
        ) { "PI_MOBILE_SCREEN_CAPTURE_ARGUMENTS_INVALID" }
        val now = nowMillis()
        val budget = sessions.computeIfAbsent(taskId) {
            CaptureBudget(startedAtMillis = now)
        }
        var expireSession = false
        var sessionExpired = false
        synchronized(budget) {
            if (budget.expired || now - budget.startedAtMillis >= SESSION_TTL_MILLIS) {
                if (!budget.stopIssued) {
                    budget.stopIssued = true
                    expireSession = true
                }
                budget.expired = true
                sessionExpired = true
            } else if (budget.captureCount >= MAX_CAPTURES_PER_SESSION) {
                return failed(
                    "SCREEN_CAPTURE_LIMIT_REACHED",
                    "This task has reached its 10-screen-capture session limit.",
                )
            } else {
                budget.captureCount += 1
            }
        }
        if (sessionExpired) {
            if (expireSession) coordinator.stop()
            return failed(
                "SCREEN_CAPTURE_SESSION_EXPIRED",
                "This task's 10-minute screen session has expired.",
            )
        }
        return when (val result = coordinator.capture(targetPackage)) {
            is ScreenCaptureResult.Failed -> failed(result.code, result.safeMessage)
            is ScreenCaptureResult.Ready -> {
                val capture = result.capture
                val metadata = buildJsonObject {
                    put("ok", true)
                    put("liveOnly", true)
                    put("source", capture.source.name.lowercase())
                    put("contentSha256", capture.contentSha256)
                    put("width", capture.width)
                    put("height", capture.height)
                    put("mimeType", capture.mimeType)
                    capture.foregroundPackage?.let { put("foregroundPackage", it) }
                }
                PiNativeAndroidToolResult(
                    contentPayload = metadata,
                    details = metadata,
                    content = JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "text")
                                put("text", metadata.toString())
                            },
                            buildJsonObject {
                                put("type", "image")
                                put(
                                    "data",
                                    Base64.getEncoder().encodeToString(capture.bytes),
                                )
                                put("mimeType", capture.mimeType)
                            },
                        ),
                    ),
                )
            }
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        if (reason == ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON) return
        val budget = sessions.remove(taskId) ?: return
        val shouldStop = synchronized(budget) {
            if (budget.stopIssued) {
                false
            } else {
                budget.stopIssued = true
                true
            }
        }
        if (shouldStop) coordinator.stop()
    }

    private fun failed(code: String, safeMessage: String): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("errorCode", code)
            put("errorMessage", safeMessage)
        }
        return PiNativeAndroidToolResult(
            contentPayload = payload,
            details = payload,
            isError = true,
        )
    }

    private data class CaptureBudget(
        val startedAtMillis: Long,
        var captureCount: Int = 0,
        var expired: Boolean = false,
        var stopIssued: Boolean = false,
    )

    companion object {
        const val TOOL_NAME = "device_screen_capture"
        private const val SESSION_TTL_MILLIS = 10 * 60_000L
        private const val MAX_CAPTURES_PER_SESSION = 10
    }
}
