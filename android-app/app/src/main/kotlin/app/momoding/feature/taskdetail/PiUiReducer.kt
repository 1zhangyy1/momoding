package app.momoding.feature.taskdetail

import app.momoding.core.data.TaskDetailEventRecord
import app.momoding.core.data.TaskDetailSnapshot
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.TaskAttentionKind
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PiUiProjection(
    val runState: TaskDetailRunState,
    val timeline: TimelineWindow,
    val queue: List<QueueItemUiModel>,
    val attention: TaskAttentionUiModel?,
    val recovery: TaskRecoveryUiModel?,
)

/** Stateful presentation reducer over durable native Pi data. It never emits another runtime protocol. */
class PiUiReducer {
    private var snapshotIdentity: String? = null
    private var settledItems: List<TimelineItem> = emptyList()
    private var activeItem: TimelineItem? = null
    private val appliedEvents = linkedMapOf<Long, String>()
    private var transientRunState: TaskDetailRunState? = null
    private var eventQueue: List<QueueItemUiModel>? = null
    private var liveRegionStartIndex: Int? = null

    fun reduce(snapshot: TaskDetailSnapshot): PiUiProjection {
        val nextSnapshotIdentity = buildString {
            append(snapshot.snapshotVersion).append(':')
            append(snapshot.windowStart).append(':').append(snapshot.windowEndExclusive).append(':')
            snapshot.messages.forEach { append(it.stableItemId).append(';') }
        }
        if (snapshotIdentity != nextSnapshotIdentity) {
            settledItems = projectSnapshotMessages(snapshot)
            liveRegionStartIndex = settledItems.size
            activeItem = null
            appliedEvents.clear()
            transientRunState = null
            eventQueue = null
            snapshotIdentity = nextSnapshotIdentity
        }

        val appliedCount = appliedEvents.size
        if (appliedCount > 0) {
            val durableTail = snapshot.rawEvents.getOrNull(appliedCount - 1)
            val appliedTail = appliedEvents.entries.last()
            require(
                durableTail?.sequence == appliedTail.key &&
                    durableTail.digest == appliedTail.value,
            ) { "Native Pi event history changed behind the UI projection" }
        }
        snapshot.rawEvents.drop(appliedCount).forEach(::applyEvent)
        val run = when {
            snapshot.activeStopFence -> TaskDetailRunState.STOPPING
            transientRunState != null -> requireNotNull(transientRunState)
            snapshot.recoveryState == "RECONCILING_DEVICE_CALLS" -> TaskDetailRunState.RECOVERING
            else -> mapRunState(snapshot.runState, snapshot.isStreaming)
        }
        return PiUiProjection(
            runState = run,
            timeline = TimelineWindow(settledItems, activeItem, liveRegionStartIndex),
            queue = eventQueue ?: parseQueue(snapshot.queueJson),
            attention = snapshot.pendingAttention.firstOrNull()?.let { attention ->
                TaskAttentionUiModel(
                    callId = attention.callId,
                    fileChanges = attention.toolName == "device_files_commit_changes",
                    kind = when (attention.toolName) {
                        "request_user_question" -> TaskAttentionKind.QUESTION
                        "request_user_confirmation" -> TaskAttentionKind.CONFIRMATION
                        "device_media_list" -> TaskAttentionKind.CONFIRMATION
                        "device_files_read" -> TaskAttentionKind.FILE_CONTENT
                        else -> TaskAttentionKind.UNSUPPORTED
                    },
                    label = if (attention.responseState == AttentionResponseState.RESPONDING) {
                        "Response saved; waiting for Momoding"
                    } else {
                        when (attention.toolName) {
                            "request_user_question" -> "Momoding asked a question"
                            "request_user_confirmation" -> "An action needs confirmation"
                            "device_media_list" -> "Photo metadata access needs approval"
                            "device_files_read" -> "File content access needs approval"
                            "device_files_commit_changes" -> "Review proposed file changes"
                            else -> "Momoding is waiting for you"
                        }
                    },
                )
            },
            recovery = if (snapshot.recoveryState == "RECONCILING_DEVICE_CALLS") {
                TaskRecoveryUiModel(TaskRecoveryKind.DEVICE_RECONCILIATION)
            } else {
                null
            },
        )
    }

