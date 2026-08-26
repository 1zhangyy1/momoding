package app.momoding.core.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class TaskFailureKind {
    PROVIDER_AUTH,
    PROVIDER_CREDITS,
    PROVIDER_POLICY,
    PROVIDER_MODEL,
    PROVIDER_RATE_LIMIT,
    PROVIDER_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_OTHER,
    RUNTIME,
    INTERRUPTED,
    UNKNOWN,
}

enum class TaskFailureRecovery {
    FIX_PROVIDER,
    RETRY,
    OPEN_TASK,
}

data class TaskFailure(
    val kind: TaskFailureKind,
    val message: String,
    val recovery: TaskFailureRecovery,
) {
    val homeDetail: String
        get() = when (kind) {
            TaskFailureKind.PROVIDER_AUTH -> "OpenRouter API key needs attention"
            TaskFailureKind.PROVIDER_CREDITS -> "OpenRouter credits need attention"
            TaskFailureKind.PROVIDER_POLICY -> "OpenRouter request was not permitted"
            TaskFailureKind.PROVIDER_MODEL -> "Selected model needs attention"
            TaskFailureKind.PROVIDER_RATE_LIMIT -> "Rate limited — try again shortly"
            TaskFailureKind.PROVIDER_TIMEOUT -> "Provider timed out — retry"
            TaskFailureKind.PROVIDER_UNAVAILABLE -> "Provider unavailable — retry"
            TaskFailureKind.PROVIDER_OTHER -> "Provider request failed — open for details"
            TaskFailureKind.RUNTIME -> "Task stopped unexpectedly — open for details"
            TaskFailureKind.INTERRUPTED -> "Run interrupted — open to recover"
            TaskFailureKind.UNKNOWN -> "Task failed — open for details"
        }
}

internal fun taskFailureForRunState(
    runState: String?,
    messages: List<JsonElement> = emptyList(),
    previous: TaskFailure? = null,
): TaskFailure? = when (runState?.uppercase()) {
    "FAILED" -> taskFailureFromPiMessages(messages)
        ?: previous?.takeUnless { it.kind == TaskFailureKind.INTERRUPTED }
        ?: unknownTaskFailure()
    "INTERRUPTED" -> TaskFailure(
        kind = TaskFailureKind.INTERRUPTED,
        message = "The previous run was interrupted. Open the task to continue safely.",
        recovery = TaskFailureRecovery.OPEN_TASK,
    )
    else -> null
}

internal fun taskFailureFromPiMessages(messages: List<JsonElement>): TaskFailure? =
    messages.asReversed().firstNotNullOfOrNull { element ->
        val message = element as? JsonObject ?: return@firstNotNullOfOrNull null
        if (message.string("role") != "assistant" || message.string("stopReason") != "error") {
            return@firstNotNullOfOrNull null
        }
        classifyTaskFailure(message.string("errorMessage"))
    }

internal fun classifyTaskFailure(errorMessage: String?): TaskFailure = when (errorMessage) {
    "OpenRouter API key is invalid" -> TaskFailure(
        TaskFailureKind.PROVIDER_AUTH,
        "OpenRouter API key is invalid. Update it in Settings.",
        TaskFailureRecovery.FIX_PROVIDER,
    )
    "OpenRouter account has insufficient credits" -> TaskFailure(
        TaskFailureKind.PROVIDER_CREDITS,
        "OpenRouter account has insufficient credits.",
        TaskFailureRecovery.FIX_PROVIDER,
    )
    "OpenRouter request is not permitted" -> TaskFailure(
        TaskFailureKind.PROVIDER_POLICY,
        "OpenRouter did not permit this request.",
        TaskFailureRecovery.FIX_PROVIDER,
    )
    "OpenRouter model was not found" -> TaskFailure(
        TaskFailureKind.PROVIDER_MODEL,
        "OpenRouter could not find this model. Update it in Settings.",
        TaskFailureRecovery.FIX_PROVIDER,
    )
    "OpenRouter rate limit reached" -> TaskFailure(
        TaskFailureKind.PROVIDER_RATE_LIMIT,
        "OpenRouter rate limit reached. Try again shortly.",
        TaskFailureRecovery.RETRY,
    )
    "OpenRouter request timed out" -> TaskFailure(
        TaskFailureKind.PROVIDER_TIMEOUT,
        "OpenRouter request timed out. Try again.",
        TaskFailureRecovery.RETRY,
    )
    "No internet connection" -> TaskFailure(
        TaskFailureKind.PROVIDER_UNAVAILABLE,
        "No internet connection. Check your connection and try again.",
        TaskFailureRecovery.RETRY,
    )
    "OpenRouter provider is unavailable",
    "OpenRouter network request failed",
    -> TaskFailure(
        TaskFailureKind.PROVIDER_UNAVAILABLE,
        "OpenRouter is unavailable. Try again shortly.",
        TaskFailureRecovery.RETRY,
    )
    "OpenRouter request failed",
    "OpenRouter stream failed",
    "OpenRouter rejected the request",
    "OpenRouter returned an invalid stream",
    -> TaskFailure(
        TaskFailureKind.PROVIDER_OTHER,
        "The Provider could not complete this response.",
        TaskFailureRecovery.RETRY,
    )
    "The model returned no response. Try again." -> TaskFailure(
        TaskFailureKind.PROVIDER_OTHER,
        "Momoding returned no response. Try again.",
        TaskFailureRecovery.RETRY,
    )
    null, "" -> unknownTaskFailure()
    else -> TaskFailure(
        TaskFailureKind.RUNTIME,
        "The task could not complete this response.",
        TaskFailureRecovery.OPEN_TASK,
    )
}

internal fun taskFailureFromStored(
    kind: String?,
    message: String?,
    recovery: String?,
): TaskFailure? {
    val parsedKind = kind?.let { runCatching { TaskFailureKind.valueOf(it) }.getOrNull() }
        ?: return null
    val parsedRecovery = recovery?.let {
        runCatching { TaskFailureRecovery.valueOf(it) }.getOrNull()
    } ?: TaskFailureRecovery.OPEN_TASK
    return TaskFailure(
        kind = parsedKind,
        message = message?.takeIf(String::isNotBlank) ?: defaultMessage(parsedKind),
        recovery = parsedRecovery,
    )
}

internal fun TaskEntity.storedTaskFailure(): TaskFailure? = taskFailureFromStored(
    failureKind,
    failureMessage,
    failureRecovery,
)

private fun unknownTaskFailure() = TaskFailure(
    TaskFailureKind.UNKNOWN,
    "The task failed. Open it to review the latest activity.",
    TaskFailureRecovery.OPEN_TASK,
)

private fun defaultMessage(kind: TaskFailureKind): String = when (kind) {
    TaskFailureKind.INTERRUPTED -> "The previous run was interrupted."
    TaskFailureKind.RUNTIME -> "The task stopped unexpectedly."
    TaskFailureKind.UNKNOWN -> "The task failed."
    else -> "The Provider could not complete this response."
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
