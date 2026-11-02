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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Momoding (调研/M39 2.2): what reaches a busy conversation is written to [InboxEntity] the moment
 * it arrives, and marked read in the same transaction that writes it into the transcript. So a
 * word is either still unread or in the transcript — never only in memory. At the next start the
 * unread ones come back by themselves ([wakeAll]).
 *
 * The chat's own queue ([com.openminis.app.ui.chat.ChatViewModel]) stays the in-memory view of
 * these rows: the agent loop checks it at every tool boundary and that check must not wait on
 * the database. Writes go through one ordered lane, so a row is always stored before it can be
 * read, dropped or marked read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Inbox {
    private const val TAG = "Inbox"
    private val lane = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var last: Job? = null

    private fun app(context: Context) = context.applicationContext as MinisApp

    private fun write(context: Context, block: suspend (TaskDao) -> Unit) {
        val dao = TaskEngine.dao(context)
        synchronized(this) {
            last = lane.launch { runCatching { block(dao) }.onFailure { AppLogger.warning(TAG, "write failed: ${it.message}") } }
        }
    }

    /** Store [prompt] for [to]. */
    fun put(context: Context, to: String, prompt: QueuedPrompt) = write(context) { it.insertInbox(prompt.toRow(to)) }

    /** Forget prompts that will never be read: withdrawn by a retry or delete, or superseded. */
    fun drop(context: Context, ids: Collection<String>) {
        if (ids.isNotEmpty()) write(context) { it.deleteInbox(ids.toList()) }
    }

    /** Run [persist] (writing [ids] into the transcript) and mark them read, as one transaction. */
    suspend fun <T> take(context: Context, ids: Collection<String>, persist: suspend () -> T): T {
        last?.join()
        val db = app(context).database
        return db.withTransaction { persist().also { db.taskDao().markRead(ids.toList(), System.currentTimeMillis()) } }
    }

    /** What [to] has not read yet, oldest first. */
    suspend fun unread(context: Context, to: String): List<QueuedPrompt> {
        last?.join()
        return TaskEngine.dao(context).unread(to).map { it.toPrompt() }
    }

    /** At startup: open every conversation that still has unread words; opening it delivers them. */
    fun wakeAll(context: Context) {
        lane.launch {
            kotlinx.coroutines.delay(3000) // like WorkResume: let the repositories and the sandbox come up
            val sessions = runCatching { TaskEngine.dao(context).sessionsWithUnread() }.getOrDefault(emptyList())
            for (sid in sessions) {
                AppLogger.info(TAG, "$sid has unread words — opening it")
                runCatching { withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(context, sid) } }
                    .onFailure { AppLogger.warning(TAG, "wake $sid: ${it.message}") }
            }
        }
    }

    private fun QueuedPrompt.toRow(to: String) = InboxEntity(
        id = id,
        toSession = to,
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
        arrivedAt = System.currentTimeMillis(),
    )

    private fun InboxEntity.toPrompt(): QueuedPrompt {
        val user = sender == InboxEntity.Sender.USER.name
        return QueuedPrompt(
            id = id,
            text = text,
            attachments = attachments?.let { s ->
                JSONArray(s).let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        InputAttachment(
                            fileName = o.getString("name"), uri = Uri.parse(o.getString("uri")),
                            mimeType = o.getString("mime"), kind = InputAttachment.Kind.valueOf(o.getString("kind")),
                        )
                    }
                }
            }.orEmpty(),
            origin = if (user) QueuedPromptOrigin.USER else QueuedPromptOrigin.PROGRAMMATIC,
            prefill = prefill?.let { s ->
                JSONArray(s).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { PrefilledToolCall(it.getString("tool"), it.getJSONObject("args").toString()) } } }
            }.orEmpty(),
            scheduledTaskId = if (user) null else ScheduledTaskMarker.parse(text)?.taskId,
        )
    }
}
