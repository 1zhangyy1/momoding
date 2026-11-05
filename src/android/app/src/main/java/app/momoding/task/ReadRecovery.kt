package app.momoding.task

import android.content.Context
import app.momoding.task.TaskEntity.State
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Replay only a saved, provably read-only invocation, on its original task and process. */
internal object ReadRecovery {
    data class Pending(val task: TaskEntity, val command: ShellWork.Command, val args: String)

    suspend fun pendingIn(dao: TaskDao, task: TaskEntity, command: ShellWork.Command): Pending? {
        if (TaskGuard.checkIn(dao, TaskGuard.Binding(task.hand, task.id)) != null) return null
        val args = command.savedArgs(dao, task.hand) ?: return null
        return if (ReadOnlyCommand.proven(command.text)) Pending(task, command, args) else null
    }

    /** Keep the interrupted receive consumed until the actual command result arrives. Other inputs retry normally. */
    suspend fun deferredInputsIn(dao: TaskDao, reads: List<Pending>): Set<String> = buildSet {
        for (read in reads) {
            val task = dao.task(read.task.id) ?: continue
            if (task.state != State.RUNNING.name || task.ask != null || task.hold != null) continue
            val call = read.command.call ?: continue
            dao.inputForCall(task.hand, call.message)?.let { add(it.id) }
        }
    }

    suspend fun execute(context: Context, read: Pending) = ChatViewModelStore.holdingForSend(read.task.hand) {
        val vm = if (Helpers.isHelper(context, read.task.hand)) Helpers.ready(context, read.task.hand, read.task)
            else withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(context, read.task.hand) }
        vm.resumeReadCommand(read.task.id, read.command, read.args)
    }
}