    fun toggleTool(stableKey: String): TimelineWindow {
        if (activeItem is TimelineItem.ToolActivity && activeItem?.stableKey == stableKey) {
            activeItem = (activeItem as TimelineItem.ToolActivity).let { it.copy(expanded = !it.expanded) }
        } else {
            val index = settledItems.indexOfFirst { it.stableKey == stableKey && it is TimelineItem.ToolActivity }
            if (index >= 0) {
                val mutable = settledItems.toMutableList()
                val tool = mutable[index] as TimelineItem.ToolActivity
                mutable[index] = tool.copy(expanded = !tool.expanded)
                settledItems = mutable
            }
        }
        return TimelineWindow(settledItems, activeItem, liveRegionStartIndex)
    }

    private fun applyEvent(record: TaskDetailEventRecord) {
        appliedEvents[record.sequence]?.let { prior ->
            require(prior == record.digest) { "Native Pi event sequence has conflicting bytes" }
            return
        }
        val event = parseObject(record.eventJson) ?: run {
            appliedEvents[record.sequence] = record.digest
            return
        }
        when (event.string("type")) {
            "agent_start" -> transientRunState = TaskDetailRunState.RUNNING
            "agent_settled", "settled" -> {
                settleActive()
                transientRunState = TaskDetailRunState.SETTLED
            }
            "message_update" -> applyMessageUpdate(record, event)
            "tool_execution_start" -> applyToolStart(record, event)
            "tool_execution_update" -> applyToolUpdate(record, event)
            "tool_execution_end" -> applyToolEnd(record, event)
            "queue_update" -> eventQueue = parseQueueEvent(event)
            "compaction_start" -> {
                activeItem = TimelineItem.RunStatus("event:${record.streamId}:${record.sequence}", "Compacting task history")
                transientRunState = TaskDetailRunState.COMPACTING
            }
            "compaction_end" -> {
                settleActive()
                transientRunState = TaskDetailRunState.RUNNING
            }
            "auto_retry_start" -> {
                val attempt = event.primitive("attempt")?.intOrNull
                val max = event.primitive("maxAttempts")?.intOrNull
                activeItem = TimelineItem.RunStatus(
                    "event:${record.streamId}:${record.sequence}",
                    if (attempt != null && max != null) "Retrying $attempt of $max" else "Retrying Momoding",
                )
                transientRunState = TaskDetailRunState.RETRYING
            }
            "auto_retry_end" -> {
                settleActive()
                transientRunState = if (event.strictBoolean("success") == false) {
                    TaskDetailRunState.FAILED
                } else {
                    TaskDetailRunState.RUNNING
                }
            }
            else -> Unit // Exact unknown event remains in Room diagnostics; never invent product state.
        }
        appliedEvents[record.sequence] = record.digest
    }

    private fun applyMessageUpdate(record: TaskDetailEventRecord, event: JsonObject) {
        val assistantEvent = event["assistantMessageEvent"] as? JsonObject
        val message = event["message"] as? JsonObject
            ?: assistantEvent?.get("partial") as? JsonObject
            ?: return
        val prior = activeItem as? TimelineItem.AssistantText
        val timestamp = message.primitive("timestamp")?.contentOrNull
        val key = timestamp
            ?.let { "assistant:${record.streamId}:$it" }
            ?: prior?.stableKey
            ?: "assistant:${record.streamId}:${record.sequence}"
        val delta = assistantEvent
            ?.takeIf { it.string("type") == "text_delta" }
            ?.primitive("delta")
            ?.contentOrNull
        val partialText = extractContentText(message["content"], setOf("text"))
        val text = if (prior?.stableKey == key && delta != null) {
            val appended = prior.text + delta
            // Pi may publish text_start and the first text_delta with the same partial.
            // The partial is authoritative when the delta cannot advance the current prefix.
            partialText.takeIf { it.isNotBlank() && it != appended } ?: appended
        } else {
            partialText
        }
        if (text.isBlank()) return
        activeItem = TimelineItem.AssistantText(key, sanitizeText(text), partial = true)
        transientRunState = TaskDetailRunState.RUNNING
    }

