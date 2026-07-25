package app.momoding.core.transport

import app.momoding.core.auth.HostBindingStatus
import app.momoding.core.auth.HostStateStore
import app.momoding.core.data.HostTaskSummary
import app.momoding.core.data.RoomTaskListMerger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Publishes a complete list only while the Host binding lease is still active. */
internal class BindingScopedTaskListPublisher(
    private val bindingMutex: Mutex,
    private val stateStore: HostStateStore,
    private val leaseIsCurrent: () -> Boolean,
    private val merger: RoomTaskListMerger,
) : TaskListPublisher {
    override suspend fun publish(
        listRevision: Long,
        summaries: List<HostTaskSummary>,
    ): String = bindingMutex.withLock {
        val current = stateStore.read()
        check(current?.status == HostBindingStatus.ACTIVE && leaseIsCurrent()) {
            "Host binding changed before task list publication"
        }
        merger.mergeCompleteList(listRevision, summaries)
    }
}
