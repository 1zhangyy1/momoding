package app.momoding.feature.share

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.data.DraftRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class ShareImportResult(
    val receiptId: String,
    val draftId: String,
    val notice: String,
)

class ShareImportCoordinator(
    context: Context,
    private val drafts: DraftRepository,
    private val attachments: AttachmentRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val parser = ShareIntentParser(context.applicationContext.contentResolver)
    private val receipts = ShareReceiptStore(context.applicationContext, nowMillis, idFactory)
    private val importMutex = Mutex()

    suspend fun import(intent: Intent): ShareImportResult = importMutex.withLock {
        val incoming = parser.parse(intent)
        val receipt = receipts.begin(incoming.fingerprint)
        if (receipt.status == ReceiptStatus.COMPLETE) return@withLock receipt.result()

        try {
            drafts.createDraft(receipt.draftId)
            drafts.flushTextAndSelection(
                draftId = receipt.draftId,
                text = incoming.text,
                selectionStart = incoming.text.length,
                selectionEnd = incoming.text.length,
            )
            val imported = attachments.importSharedSelection(
                draftId = receipt.draftId,
                receiptId = receipt.receiptId,
                inputs = incoming.attachments,
            )
            val notice = when {
                incoming.errors.isNotEmpty() -> incoming.errors.first().safeMessage
                imported.failures.isNotEmpty() -> imported.failures.first().safeMessage
                imported.imported.any { it.kind == AttachmentKind.VIDEO } ->
                    "Video is saved in this draft, but the Agent cannot read video yet. Remove it before sending."
                incoming.text.isNotEmpty() || imported.imported.isNotEmpty() ->
                    "Shared content is ready to review. Nothing has been sent."
                else -> "No usable shared content was found. You can still edit or cancel this draft."
            }
            receipts.complete(receipt.receiptId, notice).result()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val notice = "Shared content could not be saved safely. Nothing was sent."
            receipts.complete(receipt.receiptId, notice).result()
        }
    }

    fun completedResult(receiptId: String?): ShareImportResult? =
        receiptId?.let(receipts::completed)?.result()
}

private enum class ReceiptStatus { PROCESSING, COMPLETE }

private data class ShareReceipt(
    val fingerprint: String,
    val receiptId: String,
    val draftId: String,
    val createdAtMillis: Long,
    val status: ReceiptStatus,
    val notice: String,
) {
    fun result() = ShareImportResult(receiptId, draftId, notice)
}

private class ShareReceiptStore(
    context: Context,
    private val nowMillis: () -> Long,
    private val idFactory: () -> String,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun begin(fingerprint: String): ShareReceipt {
        require(FINGERPRINT.matches(fingerprint)) { "share fingerprint is invalid" }
        val now = nowMillis()
        val recent = all().firstOrNull { receipt ->
            receipt.fingerprint == fingerprint &&
                now - receipt.createdAtMillis in 0..DEDUPLICATION_TTL_MILLIS
        }
        if (recent != null) return recent
        prune(now)
        val receipt = ShareReceipt(
            fingerprint = fingerprint,
            receiptId = idFactory().also(::requireUuid),
            draftId = idFactory().also(::requireUuid),
            createdAtMillis = now,
            status = ReceiptStatus.PROCESSING,
            notice = "",
        )
        write(receipt)
        return receipt
    }

    @Synchronized
    fun complete(receiptId: String, notice: String): ShareReceipt {
        requireUuid(receiptId)
        val existing = all().firstOrNull { it.receiptId == receiptId }
            ?: error("SHARE_RECEIPT_MISSING")
        val complete = existing.copy(
            status = ReceiptStatus.COMPLETE,
            notice = notice.filterNot(Char::isISOControl).take(MAX_NOTICE_CHARS),
        )
        write(complete)
        return complete
    }

    @Synchronized
    fun completed(receiptId: String): ShareReceipt? {
        if (!UUID_PATTERN.matches(receiptId)) return null
        val now = nowMillis()
        return all().firstOrNull { receipt ->
            receipt.receiptId == receiptId &&
                receipt.status == ReceiptStatus.COMPLETE &&
                now - receipt.createdAtMillis in 0..NAVIGATION_TTL_MILLIS
        }
    }

    private fun all(): List<ShareReceipt> = preferences.all.values.mapNotNull { value ->
        runCatching { JSONObject(value as String).toReceipt() }.getOrNull()
    }

    private fun prune(now: Long) {
        val decoded = preferences.all.map { (key, value) ->
            key to runCatching { JSONObject(value as String).toReceipt() }.getOrNull()
        }
        val staleKeys = decoded.mapNotNull { (key, receipt) ->
            key.takeIf { receipt == null || now - receipt.createdAtMillis !in 0..NAVIGATION_TTL_MILLIS }
        }
        val overflowKeys = decoded
            .filter { (_, receipt) ->
                receipt != null && now - receipt.createdAtMillis in 0..NAVIGATION_TTL_MILLIS
            }
            .sortedByDescending { (_, receipt) -> requireNotNull(receipt).createdAtMillis }
            .drop(MAX_RECEIPTS - 1)
            .map { (key, _) -> key }
        val removable = (staleKeys + overflowKeys).distinct()
        if (removable.isNotEmpty()) {
            preferences.edit { removable.forEach(::remove) }
        }
    }

    private fun write(receipt: ShareReceipt) {
        val json = JSONObject()
            .put("fingerprint", receipt.fingerprint)
            .put("receiptId", receipt.receiptId)
            .put("draftId", receipt.draftId)
            .put("createdAtMillis", receipt.createdAtMillis)
            .put("status", receipt.status.name)
            .put("notice", receipt.notice)
        preferences.edit(commit = true) {
            putString("receipt.${receipt.receiptId}", json.toString())
        }
    }

    private fun JSONObject.toReceipt(): ShareReceipt = ShareReceipt(
        fingerprint = getString("fingerprint").also { require(FINGERPRINT.matches(it)) },
        receiptId = getString("receiptId").also(::requireUuid),
        draftId = getString("draftId").also(::requireUuid),
        createdAtMillis = getLong("createdAtMillis"),
        status = ReceiptStatus.valueOf(getString("status")),
        notice = getString("notice").take(MAX_NOTICE_CHARS),
    )

    private companion object {
        const val PREFERENCES_NAME = "share-import-receipts-v1"
        const val DEDUPLICATION_TTL_MILLIS = 10L * 60L * 1_000L
        const val NAVIGATION_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val MAX_NOTICE_CHARS = 240
        const val MAX_RECEIPTS = 64
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}

private fun requireUuid(value: String) {
    require(UUID_PATTERN.matches(value)) { "share identifier is invalid" }
}

private val UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)