    private fun applyToolStart(record: TaskDetailEventRecord, event: JsonObject) {
        settleActive()
        val callId = event.nonBlankString("toolCallId")
        val toolName = event.nonBlankString("toolName")
        if (callId == null || toolName == null || event["args"] !is JsonObject) {
            settledItems = settledItems + TimelineItem.UnsupportedActivity(
                "event:${record.streamId}:${record.sequence}:unsupported-tool-start",
            )
            return
        }
        activeItem = toolItem(callId, toolName, ToolActivityState.RUNNING, "Working")
        transientRunState = TaskDetailRunState.RUNNING
    }

    private fun applyToolEnd(record: TaskDetailEventRecord, event: JsonObject) {
        val callId = event.nonBlankString("toolCallId")
        val toolName = event.nonBlankString("toolName")
        val failed = event.strictBoolean("isError")
        val result = event["result"] as? JsonObject
        if (
            callId == null ||
            toolName == null ||
            failed == null ||
            result == null ||
            !isSupportedToolResultContent(result["content"])
        ) {
            replaceToolWithUnsupported(
                stableKey = "event:${record.streamId}:${record.sequence}:unsupported-tool-end",
                callId = callId,
            )
            return
        }
        if (toolName == TASK_PLAN_UPDATE_TOOL_NAME) {
            val plan = planItem(callId, result)
            if (plan == null) {
                replaceToolWithUnsupported(
                    stableKey = "event:${record.streamId}:${record.sequence}:unsupported-plan",
                    callId = callId,
                )
            } else {
                replaceToolWithPlan(plan)
            }
            transientRunState = TaskDetailRunState.RUNNING
            return
        }
        val terminal = toolItem(
            callId,
            toolName,
            if (failed) ToolActivityState.FAILURE else ToolActivityState.SUCCESS,
            if (failed) "Failed" else "Completed",
            result,
        )
        if (activeItem is TimelineItem.ToolActivity && (activeItem as TimelineItem.ToolActivity).toolCallId == callId) {
            activeItem = terminal
            settleActive()
        } else {
            replaceOrAppendTool(terminal)
        }
        transientRunState = TaskDetailRunState.RUNNING
    }

    private fun applyToolUpdate(record: TaskDetailEventRecord, event: JsonObject) {
        val callId = event.nonBlankString("toolCallId")
        val toolName = event.nonBlankString("toolName")
        val partial = event["partialResult"] as? JsonObject
        if (
            callId == null ||
            toolName == null ||
            event["args"] !is JsonObject ||
            partial == null ||
            !isSupportedToolResultContent(partial["content"])
        ) {
            replaceToolWithUnsupported(
                stableKey = "event:${record.streamId}:${record.sequence}:unsupported-tool-update",
                callId = callId,
            )
            return
        }
        val update = toolItem(
            callId = callId,
            toolName = toolName,
            state = ToolActivityState.RUNNING,
            detail = "Running",
            resultContainer = partial,
        )
        if (activeItem is TimelineItem.ToolActivity &&
            (activeItem as TimelineItem.ToolActivity).toolCallId == callId
        ) {
            activeItem = update
        } else {
            replaceOrAppendTool(update)
        }
        transientRunState = TaskDetailRunState.RUNNING
    }

    private fun settleActive() {
        val active = activeItem ?: return
        settledItems = settledItems + active
        activeItem = null
    }

    private fun replaceOrAppendTool(tool: TimelineItem.ToolActivity) {
        val index = settledItems.indexOfFirst {
            it is TimelineItem.ToolActivity && it.toolCallId == tool.toolCallId
        }
        settledItems = if (index < 0) {
            settledItems + tool
        } else {
            settledItems.toMutableList().also { it[index] = tool }
        }
    }

    private fun replaceToolWithUnsupported(stableKey: String, callId: String?) {
        val fallback = TimelineItem.UnsupportedActivity(stableKey)
        if (
            callId != null &&
            activeItem is TimelineItem.ToolActivity &&
            (activeItem as TimelineItem.ToolActivity).toolCallId == callId
        ) {
            activeItem = fallback
            settleActive()
            return
        }
        val existing = if (callId == null) -1 else settledItems.indexOfFirst {
            it is TimelineItem.ToolActivity && it.toolCallId == callId
        }
        settledItems = if (existing < 0) {
            settledItems + fallback
        } else {
            settledItems.toMutableList().also { it[existing] = fallback }
        }
    }

