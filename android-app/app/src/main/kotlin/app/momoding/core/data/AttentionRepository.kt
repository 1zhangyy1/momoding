package app.momoding.core.data

import java.time.OffsetDateTime
import app.momoding.core.files.AuthorizedContentReadPolicy
import app.momoding.core.files.AuthorizedFolderListing
import app.momoding.core.files.AuthorizedFoldersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class AttentionPromptKind {
    QUESTION,
    CONFIRMATION,
    CONTENT_READ,
    FILE_CHANGES,
}

data class AttentionOption(
    val label: String,
    val description: String?,
    val recommended: Boolean,
)

sealed interface AttentionPrompt {
    val kind: AttentionPromptKind

    data class Question(
        val question: String,
        val options: List<AttentionOption>,
    ) : AttentionPrompt {
        override val kind = AttentionPromptKind.QUESTION
    }

    data class Confirmation(
        val summary: String,
        val details: String?,
    ) : AttentionPrompt {
        override val kind = AttentionPromptKind.CONFIRMATION
    }

    data class ContentRead(
        val grantId: String,
        val purpose: String,
        val documents: List<ContentReadDocument>,
        val totalMaxBytes: Int,
        val filesVerified: Boolean = false,
    ) : AttentionPrompt {
        override val kind = AttentionPromptKind.CONTENT_READ
    }

    data class FileChanges(
        val preparedId: String,
        val planDigest: String,
    ) : AttentionPrompt {
        override val kind = AttentionPromptKind.FILE_CHANGES
    }
}

/** Trusted, presentation-only Android confirmation classes derived from a durable operation. */
enum class AttentionConfirmationPresentation {
    ANDROID_CALENDAR_LIST_CALENDARS,
    ANDROID_CALENDAR_LIST_EVENTS,
    ANDROID_CALENDAR_CREATE_EVENT,
    ANDROID_CLIPBOARD_GET,
    ANDROID_CLIPBOARD_SET,
    ANDROID_CLIPBOARD_CLEAR,
}

data class ContentReadDocument(
    val alias: String,
    val displayName: String,
    val expectedMimeType: String,
    val maxBytes: Int,
)

enum class AttentionValidationCode(val wireValue: String) {
    ANSWER_REQUIRED("ANSWER_REQUIRED"),
    ANSWER_TOO_LONG("ANSWER_TOO_LONG"),
}

data class AttentionDraft(
    val selectedOptionIndex: Int?,
    val customAnswer: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val validationCode: AttentionValidationCode?,
)

data class AttentionRecord(
    val taskId: String,
    val callId: String,
    val prompt: AttentionPrompt,
    val ledgerState: AttentionLedgerState,
    val deliveryState: AttentionDeliveryState,
    val responseState: AttentionResponseState,
    val terminalKind: AttentionTerminalKind?,
    val draft: AttentionDraft,
    val expiresAtMillis: Long,
    val receivedAtMillis: Long,
    val dismissed: Boolean,
    val activeStopFence: Boolean = false,
    val confirmationPresentation: AttentionConfirmationPresentation? = null,
)

enum class AttentionTerminalKind {
    SUCCEEDED,
    REJECTED,
    TIMED_OUT,
    CANCELLED,
    FAILED,
}

sealed interface AttentionRecordState {
    data object Missing : AttentionRecordState
    data object Corrupt : AttentionRecordState
    data object FailedClosedHidden : AttentionRecordState
    data class Available(val record: AttentionRecord) : AttentionRecordState
}

data class TaskAttentionSummary(
    val taskId: String,
    val callId: String,
    val promptKind: AttentionPromptKind,
    val responseState: AttentionResponseState,
    val receivedAtMillis: Long,
)

data class AttentionDraftWrite(
    val selectedOptionIndex: Int?,
    val customAnswer: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val validationCode: AttentionValidationCode?,
)

