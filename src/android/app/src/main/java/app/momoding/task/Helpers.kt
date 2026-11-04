package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import app.momoding.prompt.MomodingHelper
import com.openminis.app.MinisApp
import com.openminis.app.agent.jobs.HelperConfig
import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.jobs.ModelTierResolver
import com.openminis.app.agent.jobs.SubAgentModelChoice
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Momoding (调研/M39): a helper is a hand of the main chat — a hidden chat of its own, on the
 * main chat's model, with no memory, that works one task and whose final answer is its report.
 * At most [SLOTS] work at once; the rest wait their turn (TaskEngine).
 */
object Helpers {
    const val SLOTS = 3

    /** Ask for the deliverable at the budget, then cancel only after the existing grace period. */
    internal fun watchBudget(scope: CoroutineScope, wrapUp: () -> Unit, expire: () -> Unit, elapsedMs: Long = 0) = scope.launch {
        val workLeft = (MomodingHelper.MAX_MINUTES * 60_000L - elapsedMs).coerceAtLeast(0)
        delay(workLeft)
        wrapUp()
        delay((MomodingHelper.MAX_MINUTES * 60_000L + HelperRunner.WRAP_UP_GRACE_MS - elapsedMs - workLeft).coerceAtLeast(0))
        expire()
    }

    private fun app(context: Context) = context.applicationContext as MinisApp

    /** A new helper's chat under [parent]; its id is the hand of the task. */
    suspend fun create(context: Context, parent: String, title: String): String = app(context).database.withTransaction {
        val repo = app(context).chatRepository
        val parentRow = repo.getSession(parent) ?: error("the parent conversation was deleted")
        val resolution = ModelTierResolver.resolveForSubAgent(
            pinnedGroupId = null, choice = SubAgentModelChoice.SAME_AS_PARENT, repo = app(context).providerRepository,
            parentBindingJson = parentRow.modelBinding, parentModelId = parentRow.modelId,
            parentActiveEntryId = ChatViewModelStore.existing(parent)?.activeEntryId?.value,
        ) ?: throw IllegalStateException("no model is set up for a helper to run on")
        val child = repo.createSession(
            modelId = resolution.seedModelId, title = HelperRunner.childSessionTitle(context, title),
            memoryEnabled = false, parentSessionId = parent,
        )
        parentRow.source?.let { repo.dao.updateSource(child.id, it) }
        resolution.bindingJson?.let { repo.updateSessionBinding(child.id, it, resolution.seedModelId) }
        child.id
    }

    /** Whether [session] is a helper's chat. */
    suspend fun isHelper(context: Context, session: String): Boolean = app(context).chatRepository.getSession(session)?.isChild == true

    /** [session]'s view model, set up to work as a helper on [task] (after a restart too). */
    suspend fun ready(context: Context, session: String, task: TaskEntity) = withContext(Dispatchers.Main) {
        HeadlessChatRunner.viewModelFor(context, session, ChatViewModelStore.PoolKind.CHILD).also { vm ->
            if (vm.helperConfig == null) {
                vm.helperConfig = HelperConfig(
                    parentSessionId = task.conversation, parentToolUseId = "", jobId = task.id,
                    maxTurns = MomodingHelper.MAX_TURNS, title = task.title, tierUsed = HelperModelTier.PRIMARY,
                )
                ChatViewModelStore.existing(task.conversation)?.let { vm.adoptBrowserTabPool(it.browserTabPool) }
            }
        }
    }

    /** A helper's task ended: the browser tabs it had go back. */
    suspend fun ended(context: Context, session: String, conversation: String) {
        ChatViewModelStore.existing(conversation)?.browserTabPool?.releaseTabs(session)
    }
}
