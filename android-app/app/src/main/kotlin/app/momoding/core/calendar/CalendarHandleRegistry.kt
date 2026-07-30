package app.momoding.core.calendar

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap

/**
 * Process-local and task-bound indirection for Calendar Provider identities.
 *
 * The map is intentionally not persisted. A process restart, explicit clear, permission loss, or
 * task mismatch makes a handle stale and requires the agent to discover live data again.
 */
class CalendarHandleRegistry(
    private val random: SecureRandom = SecureRandom(),
) {
    private val bindings = LinkedHashMap<String, Binding>(MAX_BINDINGS, 0.75f, true)

    @Synchronized
    fun bindCalendar(taskId: String, calendarId: Long): String {
        requireTask(taskId)
        return bind("calendar-", Binding.Calendar(taskId, calendarId))
    }

    @Synchronized
    fun bindEvent(taskId: String, target: CalendarEventTarget): String {
        requireTask(taskId)
        return bind("event-", Binding.Event(taskId, target))
    }

    @Synchronized
    fun bindCursor(
        taskId: String,
        fingerprint: String,
        offset: Int,
    ): String {
        requireTask(taskId)
        require(fingerprint.matches(FINGERPRINT))
        require(offset in 1..MAX_CURSOR_OFFSET)
        return bind(
            "calendar-page-",
            Binding.Cursor(taskId, fingerprint, offset),
        )
    }

    @Synchronized
    fun calendar(taskId: String, handle: String): Long? =
        (bindings[handle] as? Binding.Calendar)
            ?.takeIf { it.taskId == taskId }
            ?.calendarId

    @Synchronized
    fun event(taskId: String, handle: String): CalendarEventTarget? =
        (bindings[handle] as? Binding.Event)
            ?.takeIf { it.taskId == taskId }
            ?.target

    @Synchronized
    fun cursor(
        taskId: String,
        handle: String,
        fingerprint: String,
    ): Int? = (bindings[handle] as? Binding.Cursor)
        ?.takeIf { it.taskId == taskId && it.fingerprint == fingerprint }
        ?.offset

    @Synchronized
    fun clearTask(taskId: String) {
        bindings.entries.removeAll { it.value.taskId == taskId }
    }

    @Synchronized
    fun clearAll() {
        bindings.clear()
    }

    fun queryFingerprint(
        startMillis: Long,
        endMillis: Long,
        calendarId: Long?,
        query: String?,
    ): String {
        val canonical = listOf(
            startMillis.toString(),
            endMillis.toString(),
            calendarId?.toString().orEmpty(),
            query?.trim()?.lowercase().orEmpty(),
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun bind(prefix: String, binding: Binding): String {
        repeat(MAX_GENERATION_ATTEMPTS) {
            val handle = prefix + randomBytes().joinToString("") { byte -> "%02x".format(byte) }
            if (handle !in bindings) {
                bindings[handle] = binding
                trim()
                return handle
            }
        }
        error("Calendar handle generation failed")
    }

    private fun randomBytes() = ByteArray(HANDLE_BYTES).also(random::nextBytes)

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

    private sealed interface Binding {
        val taskId: String

        data class Calendar(
            override val taskId: String,
            val calendarId: Long,
        ) : Binding

        data class Event(
            override val taskId: String,
            val target: CalendarEventTarget,
        ) : Binding

        data class Cursor(
            override val taskId: String,
            val fingerprint: String,
            val offset: Int,
        ) : Binding
    }

    private companion object {
        const val HANDLE_BYTES = 12
        const val MAX_BINDINGS = 1_024
        const val MAX_GENERATION_ATTEMPTS = 8
        const val MAX_CURSOR_OFFSET = 10_000
        const val MAX_TASK_ID_CHARS = 160
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}
