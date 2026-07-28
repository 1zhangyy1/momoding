package app.momoding.core.runtime.local

import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.PiSessionSnapshotEntity
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PhoneLocalTaskPlanState(
    val enabled: Boolean = false,
    val activeToolNames: List<String> = emptyList(),
    val prePlanActiveToolNames: List<String>? = null,
    val latestPlan: PiTaskPlanSnapshot? = null,
    val actionPending: Boolean = false,
    val error: String? = null,
) {
    val canImplement: Boolean get() = enabled && latestPlan != null && !actionPending
}

data class PersistedPiTaskSession(
    val taskId: String,
    val piSessionId: String,
    val streamId: String,
    val snapshot: PiNativeTaskSessionSnapshot,
    val updatedAtMillis: Long,
)

/**
 * Durable storage for Pi's own settled Session entries.
 *
 * Pi's entry structure is preserved exactly. Image payload bytes are replaced with opaque
 * attachment references. Text attachments persist only bounded metadata until attachment_read
 * returns content inside the task's normal Pi tool history.
 */
class PhoneLocalPiSessionStore(
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun save(
        taskId: String,
        piSessionId: String,
        snapshot: PiNativeTaskSessionSnapshot,
    ) {
        database.runInTransaction {
            saveInCurrentTransaction(taskId, piSessionId, snapshot)
        }
    }

    internal fun saveInCurrentTransaction(
        taskId: String,
        piSessionId: String,
        snapshot: PiNativeTaskSessionSnapshot,
    ) {
        check(snapshot.taskId == taskId) { "PI_MOBILE_SESSION_SNAPSHOT_TASK_MISMATCH" }
        val task = requireNotNull(database.p2Dao().task(taskId)) {
            "PI_MOBILE_TASK_NOT_FOUND"
        }
        check(task.piSessionId == piSessionId) {
            "PI_MOBILE_SESSION_SNAPSHOT_SESSION_MISMATCH"
        }
        validateEntries(snapshot.entries)
        database.p2Dao().upsertPiSessionSnapshot(
            PiSessionSnapshotEntity(
                taskId = taskId,
                piSessionId = piSessionId,
                entriesJson = snapshot.entries.toString(),
                schemaVersion = SCHEMA_VERSION,
                updatedAtMillis = nowMillis(),
            ),
        )
    }

    fun load(taskId: String): PersistedPiTaskSession? {
        val entity = database.p2Dao().piSessionSnapshot(taskId) ?: return null
        val task = requireNotNull(database.p2Dao().task(taskId)) {
            "PI_MOBILE_TASK_NOT_FOUND"
        }
        check(entity.schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
            "PI_MOBILE_SESSION_SNAPSHOT_SCHEMA_UNSUPPORTED"
        }
        check(task.piSessionId == entity.piSessionId) {
            "PI_MOBILE_SESSION_SNAPSHOT_SESSION_MISMATCH"
        }
        val streamId = requireNotNull(task.streamId) {
            "PI_MOBILE_SESSION_SNAPSHOT_STREAM_MISSING"
        }
        val entries = try {
            json.parseToJsonElement(entity.entriesJson) as? JsonArray
                ?: error("PI_MOBILE_SESSION_SNAPSHOT_ENTRIES_NOT_ARRAY")
        } catch (error: Throwable) {
            throw IllegalStateException("PI_MOBILE_SESSION_SNAPSHOT_CORRUPT", error)
        }
        try {
            validateEntries(entries)
        } catch (error: Throwable) {
            throw IllegalStateException("PI_MOBILE_SESSION_SNAPSHOT_CORRUPT", error)
        }
        return PersistedPiTaskSession(
            taskId = taskId,
            piSessionId = entity.piSessionId,
            streamId = streamId,
            snapshot = taskSessionSnapshot(taskId, entries),
            updatedAtMillis = entity.updatedAtMillis,
        )
    }

    private fun taskSessionSnapshot(taskId: String, entries: JsonArray): PiNativeTaskSessionSnapshot {
        val plan = taskPlanStateFromEntries(entries)
        val goal = taskGoalSnapshotFromEntries(entries)
        return PiNativeTaskSessionSnapshot(
            taskId = taskId,
            turnCount = entries.count(::isUserMessage),
            entries = entries,
            planMode = plan.enabled,
            activeToolNames = plan.activeToolNames,
            prePlanActiveToolNames = plan.prePlanActiveToolNames,
            latestPlan = plan.latestPlan,
            goal = goal,
        )
    }

    private fun validateEntries(entries: JsonArray) {
        check(entries.isNotEmpty()) { "PI_MOBILE_SESSION_SNAPSHOT_ENTRIES_EMPTY" }
        val ids = mutableSetOf<String>()
        entries.forEach { element ->
            val entry = element as? JsonObject
                ?: error("PI_MOBILE_SESSION_SNAPSHOT_ENTRY_INVALID")
            val id = (entry["id"] as? JsonPrimitive)?.content
                ?.takeIf(String::isNotBlank)
                ?: error("PI_MOBILE_SESSION_SNAPSHOT_ENTRY_INVALID")
            (entry["type"] as? JsonPrimitive)?.content
                ?.takeIf(String::isNotBlank)
                ?: error("PI_MOBILE_SESSION_SNAPSHOT_ENTRY_INVALID")
            (entry["timestamp"] as? JsonPrimitive)?.content
                ?.takeIf(String::isNotBlank)
                ?: error("PI_MOBILE_SESSION_SNAPSHOT_ENTRY_INVALID")
            check(ids.add(id)) { "PI_MOBILE_SESSION_SNAPSHOT_ENTRY_ID_DUPLICATED" }
            val parent = (entry["parentId"] as? JsonPrimitive)?.contentOrNull
            check(parent == null || parent in ids) {
                "PI_MOBILE_SESSION_SNAPSHOT_PARENT_INVALID"
            }
            validatePersistedImageReferences(entry)
            validatePersistedTextAttachmentControl(entry)
        }
    }

    private fun isUserMessage(element: kotlinx.serialization.json.JsonElement): Boolean {
        val entry = element as? JsonObject ?: return false
        if ((entry["type"] as? JsonPrimitive)?.content != "message") return false
        val message = entry["message"] as? JsonObject ?: return false
        return (message["role"] as? JsonPrimitive)?.content == "user"
    }

    companion object {
        const val SCHEMA_VERSION: Int = 2
        private val SUPPORTED_SCHEMA_VERSIONS = setOf(1, SCHEMA_VERSION)
        private val IMAGE_REFERENCE = Regex(
            "^attachment:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        private const val TEXT_ATTACHMENT_CONTROL = "pi_mobile_text_attachments"
        private val ATTACHMENT_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
    }

    private fun validatePersistedImageReferences(element: kotlinx.serialization.json.JsonElement) {
        when (element) {
            is JsonArray -> element.forEach(::validatePersistedImageReferences)
            is JsonObject -> {
                val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                if (type == "image") {
                    val data = (element["data"] as? JsonPrimitive)?.contentOrNull
                    val mimeType = (element["mimeType"] as? JsonPrimitive)?.contentOrNull
                    check(data != null && IMAGE_REFERENCE.matches(data)) {
                        "PI_MOBILE_SESSION_IMAGE_DATA_NOT_REDACTED"
                    }
                    check(mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
                        "PI_MOBILE_SESSION_IMAGE_MIME_INVALID"
                    }
                }
                element.values.forEach(::validatePersistedImageReferences)
            }
            else -> Unit
        }
    }

    private fun validatePersistedTextAttachmentControl(entry: JsonObject) {
        if ((entry["type"] as? JsonPrimitive)?.contentOrNull != "custom") return
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != TEXT_ATTACHMENT_CONTROL) return
        val data = entry["data"] as? JsonObject
            ?: error("PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID")
        check(data.keys == setOf("kind", "originalText", "attachments")) {
            "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
        }
        check((data["kind"] as? JsonPrimitive)?.contentOrNull == "text_attachments") {
            "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
        }
        val originalText = (data["originalText"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
        check(originalText != null && originalText.length <= 65_536 && '\u0000' !in originalText) {
            "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
        }
        val attachments = data["attachments"] as? JsonArray
            ?: error("PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID")
        check(attachments.size in 1..5) { "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID" }
        val ids = mutableSetOf<String>()
        attachments.forEach { element ->
            val value = element as? JsonObject
                ?: error("PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID")
            check(value.keys == setOf("attachmentId", "displayName", "mimeType", "byteSize")) {
                "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
            }
            val attachmentId = (value["attachmentId"] as? JsonPrimitive)?.contentOrNull
            check(attachmentId != null && ATTACHMENT_ID.matches(attachmentId) && ids.add(attachmentId)) {
                "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
            }
            val displayName = (value["displayName"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
            check(
                displayName != null && displayName.length in 1..240 && '\u0000' !in displayName
            ) { "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID" }
            val mimeType = (value["mimeType"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
            check(mimeType != null && mimeType.length in 1..128 && '\u0000' !in mimeType) {
                "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
            }
            val byteSize = (value["byteSize"] as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.longOrNull
            check(byteSize != null && byteSize in 1..4L * 1024L * 1024L) {
                "PI_MOBILE_SESSION_TEXT_ATTACHMENT_CONTROL_INVALID"
            }
        }
    }
}

internal fun taskGoalSnapshotFromEntries(entries: JsonArray): PiTaskGoalSnapshot? {
    var latest: PiTaskGoalSnapshot? = null
    entries.forEach { element ->
        val entry = element as? JsonObject ?: return@forEach
        if ((entry["type"] as? JsonPrimitive)?.contentOrNull != "custom") return@forEach
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != "pi_mobile_task_goal") {
            return@forEach
        }
        parseGoalSnapshot(entry["data"])?.let { latest = it }
    }
    return latest
}

private fun parseGoalSnapshot(element: kotlinx.serialization.json.JsonElement?): PiTaskGoalSnapshot? {
    val value = element as? JsonObject ?: return null
    val goalId = (value["goalId"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) }
        ?: return null
    val instruction = (value["instruction"] as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.takeIf { it.length in 1..4096 }
        ?: return null
    val state = (value["state"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it in setOf("active", "paused", "blocked", "limited", "failed", "achieved", "cleared") }
        ?: return null
    val progressSummary = value.nullableString("progressSummary")
        ?.takeIf { it.length <= 4096 }
        ?: if (value["progressSummary"] is kotlinx.serialization.json.JsonNull) null else return null
    val progressMarker = value.nullableString("progressMarker")
        ?.takeIf { it.length in 1..128 }
        ?: if (value["progressMarker"] is kotlinx.serialization.json.JsonNull) null else return null
    val terminalReason = value.nullableString("terminalReason")
        ?.takeIf { it.length <= 128 }
        ?: if (value["terminalReason"] is kotlinx.serialization.json.JsonNull) null else return null
    val generation = (value["generation"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: return null
    val startedAtMillis = (value["startedAtMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: return null
    val preGoalTools = (value["preGoalActiveToolNames"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.distinct().size == it.size }
        ?: return null
    return PiTaskGoalSnapshot(
        goalId = goalId,
        instruction = instruction,
        state = state,
        progressSummary = progressSummary,
        progressMarker = progressMarker,
        terminalReason = terminalReason,
        generation = generation,
        startedAtMillis = startedAtMillis,
        preGoalActiveToolNames = preGoalTools,
    )
}

private fun JsonObject.nullableString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun taskPlanStateFromEntries(entries: JsonArray): PhoneLocalTaskPlanState {
    var enabled = false
    var prePlanTools: List<String>? = null
    var activeTools = emptyList<String>()
    var latestPlan: PiTaskPlanSnapshot? = null
    entries.forEach { element ->
        val entry = element as? JsonObject ?: return@forEach
        when ((entry["type"] as? JsonPrimitive)?.contentOrNull) {
            "active_tools_change" -> {
                activeTools = (entry["activeToolNames"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
            }
            "custom" -> when ((entry["customType"] as? JsonPrimitive)?.contentOrNull) {
                "pi_mobile_plan_mode" -> {
                    val data = entry["data"] as? JsonObject ?: return@forEach
                    when ((data["enabled"] as? JsonPrimitive)?.contentOrNull) {
                        "true" -> {
                            val prior = (data["prePlanActiveToolNames"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                ?.takeIf { it.distinct().size == it.size }
                            if (prior != null) {
                                enabled = true
                                prePlanTools = prior
                            }
                        }
                        "false" -> {
                            enabled = false
                            prePlanTools = null
                        }
                    }
                }
                "pi_mobile_task_plan" -> parsePlanSnapshot(entry["data"])?.let { latestPlan = it }
            }
        }
    }
    return PhoneLocalTaskPlanState(
        enabled = enabled,
        activeToolNames = activeTools,
        prePlanActiveToolNames = prePlanTools,
        latestPlan = latestPlan,
    )
}

private fun parsePlanSnapshot(element: kotlinx.serialization.json.JsonElement?): PiTaskPlanSnapshot? {
    val value = element as? JsonObject ?: return null
    val explanation = (value["explanation"] as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 4_096 }
        ?: return null
    val steps = (value["steps"] as? JsonArray)?.map { stepElement ->
        val step = stepElement as? JsonObject ?: return null
        val id = (step["id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")) }
            ?: return null
        val text = (step["text"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 1_024 }
            ?: return null
        val status = (step["status"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it in setOf("pending", "in_progress", "completed") }
            ?: return null
        PiTaskPlanStep(id, text, status)
    }?.takeIf { it.size in 1..12 && it.map(PiTaskPlanStep::id).distinct().size == it.size }
        ?: return null
    val digest = (value["planDigest"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
        ?: return null
    val canonical = buildJsonObject {
        put("explanation", explanation)
        put("steps", buildJsonArray {
            steps.forEach { step ->
                add(buildJsonObject {
                    put("id", step.id)
                    put("text", step.text)
                    put("status", step.status)
                })
            }
        })
    }.toString()
    val actual = MessageDigest.getInstance("SHA-256")
        .digest(canonical.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return PiTaskPlanSnapshot(explanation, steps, digest).takeIf { digest == actual }
}
