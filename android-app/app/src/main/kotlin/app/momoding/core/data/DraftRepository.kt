package app.momoding.core.data

import app.momoding.core.policy.TaskApprovalMode
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DraftRepository(
    database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.p2Dao()
    private val journal = RoomCommandDraftJournal(database, nowMillis = nowMillis)

    fun observeDraft(draftId: String): Flow<DraftRecord?> {
        require(draftId.isNotBlank()) { "draftId is blank" }
        return dao.observeDraft(draftId)
            .map { entity -> entity?.let { journal.draft(draftId) } }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    suspend fun createDraft(
        draftId: String,
        text: String = "",
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
    ): DraftRecord = withContext(ioDispatcher) {
        require(draftId.isNotBlank()) { "draftId is blank" }
        journal.draft(draftId) ?: journal.saveDraft(
            DraftRecord(
                draftId = draftId,
                text = text,
                selectedHostId = null,
                selectedModelId = null,
                selectedMode = null,
                selectedGrantId = null,
                createCommandId = commandIdFactory(),
                promptCommandId = commandIdFactory(),
                taskId = null,
                updatedAtMillis = nowMillis(),
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                attemptOrdinal = 0,
                approvalMode = TaskApprovalMode.REQUEST_APPROVAL,
            ),
        )
    }

    /** Immediate durable flush; invalid UTF-16 ranges are repaired to 0..0 before return. */
    suspend fun flushTextAndSelection(
        draftId: String,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): DraftRecord = withContext(ioDispatcher) {
        val (canonicalStart, canonicalEnd) = if (
            hasValidUtf16Selection(text, selectionStart, selectionEnd)
        ) {
            selectionStart to selectionEnd
        } else {
            0 to 0
        }
        check(
            dao.updateDraftTextAndSelection(
                draftId = draftId,
                text = text,
                selectionStart = canonicalStart,
                selectionEnd = canonicalEnd,
                updatedAtMillis = nowMillis(),
            ) == 1,
        ) { "Cannot save an unknown draft" }
        journal.draft(draftId) ?: error("Draft text update was lost")
    }

    /** Reads and durably repairs a corrupt persisted selection, if one exists. */
    suspend fun repairDraft(draftId: String): DraftRecord? = withContext(ioDispatcher) {
        journal.draft(draftId)
    }

    suspend fun selectAuthorizedFolder(
        draftId: String,
        grantId: String?,
    ): DraftRecord = withContext(ioDispatcher) {
        check(dao.updateDraftGrant(draftId, grantId, nowMillis()) == 1) {
            "An unknown or started draft cannot change its mobile folder grant"
        }
        journal.draft(draftId) ?: error("Draft folder update was lost")
    }

    suspend fun selectMode(
        draftId: String,
        mode: String?,
    ): DraftRecord = withContext(ioDispatcher) {
        require(mode == null || mode == "PLAN") { "Unsupported draft mode" }
        check(dao.updateDraftMode(draftId, mode, nowMillis()) == 1) {
            "An unknown or started draft cannot change its mode"
        }
        journal.draft(draftId) ?: error("Draft mode update was lost")
    }

    suspend fun selectApprovalMode(
        draftId: String,
        mode: TaskApprovalMode,
    ): DraftRecord = withContext(ioDispatcher) {
        check(dao.updateDraftApprovalMode(draftId, mode, nowMillis()) == 1) {
            "An unknown or started draft cannot change its approval mode through its draft"
        }
        journal.draft(draftId) ?: error("Draft approval mode update was lost")
    }
}

internal fun hasValidUtf16Selection(text: String, start: Int, end: Int): Boolean =
    start >= 0 &&
        end >= start &&
        end <= text.length &&
        !text.splitsSurrogatePair(start) &&
        !text.splitsSurrogatePair(end)

internal fun DraftRecord.repairInvalidSelection(): DraftRecord =
    if (hasValidUtf16Selection(text, selectionStart, selectionEnd)) {
        this
    } else {
        copy(selectionStart = 0, selectionEnd = 0)
    }

internal fun DraftEntity.repairInvalidSelection(): DraftEntity =
    if (hasValidUtf16Selection(text, selectionStart, selectionEnd)) {
        this
    } else {
        copy(selectionStart = 0, selectionEnd = 0)
    }

private fun String.splitsSurrogatePair(offset: Int): Boolean =
    offset in 1 until length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()
