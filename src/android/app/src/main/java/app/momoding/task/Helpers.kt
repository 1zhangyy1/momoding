package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import app.momoding.prompt.MomodingHelper
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Set on a helper's view model before its first turn: the task it works, and its round budget. */
data class HelperConfig(val parentSessionId: String, val taskId: String, val maxTurns: Int, val title: String)

/**
 * Momoding (调研/M39): a helper is a hand of the main chat — a hidden chat of its own, on the
 * main chat's model, with no memory, that works one task and whose final answer is its report.
 * At most [SLOTS] work at once; the rest wait their turn (TaskEngine).
 */
object Helpers {
    const val SLOTS = 3
    /** After the time budget the helper is asked for its answer; this long later it is stopped. */
    const val WRAP_UP_GRACE_MS = 90_000L

    /** Ask for the deliverable at the budget, then cancel only after the existing grace period. */
    internal fun watchBudget(scope: CoroutineScope, wrapUp: () -> Unit, expire: () -> Unit, elapsedMs: Long = 0) = scope.launch {
        val workLeft = (MomodingHelper.MAX_MINUTES * 60_000L - elapsedMs).coerceAtLeast(0)
        delay(workLeft)
        wrapUp()
        delay((MomodingHelper.MAX_MINUTES * 60_000L + WRAP_UP_GRACE_MS - elapsedMs - workLeft).coerceAtLeast(0))
        expire()
    }

    private fun app(context: Context) = context.applicationContext as MinisApp

    /** A new helper's chat under [parent]; its id is the hand of the task. */
    suspend fun create(context: Context, parent: String, title: String): String = app(context).database.withTransaction {
        val repo = app(context).chatRepository
        val parentRow = repo.getSession(parent) ?: error("the parent conversation was deleted")
        // the main chat's own model: its binding when it has one, else the entry it runs on
        val entries = app(context).providerRepository.config.value.modelEntries
        val activeEntry = ChatViewModelStore.existing(parent)?.activeEntryId?.value
        val (binding, modelId) = parentRow.modelBinding?.let { it to parentRow.modelId }
                ?: ((activeEntry?.let { id -> entries.firstOrNull { it.id == id } } ?: entries.firstOrNull { it.model.id == parentRow.modelId })
                ?: throw IllegalStateException("no model is set up for a helper to run on"))
                .let { JSONObject().put("type", "entry").put("entryId", it.id).toString() to it.model.id }
        val child = repo.createSession(
            modelId = modelId, title = context.getString(R.string.helper_label) + " · " + title,
            memoryEnabled = false, parentSessionId = parent,
        )
        parentRow.source?.let { repo.dao.updateSource(child.id, it) }
        repo.updateSessionBinding(child.id, binding, modelId)
        child.id
    }

    /** Whether [session] is a helper's chat. */
    suspend fun isHelper(context: Context, session: String): Boolean = app(context).chatRepository.getSession(session)?.isChild == true

    /** [session]'s view model, set up to work as a helper on [task] (after a restart too). */
    suspend fun ready(context: Context, session: String, task: TaskEntity) = withContext(Dispatchers.Main) {
        HeadlessChatRunner.viewModelFor(context, session, ChatViewModelStore.PoolKind.CHILD).also { vm ->
            if (vm.helperConfig == null) {
                vm.helperConfig = HelperConfig(task.conversation, task.id, MomodingHelper.MAX_TURNS, task.title)
                ChatViewModelStore.existing(task.conversation)?.let { vm.adoptBrowserTabPool(it.browserTabPool) }
            }
        }
    }

    /** A helper's task ended: the browser tabs it had go back. */
    suspend fun ended(context: Context, session: String, conversation: String) {
        val vm = ChatViewModelStore.existing(session) ?: ChatViewModelStore.existing(conversation)
        vm?.releaseBrowserTabs(session)
    }
}