    private fun projectSnapshotMessages(snapshot: TaskDetailSnapshot): List<TimelineItem> {
        val output = mutableListOf<TimelineItem>()
        snapshot.messages.forEach { row ->
            val message = parseObject(row.rawPayload)
            if (message == null) {
                output += TimelineItem.UnsupportedActivity("snapshot:${row.stableItemId}")
                return@forEach
            }
            val before = output.size
            val role = message.string("role")
            when (role) {
                "user" -> {
                    val text = extractContentText(message["content"], setOf("text"))
                    val attachmentIds = extractAttachmentReferences(message["content"])
                    if (text.isNotBlank() || attachmentIds.isNotEmpty()) {
                        output += TimelineItem.UserMessage(
                            stableKey = "snapshot:${row.stableItemId}:user",
                            text = sanitizeText(text),
                            attachmentIds = attachmentIds,
                        )
                    }
                }
                "phoneLocalControl" -> when (message.string("kind")) {
                    "implement_plan" -> if (
                        message.nonBlankString("controlId") != null &&
                        message.string("planDigest")?.matches(SHA256) == true
                    ) {
                        output += TimelineItem.RunStatus(
                            "snapshot:${row.stableItemId}:implement-plan",
                            "Implementing approved plan",
                        )
                    } else {
                        output += TimelineItem.UnsupportedActivity("snapshot:${row.stableItemId}")
                    }
                    "goal_continuation" -> {
                        val turnIndex = message.primitive("turnIndex")?.intOrNull
                        val valid = message.nonBlankString("controlId") != null &&
                            message.string("goalId")?.matches(GOAL_ID) == true &&
                            message.primitive("generation")?.intOrNull?.let { it > 0 } == true &&
                            turnIndex != null && turnIndex in 0..10_000 &&
                            message.string("trigger") in setOf("start", "continue", "resume")
                        output += if (valid) {
                            TimelineItem.RunStatus(
                                "snapshot:${row.stableItemId}:goal-continuation",
                                if (turnIndex == 0) "Starting goal" else "Continuing goal · turn $turnIndex",
                            )
                        } else {
                            TimelineItem.UnsupportedActivity("snapshot:${row.stableItemId}")
                        }
                    }
                    else -> output += TimelineItem.UnsupportedActivity("snapshot:${row.stableItemId}")
                }
                "assistant" -> projectAssistantMessage(row.stableItemId, message, output)
                "toolResult" -> projectToolResult(row.stableItemId, message, output)
                else -> output += TimelineItem.UnsupportedActivity("snapshot:${row.stableItemId}")
            }
            if (role in setOf("user", "assistant") && output.size == before) {
                output += TimelineItem.UnsupportedActivity("snapshot:${row.stableItemId}:unsupported")
            }
        }
        return output
    }

    private fun projectAssistantMessage(
        messageId: String,
        message: JsonObject,
        output: MutableList<TimelineItem>,
    ) {
        val content = message["content"] as? JsonArray
        content?.forEachIndexed { index, element ->
            val item = element as? JsonObject
            if (item == null) {
                output += TimelineItem.UnsupportedActivity("snapshot:$messageId:content:$index")
                return@forEachIndexed
            }
            when (item.string("type")) {
                "text" -> item.string("text")?.takeIf(String::isNotBlank)?.let { text ->
                    output += TimelineItem.AssistantText(
                        "snapshot:$messageId:text:$index",
                        sanitizeText(text),
                        partial = false,
                    )
                }
                "thinking" -> item.string("thinking")?.takeIf(String::isNotBlank)?.let { text ->
                    output += TimelineItem.ThinkingSummary(
                        "snapshot:$messageId:thinking:$index",
                        sanitizeText(text),
                    )
                }
                "toolCall" -> {
                    val callId = item.nonBlankString("id")
                    val toolName = item.nonBlankString("name")
                    if (callId == null || toolName == null || item["arguments"] !is JsonObject) {
                        output += TimelineItem.UnsupportedActivity("snapshot:$messageId:content:$index")
                    } else {
                        output += toolItem(callId, toolName, ToolActivityState.RUNNING, "Waiting for result")
                    }
                }
                else -> output += TimelineItem.UnsupportedActivity("snapshot:$messageId:content:$index")
            }
        }
        if (message.string("stopReason") == "error") {
            output += TimelineItem.Error(
                stableKey = "snapshot:$messageId:error",
                message = safeProviderError(message.string("errorMessage")),
            )
        }
    }

