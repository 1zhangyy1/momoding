package app.momoding.task

/** A live executor's receipt handle. The task log, rather than this handle, owns the action. */
data class TaskAction(val taskId: String, val id: String, val command: ShellWork.Command? = null) {
    internal class NotStarted(message: String) : IllegalStateException(message)

    companion object {
        internal fun pending(history: List<TaskLogEntity>) =
            TaskReceipt.pending(history, TaskLogEntity.Kind.ABOUT_TO, TaskLogEntity.Kind.RESULT)
    }
}