interface AttentionDataSource {
    fun observe(taskId: String, callId: String): Flow<AttentionRecordState>
    fun observeTaskAttention(taskId: String): Flow<List<TaskAttentionSummary>>
    suspend fun current(taskId: String, callId: String): AttentionRecordState
    suspend fun saveDraft(taskId: String, callId: String, draft: AttentionDraftWrite)
    suspend fun dismiss(taskId: String, callId: String)
}

/** Exact, task-bound UI access to the Android-authoritative attention ledger. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionRepository(
    private val database: MomodingDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    folders: AuthorizedFoldersRepository? = null,
    private val contentMetadataLoader: suspend (String) -> AuthorizedFolderListing? = { grantId ->
        folders?.metadata(
            grantId = grantId,
            maxDepth = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_DEPTH,
            maxItems = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_ITEMS,
        )
    },
) : AttentionDataSource {
    private val dao = database.momodingDao()
    private val ledger = RoomAttentionLedger(database)

    override fun observe(taskId: String, callId: String): Flow<AttentionRecordState> =
        dao.observeLocalAttention(taskId, callId)
            .mapLatest(::projectExact)
            .catch { emit(AttentionRecordState.Corrupt) }
            .flowOn(ioDispatcher)

    override fun observeTaskAttention(taskId: String): Flow<List<TaskAttentionSummary>> =
        dao.observeLocalTaskAttention(taskId)
            .mapLatest { pairs ->
                pairs.mapNotNull { pair ->
                    val available = projectExact(pair) as? AttentionRecordState.Available
                        ?: return@mapNotNull null
                    val record = available.record
                    TaskAttentionSummary(
                        taskId = record.taskId,
                        callId = record.callId,
                        promptKind = record.prompt.kind,
                        responseState = record.responseState,
                        receivedAtMillis = record.receivedAtMillis,
                    )
                }
            }
            .catch { emit(emptyList()) }
            .flowOn(ioDispatcher)

    override suspend fun current(taskId: String, callId: String): AttentionRecordState =
        withContext(ioDispatcher) { observe(taskId, callId).first() }

    override suspend fun saveDraft(
        taskId: String,
        callId: String,
        draft: AttentionDraftWrite,
    ) = withContext(ioDispatcher) {
        ledger.saveDraft(
            taskId = taskId,
            callId = callId,
            selectedOptionIndex = draft.selectedOptionIndex,
            customAnswer = draft.customAnswer,
            selectionStart = draft.selectionStart,
            selectionEnd = draft.selectionEnd,
            validationCode = draft.validationCode?.wireValue,
        )
        Unit
    }

    override suspend fun dismiss(taskId: String, callId: String) = withContext(ioDispatcher) {
        ledger.dismiss(taskId, callId)
        Unit
    }

    private suspend fun projectExact(pair: LocalAttentionPairEntity?): AttentionRecordState {
        if (pair == null) return AttentionRecordState.Missing
        val attention = pair.attention.singleOrNull() ?: return AttentionRecordState.Corrupt
        return try {
            ledger.validatePair(pair.operation, attention)
            if (pair.operation.ledgerState == AttentionLedgerState.FAILED_CLOSED.name) {
                AttentionRecordState.FailedClosedHidden
            } else {
                AttentionRecordState.Available(
                    resolveContentNames(projectRecord(
                        pair.operation,
                        attention,
                        activeStopFence = pair.outboundCommands.any { command ->
                            command.kind == "session.stop" &&
                                command.stopFenceState == StopFenceState.ACTIVE.name
                        },
                    )),
                )
            }
        } catch (_: IllegalArgumentException) {
            AttentionRecordState.Corrupt
        } catch (_: IllegalStateException) {
            AttentionRecordState.Corrupt
        }
    }

    private suspend fun resolveContentNames(record: AttentionRecord): AttentionRecord {
        val prompt = record.prompt as? AttentionPrompt.ContentRead ?: return record
        val listing = try {
            contentMetadataLoader(prompt.grantId) ?: return record
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return record
        }
        if (listing.truncated) return record
        val metadata = listing.documents.associateBy { it.alias }
        val filesVerified = prompt.documents.all { requested ->
            metadata[requested.alias]?.let { current ->
                AuthorizedContentReadPolicy.isApprovable(
                    metadata = current,
                    expectedMimeType = requested.expectedMimeType,
                    maxBytes = requested.maxBytes,
                )
            } == true
        }
        return record.copy(
            prompt = prompt.copy(
                documents = prompt.documents.map { document ->
                    document.copy(
                        displayName = metadata[document.alias]?.displayName ?: document.displayName,
                    )
                },
                filesVerified = filesVerified,
            ),
        )
    }

    private fun projectRecord(
        operation: DeviceOperationEntity,
        attention: PendingAttentionEntity,
        activeStopFence: Boolean,
    ): AttentionRecord {
        val arguments = STRICT_JSON.parseToJsonElement(
            operation.argumentsCanonicalJson,
        ) as JsonObject
        val prompt = parsePrompt(operation.toolName, arguments)
        if (prompt is AttentionPrompt.Confirmation) {
            require(attention.selectedOptionIndex == null)
            require(attention.customAnswer.isEmpty())
            require(attention.selectionStart == 0 && attention.selectionEnd == 0)
            require(attention.validationCode == null)
        }
        return AttentionRecord(
            taskId = operation.taskId,
            callId = operation.callId,
            prompt = prompt,
            ledgerState = enumValueOf(operation.ledgerState),
            deliveryState = enumValueOf(operation.deliveryState),
            responseState = enumValueOf(attention.responseState),
            terminalKind = operation.terminalKind?.let(::parseTerminalKind),
            draft = AttentionDraft(
                selectedOptionIndex = attention.selectedOptionIndex,
                customAnswer = attention.customAnswer,
                selectionStart = attention.selectionStart,
                selectionEnd = attention.selectionEnd,
                validationCode = attention.validationCode?.let { code ->
                    AttentionValidationCode.entries.single { it.wireValue == code }
                },
            ),
            expiresAtMillis = OffsetDateTime.parse(operation.expiresAt).toInstant().toEpochMilli(),
            receivedAtMillis = operation.receivedAtMillis,
            dismissed = attention.dismissedAtMillis != null,
            activeStopFence = activeStopFence,
            confirmationPresentation = trustedConfirmationPresentation(
                toolName = operation.toolName,
                arguments = arguments,
            ),
        )
    }

    private fun parsePrompt(toolName: String, value: JsonObject): AttentionPrompt {
        return when (toolName) {
            "request_user_question" -> AttentionPrompt.Question(
                question = value.getValue("question").jsonPrimitive.content,
                options = (value["options"] as? JsonArray).orEmpty().map { item ->
                    val option = item as JsonObject
                    AttentionOption(
                        label = option.getValue("label").jsonPrimitive.content,
                        description = option["description"]?.jsonPrimitive?.contentOrNull,
                        recommended = option["recommended"]?.jsonPrimitive?.boolean ?: false,
                    )
                },
            )
            "request_user_confirmation" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value["details"]?.jsonPrimitive?.contentOrNull,
            )
            "device_ui_action" -> AttentionPrompt.Confirmation(
                summary = value["approvalSummary"]?.jsonPrimitive?.contentOrNull ?: when (
                    value.getValue("action").jsonPrimitive.content
                ) {
                    "click" -> "Allow Momoding to click the selected control?"
                    "scroll" -> "Allow Momoding to scroll the selected view?"
                    "input_draft" -> "Allow Momoding to enter draft text?"
                    "back" -> "Allow Momoding to go back?"
                    else -> error("Unsupported interface action")
                },
                details = value["approvalDetails"]?.jsonPrimitive?.contentOrNull
                    ?: "Android will re-check the foreground app, snapshot, and target " +
                        "immediately before acting.",
            )
            "device_media_list" -> AttentionPrompt.Confirmation(
                summary = "Allow Momoding to inspect recent photo metadata?",
                details = buildString {
                    append(value.getValue("purpose").jsonPrimitive.content)
                    append(" Up to ")
                    append(value["limit"]?.jsonPrimitive?.contentOrNull ?: "20")
                    append(" recent photos; no image bytes, names, paths, location, or EXIF data.")
                },
            )
            "device_media" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value.getValue("details").jsonPrimitive.content,
            )
            "device_calendar" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value.getValue("details").jsonPrimitive.content,
            )
            "device_contacts" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value.getValue("details").jsonPrimitive.content,
            )
            "device_location" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value.getValue("details").jsonPrimitive.content,
            )
            "device_clipboard" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value.getValue("details").jsonPrimitive.content,
            )
            "device_notification" -> AttentionPrompt.Confirmation(
                summary = value.getValue("summary").jsonPrimitive.content,
                details = value.getValue("details").jsonPrimitive.content,
            )
            "device_files_read" -> AttentionPrompt.ContentRead(
                grantId = value.getValue("grantId").jsonPrimitive.content,
                purpose = value.getValue("purpose").jsonPrimitive.content,
                documents = (value.getValue("documents") as JsonArray).map { item ->
                    val document = item as JsonObject
                    val alias = document.getValue("alias").jsonPrimitive.content
                    ContentReadDocument(
                        alias = alias,
                        displayName = alias,
                        expectedMimeType = document.getValue("expectedMimeType")
                            .jsonPrimitive.content,
                        maxBytes = document.getValue("maxBytes").jsonPrimitive.content.toInt(),
                    )
                },
                totalMaxBytes = value.getValue("totalMaxBytes").jsonPrimitive.content.toInt(),
            )
            "device_files_commit_changes" -> AttentionPrompt.FileChanges(
                preparedId = value.getValue("preparedId").jsonPrimitive.content,
                planDigest = value.getValue("planDigest").jsonPrimitive.content,
            )
            else -> error("Unsupported attention tool")
        }
    }

    private fun parseTerminalKind(value: String): AttentionTerminalKind = when (value) {
        "succeeded" -> AttentionTerminalKind.SUCCEEDED
        "rejected" -> AttentionTerminalKind.REJECTED
        "timed_out" -> AttentionTerminalKind.TIMED_OUT
        "cancelled" -> AttentionTerminalKind.CANCELLED
        "failed" -> AttentionTerminalKind.FAILED
        else -> error("Unsupported terminal kind")
    }

    private companion object {
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
    }
}

internal fun trustedConfirmationPresentation(
    toolName: String,
    arguments: JsonObject,
): AttentionConfirmationPresentation? {
    val action = arguments["action"]?.jsonPrimitive?.contentOrNull
    val approvalKind = arguments["approvalKind"]?.jsonPrimitive?.contentOrNull
    return when {
        toolName == "device_calendar" &&
            action == "list_calendars" && approvalKind == "read" ->
            AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_CALENDARS
        toolName == "device_calendar" &&
            action == "list_events" && approvalKind == "read" ->
            AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_EVENTS
        toolName == "device_calendar" &&
            action == "create_event" && approvalKind == "mutation" ->
            AttentionConfirmationPresentation.ANDROID_CALENDAR_CREATE_EVENT
        toolName == "device_clipboard" &&
            action == "get" && approvalKind == "read" ->
            AttentionConfirmationPresentation.ANDROID_CLIPBOARD_GET
        toolName == "device_clipboard" &&
            action == "set" && approvalKind == "mutation" ->
            AttentionConfirmationPresentation.ANDROID_CLIPBOARD_SET
        toolName == "device_clipboard" &&
            action == "clear" && approvalKind == "mutation" ->
            AttentionConfirmationPresentation.ANDROID_CLIPBOARD_CLEAR
        else -> null
    }
}
