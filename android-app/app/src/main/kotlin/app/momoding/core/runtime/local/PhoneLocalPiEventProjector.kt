package app.momoding.core.runtime.local

import app.momoding.wire.P1aProtocol
import app.momoding.wire.RawPiSnapshot
import app.momoding.wire.ReceivedP1bServerFrame
import app.momoding.wire.RecoveryState
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.ReliableProjection
import app.momoding.wire.StreamCursor
import app.momoding.wire.TaskRunState
import app.momoding.wire.TaskSnapshotFrame
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomProjectionTransactionStore
import app.momoding.core.data.TaskEntity
import app.momoding.core.data.storedTaskFailure
import app.momoding.core.data.taskFailureForRunState
import app.momoding.core.extensions.ExtensionToolActivityContract
import app.momoding.core.policy.TaskApprovalMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class PhoneLocalPiProjectionProof(
    val taskId: String,
    val streamId: String,
    val eventCount: Int,
    val throughSequence: Long,
)

/**
 * Persists exact native Pi events into the existing durable task projection.
 *
 * This is a local event sink, not a runtime adapter: event payloads are not renamed or reshaped,
 * and the locally serialized pi.event reliability envelope is retained byte-for-byte in Room.
 */
class PhoneLocalPiEventProjector(
    private val database: MomodingDatabase,
    private val emittedAt: () -> String = {
        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val store = RoomProjectionTransactionStore(database)
    private val sessionStore = PhoneLocalPiSessionStore(database, nowMillis)
    private val projection = ReliableProjection(store)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun createTask(
        taskId: String,
        title: String,
        piSessionId: String,
        streamId: String,
        initialPrompt: String,
        attachmentIds: List<String> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
        approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
    ) {
        require(taskId.isNotBlank()) { "PI_MOBILE_TASK_ID_BLANK" }
        require(title.isNotBlank()) { "PI_MOBILE_TASK_TITLE_BLANK" }
        require(initialPrompt.isNotBlank() || attachmentIds.isNotEmpty() || textAttachments.isNotEmpty()) {
            "PI_MOBILE_TASK_INPUT_EMPTY"
        }
        database.runInTransaction {
            check(database.momodingDao().task(taskId) == null) { "PI_MOBILE_TASK_ALREADY_EXISTS" }
            database.momodingDao().upsertTask(
                TaskEntity(
                    taskId = taskId,
                    title = title,
                    runState = TaskRunState.STARTING.name,
                    recoveryState = RecoveryState.NORMAL.name,
                    readState = "READ",
                    attentionState = "NONE",
                    streamId = null,
                    throughSequence = 0,
                    snapshotVersion = null,
                    windowStart = 0,
                    windowEndExclusive = 0,
                    nextStageBatchOrdinal = 1,
                    queueJson = "[]",
                    piSessionId = piSessionId,
                    isStreaming = true,
                    updatedAtMillis = nowMillis(),
                    listedByHost = true,
                    titleSource = "PROVISIONAL",
                    approvalMode = approvalMode,
                ),
            )
        }
        replaceSnapshot(
            taskId = taskId,
            piSessionId = piSessionId,
            streamId = streamId,
            messages = listOf(userMessage(initialPrompt, attachmentIds, textAttachments)),
            runState = TaskRunState.STARTING,
            isStreaming = true,
        )
    }

    fun appendPendingPrompt(
        taskId: String,
        piSessionId: String,
        streamId: String,
        priorEntries: JsonArray,
        prompt: String,
        attachmentIds: List<String> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ) {
        val messages = sessionMessages(taskId, priorEntries) +
            userMessage(prompt, attachmentIds, textAttachments)
        val activities = durableExtensionActivities(taskId, emptyList())
        val webActivities = durableProviderWebActivities(taskId, emptyList(), messages)
        replaceSnapshot(
            taskId = taskId,
            piSessionId = piSessionId,
            streamId = streamId,
            messages = weaveProviderWebActivities(
                weaveExtensionActivities(messages, activities),
                webActivities,
            ),
            runState = TaskRunState.STARTING,
            isStreaming = true,
        )
    }

    fun replaceWithSessionSnapshot(
        taskId: String,
        piSessionId: String,
        streamId: String,
        snapshot: PiNativeTaskSessionSnapshot,
        runState: TaskRunState,
        extensionActivities: List<JsonObject> = emptyList(),
        providerWebActivities: List<JsonObject> = emptyList(),
    ) {
        check(snapshot.taskId == taskId) { "PI_MOBILE_SESSION_SNAPSHOT_TASK_MISMATCH" }
        database.runInTransaction {
            val messages = sessionMessages(taskId, snapshot.entries)
            val durableActivities = durableExtensionActivities(taskId, extensionActivities)
            val durableWebActivities = durableProviderWebActivities(
                taskId,
                providerWebActivities,
                messages,
            )
            replaceSnapshot(
                taskId = taskId,
                piSessionId = piSessionId,
                streamId = streamId,
                messages = weaveProviderWebActivities(
                    weaveExtensionActivities(messages, durableActivities),
                    durableWebActivities,
                ),
                runState = runState,
                isStreaming = false,
            )
            sessionStore.saveInCurrentTransaction(taskId, piSessionId, snapshot)
        }
    }

    fun persistedSession(taskId: String): PersistedPiTaskSession? = sessionStore.load(taskId)

    fun saveSessionSnapshot(
        taskId: String,
        piSessionId: String,
        snapshot: PiNativeTaskSessionSnapshot,
    ) {
        sessionStore.save(taskId, piSessionId, snapshot)
    }

    fun stabilizeTaskTitle(taskId: String) {
        database.runInTransaction {
            val current = requireNotNull(database.momodingDao().task(taskId)) {
                "PI_MOBILE_TASK_NOT_FOUND"
            }
            if (current.titleSource in setOf("USER", "AUTOMATIC")) return@runInTransaction
            database.momodingDao().setAutomaticTaskTitle(
                taskId,
                automaticTaskTitle(current.title),
            )
        }
    }

    fun markRunState(
        taskId: String,
        runState: TaskRunState,
        isStreaming: Boolean,
    ) {
        database.runInTransaction {
            val current = requireNotNull(database.momodingDao().task(taskId)) {
                "PI_MOBILE_TASK_NOT_FOUND"
            }
            val messages = if (runState == TaskRunState.FAILED) {
                persistedSession(taskId)?.snapshot?.entries?.let { sessionMessages(taskId, it) }
                    .orEmpty()
            } else {
                emptyList()
            }
            val failure = taskFailureForRunState(
                runState = runState.name,
                messages = messages,
                previous = current.storedTaskFailure(),
            )
            database.momodingDao().upsertTask(
                current.copy(
                    runState = runState.name,
                    isStreaming = isStreaming,
                    updatedAtMillis = nowMillis(),
                    failureKind = failure?.kind?.name,
                    failureMessage = failure?.message,
                    failureRecovery = failure?.recovery?.name,
                ),
            )
        }
    }

    fun interruptStaleLocalRuns(): Int = database.runInTransaction<Int> {
        var interrupted = 0
        val failure = requireNotNull(taskFailureForRunState(TaskRunState.INTERRUPTED.name))
        database.momodingDao().allTasks()
            .filter { task ->
                task.lastListSyncGeneration == null &&
                    task.hostUpdatedAtMillis == null &&
                    task.piSessionId != null &&
                    task.runState in ACTIVE_RUN_STATES
            }
            .forEach { task ->
                database.momodingDao().upsertTask(
                    task.copy(
                        runState = TaskRunState.INTERRUPTED.name,
                        recoveryState = RecoveryState.INTERRUPTED.name,
                        isStreaming = false,
                        updatedAtMillis = nowMillis(),
                        failureKind = failure.kind.name,
                        failureMessage = failure.message,
                        failureRecovery = failure.recovery.name,
                    ),
                )
                interrupted += 1
            }
        interrupted
    }

    fun append(
        taskId: String,
        piSessionId: String,
        streamId: String,
        events: List<JsonObject>,
    ): PhoneLocalPiProjectionProof {
        require(events.isNotEmpty()) { "PI_MOBILE_EMPTY_EVENT_BATCH" }
        val existing = store.read(taskId)
        check(existing?.streamId == null || existing.streamId == streamId) {
            "PI_MOBILE_DURABLE_STREAM_MISMATCH"
        }
        var throughSequence = existing?.throughSequence ?: 0
        val receivedEvents = events.map { event ->
            throughSequence += 1
            val rawEnvelope = buildJsonObject {
                put("protocolVersion", P1aProtocol.PROTOCOL_VERSION)
                put("kind", "pi.event")
                put("taskId", taskId)
                put("piSessionId", piSessionId)
                put("piVersion", P1aProtocol.PI_VERSION)
                put("streamId", streamId)
                put("sequence", throughSequence)
                put("emittedAt", emittedAt())
                put("event", event)
            }.toString().encodeToByteArray()
            ReliabilityContractDecoder.decode(rawEnvelope)
        }
        val result = projection.applyEventBatch(receivedEvents)
        check(result.ack?.throughSequence == throughSequence) {
            "PI_MOBILE_EVENT_BATCH_NOT_DURABLE throughSequence=$throughSequence"
        }
        return PhoneLocalPiProjectionProof(
            taskId = taskId,
            streamId = streamId,
            eventCount = events.size,
            throughSequence = throughSequence,
        )
    }

    private fun replaceSnapshot(
        taskId: String,
        piSessionId: String,
        streamId: String,
        messages: List<JsonElement>,
        runState: TaskRunState,
        isStreaming: Boolean,
    ) {
        val current = requireNotNull(store.read(taskId)) { "PI_MOBILE_TASK_NOT_FOUND" }
        check(current.streamId == null || current.streamId == streamId) {
            "PI_MOBILE_DURABLE_STREAM_MISMATCH"
        }
        val frame = TaskSnapshotFrame(
            kind = "task.snapshot",
            requestId = null,
            taskId = taskId,
            snapshotVersion = (current.snapshotVersion ?: 0) + 1,
            recoveryState = RecoveryState.NORMAL,
            runState = runState,
            piSessionId = piSessionId,
            pi = RawPiSnapshot(
                messages = messages,
                isStreaming = isStreaming,
                queue = emptyList(),
            ),
            pendingAttention = emptyList(),
            deviceCalls = emptyList(),
            cursor = StreamCursor(
                streamId = streamId,
                highWatermarkSequence = current.throughSequence,
                oldestReplayableSequence = 1,
            ),
        )
        val raw = json.encodeToString(TaskSnapshotFrame.serializer(), frame).encodeToByteArray()
        val result = projection.applyDirectSnapshot(ReceivedP1bServerFrame(frame, raw))
        check(result.state.snapshotVersion == frame.snapshotVersion) {
            "PI_MOBILE_SNAPSHOT_NOT_DURABLE"
        }
    }

    private fun sessionMessages(taskId: String, entries: JsonArray): List<JsonElement> {
        val implementationControls = mutableMapOf<String, PlanImplementationControl>()
        val goalControls = mutableMapOf<String, GoalContinuationControl>()
        val skillControls = mutableMapOf<String, SkillInvocationControl>()
        val textAttachmentControls = mutableMapOf<String, TextAttachmentControl>()
        return buildList {
            entries.forEach { entry ->
                val objectValue = entry as? JsonObject ?: return@forEach
                when ((objectValue["type"] as? JsonPrimitive)?.content) {
                    "custom" -> parsePlanImplementationControl(taskId, objectValue)?.let { control ->
                        implementationControls[control.id] = control
                    } ?: parseGoalContinuationControl(taskId, objectValue)?.let { control ->
                        goalControls[control.id] = control
                    } ?: parseSkillInvocationControl(objectValue)?.let { control ->
                        skillControls[control.id] = control
                    } ?: parseTextAttachmentControl(objectValue)?.let { control ->
                        textAttachmentControls[control.id] = control
                    }
                    "message" -> {
                        val message = objectValue["message"] as? JsonObject ?: return@forEach
                        val parentId = (objectValue["parentId"] as? JsonPrimitive)?.contentOrNull
                        val control = parentId?.let(implementationControls::get)
                        val goalControl = parentId?.let(goalControls::get)
                        val skillControl = parentId?.let(skillControls::get)
                        val textAttachmentControl = parentId?.let(textAttachmentControls::get)
                        add(
                            if (control != null && message.matchesImplementationControl(control)) {
                                implementationControlMessage(control)
                            } else if (
                                goalControl != null && message.matchesGoalContinuationControl(goalControl)
                            ) {
                                goalContinuationControlMessage(goalControl)
                            } else if (
                                skillControl != null && message.matchesSkillInvocationControl(skillControl)
                            ) {
                                userMessage(skillControl.composerText)
                            } else if (
                                textAttachmentControl != null &&
                                message.matchesTextAttachmentControl(textAttachmentControl)
                            ) {
                                userMessage(
                                    textAttachmentControl.originalText,
                                    textAttachments = textAttachmentControl.attachments,
                                )
                            } else {
                                message
                            },
                        )
                    }
                }
            }
        }
    }

    private fun durableExtensionActivities(
        taskId: String,
        newEvents: List<JsonObject>,
    ): List<JsonObject> {
        val prior = store.read(taskId)?.messages.orEmpty().mapNotNull { message ->
            val wrapper = message as? JsonObject ?: return@mapNotNull null
            if (
                wrapper.keys != EXTENSION_ACTIVITY_MESSAGE_KEYS ||
                wrapper.stringValue("role") != EXTENSION_ACTIVITY_ROLE
            ) {
                return@mapNotNull null
            }
            wrapper["event"] as? JsonObject
        }
        val latest = linkedMapOf<String, JsonObject>()
        (prior + newEvents).forEach { event ->
            val parsed = ExtensionToolActivityContract.parse(event) ?: return@forEach
            latest[parsed.identity] = event
        }
        return latest.values.filter { event ->
            ExtensionToolActivityContract.parse(event)?.terminal == true
        }
    }

    private fun weaveExtensionActivities(
        messages: List<JsonElement>,
        activities: List<JsonObject>,
    ): List<JsonElement> {
        if (activities.isEmpty()) return messages
        val byToolCallId = activities.groupBy { activity ->
            requireNotNull(ExtensionToolActivityContract.parse(activity)).outerToolCallId
        }
            .toMutableMap()
        return buildList {
            messages.forEach { message ->
                add(message)
                val objectValue = message as? JsonObject ?: return@forEach
                if (objectValue.stringValue("role") != "assistant") return@forEach
                val callIds = (objectValue["content"] as? JsonArray).orEmpty().mapNotNull { block ->
                    val content = block as? JsonObject ?: return@mapNotNull null
                    if (content.stringValue("type") == "toolCall") {
                        content.stringValue("id")
                    } else {
                        null
                    }
                }
                callIds.forEach { callId ->
                    byToolCallId.remove(callId).orEmpty().forEach { activity ->
                        add(extensionActivityMessage(activity))
                    }
                }
            }
        }
    }

    private fun extensionActivityMessage(activity: JsonObject): JsonObject = buildJsonObject {
        put("role", EXTENSION_ACTIVITY_ROLE)
        put("event", activity)
    }

    private fun durableProviderWebActivities(
        taskId: String,
        newEvents: List<JsonObject>,
        messages: List<JsonElement>,
    ): List<ProviderWebActivityRecord> {
        val prior = store.read(taskId)?.messages.orEmpty().mapNotNull { message ->
            val wrapper = message as? JsonObject ?: return@mapNotNull null
            if (
                wrapper.keys != PROVIDER_WEB_ACTIVITY_MESSAGE_KEYS ||
                wrapper.stringValue("role") != PROVIDER_WEB_ACTIVITY_ROLE
            ) {
                return@mapNotNull null
            }
            val event = wrapper["event"] as? JsonObject ?: return@mapNotNull null
            val anchor = wrapper.stringValue("assistantAnchor") ?: return@mapNotNull null
            providerWebActivityRecord(event, anchor)
        }
        val fallbackAnchor = messages.asReversed().firstNotNullOfOrNull(::assistantAnchor)
        val latest = linkedMapOf<String, ProviderWebActivityRecord>()
        (prior + newEvents.mapNotNull { event ->
            val anchor = event.stringValue("responseId")
                ?.takeIf(String::isNotBlank)
                ?.let { "response:$it" }
                ?: fallbackAnchor
                ?: return@mapNotNull null
            providerWebActivityRecord(event, anchor)
        }).forEach { record ->
            latest[record.identity] = record
        }
        return latest.values.toList()
    }

    private fun providerWebActivityRecord(
        event: JsonObject,
        assistantAnchor: String,
    ): ProviderWebActivityRecord? {
        if (event.toString().toByteArray().size > MAX_PROVIDER_WEB_ACTIVITY_BYTES) return null
        val sanitized = sanitizeProviderWebActivity(event) ?: return null
        val requestId = sanitized.stringValue("requestId")
            ?.takeIf(PROVIDER_REQUEST_ID::matches)
            ?: return null
        if (!ASSISTANT_ANCHOR.matches(assistantAnchor)) return null
        return ProviderWebActivityRecord(requestId, assistantAnchor, sanitized)
    }

    private fun sanitizeProviderWebActivity(event: JsonObject): JsonObject? {
        if (!PROVIDER_WEB_ACTIVITY_EVENT_KEYS.containsAll(event.keys)) return null
        val type = event.stringValue("type")?.takeIf { it in PROVIDER_WEB_ACTIVITY_TYPES }
            ?: return null
        val state = event.stringValue("state")?.takeIf { it in PROVIDER_WEB_TERMINAL_STATES }
            ?: return null
        val requestId = event.stringValue("requestId")?.takeIf(PROVIDER_REQUEST_ID::matches)
            ?: return null
        val responseId = event.stringValue("responseId")
            ?.takeIf { it.length in 1..MAX_PROVIDER_RESPONSE_ID_CHARS }
            ?: if ("responseId" in event) return null else null
        val childName = event.stringValue("childName")
            ?.takeIf { it.length in 1..MAX_PROVIDER_CHILD_NAME_CHARS }
            ?: if ("childName" in event) return null else null
        fun count(name: String, maximum: Int): Int? {
            val element = event[name] ?: return null
            return (element as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.intOrNull
                ?.takeIf { it in 0..maximum }
                ?: Int.MIN_VALUE
        }
        val webRequests = count("webRequests", MAX_PROVIDER_WEB_REQUESTS)
        val searchRequests = count("searchRequests", MAX_PROVIDER_SEARCH_REQUESTS)
        val fetchRequests = count("fetchRequests", MAX_PROVIDER_FETCH_REQUESTS)
        if (listOf(webRequests, searchRequests, fetchRequests).any { it == Int.MIN_VALUE }) return null
        val sources = event["sources"] as? JsonArray ?: return null
        if (sources.size > MAX_PROVIDER_WEB_SOURCES) return null
        val sanitizedSources = buildJsonArray {
            sources.forEach { source ->
                val value = source as? JsonObject ?: return null
                if (!PROVIDER_WEB_SOURCE_KEYS.containsAll(value.keys)) return null
                val url = value.stringValue("url")
                    ?.takeIf { it.length <= MAX_PROVIDER_SOURCE_URL_CHARS && WEB_URL.matches(it) }
                    ?: return null
                val title = value.stringValue("title")
                    ?.takeIf { it.length in 1..MAX_PROVIDER_SOURCE_TITLE_CHARS }
                    ?: return null
                val domain = value.stringValue("domain")
                    ?.takeIf { it.length in 1..MAX_PROVIDER_SOURCE_DOMAIN_CHARS }
                    ?: return null
                val start = value["startIndex"]?.let { element ->
                    (element as? JsonPrimitive)
                        ?.takeUnless(JsonPrimitive::isString)
                        ?.intOrNull
                        ?.takeIf { it >= 0 }
                        ?: return null
                }
                val end = value["endIndex"]?.let { element ->
                    (element as? JsonPrimitive)
                        ?.takeUnless(JsonPrimitive::isString)
                        ?.intOrNull
                        ?.takeIf { it >= 0 }
                        ?: return null
                }
                if (start != null && end != null && end < start) return null
                add(buildJsonObject {
                    put("url", url)
                    put("title", title)
                    put("domain", domain)
                    start?.let { put("startIndex", it) }
                    end?.let { put("endIndex", it) }
                })
            }
        }
        return buildJsonObject {
            put("type", type)
            put("state", state)
            put("requestId", requestId)
            responseId?.let { put("responseId", it) }
            webRequests?.let { put("webRequests", it) }
            searchRequests?.let { put("searchRequests", it) }
            fetchRequests?.let { put("fetchRequests", it) }
            put("sources", sanitizedSources)
            childName?.let { put("childName", it) }
        }
    }

    private fun weaveProviderWebActivities(
        messages: List<JsonElement>,
        activities: List<ProviderWebActivityRecord>,
    ): List<JsonElement> {
        if (activities.isEmpty()) return messages
        val byAnchor = activities.groupBy(ProviderWebActivityRecord::assistantAnchor).toMutableMap()
        return buildList {
            messages.forEach { message ->
                assistantAnchor(message)?.let { anchor ->
                    byAnchor.remove(anchor).orEmpty().forEach { activity ->
                        add(providerWebActivityMessage(activity))
                    }
                }
                add(message)
            }
        }
    }

    private fun assistantAnchor(message: JsonElement): String? {
        val objectValue = message as? JsonObject ?: return null
        if (objectValue.stringValue("role") != "assistant") return null
        objectValue.stringValue("responseId")
            ?.takeIf(String::isNotBlank)
            ?.let { return "response:$it" }
        val timestamp = (objectValue["timestamp"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return null
        return "timestamp:$timestamp"
    }

    private fun providerWebActivityMessage(activity: ProviderWebActivityRecord): JsonObject =
        buildJsonObject {
            put("role", PROVIDER_WEB_ACTIVITY_ROLE)
            put("assistantAnchor", activity.assistantAnchor)
            put("event", activity.event)
        }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun parsePlanImplementationControl(
        taskId: String,
        entry: JsonObject,
    ): PlanImplementationControl? {
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != PLAN_IMPLEMENT_CONTROL_TYPE) {
            return null
        }
        val id = (entry["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        val data = entry["data"] as? JsonObject ?: return null
        if (data.keys != setOf("kind", "taskId", "planDigest")) return null
        if ((data["kind"] as? JsonPrimitive)?.contentOrNull != "implement_plan") return null
        if ((data["taskId"] as? JsonPrimitive)?.contentOrNull != taskId) return null
        val digest = (data["planDigest"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { SHA256.matches(it) }
            ?: return null
        return PlanImplementationControl(id, digest)
    }

    private fun JsonObject.matchesImplementationControl(
        control: PlanImplementationControl,
    ): Boolean {
        if ((this["role"] as? JsonPrimitive)?.contentOrNull != "user") return false
        val content = this["content"] as? JsonArray ?: return false
        val text = content.mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            if ((value["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                (value["text"] as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
        }.joinToString("")
        return text.lineSequence().firstOrNull() ==
            "[momoding:implement-plan control=${control.id}]" &&
            text.lineSequence().lastOrNull() == "planDigest=${control.planDigest}"
    }

    private fun implementationControlMessage(control: PlanImplementationControl): JsonObject =
        buildJsonObject {
            put("role", "phoneLocalControl")
            put("kind", "implement_plan")
            put("controlId", control.id)
            put("planDigest", control.planDigest)
        }

    private fun parseGoalContinuationControl(
        taskId: String,
        entry: JsonObject,
    ): GoalContinuationControl? {
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != GOAL_CONTROL_TYPE) return null
        val id = (entry["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        val data = entry["data"] as? JsonObject ?: return null
        if (data.keys != setOf("kind", "taskId", "goalId", "generation", "turnIndex", "trigger")) {
            return null
        }
        if ((data["kind"] as? JsonPrimitive)?.contentOrNull != "goal_continuation") return null
        if ((data["taskId"] as? JsonPrimitive)?.contentOrNull != taskId) return null
        val goalId = (data["goalId"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { GOAL_ID.matches(it) }
            ?: return null
        val generation = (data["generation"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        val turnIndex = (data["turnIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?.takeIf { it in 0..10_000 }
            ?: return null
        val trigger = (data["trigger"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it in setOf("start", "continue", "resume") }
            ?: return null
        return GoalContinuationControl(id, goalId, generation, turnIndex, trigger)
    }

    private fun JsonObject.matchesGoalContinuationControl(
        control: GoalContinuationControl,
    ): Boolean {
        if ((this["role"] as? JsonPrimitive)?.contentOrNull != "user") return false
        val content = this["content"] as? JsonArray ?: return false
        val text = content.mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            if ((value["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                (value["text"] as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
        }.joinToString("")
        val lines = text.lineSequence().toList()
        return lines.firstOrNull() == "[momoding:goal-continuation control=${control.id}]" &&
            "goalId=${control.goalId}" in lines &&
            "generation=${control.generation}" in lines &&
            lines.lastOrNull() == "turnIndex=${control.turnIndex}"
    }

    private fun goalContinuationControlMessage(control: GoalContinuationControl): JsonObject =
        buildJsonObject {
            put("role", "phoneLocalControl")
            put("kind", "goal_continuation")
            put("controlId", control.id)
            put("goalId", control.goalId)
            put("generation", control.generation)
            put("turnIndex", control.turnIndex)
            put("trigger", control.trigger)
        }

    private fun parseSkillInvocationControl(entry: JsonObject): SkillInvocationControl? {
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != SKILL_CONTROL_TYPE) return null
        val id = (entry["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        val data = entry["data"] as? JsonObject ?: return null
        if (data.keys != setOf("kind", "name", "additionalInstructions")) return null
        if ((data["kind"] as? JsonPrimitive)?.contentOrNull != "skill_invocation") return null
        val name = (data["name"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { SKILL_NAME.matches(it) }
            ?: return null
        val instructions = when (val value = data["additionalInstructions"]) {
            JsonNull -> null
            is JsonPrimitive -> value.contentOrNull
                ?.takeIf { value.isString && it.length <= 65_536 && '\u0000' !in it }
                ?: return null
            else -> return null
        }
        return SkillInvocationControl(id, name, instructions)
    }

    private fun JsonObject.matchesSkillInvocationControl(
        control: SkillInvocationControl,
    ): Boolean {
        if ((this["role"] as? JsonPrimitive)?.contentOrNull != "user") return false
        val text = when (val content = this["content"]) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { item ->
                val value = item as? JsonObject ?: return@mapNotNull null
                if ((value["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (value["text"] as? JsonPrimitive)?.contentOrNull
                } else {
                    null
                }
            }.joinToString("")
            else -> null
        } ?: return false
        if (!text.contains("<skill name=\"${control.name}\"")) return false
        return control.additionalInstructions == null || text.endsWith(control.additionalInstructions)
    }

    private val SkillInvocationControl.composerText: String
        get() = buildString {
            append("/skill:")
            append(name)
            additionalInstructions?.let {
                append(' ')
                append(it)
            }
        }

    private fun parseTextAttachmentControl(entry: JsonObject): TextAttachmentControl? {
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != TEXT_ATTACHMENT_CONTROL_TYPE) {
            return null
        }
        val id = (entry["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        val data = entry["data"] as? JsonObject ?: return null
        if (data.keys != setOf("kind", "originalText", "attachments")) return null
        if ((data["kind"] as? JsonPrimitive)?.contentOrNull != "text_attachments") return null
        val originalText = (data["originalText"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.takeIf { it.length <= 65_536 && '\u0000' !in it }
            ?: return null
        val attachments = (data["attachments"] as? JsonArray)?.map { item ->
            val value = item as? JsonObject ?: return null
            if (value.keys != setOf("attachmentId", "displayName", "mimeType", "byteSize")) {
                return null
            }
            val attachmentId = (value["attachmentId"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { ATTACHMENT_ID.matches(it) }
                ?: return null
            val displayName = (value["displayName"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf { it.isNotEmpty() && it.length <= 240 && '\u0000' !in it }
                ?: return null
            val mimeType = (value["mimeType"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf { it.isNotEmpty() && it.length <= 128 && '\u0000' !in it }
                ?: return null
            val byteSize = (value["byteSize"] as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.longOrNull
                ?.takeIf { it in 1..4L * 1024L * 1024L }
                ?: return null
            PiRuntimeTextAttachmentInput(attachmentId, displayName, mimeType, byteSize)
        } ?: return null
        if (attachments.isEmpty() || attachments.size > 5 ||
            attachments.map { it.attachmentId }.distinct().size != attachments.size
        ) return null
        return TextAttachmentControl(id, originalText, attachments)
    }

    private fun JsonObject.matchesTextAttachmentControl(control: TextAttachmentControl): Boolean {
        if ((this["role"] as? JsonPrimitive)?.contentOrNull != "user") return false
        val content = this["content"] as? JsonArray ?: return false
        val text = content.mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            if ((value["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                (value["text"] as? JsonPrimitive)?.contentOrNull
            } else null
        }.joinToString("")
        return text.lineSequence().firstOrNull() ==
            "[momoding:text-attachments control=${control.id}]" &&
            text.lineSequence().lastOrNull() ==
            "attachments=${textAttachmentManifest(control.attachments)}"
    }

    private fun textAttachmentManifest(attachments: List<PiRuntimeTextAttachmentInput>): String =
        buildJsonArray {
            attachments.forEach { attachment ->
                add(buildJsonObject {
                    put("attachmentId", attachment.attachmentId)
                    put("displayName", attachment.displayName)
                    put("mimeType", attachment.mimeType)
                    put("byteSize", attachment.byteSize)
                })
            }
        }.toString()

    private fun userMessage(
        prompt: String,
        attachmentIds: List<String> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("role", "user")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    },
                )
                attachmentIds.forEach { attachmentId ->
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put("data", "attachment:$attachmentId")
                            put("mimeType", "image/jpeg")
                        },
                    )
                }
                textAttachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("type", "file")
                            put("data", "attachment:${attachment.attachmentId}")
                            put("mimeType", attachment.mimeType)
                            put("name", attachment.displayName)
                            put("byteSize", attachment.byteSize)
                        },
                    )
                }
            },
        )
        put("timestamp", nowMillis())
    }

    private companion object {
        const val PLAN_IMPLEMENT_CONTROL_TYPE = "pi_mobile_plan_implementation"
        const val GOAL_CONTROL_TYPE = "pi_mobile_goal_continuation"
        const val SKILL_CONTROL_TYPE = "pi_mobile_skill_invocation"
        const val TEXT_ATTACHMENT_CONTROL_TYPE = "pi_mobile_text_attachments"
        const val EXTENSION_ACTIVITY_ROLE = "phoneLocalExtensionActivity"
        const val PROVIDER_WEB_ACTIVITY_ROLE = "phoneLocalProviderWebActivity"
        val EXTENSION_ACTIVITY_MESSAGE_KEYS = setOf("role", "event")
        val PROVIDER_WEB_ACTIVITY_MESSAGE_KEYS = setOf("role", "assistantAnchor", "event")
        val PROVIDER_WEB_ACTIVITY_EVENT_KEYS = setOf(
            "type",
            "state",
            "requestId",
            "responseId",
            "searchRequests",
            "fetchRequests",
            "webRequests",
            "sources",
            "childName",
        )
        val PROVIDER_WEB_SOURCE_KEYS = setOf("url", "title", "domain", "startIndex", "endIndex")
        val PROVIDER_WEB_ACTIVITY_TYPES = setOf("provider_web_search", "provider_web_activity")
        val PROVIDER_WEB_TERMINAL_STATES = setOf("completed", "failed", "cancelled")
        const val MAX_PROVIDER_WEB_ACTIVITY_BYTES = 128 * 1024
        const val MAX_PROVIDER_WEB_SOURCES = 15
        const val MAX_PROVIDER_WEB_REQUESTS = 5
        const val MAX_PROVIDER_SEARCH_REQUESTS = 3
        const val MAX_PROVIDER_FETCH_REQUESTS = 3
        const val MAX_PROVIDER_RESPONSE_ID_CHARS = 256
        const val MAX_PROVIDER_CHILD_NAME_CHARS = 128
        const val MAX_PROVIDER_SOURCE_URL_CHARS = 2_048
        const val MAX_PROVIDER_SOURCE_TITLE_CHARS = 240
        const val MAX_PROVIDER_SOURCE_DOMAIN_CHARS = 253
        val PROVIDER_REQUEST_ID = Regex("^provider-[1-9][0-9]{0,8}$")
        val ASSISTANT_ANCHOR = Regex("^(?:response|timestamp):.{1,256}$")
        val WEB_URL = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val GOAL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val SKILL_NAME = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val ATTACHMENT_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        val ACTIVE_RUN_STATES = setOf(
            TaskRunState.STARTING.name,
            TaskRunState.RUNNING.name,
            TaskRunState.WAITING.name,
            TaskRunState.STOPPING.name,
        )
    }

    private data class PlanImplementationControl(
        val id: String,
        val planDigest: String,
    )

    private data class GoalContinuationControl(
        val id: String,
        val goalId: String,
        val generation: Int,
        val turnIndex: Int,
        val trigger: String,
    )

    private data class SkillInvocationControl(
        val id: String,
        val name: String,
        val additionalInstructions: String?,
    )

    private data class ProviderWebActivityRecord(
        val requestId: String,
        val assistantAnchor: String,
        val event: JsonObject,
    ) {
        val identity: String = "$assistantAnchor\u0000$requestId"
    }

    private data class TextAttachmentControl(
        val id: String,
        val originalText: String,
        val attachments: List<PiRuntimeTextAttachmentInput>,
    )
}

internal fun automaticTaskTitle(provisionalTitle: String): String {
    val normalized = provisionalTitle
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        .orEmpty()
        .replace(Regex("\\s+"), " ")
        .replace(
            Regex(
                "^(please\\s+|can you\\s+|could you\\s+|would you\\s+|" +
                    "i need you to\\s+|i want you to\\s+|请|帮我|麻烦你)",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        .trim()
    val phrase = normalized.split(Regex("[.!?。！？\\n]"), limit = 2).first()
        .trim()
        .ifBlank { normalized }
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    val count = phrase.codePointCount(0, phrase.length)
    if (count <= 56) return phrase.ifBlank { "Untitled task" }
    val end = phrase.offsetByCodePoints(0, 56)
    return phrase.substring(0, end).trimEnd() + "…"
}