    private fun safeProviderError(errorMessage: String?): String = when (errorMessage) {
        "OpenRouter API key is invalid" ->
            "OpenRouter API key is invalid. Update it in Settings."
        "OpenRouter account has insufficient credits" ->
            "OpenRouter account has insufficient credits."
        "OpenRouter request is not permitted" ->
            "OpenRouter did not permit this request."
        "OpenRouter model was not found" ->
            "OpenRouter could not find this model. Update it in Settings."
        "OpenRouter request timed out" ->
            "OpenRouter request timed out. Try again."
        "OpenRouter rate limit reached" ->
            "OpenRouter rate limit reached. Try again shortly."
        "OpenRouter provider is unavailable" ->
            "OpenRouter provider is unavailable. Try again shortly."
        else -> "The Provider could not complete this response."
    }

    private fun projectToolResult(
        messageId: String,
        message: JsonObject,
        output: MutableList<TimelineItem>,
    ) {
        val callId = message.nonBlankString("toolCallId")
        val toolName = message.nonBlankString("toolName")
        val failed = message.strictBoolean("isError")
        if (callId == null || toolName == null || failed == null || !isSupportedToolResultContent(message["content"])) {
            val fallback = TimelineItem.UnsupportedActivity("snapshot:$messageId:unsupported")
            val existing = if (callId == null) -1 else output.indexOfFirst {
                it is TimelineItem.ToolActivity && it.toolCallId == callId
            }
            if (existing >= 0) output[existing] = fallback else output += fallback
            return
        }
        if (toolName == TASK_PLAN_UPDATE_TOOL_NAME) {
            val plan = planItem(callId, message)
            val existing = output.indexOfFirst {
                (it is TimelineItem.ToolActivity && it.toolCallId == callId) ||
                    (it is TimelineItem.Plan && it.toolCallId == callId)
            }
            val replacement = plan ?: TimelineItem.UnsupportedActivity("snapshot:$messageId:unsupported-plan")
            if (existing >= 0) output[existing] = replacement else output += replacement
            return
        }
        val terminal = toolItem(
            callId,
            toolName,
            if (failed) ToolActivityState.FAILURE else ToolActivityState.SUCCESS,
            if (failed) "Failed" else "Completed",
            message,
        )
        val existing = output.indexOfFirst { it is TimelineItem.ToolActivity && it.toolCallId == callId }
        if (existing >= 0) output[existing] = terminal else output += terminal
    }

    private fun isSupportedToolResultContent(content: JsonElement?): Boolean {
        val array = content as? JsonArray ?: return false
        return array.all { element ->
            val item = element as? JsonObject ?: return@all false
            when (item.string("type")) {
                "text" -> item.string("text") != null
                "image" -> item.nonBlankString("data") != null && item.nonBlankString("mimeType") != null
                else -> false
            }
        }
    }

