package com.openminis.app.data.session

import android.content.Context
import app.momoding.task.TaskEngine
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-child-session-delete-storage] THE session-delete funnel.
 *
 * Before this, four entry points (home list single/multi delete, delete-
 * folder-with-sessions, empty-session cleanup on exit, the debug RPC) each
 * did `chatRepository.deleteSession` plus whatever cache clears that call
 * site remembered — and none of them touched the session's files, so every
 * deleted session leaked its `minis-sessions/<id>` workspace and dated media
 * forever. With helper child sessions the leak multiplied: a parent delete
 * dropped the children's DB rows but left their directories, their cached
 * ViewModels and their badges behind.
 *
 * One call deletes the whole tree:
 *   1. stop related tasks and delete session rows in one transaction, so late
 *      receives cannot recreate a deleted chat;
 *   2. cancel precisely those runs and wait for their coroutines to finish;
 *   3. release ViewModels, files and badges, children before the root.
 *
 * Lives in `data/session`; the repository stays Context-free, and the
 * UI/service singletons it touches
 * (ViewModel store, badge store, headless runner) are process-wide objects,
 * not screen state. Every delete entry point MUST route through here.
 */
object SessionDeleter {
    private const val TAG = "SessionDeleter"

    data class Report(val deletedIds: List<String>, val bytesFreed: Long, val jobsCancelled: Int)

    suspend fun deleteTree(
        context: Context,
        repo: ChatRepository,
        rootId: String,
        reason: String,
    ): Report = withContext(Dispatchers.IO) {
        val storage = SessionStorage(context.applicationContext.filesDir)
        val deleted = TaskEngine.deleteSessionTree(context, rootId)
        val ids = deleted.ids
        var freed = 0L
        val jobs = deleted.stopped
        // A Bot may live outside the deleted tree. Cancel only the run for this task.
        for (task in deleted.tasks.filter { it.hand !in ids }) {
            val vm = withContext(Dispatchers.Main) { ChatViewModelStore.existing(task.hand) }
            vm?.cancelTaskForDeletion(task.id)
        }
        for (id in ids) {
            val vm = withContext(Dispatchers.Main) { ChatViewModelStore.existing(id) }
            vm?.closeForDeletion()
            withContext(Dispatchers.Main) { ChatViewModelStore.release(id) }
            HeadlessChatRunner.forget(id)
            app.momoding.guard.Grants.clearSession(id)
            app.momoding.guard.RuleHits.clearSession(id)
            // No live writer remains when files are removed.
            freed += storage.deleteFiles(id)
            SessionBadgeStore.clear(id)
            // [T-android-browser-cli-own-pool] A pool minis-browser-use made
            // for this session while it had no live view model.
            com.openminis.app.sandbox.offload.BrowserCliPools.release(id)
        }
        AppLogger.info(
            TAG,
            "deleteTree root=${rootId.take(8)} reason=$reason sessions=${ids.size} " +
                "jobsCancelled=$jobs freed=${freed}B",
        )
        Report(ids, freed, jobs)
    }

    /**
     * Clear a session tree's FILES only (storage screen "clear files"): every
     * descendant's workspace and media go too, the DB rows stay.
     */
    suspend fun clearTreeFiles(context: Context, repo: ChatRepository, rootId: String): Long =
        withContext(Dispatchers.IO) {
            val storage = SessionStorage(context.applicationContext.filesDir)
            val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
            ids.sumOf { storage.deleteFiles(it) }
        }
}
