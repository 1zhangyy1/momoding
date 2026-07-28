package app.momoding.core.data

import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.TaskRunState
import app.momoding.core.policy.TaskApprovalMode
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class OutboundCommandState {
    ACCEPTED,
    TERMINAL,
}

enum class StopFenceState {
    ACTIVE,
    RELEASED,
}

data class OutboundCommandRecord(
    val requestId: String,
    val commandId: String?,
    val kind: String,
    val taskId: String?,
    val canonicalPayload: String,
    val state: OutboundCommandState,
    val responseJson: String?,
    val stopFenceState: StopFenceState?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class DraftRecord(
    val draftId: String,
    val text: String,
    val selectedHostId: String?,
    val selectedModelId: String?,
    val selectedMode: String?,
    val selectedGrantId: String? = null,
    val createCommandId: String,
    val promptCommandId: String,
    val taskId: String?,
    val updatedAtMillis: Long,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val attemptOrdinal: Long = 0,
    val approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
)

class JournalConflictException(message: String) : IllegalStateException(message)

interface CommandDurabilityJournal {
    fun persistAccepted(
        requestId: String,
        commandId: String?,
        kind: String,
        taskId: String?,
        canonicalPayload: String,
    ): OutboundCommandRecord

    fun markTerminal(requestId: String, canonicalResponseJson: String): OutboundCommandRecord
}

/** Durable send-intent and composer state used before any mutating command is put on the wire. */
class RoomCommandDraftJournal(
    private val database: MomodingDatabase,
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CommandDurabilityJournal {
    private val dao = database.momodingDao()

    override fun persistAccepted(
        requestId: String,
        commandId: String?,
        kind: String,
        taskId: String?,
        canonicalPayload: String,
    ): OutboundCommandRecord = database.runInTransaction<OutboundCommandRecord> {
        requireToken(requestId, "requestId")
        requireToken(kind, "kind")
        val stableCommandId = commandIdentity(kind, commandId)
        taskId?.let { requireToken(it, "taskId") }
        requireCommandPayload(kind, taskId, canonicalPayload, "command payload")

        val timestamp = nowMillis()
        val exact = OutboundCommandEntity(
            requestId = requestId,
            commandId = stableCommandId,
            kind = kind,
            taskId = taskId,
            canonicalPayload = canonicalPayload,
            payloadSha256 = sha256(canonicalPayload),
            state = OutboundCommandState.ACCEPTED.name,
            responseJson = null,
            responseSha256 = null,
            stopFenceState = if (kind == STOP_KIND) StopFenceState.ACTIVE.name else null,
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp,
        )
        dao.outboundCommand(requestId)?.let { existing ->
            existing.validate()
            if (!existing.sameIntent(exact)) {
                throw JournalConflictException("requestId is already bound to a different command intent")
            }
            return@runInTransaction existing.toRecord()
        }
        stableCommandId?.let { stableId ->
            dao.outboundCommandByCommandId(stableId)?.let { existing ->
                existing.validate()
                throw JournalConflictException(
                    "commandId is already bound to requestId ${existing.requestId}",
                )
            }
        }
        dao.insertOutboundCommand(exact)
        exact.toRecord()
    }

    override fun markTerminal(
        requestId: String,
        canonicalResponseJson: String,
    ): OutboundCommandRecord = database.runInTransaction<OutboundCommandRecord> {
        requireToken(requestId, "requestId")
        val response = requireCommandResponse(canonicalResponseJson, requestId)
        val existing = dao.outboundCommand(requestId)
            ?: throw JournalConflictException("Cannot complete an unknown outbound command")
        existing.validate()
        if (existing.state == OutboundCommandState.TERMINAL.name) {
            if (existing.responseJson != canonicalResponseJson) {
                throw JournalConflictException("Terminal command response cannot be changed")
            }
            return@runInTransaction existing.toRecord()
        }
        if (existing.state != OutboundCommandState.ACCEPTED.name || existing.responseJson != null) {
            throw JournalConflictException("Outbound command has an invalid durable state")
        }
        val terminal = existing.copy(
            state = OutboundCommandState.TERMINAL.name,
            responseJson = canonicalResponseJson,
            responseSha256 = sha256(canonicalResponseJson),
            stopFenceState = terminalStopFenceState(existing, response),
            updatedAtMillis = nowMillis(),
        )
        check(dao.updateOutboundCommand(terminal) == 1) { "Outbound command update was lost" }
        terminal.toRecord()
    }

    fun command(requestId: String): OutboundCommandRecord? =
        database.runInTransaction<OutboundCommandRecord?> {
            dao.outboundCommand(requestId)?.also { it.validate() }?.toRecord()
        }

    fun commandByCommandId(commandId: String): OutboundCommandRecord? =
        database.runInTransaction<OutboundCommandRecord?> {
            requireToken(commandId, "commandId")
            dao.outboundCommandByCommandId(commandId)?.also { it.validate() }?.toRecord()
        }

    fun pendingCommands(): List<OutboundCommandRecord> =
        database.runInTransaction<List<OutboundCommandRecord>> {
            dao.outboundCommands()
                .onEach { it.validate() }
                .filter { it.state == OutboundCommandState.ACCEPTED.name }
                .map { it.toRecord() }
        }

    fun hasPendingStopFence(taskId: String): Boolean =
        database.runInTransaction<Boolean> {
            requireToken(taskId, "taskId")
            dao.outboundCommands()
                .onEach { it.validate() }
                .any { command ->
                    command.taskId == taskId &&
                        command.kind == STOP_KIND &&
                        command.stopFenceState == StopFenceState.ACTIVE.name
                }
        }

    fun saveDraft(record: DraftRecord): DraftRecord = database.runInTransaction<DraftRecord> {
        val canonical = record.repairInvalidSelection().copy(
            createCommandId = normalizeUuid(record.createCommandId, "createCommandId"),
            promptCommandId = normalizeUuid(record.promptCommandId, "promptCommandId"),
        )
        canonical.validate()
        val existing = dao.draft(canonical.draftId)
        if (existing == null) {
            if (canonical.taskId != null) {
                throw JournalConflictException("A new draft must be bound through bindDraftTask")
            }
            dao.insertDraft(canonical.toEntity())
            return@runInTransaction canonical
        }
        existing.validate()
        if (existing.createCommandId != canonical.createCommandId ||
            existing.promptCommandId != canonical.promptCommandId ||
            existing.attemptOrdinal != canonical.attemptOrdinal
        ) {
            throw JournalConflictException("Draft command IDs and attempt ordinal are immutable")
        }
        if (canonical.taskId != existing.taskId) {
            throw JournalConflictException("Draft binding can only change through bindDraftTask")
        }
        if (existing.taskId != null && canonical.selectedGrantId != existing.selectedGrantId) {
            throw JournalConflictException("A task-bound mobile folder grant cannot be changed")
        }
        if (existing.taskId != null && canonical.approvalMode != existing.approvalMode) {
            throw JournalConflictException("A task-bound approval mode cannot be changed through its draft")
        }
        val updated = canonical.copy(
            taskId = existing.taskId ?: canonical.taskId,
            updatedAtMillis = nowMillis(),
        )
        check(dao.updateDraft(updated.toEntity()) == 1) { "Draft update was lost" }
        updated
    }

    fun bindDraftTask(draftId: String, taskId: String): DraftRecord =
        database.runInTransaction<DraftRecord> {
            requireToken(draftId, "draftId")
            requireToken(taskId, "taskId")
            val existing = dao.draft(draftId)
                ?: throw JournalConflictException("Cannot bind an unknown draft")
            existing.validate()
            if (existing.taskId == taskId) return@runInTransaction existing.toRecord()
            if (existing.taskId != null) {
                throw JournalConflictException("Draft is already bound to another task")
            }
            dao.draftForTaskSync(taskId)?.let { claimed ->
                throw JournalConflictException(
                    "Task is already bound to draft ${claimed.draftId}",
                )
            }
            dao.task(taskId)?.let { task ->
                if (task.approvalMode != existing.approvalMode) {
                    check(dao.updateTaskApprovalMode(taskId, existing.approvalMode) == 1) {
                        "Task approval mode copy was lost"
                    }
                }
            }
            val updated = existing.copy(taskId = taskId, updatedAtMillis = nowMillis())
            check(dao.updateDraft(updated) == 1) { "Draft binding update was lost" }
            updated.toRecord()
        }

    fun draft(draftId: String): DraftRecord? = database.runInTransaction<DraftRecord?> {
        dao.draft(draftId)?.repairSelectionDurably()?.also { it.validate() }?.toRecord()
    }

    /**
     * The only post-bind grant mutation path. It is called after the user explicitly selects a
     * new SAF tree for the running task; ordinary draft saves keep the grant immutable.
     */
    fun rebindTaskGrantFromExplicitSafSelection(
        taskId: String,
        grantId: String,
    ): DraftRecord = database.runInTransaction<DraftRecord> {
        val canonicalTaskId = normalizeUuid(taskId, "taskId")
        val canonicalGrantId = normalizeUuid(grantId, "grantId")
        val existing = dao.draftForTaskSync(canonicalTaskId)
            ?.repairSelectionDurably()
            ?: throw JournalConflictException("Cannot bind a folder to an unknown task draft")
        existing.validate()
        val timestamp = nowMillis()
        check(
            dao.updateTaskDraftGrant(
                taskId = canonicalTaskId,
                grantId = canonicalGrantId,
                updatedAtMillis = timestamp,
            ) == 1,
        ) {
            "Task folder grant update was lost"
        }
        existing.copy(
            selectedGrantId = canonicalGrantId,
            updatedAtMillis = timestamp,
        ).toRecord()
    }

    fun drafts(): List<DraftRecord> = database.runInTransaction<List<DraftRecord>> {
        dao.drafts().map { entity ->
            entity.repairSelectionDurably().also { it.validate() }.toRecord()
        }
    }

    /** Rotates identity only after a terminal retryable create or prompt response. */
    fun rotateRetryableAttempt(draftId: String, expectedOrdinal: Long): DraftRecord =
        database.runInTransaction<DraftRecord> {
            requireToken(draftId, "draftId")
            val existing = dao.draft(draftId)
                ?.repairSelectionDurably()
                ?: throw JournalConflictException("Cannot retry an unknown draft")
            existing.validate()
            if (existing.attemptOrdinal != expectedOrdinal) {
                throw JournalConflictException("Draft retry ordinal changed")
            }

            val create = dao.outboundCommandByCommandId(existing.createCommandId)
            val prompt = dao.outboundCommandByCommandId(existing.promptCommandId)
            val newCreateId: String
            val newPromptId: String
            if (existing.taskId == null) {
                requireRetryableFailure(create, "task.create", null, "create")
                if (prompt != null) {
                    throw JournalConflictException("Create-stage retry found an existing prompt command")
                }
                newCreateId = freshCommandId(existing.createCommandId, existing.promptCommandId)
                newPromptId = freshCommandId(
                    existing.createCommandId,
                    existing.promptCommandId,
                    newCreateId,
                )
            } else {
                requireSuccessfulCreate(create, existing.taskId)
                requireRetryableFailure(prompt, "session.prompt", existing.taskId, "prompt")
                newCreateId = existing.createCommandId
                newPromptId = freshCommandId(existing.createCommandId, existing.promptCommandId)
            }

            val updated = existing.copy(
                createCommandId = newCreateId,
                promptCommandId = newPromptId,
                attemptOrdinal = existing.attemptOrdinal + 1,
                updatedAtMillis = nowMillis(),
            )
            check(dao.updateDraft(updated) == 1) { "Draft retry update was lost" }
            updated.toRecord()
        }

    private fun DraftEntity.repairSelectionDurably(): DraftEntity {
        val repaired = repairInvalidSelection()
        if (repaired != this) {
            check(dao.updateDraft(repaired) == 1) { "Draft selection repair was lost" }
        }
        return repaired
    }

    private fun requireRetryableFailure(
        command: OutboundCommandEntity?,
        expectedKind: String,
        expectedTaskId: String?,
        stage: String,
    ) {
        val exact = command ?: throw JournalConflictException("$stage command is missing")
        exact.validate()
        if (exact.kind != expectedKind || exact.taskId != expectedTaskId) {
            throw JournalConflictException("$stage command binding does not match the draft")
        }
        if (exact.state != OutboundCommandState.TERMINAL.name) {
            throw JournalConflictException("$stage command is still pending")
        }
        val response = requireCommandResponse(
            exact.responseJson ?: throw JournalConflictException("$stage response is missing"),
            exact.requestId,
        )
        if (response.ok || response.error?.retryable != true) {
            throw JournalConflictException("$stage command is not retryable")
        }
    }

    private fun requireSuccessfulCreate(command: OutboundCommandEntity?, taskId: String) {
        val exact = command ?: throw JournalConflictException("create command is missing")
        exact.validate()
        if (exact.kind != "task.create" || exact.taskId != null) {
            throw JournalConflictException("create command binding does not match the draft")
        }
        if (exact.state != OutboundCommandState.TERMINAL.name) {
            throw JournalConflictException("create command is still pending")
        }
        val response = requireCommandResponse(
            exact.responseJson ?: throw JournalConflictException("create response is missing"),
            exact.requestId,
        )
        if (!response.ok) throw JournalConflictException("create command did not succeed")
        val responseTaskId = (response.data as? JsonObject)
            ?.get("taskId")
            ?.let { it as? JsonPrimitive }
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
        if (responseTaskId != taskId) {
            throw JournalConflictException("create response task binding does not match the draft")
        }
    }

    private fun freshCommandId(vararg excluded: String): String {
        val candidate = normalizeUuid(commandIdFactory(), "generated commandId")
        if (candidate in excluded || dao.outboundCommandByCommandId(candidate) != null) {
            throw JournalConflictException("Generated commandId is not fresh")
        }
        return candidate
    }

    private fun OutboundCommandEntity.sameIntent(other: OutboundCommandEntity): Boolean =
        requestId == other.requestId &&
            commandId == other.commandId &&
            kind == other.kind &&
            taskId == other.taskId &&
            canonicalPayload == other.canonicalPayload &&
            payloadSha256 == other.payloadSha256

    private fun OutboundCommandEntity.validate() {
        requireToken(requestId, "durable requestId")
        requireToken(kind, "durable command kind")
        val canonicalCommandId = try {
            commandIdentity(kind, commandId)
        } catch (error: IllegalArgumentException) {
            throw JournalConflictException("Durable command identity is invalid: ${error.message}")
        }
        if (canonicalCommandId != commandId) {
            throw JournalConflictException("Durable commandId is not canonical")
        }
        taskId?.let { requireToken(it, "durable taskId") }
        requireCommandPayload(kind, taskId, canonicalPayload, "durable command payload")
        if (payloadSha256 != sha256(canonicalPayload)) {
            throw JournalConflictException("Durable command payload digest is invalid")
        }
        val parsedState = try {
            OutboundCommandState.valueOf(state)
        } catch (error: IllegalArgumentException) {
            throw JournalConflictException("Durable command state is invalid")
        }
        var releaseBackedByStoppedResponse = false
        when (parsedState) {
            OutboundCommandState.ACCEPTED -> if (responseJson != null || responseSha256 != null) {
                throw JournalConflictException("Accepted command cannot have a terminal response")
            }
            OutboundCommandState.TERMINAL -> {
                val response = responseJson
                    ?: throw JournalConflictException("Terminal command is missing its response")
                val decoded = try {
                    requireCommandResponse(response, requestId)
                } catch (error: IllegalArgumentException) {
                    throw JournalConflictException("Durable command response is invalid: ${error.message}")
                }
                if (responseSha256 != sha256(response)) {
                    throw JournalConflictException("Durable command response digest is invalid")
                }
                if (kind == STOP_KIND) {
                    releaseBackedByStoppedResponse =
                        stopResponseRunState(decoded) == TaskRunState.STOPPED
                    if (releaseBackedByStoppedResponse &&
                        stopFenceState != StopFenceState.RELEASED.name
                    ) {
                        throw JournalConflictException("Stopped response must release its durable fence")
                    }
                }
            }
        }
        val parsedFence = stopFenceState?.let { value ->
            try {
                StopFenceState.valueOf(value)
            } catch (error: IllegalArgumentException) {
                throw JournalConflictException("Durable stop-fence state is invalid")
            }
        }
        if (kind == STOP_KIND && parsedFence == null) {
            throw JournalConflictException("Durable stop command is missing its fence state")
        }
        if (kind != STOP_KIND && parsedFence != null) {
            throw JournalConflictException("Non-stop command cannot own a stop fence")
        }
        if (kind == STOP_KIND && parsedFence == StopFenceState.RELEASED &&
            !releaseBackedByStoppedResponse &&
            taskId?.let { dao.task(it)?.runState == TaskRunState.STOPPED.name } != true
        ) {
            throw JournalConflictException(
                "Released stop fence is missing durable STOPPED proof",
            )
        }
        if (createdAtMillis < 0 || updatedAtMillis < createdAtMillis) {
            throw JournalConflictException("Durable command timestamps are invalid")
        }
    }

    private fun DraftRecord.validate() {
        requireToken(draftId, "draftId")
        if (normalizeUuid(createCommandId, "createCommandId") != createCommandId ||
            normalizeUuid(promptCommandId, "promptCommandId") != promptCommandId
        ) {
            throw IllegalArgumentException("Draft command IDs must use canonical UUID encoding")
        }
        if (createCommandId == promptCommandId) {
            throw IllegalArgumentException("Create and prompt command IDs must differ")
        }
        taskId?.let { requireToken(it, "taskId") }
        selectedGrantId?.let { normalizeUuid(it, "selectedGrantId") }
        if (!hasValidUtf16Selection(text, selectionStart, selectionEnd)) {
            throw IllegalArgumentException("Draft selection is invalid")
        }
        if (attemptOrdinal < 0) throw IllegalArgumentException("Draft attempt ordinal is invalid")
        if (updatedAtMillis < 0) throw IllegalArgumentException("Draft timestamp is invalid")
    }

    private fun DraftEntity.validate() = toRecord().validate()

    private fun OutboundCommandEntity.toRecord() = OutboundCommandRecord(
        requestId = requestId,
        commandId = commandId,
        kind = kind,
        taskId = taskId,
        canonicalPayload = canonicalPayload,
        state = OutboundCommandState.valueOf(state),
        responseJson = responseJson,
        stopFenceState = stopFenceState?.let(StopFenceState::valueOf),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    private fun DraftRecord.toEntity() = DraftEntity(
        draftId = draftId,
        text = text,
        selectedHostId = selectedHostId,
        selectedModelId = selectedModelId,
        selectedMode = selectedMode,
        selectedGrantId = selectedGrantId,
        createCommandId = createCommandId,
        promptCommandId = promptCommandId,
        taskId = taskId,
        updatedAtMillis = updatedAtMillis,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        attemptOrdinal = attemptOrdinal,
        approvalMode = approvalMode,
    )

    private fun DraftEntity.toRecord() = DraftRecord(
        draftId = draftId,
        text = text,
        selectedHostId = selectedHostId,
        selectedModelId = selectedModelId,
        selectedMode = selectedMode,
        selectedGrantId = selectedGrantId,
        createCommandId = createCommandId,
        promptCommandId = promptCommandId,
        taskId = taskId,
        updatedAtMillis = updatedAtMillis,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        attemptOrdinal = attemptOrdinal,
        approvalMode = approvalMode,
    )

    private companion object {
        const val STOP_KIND = "session.stop"

        val mutatingKinds = setOf(
            "task.create",
            "session.prompt",
            "session.steer",
            "session.follow_up",
            STOP_KIND,
            "device.capabilities.report",
            "device.tool.reconcile.result",
        )

        val readOnlyKinds = setOf(
            "task.list",
            "task.open",
            "task.snapshot.request",
            "task.history.request",
        )

        val taskBoundKinds = setOf(
            "task.open",
            "task.snapshot.request",
            "task.history.request",
            "session.prompt",
            "session.steer",
            "session.follow_up",
            STOP_KIND,
            "device.tool.reconcile.result",
        )

        val uuidPattern =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

        val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        fun requireCanonicalObject(value: String, label: String): JsonObject {
            val parsed = requireCanonicalJson(value, label)
            if (parsed !is JsonObject) throw IllegalArgumentException("$label must be a JSON object")
            return parsed
        }

        fun requireCommandPayload(kind: String, taskId: String?, value: String, label: String) {
            val payload = requireCanonicalObject(value, label)
            val payloadKind = (payload["kind"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
            if (payloadKind != kind) throw IllegalArgumentException("$label kind does not match $kind")
            if (kind in taskBoundKinds) {
                val requiredTaskId = taskId
                    ?: throw IllegalArgumentException("$kind requires taskId")
                val payloadTaskId = (payload["taskId"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                if (payloadTaskId != requiredTaskId) {
                    throw IllegalArgumentException("$label taskId does not match $kind")
                }
            }
        }

        fun commandIdentity(kind: String, commandId: String?): String? = when (kind) {
            in mutatingKinds -> normalizeUuid(
                commandId ?: throw IllegalArgumentException("$kind requires commandId"),
                "commandId",
            )
            in readOnlyKinds -> {
                if (commandId != null) {
                    throw IllegalArgumentException("$kind must not carry commandId")
                }
                null
            }
            else -> throw IllegalArgumentException("Unsupported durable command kind: $kind")
        }

        fun normalizeUuid(value: String, label: String): String {
            if (!uuidPattern.matches(value)) throw IllegalArgumentException("$label must be a UUID")
            return UUID.fromString(value).toString()
        }

        fun requireCommandResponse(value: String, expectedRequestId: String): CommandResponseFrame {
            requireCanonicalJson(value, "command response")
            val frame = try {
                ReliabilityContractDecoder.decode(value).frame
            } catch (error: Exception) {
                throw IllegalArgumentException("command response violates the shared Wire schema", error)
            }
            val response = frame as? CommandResponseFrame
                ?: throw IllegalArgumentException("command response must have kind=response")
            if (response.requestId != expectedRequestId) {
                throw IllegalArgumentException("Response requestId does not match its command")
            }
            return response
        }

        fun terminalStopFenceState(
            existing: OutboundCommandEntity,
            response: CommandResponseFrame,
        ): String? {
            if (existing.kind != STOP_KIND) return null
            return if (stopResponseRunState(response) == TaskRunState.STOPPED) {
                StopFenceState.RELEASED.name
            } else {
                existing.stopFenceState
            }
        }

        fun stopResponseRunState(response: CommandResponseFrame): TaskRunState? {
            if (!response.ok) return null
            val data = response.data as? JsonObject
                ?: throw IllegalArgumentException("Successful stop response data must be an object")
            if (data.keys != setOf("accepted", "runState")) {
                throw IllegalArgumentException("Successful stop response data has unexpected fields")
            }
            val accepted = (data["accepted"] as? JsonPrimitive)?.booleanOrNull
            if (accepted != true) throw IllegalArgumentException("Stop response must be accepted")
            val runState = (data["runState"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
            return when (runState) {
                "stopping" -> TaskRunState.STOPPING
                "stopped" -> TaskRunState.STOPPED
                else -> throw IllegalArgumentException("Stop response runState is invalid")
            }
        }

        fun requireCanonicalJson(value: String, label: String) = try {
            strictJson.parseToJsonElement(value).also { parsed ->
                if (parsed.toString() != value) {
                    throw IllegalArgumentException("$label must use compact canonical JSON encoding")
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalArgumentException("$label is invalid JSON", error)
        }

        fun requireToken(value: String, label: String) {
            if (value.isBlank()) throw IllegalArgumentException("$label is blank")
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
