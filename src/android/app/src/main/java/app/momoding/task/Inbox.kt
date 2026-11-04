package app.momoding.task

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.QueuedPrompt
import com.openminis.app.ui.chat.QueuedPromptOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Reception commits before it is acknowledged. Consumption and transcript writes are one
 * transaction. Room is the authority; a view model only caches its observable rows. */
object Inbox {
    private const val TAG = "Inbox"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun app(context: Context) = context.applicationContext as MinisApp

    suspend fun put(context: Context, to: String, prompt: QueuedPrompt) {
        val db = app(context).database
        db.withTransaction { putIn(db.taskDao(), to, prompt) }
    }

    suspend fun putIn(dao: TaskDao, to: String, prompt: QueuedPrompt) {
        if (prompt.origin == QueuedPromptOrigin.PROGRAMMATIC) {
            prompt.scheduledTaskId?.let { scheduled ->
                val superseded = dao.unread(to).filter {
                    it.sender == InboxEntity.Sender.ROUTINE.name && ScheduledTaskMarker.parse(it.text)?.taskId == scheduled
                }.map { it.id }
                if (superseded.isNotEmpty()) dao.deleteInbox(superseded)
            }
        }
        dao.insertInbox(prompt.toRow(to))
    }

    suspend fun drop(context: Context, ids: Collection<String>) {
        if (ids.isNotEmpty()) TaskEngine.dao(context).deleteInbox(ids.toList())
    }

    /** A retry reuses the original message id; [persist] must be idempotent for that id. */
    suspend fun <T> take(context: Context, ids: Collection<String>, persist: suspend () -> T): T {
        val db = app(context).database
        return db.withTransaction {
            for (id in ids) {
                val row = db.taskDao().inbox(id)
                check(row != null && row.readAt == null && row.failedAt == null) { "message $id is no longer unread" }
            }
            persist().also { db.taskDao().markRead(ids.toList(), System.currentTimeMillis()) }
        }
    }

    suspend fun unread(context: Context, to: String): List<QueuedPrompt> =
        TaskEngine.dao(context).unread(to).map { it.toPrompt() }

    suspend fun rows(context: Context, to: String): List<QueuedPrompt> =
        TaskEngine.dao(context).inboxFor(to).map { it.toPrompt() }

    suspend fun complete(context: Context, to: String) = TaskEngine.dao(context).completeRead(to)

    suspend fun failed(context: Context, to: String, terminal: Boolean, ids: List<String>? = null): Boolean {
        val db = app(context).database
        return db.withTransaction {
            if (ids == null) db.taskDao().failRead(to, System.currentTimeMillis(), terminal)
            else db.taskDao().failInbox(ids, System.currentTimeMillis(), terminal)
            db.taskDao().unread(to).isNotEmpty()
        }
    }

    suspend fun retry(context: Context, id: String) = TaskEngine.dao(context).retryInbox(id)

    /** A transaction can post work or a result with the task state that authorized it. */
    suspend fun post(dao: TaskDao, to: String, text: String, taskId: String? = null, sender: InboxEntity.Sender = InboxEntity.Sender.HAND) {
        dao.insertInbox(InboxEntity(
            id = java.util.UUID.randomUUID().toString(), toSession = to, taskId = taskId,
            sender = sender.name, text = text, arrivedAt = System.currentTimeMillis(),
            partsJson = JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString(),
        ))
    }

    /** Opening/refreshing wakes persisted work; it never posts another copy of the message. */
    suspend fun wake(context: Context, to: String) = withContext(Dispatchers.Main) {
        if (Helpers.isHelper(context, to)) {
            val task = TaskEngine.dao(context).heldBy(to) ?: return@withContext
            Helpers.ready(context, to, task)
        }
        HeadlessChatRunner.viewModelFor(context, to).receiveInbox()
    }

    fun wakeAll(context: Context) {
        scope.launch {
            kotlinx.coroutines.delay(3000)
            for (sid in TaskEngine.dao(context).sessionsWithUnread()) {
                runCatching { wake(context, sid) }.onFailure { AppLogger.warning(TAG, "wake $sid: ${it.message}") }
            }
        }
    }

    private fun QueuedPrompt.toRow(to: String) = InboxEntity(
        id = id, toSession = to, taskId = taskId,
        sender = when {
            origin == QueuedPromptOrigin.USER -> InboxEntity.Sender.USER
            isScheduledFire -> InboxEntity.Sender.ROUTINE
            else -> InboxEntity.Sender.COMMAND
        }.name,
        text = text,
        attachments = attachments.takeIf { it.isNotEmpty() }?.let { list ->
            JSONArray(list.map { JSONObject().put("name", it.fileName).put("uri", it.uri.toString()).put("mime", it.mimeType).put("kind", it.kind.name) }).toString()
        },
        prefill = prefill.takeIf { it.isNotEmpty() }?.let { list -> JSONArray(list.map { it.toJson() }).toString() },
        partsJson = partsJson, arrivedAt = arrivedAt,
    )

    private fun InboxEntity.toPrompt(): QueuedPrompt {
        val user = sender == InboxEntity.Sender.USER.name
        return QueuedPrompt(
            id = id, text = text,
            attachments = attachments?.let { s ->
                JSONArray(s).let { a -> (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    InputAttachment(fileName = o.getString("name"), uri = Uri.parse(o.getString("uri")),
                        mimeType = o.getString("mime"), kind = InputAttachment.Kind.valueOf(o.getString("kind")))
                } }
            }.orEmpty(),
            origin = if (user) QueuedPromptOrigin.USER else QueuedPromptOrigin.PROGRAMMATIC,
            prefill = prefill?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { i ->
                a.getJSONObject(i).let { PrefilledToolCall(it.getString("tool"), it.getJSONObject("args").toString()) }
            } } }.orEmpty(),
            scheduledTaskId = if (user) null else ScheduledTaskMarker.parse(text)?.taskId,
            partsJson = partsJson, arrivedAt = arrivedAt, readAt = readAt, failedAt = failedAt, tries = tries, taskId = taskId,
        )
    }
}
