package app.momoding.core.notification

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotificationHandleRegistry(
    private val handleFactory: () -> String = {
        "notification-" + UUID.randomUUID().toString().replace("-", "")
    },
) {
    private data class Binding(
        val taskId: String,
        val identity: NotificationIdentity,
    )

    private val byHandle = ConcurrentHashMap<String, Binding>()
    private val byTaskAndIdentity = ConcurrentHashMap<String, String>()

    fun bind(taskId: String, identity: NotificationIdentity): String {
        val identityKey = identityKey(taskId, identity)
        byTaskAndIdentity[identityKey]?.let { existing ->
            if (byHandle[existing] == Binding(taskId, identity)) return existing
        }
        while (true) {
            val handle = handleFactory()
            require(HANDLE_PATTERN.matches(handle)) { "Notification handle factory is invalid" }
            val binding = Binding(taskId, identity)
            if (byHandle.putIfAbsent(handle, binding) == null) {
                byTaskAndIdentity[identityKey] = handle
                return handle
            }
        }
    }

    fun resolve(taskId: String, handle: String): NotificationIdentity? =
        byHandle[handle]?.takeIf { it.taskId == taskId }?.identity

    fun forget(taskId: String, identity: NotificationIdentity) {
        val key = identityKey(taskId, identity)
        val handle = byTaskAndIdentity.remove(key) ?: return
        byHandle.remove(handle, Binding(taskId, identity))
    }

    fun clearTask(taskId: String) {
        byHandle.entries
            .filter { it.value.taskId == taskId }
            .forEach { (handle, binding) ->
                byHandle.remove(handle, binding)
                byTaskAndIdentity.remove(identityKey(taskId, binding.identity), handle)
            }
    }

    private fun identityKey(taskId: String, identity: NotificationIdentity): String =
        "$taskId\u001f${identity.tag}\u001f${identity.id}"

    private companion object {
        val HANDLE_PATTERN = Regex("^notification-[0-9a-f]{32}$")
    }
}
