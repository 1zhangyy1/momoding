package app.momoding.feature.taskdetail

import app.momoding.core.data.TaskDetailEventRecord
import app.momoding.core.data.TaskDetailSnapshot
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.TaskFailure
import app.momoding.core.data.TaskAttentionKind
import app.momoding.core.data.classifyTaskFailure
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PiUiProjection(
    val runState: TaskDetailRunState,
    val timeline: TimelineWindow,
    val queue: List<QueueItemUiModel>,
    val attention: TaskAttentionUiModel?,
    val recovery: TaskRecoveryUiModel?,
    val failure: TaskFailure? = null,
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
                        "device_calendar" -> TaskAttentionKind.CONFIRMATION
                        "device_contacts" -> TaskAttentionKind.CONFIRMATION
                        "device_location" -> TaskAttentionKind.CONFIRMATION
                        "device_clipboard" -> TaskAttentionKind.CONFIRMATION
                        "device_ui_action" -> TaskAttentionKind.CONFIRMATION
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
                            "device_calendar" -> "Calendar access needs approval"
                            "device_contacts" -> "Contacts access needs approval"
                            "device_location" -> "Current location access needs approval"
                            "device_clipboard" -> "Clipboard access needs approval"
                            "device_ui_action" -> "Interface action needs approval"
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
            failure = if (run == TaskDetailRunState.FAILED) {
                snapshot.messages.asReversed().firstNotNullOfOrNull { row ->
                    val message = parseObject(row.rawPayload) ?: return@firstNotNullOfOrNull null
                    if (
                        message.string("role") == "assistant" &&
                        message.string("stopReason") == "error"
                    ) {
                        classifyTaskFailure(message.string("errorMessage"))
                    } else {
                        null
                    }
                }
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
            "provider_web_search", "provider_web_activity" -> applyProviderWebActivity(record, event)
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
        val settledPrior = settledItems
            .asReversed()
            .firstOrNull { it is TimelineItem.AssistantText && it.stableKey == key }
            as? TimelineItem.AssistantText
        val messagePrior = prior?.takeIf { it.stableKey == key } ?: settledPrior
        val delta = assistantEvent
            ?.takeIf { it.string("type") == "text_delta" }
            ?.primitive("delta")
            ?.contentOrNull
        val partialText = extractContentText(message["content"], setOf("text"))
        val text = if (messagePrior != null && delta != null) {
            val appended = messagePrior.text + delta
            // Pi may publish text_start and the first text_delta with the same partial.
            // The partial is authoritative when the delta cannot advance the current prefix.
            partialText.takeIf { it.isNotBlank() && it != appended } ?: appended
        } else {
            partialText
        }
        if (text.isBlank()) return
        val next = TimelineItem.AssistantText(key, sanitizeText(text), partial = true)
        if (prior?.stableKey == key) {
            activeItem = next
        } else {
            // Provider-owned activities may arrive in the middle of one Assistant message.
            // Settle that activity, then reopen the accumulated message after it instead of
            // rendering the same stable key in both the settled and active sections.
            settleActive()
            removeSettledItem(key)
            activeItem = next
        }
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

    private fun applyProviderWebActivity(
        record: TaskDetailEventRecord,
        event: JsonObject,
    ) {
        val requestId = event.nonBlankString("requestId")
            ?.takeIf { PROVIDER_REQUEST_ID.matches(it) }
        val state = when (event.string("state")) {
            "running" -> ToolActivityState.RUNNING
            "completed" -> ToolActivityState.SUCCESS
            "failed" -> ToolActivityState.FAILURE
            "cancelled" -> ToolActivityState.CANCELLED
            else -> null
        }
        val sources = providerSearchSources(event["sources"])
        val webRequests = event.primitive("webRequests")?.intOrNull
        val requests = event.primitive("searchRequests")?.intOrNull
        val fetches = event.primitive("fetchRequests")?.intOrNull
        if (
            requestId == null || state == null ||
            webRequests?.let { it !in 0..MAX_PROVIDER_WEB_REQUESTS } == true ||
            requests?.let { it !in 0..MAX_PROVIDER_SEARCH_REQUESTS } == true ||
            fetches?.let { it !in 0..MAX_PROVIDER_FETCH_REQUESTS } == true ||
            event["sources"] !is JsonArray
        ) {
            settledItems = settledItems + TimelineItem.UnsupportedActivity(
                "event:${record.streamId}:${record.sequence}:unsupported-provider-search",
            )
            return
        }
        val detail = buildList {
            requests?.takeIf { it > 0 }?.let {
                add("$it search${if (it == 1) "" else "es"}")
            }
            fetches?.takeIf { it > 0 }?.let {
                add("$it page${if (it == 1) "" else "s"} read")
            }
            if ((requests ?: 0) == 0 && (fetches ?: 0) == 0) {
                webRequests?.takeIf { it > 0 }?.let {
                    add("$it web action${if (it == 1) "" else "s"}")
                }
            }
            sources.size.takeIf { it > 0 }?.let {
                add("$it source${if (it == 1) "" else "s"}")
            }
        }.joinToString(" · ").ifBlank {
            if (state == ToolActivityState.RUNNING) "Using web" else "No sources returned"
        }
        val activity = when {
            (requests ?: 0) > 0 && (fetches ?: 0) > 0 -> WebActivity.SEARCH_AND_FETCH
            (fetches ?: 0) > 0 -> WebActivity.FETCH
            (webRequests ?: 0) > 0 -> WebActivity.GENERIC
            else -> WebActivity.SEARCH
        }
        val item = TimelineItem.ToolActivity(
            stableKey = "provider-web:$requestId",
            toolCallId = "provider-web:$requestId",
            title = when (state) {
                ToolActivityState.RUNNING -> activity.runningTitle
                ToolActivityState.SUCCESS -> activity.completedTitle
                ToolActivityState.FAILURE -> "Web access failed"
                ToolActivityState.CANCELLED -> "Web access stopped"
                ToolActivityState.UNSUPPORTED -> "Web access unavailable"
            },
            detail = detail,
            state = state,
            kind = ToolActivityKind.WEB_ACCESS,
            result = sources.takeIf { it.isNotEmpty() }?.let {
                ToolResultUiModel(text = "", sources = it)
            },
            expanded = false,
        )
        if (activeItem is TimelineItem.ToolActivity && activeItem?.stableKey == item.stableKey) {
            activeItem = item
        } else {
            settleActive()
            replaceOrAppendTool(item)
            if (state == ToolActivityState.RUNNING) {
                val existing = settledItems.indexOfLast { it.stableKey == item.stableKey }
                if (existing >= 0) {
                    settledItems = settledItems.toMutableList().also { it.removeAt(existing) }
                }
                activeItem = item
            }
        }
        if (state != ToolActivityState.RUNNING) settleActive()
        transientRunState = TaskDetailRunState.RUNNING
    }

    private enum class WebActivity(
        val runningTitle: String,
        val completedTitle: String,
    ) {
        SEARCH("Searching web", "Searched web"),
        FETCH("Reading web page", "Read web page"),
        SEARCH_AND_FETCH("Researching web", "Researched web"),
        GENERIC("Using web", "Used web"),
    }

    private fun settleActive() {
        val active = activeItem ?: return
        val existing = settledItems.indexOfLast { it.stableKey == active.stableKey }
        settledItems = if (existing < 0) {
            settledItems + active
        } else {
            settledItems.toMutableList().also { it[existing] = active }
        }
        activeItem = null
    }

    private fun removeSettledItem(stableKey: String) {
        if (settledItems.none { it.stableKey == stableKey }) return
        settledItems = settledItems.filterNot { it.stableKey == stableKey }
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

    private fun safeProviderError(errorMessage: String?): String =
        classifyTaskFailure(errorMessage).message

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
            toolName == "device_calendar" ->
                calendarToolTitle(state, resultContainer)
            toolName == "device_contacts" ->
                contactsToolTitle(state, resultContainer)
            toolName == "device_location" ->
                locationToolTitle(state)
            toolName == "device_clipboard" ->
                clipboardToolTitle(state, resultContainer)
            toolName == "device_notification" ->
                notificationToolTitle(state, resultContainer)
            toolName == "image_generate" && state == ToolActivityState.RUNNING -> "Generating image"
            toolName == "image_generate" && state == ToolActivityState.SUCCESS -> "Generated image"
            toolName == "image_generate" && state == ToolActivityState.FAILURE -> "Image generation failed"
            toolName == "image_generate" && state == ToolActivityState.CANCELLED -> "Image generation stopped"
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
                "device_contacts" -> "Used Android Contacts"
                "device_location" -> "Checked current location"
                "device_clipboard" -> "Used Android Clipboard"
                "device_notification" -> "Managed Momoding notifications"
                "device_ui_inspect" -> "Inspected the current interface"
                "device_ui_action" -> "Performed an interface action"
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
            expanded = result?.images?.isNotEmpty() == true,
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
            ?.let(::sourceItems)
            .orEmpty()
            .distinct()
            .take(MAX_TOOL_SOURCES)
        val generatedImageAttachmentId = generatedImageAttachmentId(toolName, state, details)
        val imageAttachmentIds = (
            extractAttachmentReferences(container["content"]) + listOfNotNull(generatedImageAttachmentId)
        )
            .distinct()
            .take(MAX_TOOL_IMAGES)
        val presented = attentionResultText(toolName, state, text) ?: prettyStructuredText(text)
        val sanitized = sanitizeText(presented)
        if (sanitized.isBlank() && sources.isEmpty() && imageAttachmentIds.isEmpty()) return null
        return ToolResultUiModel(
            text = sanitized,
            sources = sources,
            images = imageAttachmentIds.map(::ToolImageUiModel),
            truncated = presented.length > sanitized.length,
        )
    }

    private fun generatedImageAttachmentId(
        toolName: String,
        state: ToolActivityState,
        details: JsonObject?,
    ): String? {
        if (
            toolName != "image_generate" || state != ToolActivityState.SUCCESS ||
            details?.string("kind") != "generated_image_artifact" ||
            details.strictBoolean("persistent") != true
        ) return null
        val attachmentId = details.nonBlankString("attachmentId") ?: return null
        return attachmentId.takeIf {
            ATTACHMENT_REFERENCE.matches("attachment:$attachmentId")
        }
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
        "device_calendar" -> calendarResultText(state, text)
        "device_contacts" -> contactsResultText(state, text)
        "device_location" -> locationResultText(state, text)
        "device_clipboard" -> clipboardResultText(state, text)
        "device_notification" -> notificationResultText(state, text)
        "image_generate" -> imageGenerationResultText(state, text)
        else -> null
    }

    private fun imageGenerationResultText(
        state: ToolActivityState,
        text: String,
    ): String {
        val result = parseObject(text.trim())
        return if (state == ToolActivityState.SUCCESS && result?.strictBoolean("ok") == true) {
            ""
        } else {
            result?.nonBlankString("errorMessage")
                ?.let(::sanitizeText)
                ?: "Image generation failed"
        }
    }

    private fun notificationToolTitle(
        state: ToolActivityState,
        resultContainer: JsonObject?,
    ): String {
        if (state == ToolActivityState.RUNNING) return "Managing Momoding notifications"
        if (state == ToolActivityState.CANCELLED) return "Notification request cancelled"
        if (state == ToolActivityState.FAILURE) return "Notification request failed"
        if (state == ToolActivityState.UNSUPPORTED) return "Notifications unavailable"
        val action = resultContainer
            ?.let { extractContentText(it["content"], setOf("text")) }
            ?.trim()
            ?.let(::parseObject)
            ?.string("action")
        return when (action) {
            "status" -> "Checked notification access"
            "post" -> "Posted a notification"
            "list_active" -> "Listed active notifications"
            "update" -> "Updated a notification"
            "cancel" -> "Cancelled a notification"
            "open_settings" -> "Opened notification settings"
            else -> "Managed Momoding notifications"
        }
    }

    private fun notificationResultText(
        state: ToolActivityState,
        text: String,
    ): String {
        val result = parseObject(text.trim())
            ?: return if (state == ToolActivityState.FAILURE) {
                "Notification request failed"
            } else {
                "Notification result unavailable"
            }
        if (state == ToolActivityState.FAILURE || result.strictBoolean("ok") != true) {
            val error = result["error"] as? JsonObject
            return when (error?.string("code")) {
                "CAPABILITY_NOT_READY" -> "Notification permission is needed."
                "STALE_HANDLE" -> "Refresh active notifications and try again."
                "APP_NOT_FOREGROUND" -> "Open Momoding to manage notification settings."
                "USER_DECLINED" -> "Notification request declined."
                "DEVICE_TOOL_TIMEOUT" -> "Notification request timed out."
                "OUTCOME_UNKNOWN" -> "Notification outcome is unknown; refresh before retrying."
                else -> error?.nonBlankString("message")
                    ?.let(::sanitizeText)
                    ?: "Notification request failed"
            }
        }
        val action = result.string("action")
        val data = result["data"] as? JsonObject
        return when (action) {
            "status" -> if (data?.strictBoolean("readyToPost") == true) {
                "Notifications are ready"
            } else {
                "Notification permission is needed"
            }
            "post" -> "Notification posted"
            "list_active" -> {
                val count = data?.get("returnedCount")?.jsonPrimitive?.contentOrNull ?: "0"
                "Listed $count active notifications"
            }
            "update" -> "Notification updated"
            "cancel" -> "Notification cancelled"
            "open_settings" -> "Notification settings opened"
            else -> "Notification action completed"
        }
    }

    private fun clipboardToolTitle(
        state: ToolActivityState,
        resultContainer: JsonObject?,
    ): String {
        if (state == ToolActivityState.RUNNING) return "Using Android Clipboard"
        if (state == ToolActivityState.CANCELLED) return "Clipboard request cancelled"
        if (state == ToolActivityState.FAILURE) return "Clipboard request failed"
        if (state == ToolActivityState.UNSUPPORTED) return "Clipboard unavailable"
        val action = resultContainer
            ?.let { extractContentText(it["content"], setOf("text")) }
            ?.trim()
            ?.let(::parseObject)
            ?.string("action")
        return when (action) {
            "get" -> "Read clipboard text"
            "set" -> "Copied text"
            "clear" -> "Cleared clipboard"
            else -> "Used Android Clipboard"
        }
    }

    private fun clipboardResultText(
        state: ToolActivityState,
        text: String,
    ): String {
        val result = parseObject(text.trim())
            ?: return if (state == ToolActivityState.FAILURE) {
                "Clipboard request failed"
            } else {
                "Clipboard result unavailable"
            }
        if (state == ToolActivityState.FAILURE || result.strictBoolean("ok") != true) {
            val error = result["error"] as? JsonObject
            return when (error?.string("code")) {
                "CLIPBOARD_CONTENT_RESTRICTED" ->
                    "Sensitive clipboard content was withheld."
                "CLIPBOARD_EMPTY" -> "Clipboard has no plain text."
                "CLIPBOARD_FORMAT_UNSUPPORTED" -> "Clipboard item is not plain text."
                "APP_NOT_FOREGROUND" -> "Open Momoding to use the clipboard."
                "USER_DECLINED" -> "Clipboard request declined."
                "DEVICE_TOOL_TIMEOUT" -> "Clipboard request timed out."
                else -> error?.nonBlankString("message")
                    ?.let(::sanitizeText)
                    ?: "Clipboard request failed"
            }
        }
        val action = result.string("action")
        val data = result["data"] as? JsonObject
        val count = data?.get("characterCount")?.jsonPrimitive?.contentOrNull
        return when (action) {
            "get" -> "Read clipboard text${count?.let { " · $it characters" }.orEmpty()}"
            "set" -> "Copied text${count?.let { " · $it characters" }.orEmpty()}"
            "clear" -> "Clipboard cleared"
            else -> "Clipboard action completed"
        }
    }

    private fun locationToolTitle(state: ToolActivityState): String = when (state) {
        ToolActivityState.RUNNING -> "Getting current location"
        ToolActivityState.SUCCESS -> "Checked current location"
        ToolActivityState.FAILURE -> "Location unavailable"
        ToolActivityState.CANCELLED -> "Location request cancelled"
        ToolActivityState.UNSUPPORTED -> "Location unavailable"
    }

    private fun locationResultText(
        state: ToolActivityState,
        text: String,
    ): String {
        val result = parseObject(text.trim())
            ?: return if (state == ToolActivityState.FAILURE) {
                "Location request failed"
            } else {
                "Location result unavailable"
            }
        if (state == ToolActivityState.FAILURE || result.strictBoolean("ok") != true) {
            val error = result["error"] as? JsonObject
            return when (error?.string("code")) {
                "CAPABILITY_NOT_READY" -> "Location permission is needed."
                "LOCATION_SERVICES_DISABLED" -> "Android location services are turned off."
                "DEVICE_TOOL_TIMEOUT" -> "Location request timed out."
                "APP_NOT_FOREGROUND" -> "Open Momoding to request location."
                "USER_DECLINED" -> "Location request declined."
                "STOPPED" -> "Location request stopped."
                else -> error?.nonBlankString("message")
                    ?.let(::sanitizeText)
                    ?: "Location request failed"
            }
        }
        val data = result["data"] as? JsonObject
        val precision = when (data?.string("precision")) {
            "precise" -> "Precise"
            else -> "Approximate"
        }
        val accuracy = data?.get("accuracyMeters")?.jsonPrimitive?.contentOrNull
        return if (accuracy == null) {
            "$precision current location"
        } else {
            "$precision current location · accuracy ±$accuracy m"
        }
    }

    private fun contactsToolTitle(
        state: ToolActivityState,
        resultContainer: JsonObject?,
    ): String {
        if (state == ToolActivityState.RUNNING) return "Using Android Contacts"
        if (state == ToolActivityState.CANCELLED) return "Contacts lookup cancelled"
        if (state == ToolActivityState.FAILURE) return "Contacts lookup failed"
        if (state == ToolActivityState.UNSUPPORTED) return "Contacts unavailable"
        val action = resultContainer
            ?.let { extractContentText(it["content"], setOf("text")) }
            ?.trim()
            ?.let(::parseObject)
            ?.string("action")
        return when (action) {
            "search" -> "Searched contacts"
            "get_contact" -> "Checked contact"
            "create_contact" -> "Created contact"
            "update_contact" -> "Updated contact"
            "delete_contact" -> "Deleted contact"
            else -> "Used Android Contacts"
        }
    }

    private fun contactsResultText(
        state: ToolActivityState,
        text: String,
    ): String {
        val result = parseObject(text.trim())
            ?: return if (state == ToolActivityState.FAILURE) {
                "Contacts lookup failed"
            } else {
                "Contacts result unavailable"
            }
        if (state == ToolActivityState.FAILURE || result.strictBoolean("ok") != true) {
            val code = (result["error"] as? JsonObject)?.string("code")
            return when (code) {
                "CAPABILITY_NOT_READY" -> "Contacts access is not enabled"
                "STALE_HANDLE" -> "Contact selection expired; search again"
                "NOT_FOUND" -> "Contact no longer exists"
                "READ_ONLY" -> "This contact cannot be changed"
                "AMBIGUOUS_TARGET" -> "Contact belongs to multiple accounts"
                "CONFLICT" -> "Contact changed; inspect it again"
                "VERIFICATION_FAILED" -> "Contacts change could not be verified"
                "OUTCOME_UNKNOWN" -> "Contacts change outcome is unknown; inspect before retrying"
                "DEVICE_TOOL_TIMEOUT" -> "Contacts lookup timed out"
                else -> "Contacts lookup failed"
            }
        }
        val data = result["data"] as? JsonObject
        return when (result.string("action")) {
            "search" -> {
                val items = data?.get("items") as? JsonArray
                val names = items.orEmpty().mapNotNull { item ->
                    (item as? JsonObject)?.nonBlankString("displayName")
                }.take(3)
                val count = data?.get("count")?.jsonPrimitive?.intOrNull ?: names.size
                when {
                    count == 0 -> "No matching contacts"
                    names.isEmpty() -> "Found $count contact${if (count == 1) "" else "s"}"
                    else -> "Found $count: ${names.joinToString(", ")}"
                }
            }
            "get_contact" -> {
                val contact = data?.get("contact") as? JsonObject
                contact?.nonBlankString("displayName")?.let { "Checked $it" }
                    ?: "Checked contact"
            }
            "create_contact" -> {
                val contact = data?.get("contact") as? JsonObject
                contact?.nonBlankString("displayName")?.let { "Created $it" }
                    ?: "Created contact"
            }
            "update_contact" -> {
                val contact = data?.get("contact") as? JsonObject
                contact?.nonBlankString("displayName")?.let { "Updated $it" }
                    ?: "Updated contact"
            }
            "delete_contact" -> "Deleted contact"
            else -> "Contacts lookup completed"
        }
    }

    private fun calendarToolTitle(
        state: ToolActivityState,
        resultContainer: JsonObject?,
    ): String {
        if (state == ToolActivityState.RUNNING) return "Using Android Calendar"
        if (state == ToolActivityState.CANCELLED) return "Calendar action cancelled"
        if (state == ToolActivityState.FAILURE) return "Calendar action failed"
        if (state == ToolActivityState.UNSUPPORTED) return "Calendar unavailable"
        val action = resultContainer
            ?.let { extractContentText(it["content"], setOf("text")) }
            ?.trim()
            ?.let(::parseObject)
            ?.string("action")
        return when (action) {
            "list_calendars" -> "Listed calendars"
            "list_events" -> "Listed calendar events"
            "get_event" -> "Checked calendar event"
            "create_event" -> "Created calendar event"
            "update_event" -> "Updated calendar event"
            "delete_event" -> "Deleted calendar event"
            else -> "Used Android Calendar"
        }
    }

    private fun calendarResultText(
        state: ToolActivityState,
        text: String,
    ): String {
        val result = parseObject(text.trim())
            ?: return if (state == ToolActivityState.FAILURE) {
                "Calendar action failed"
            } else {
                "Calendar result unavailable"
            }
        if (state == ToolActivityState.FAILURE || result.strictBoolean("ok") != true) {
            return calendarFailureText(result)
        }
        val data = result["data"] as? JsonObject
        return when (result.string("action")) {
            "list_calendars" -> {
                val items = data?.get("items") as? JsonArray
                val names = items.orEmpty().mapNotNull { item ->
                    (item as? JsonObject)?.nonBlankString("displayName")
                }
                buildCalendarListText(
                    "${names.size} calendar${if (names.size == 1) "" else "s"}",
                    names,
                    items?.size.orZero(),
                )
            }
            "list_events" -> {
                val items = data?.get("items") as? JsonArray
                val labels = items.orEmpty().mapNotNull { item ->
                    (item as? JsonObject)?.let(::calendarEventLabel)
                }
                buildCalendarListText(
                    "${labels.size} event${if (labels.size == 1) "" else "s"}",
                    labels,
                    items?.size.orZero(),
                )
            }
            "get_event" -> data?.get("event")
                ?.let { it as? JsonObject }
                ?.let(::calendarEventLabel)
                ?.let { "Calendar event\n$it" }
                ?: "Calendar event checked"
            "create_event" -> data?.get("event")
                ?.let { it as? JsonObject }
                ?.let(::calendarEventLabel)
                ?.let { "Created $it" }
                ?: "Calendar event created"
            "update_event" -> data?.get("event")
                ?.let { it as? JsonObject }
                ?.let(::calendarEventLabel)
                ?.let { "Updated $it" }
                ?: "Calendar event updated"
            "delete_event" -> "Calendar event deleted"
            else -> "Calendar action completed"
        }
    }

    private fun calendarFailureText(result: JsonObject): String {
        val error = result["error"] as? JsonObject
        return when (error?.string("code")) {
            "USER_DECLINED" -> "Calendar action declined"
            "OUTCOME_UNKNOWN" ->
                "Calendar change outcome unknown. Check the live calendar before retrying."
            "CAPABILITY_NOT_READY" ->
                "Calendar permission is needed before this action."
            "STALE_HANDLE" ->
                "Calendar data changed. Refresh the calendar before retrying."
            "CALENDAR_CONFLICT" ->
                "Calendar data changed before the action. Refresh and review it again."
            else -> error?.nonBlankString("message")
                ?.let(::sanitizeText)
                ?: "Calendar action failed"
        }
    }

    private fun buildCalendarListText(
        heading: String,
        items: List<String>,
        rawCount: Int,
    ): String = buildString {
        append(heading)
        items.take(MAX_CALENDAR_PRESENTATION_ITEMS).forEach { item ->
            append("\n• ").append(item)
        }
        val hidden = rawCount - MAX_CALENDAR_PRESENTATION_ITEMS
        if (hidden > 0) append("\n+ ").append(hidden).append(" more")
    }

    private fun calendarEventLabel(event: JsonObject): String? {
        val title = event.nonBlankString("title") ?: return null
        val schedule = event["schedule"] as? JsonObject
        val time = when (schedule?.string("kind")) {
            "timed" -> listOfNotNull(
                schedule.nonBlankString("start"),
                schedule.nonBlankString("end"),
            ).joinToString(" → ").takeIf(String::isNotBlank)
            "all_day" -> listOfNotNull(
                schedule.nonBlankString("startDate"),
                schedule.nonBlankString("endDateExclusive"),
            ).joinToString(" → ").takeIf(String::isNotBlank)
            else -> null
        }
        val location = event.nonBlankString("location")
        return listOfNotNull(title, time, location)
            .joinToString(" · ")
            .let(::sanitizeText)
    }

    private fun Int?.orZero(): Int = this ?: 0

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

    private fun sourceItems(element: JsonElement): List<ToolSourceUiModel> =
        (element as? JsonArray).orEmpty().mapNotNull { value ->
            val raw = when (value) {
                is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
                is JsonObject -> value.string("title") ?: value.string("name") ?: value.string("url")
                else -> null
            }
            raw?.takeIf(String::isNotBlank)?.let { ToolSourceUiModel(sanitizeText(it)) }
        }

    private fun providerSearchSources(element: JsonElement?): List<ToolSourceUiModel> =
        (element as? JsonArray).orEmpty().mapNotNull { value ->
            val source = value as? JsonObject ?: return@mapNotNull null
            val url = source.nonBlankString("url")
                ?.takeIf { it.length <= MAX_PROVIDER_SOURCE_URL_CHARS && WEB_URL.matches(it) }
                ?: return@mapNotNull null
            val title = source.nonBlankString("title")
                ?.takeIf { it.length <= MAX_PROVIDER_SOURCE_TITLE_CHARS }
                ?: source.nonBlankString("domain")
                ?: url
            ToolSourceUiModel(label = sanitizeText(title), url = url)
        }.distinctBy(ToolSourceUiModel::url).take(MAX_PROVIDER_SEARCH_SOURCES)

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
        const val MAX_TOOL_IMAGES = 1
        const val MAX_PROVIDER_SEARCH_SOURCES = 15
        const val MAX_PROVIDER_SEARCH_REQUESTS = 3
        const val MAX_PROVIDER_FETCH_REQUESTS = 3
        const val MAX_PROVIDER_WEB_REQUESTS = 5
        const val MAX_PROVIDER_SOURCE_URL_CHARS = 2_048
        const val MAX_PROVIDER_SOURCE_TITLE_CHARS = 240
        val PROVIDER_REQUEST_ID = Regex("^provider-[1-9][0-9]{0,8}$")
        val WEB_URL = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)
        const val MAX_CALENDAR_PRESENTATION_ITEMS = 5
        const val MAX_PRESENTATION_CHARS = 32_768

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