    private fun extractAttachmentReferences(content: JsonElement?): List<String> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (item.string("type") !in setOf("image", "file")) return@mapNotNull null
            item.string("data")?.let(ATTACHMENT_REFERENCE::matchEntire)?.groupValues?.get(1)
        }
    }

    private fun toolItem(
        callId: String,
        toolName: String,
        state: ToolActivityState,
        detail: String,
        resultContainer: JsonObject? = null,
    ): TimelineItem.ToolActivity {
        if (toolName.startsWith("device_files_") && toolName !in SUPPORTED_FILE_TOOLS) {
            return TimelineItem.ToolActivity(
                stableKey = "tool:$callId",
                toolCallId = callId,
                title = "Mobile file activity unavailable",
                detail = "This task cannot use mobile files in this version.",
                state = ToolActivityState.UNSUPPORTED,
            )
        }
        val kind = toolKind(toolName)
        val title = when {
            kind == ToolActivityKind.TEST && state == ToolActivityState.RUNNING -> "Running tests"
            kind == ToolActivityKind.TEST && state == ToolActivityState.SUCCESS -> "Tests passed"
            kind == ToolActivityKind.TEST && state == ToolActivityState.FAILURE -> "Tests failed"
            kind == ToolActivityKind.TERMINAL && state == ToolActivityState.RUNNING -> "Running command"
            kind == ToolActivityKind.TERMINAL && state == ToolActivityState.SUCCESS -> "Command completed"
            kind == ToolActivityKind.TERMINAL && state == ToolActivityState.FAILURE -> "Command failed"
            else -> when (toolName) {
            "request_user_question" -> "Asked a question"
            "request_user_confirmation" -> "Requested confirmation"
            "device_capabilities_get" -> "Checked mobile capabilities"
            "device_files_list" -> "Listed authorized files"
            "device_files_read" -> "Requested file content"
            "device_media_list" -> "Listed recent photo metadata"
            "attachment_read" -> "Read text attachment"
            "device_files_prepare_changes" -> "Prepared file changes"
            "device_files_commit_changes" -> "Applied file changes"
            else -> toolName.replace('_', ' ').replaceFirstChar(Char::uppercase)
            }
        }
        val result = resultContainer?.let { toolResult(toolName, state, it) }
        val action = if (state == ToolActivityState.SUCCESS) {
            when (toolName) {
                "device_files_prepare_changes" -> ToolActivityAction.REVIEW_CHANGES
                "device_files_commit_changes" -> ToolActivityAction.VIEW_OUTPUTS
                else -> null
            }
        } else {
            null
        }
        return TimelineItem.ToolActivity(
            stableKey = "tool:$callId",
            toolCallId = callId,
            title = title,
            detail = if (result?.truncated == true) "$detail · output truncated" else detail,
            state = state,
            kind = kind,
            result = result,
            action = action,
        )
    }

    private fun replaceToolWithPlan(plan: TimelineItem.Plan) {
        if (
            activeItem is TimelineItem.ToolActivity &&
            (activeItem as TimelineItem.ToolActivity).toolCallId == plan.toolCallId
        ) {
            activeItem = plan
            settleActive()
            return
        }
        val existing = settledItems.indexOfFirst {
            (it is TimelineItem.ToolActivity && it.toolCallId == plan.toolCallId) ||
                (it is TimelineItem.Plan && it.toolCallId == plan.toolCallId)
        }
        settledItems = if (existing < 0) {
            settledItems + plan
        } else {
            settledItems.toMutableList().also { it[existing] = plan }
        }
    }

    private fun planItem(callId: String, container: JsonObject): TimelineItem.Plan? {
        val details = container["details"] as? JsonObject ?: return null
        if (details.string("kind") != TASK_PLAN_UPDATE_TOOL_NAME) return null
        val explanation = details.string("explanation")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 4_096 }
            ?: return null
        val rawSteps = details["steps"] as? JsonArray ?: return null
        if (rawSteps.size !in 1..12) return null
        val ids = mutableSetOf<String>()
        val canonicalSteps = mutableListOf<Triple<String, String, String>>()
        val steps = rawSteps.map { element ->
            val step = element as? JsonObject ?: return null
            val id = step.string("id")
                ?.takeIf { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")) && ids.add(it) }
                ?: return null
            val text = step.string("text")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 1_024 }
                ?: return null
            val rawStatus = step.string("status")
            val state = when (rawStatus) {
                "pending" -> TaskPlanStepState.PENDING
                "in_progress" -> TaskPlanStepState.IN_PROGRESS
                "completed" -> TaskPlanStepState.COMPLETED
                else -> return null
            }
            canonicalSteps += Triple(id, text, requireNotNull(rawStatus))
            TaskPlanStepUiModel(id, sanitizeText(text), state)
        }
        val digest = details.string("planDigest")
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: return null
        val canonical = buildJsonObject {
            put("explanation", explanation)
            put("steps", buildJsonArray {
                canonicalSteps.forEach { (id, text, status) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("text", text)
                        put("status", status)
                    })
                }
            })
        }.toString()
        if (sha256(canonical) != digest) return null
        return TimelineItem.Plan(
            stableKey = "plan:$callId",
            toolCallId = callId,
            explanation = sanitizeText(explanation),
            steps = steps,
            planDigest = digest,
        )
    }

    private fun toolKind(toolName: String): ToolActivityKind {
        val normalized = toolName.lowercase()
        return when {
            normalized == "request_user_question" -> ToolActivityKind.USER_INPUT
            normalized.startsWith("device_files_") || normalized == "device_capabilities_get" ||
                normalized == "attachment_read" ->
                ToolActivityKind.MOBILE_FILE
            normalized == "test" || normalized.contains("pytest") ||
                normalized.contains("gradle_test") || normalized.endsWith("_tests") ->
                ToolActivityKind.TEST
            normalized in TERMINAL_TOOL_NAMES -> ToolActivityKind.TERMINAL
            else -> ToolActivityKind.GENERIC
        }
    }

    private fun toolResult(
        toolName: String,
        state: ToolActivityState,
        container: JsonObject,
    ): ToolResultUiModel? {
        val text = extractContentText(container["content"], setOf("text"))
        val details = container["details"] as? JsonObject
        val sources = details?.get("sources")
            ?.let(::sourceLabels)
            .orEmpty()
            .distinct()
            .take(MAX_TOOL_SOURCES)
        val presented = attentionResultText(toolName, state, text) ?: prettyStructuredText(text)
        val sanitized = sanitizeText(presented)
        if (sanitized.isBlank() && sources.isEmpty()) return null
        return ToolResultUiModel(
            text = sanitized,
            sources = sources,
            truncated = presented.length > sanitized.length,
        )
    }

    private fun attentionResultText(
        toolName: String,
        state: ToolActivityState,
        text: String,
    ): String? = when (toolName) {
        "request_user_question" -> {
            val result = parseObject(text.trim())
            when (result?.string("outcome")) {
                "answered" -> {
                    val answer = result["answer"] as? JsonObject
                    when (answer?.string("kind")) {
                        "option" -> answer.nonBlankString("label")?.let { "You chose $it" }
                        "custom" -> answer.nonBlankString("text")?.let { "You answered: $it" }
                        else -> null
                    } ?: "Answer recorded"
                }
                "skipped" -> "Skipped"
                else -> if (state == ToolActivityState.FAILURE) "Answer not recorded" else "Answer recorded"
            }
        }
        "request_user_confirmation" -> {
            val result = parseObject(text.trim())
            when {
                result?.string("outcome") == "confirmed" -> "Confirmed"
                result?.string("code") == "USER_DECLINED" -> "Declined"
                state == ToolActivityState.FAILURE -> "Decision not recorded"
                else -> "Decision recorded"
            }
        }
        else -> null
    }

    private fun prettyStructuredText(text: String): String {
        val trimmed = text.trim()
        if (!(trimmed.startsWith('{') && trimmed.endsWith('}')) &&
            !(trimmed.startsWith('[') && trimmed.endsWith(']'))
        ) {
            return text
        }
        return runCatching {
            prettyJson.encodeToString(JsonElement.serializer(), strictJson.parseToJsonElement(trimmed))
        }.getOrDefault(text)
    }

    private fun sourceLabels(element: JsonElement): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { value ->
            val raw = when (value) {
                is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
                is JsonObject -> value.string("title") ?: value.string("name") ?: value.string("url")
                else -> null
            }
            raw?.takeIf(String::isNotBlank)?.let(::sanitizeText)
        }

    private fun parseQueue(raw: String): List<QueueItemUiModel> {
        val array = runCatching { strictJson.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return parseQueueEntries(array)
    }

    private fun parseQueueEvent(event: JsonObject): List<QueueItemUiModel> {
        val entries = buildList {
            addQueueMessages(event["steer"] ?: event["steering"], "steering")
            addQueueMessages(event["followUp"], "follow_up")
        }
        return entries.mapIndexedNotNull { index, (kind, text) -> queueItem(kind, text, index) }
    }

    private fun MutableList<Pair<String, String>>.addQueueMessages(
        element: JsonElement?,
        kind: String,
    ) {
        (element as? JsonArray)?.forEach { value ->
            val text = when (value) {
                is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
                is JsonObject -> value.queueMessageText()
                else -> null
            }
            text?.takeIf(String::isNotBlank)?.let { add(kind to it) }
        }
    }

    private fun JsonObject.queueMessageText(): String? {
        if (string("role") != "user") return null
        return when (val content = this["content"]) {
            is JsonPrimitive -> content.takeIf(JsonPrimitive::isString)?.contentOrNull
            is JsonArray -> content.mapNotNull { block ->
                val item = block as? JsonObject ?: return@mapNotNull null
                if (item.string("type") != "text") return@mapNotNull null
                item.string("text")
            }.joinToString("\n")
            else -> null
        }
    }

    private fun parseQueueEntries(array: JsonArray): List<QueueItemUiModel> = array.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val kind = item.string("kind") ?: return@mapIndexedNotNull null
        val text = item.string("text") ?: return@mapIndexedNotNull null
        queueItem(kind, text, index)
    }

    private fun queueItem(kind: String, text: String, index: Int): QueueItemUiModel? {
        val mode = when (kind) {
            "steering" -> RunningComposerMode.STEER
            "follow_up" -> RunningComposerMode.FOLLOW_UP
            else -> return null
        }
        return QueueItemUiModel(
        stableKey = "queue:$index:${sha256(kind + '\u0000' + text)}",
        kind = mode,
        text = sanitizeText(text),
        position = index + 1,
        )
    }

    private fun mapRunState(value: String?, streaming: Boolean): TaskDetailRunState = when (value) {
        "STARTING" -> TaskDetailRunState.STARTING
        "RUNNING" -> TaskDetailRunState.RUNNING
        "WAITING" -> TaskDetailRunState.WAITING
        "STOPPING" -> TaskDetailRunState.STOPPING
        "STOPPED" -> TaskDetailRunState.STOPPED
        "FAILED" -> TaskDetailRunState.FAILED
        "INTERRUPTED" -> TaskDetailRunState.INTERRUPTED
        "IDLE", "COMPLETED" -> if (streaming) TaskDetailRunState.RUNNING else TaskDetailRunState.SETTLED
        null -> TaskDetailRunState.UNKNOWN
        else -> TaskDetailRunState.UNKNOWN
    }

    private fun extractContentText(element: JsonElement?, acceptedTypes: Set<String>): String {
        val array = element as? JsonArray ?: return ""
        return array.mapNotNull { content ->
            val item = content as? JsonObject ?: return@mapNotNull null
            if (item.string("type") !in acceptedTypes) return@mapNotNull null
            item.string("text")
        }.joinToString("\n")
    }

    private fun parseObject(raw: String): JsonObject? = runCatching {
        strictJson.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    private fun sanitizeText(value: String): String = value
        .replace(CONTENT_URI, "[redacted mobile reference]")
        .replace(ABSOLUTE_PATH, "[redacted path]")
        .take(MAX_PRESENTATION_CHARS)

    private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    private fun JsonObject.nonBlankString(key: String): String? = string(key)?.takeIf(String::isNotBlank)
    private fun JsonObject.strictBoolean(key: String): Boolean? = primitive(key)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull
    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private companion object {
        val strictJson = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
        val prettyJson = Json { prettyPrint = true }
        val CONTENT_URI = Regex("content://[^\\s]+", RegexOption.IGNORE_CASE)
        val ABSOLUTE_PATH = Regex("(?:/Users/|/home/|/data/|/storage/)[^\\s]+")
        val SUPPORTED_FILE_TOOLS = setOf(
            "device_files_list",
            "device_files_read",
            "device_files_prepare_changes",
            "device_files_commit_changes",
        )
        val TERMINAL_TOOL_NAMES = setOf("terminal", "shell", "bash", "exec", "run_command")
        const val TASK_PLAN_UPDATE_TOOL_NAME = "task_plan_update"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val GOAL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val ATTACHMENT_REFERENCE = Regex(
            "^attachment:([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$",
        )
        const val MAX_TOOL_SOURCES = 12
        const val MAX_PRESENTATION_CHARS = 32_768

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
