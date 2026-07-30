package app.momoding.core.media

import java.security.SecureRandom
import java.util.LinkedHashMap

/**
 * Process-local, task-bound indirection for MediaStore row identities.
 *
 * Handles are intentionally not persisted. After process death, permission loss, or eviction the
 * agent must list live media again instead of reconstructing a provider URI or row ID.
 */
class MediaHandleRegistry(
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Binding(
        val taskId: String,
        val mediaId: Long,
    )

    private val bindings = LinkedHashMap<String, Binding>(MAX_BINDINGS, 0.75f, true)

    @Synchronized
    fun bind(taskId: String, mediaId: Long): String {
        requireTask(taskId)
        require(mediaId >= 0L) { "Media ID is invalid" }
        bindings.entries.firstOrNull { (_, binding) ->
            binding.taskId == taskId && binding.mediaId == mediaId
        }?.key?.let { existing ->
            bindings[existing]
            return existing
        }
        repeat(MAX_GENERATION_ATTEMPTS) {
            val handle = "media-" + ByteArray(HANDLE_BYTES)
                .also(random::nextBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            if (handle !in bindings) {
                bindings[handle] = Binding(taskId, mediaId)
                trim()
                return handle
            }
        }
        error("Media handle generation failed")
    }

    @Synchronized
    fun resolve(taskId: String, handle: String): Long? =
        bindings[handle]?.takeIf { it.taskId == taskId }?.mediaId

    @Synchronized
    fun forget(taskId: String, handle: String) {
        bindings[handle]?.takeIf { it.taskId == taskId }?.let {
            bindings.remove(handle)
        }
    }

    @Synchronized
    fun clearTask(taskId: String) {
        bindings.entries.removeAll { it.value.taskId == taskId }
    }

    private fun trim() {
        while (bindings.size > MAX_BINDINGS) {
            bindings.entries.iterator().apply {
                next()
                remove()
            }
        }
    }

    private fun requireTask(taskId: String) {
        require(taskId.isNotBlank() && taskId.length <= MAX_TASK_ID_CHARS)
    }

    private companion object {
        const val HANDLE_BYTES = 12
        const val MAX_BINDINGS = 1_024
        const val MAX_GENERATION_ATTEMPTS = 8
        const val MAX_TASK_ID_CHARS = 160
    }
}
