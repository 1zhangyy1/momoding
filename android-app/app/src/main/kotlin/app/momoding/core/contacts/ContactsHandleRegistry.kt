package app.momoding.core.contacts

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap

class ContactsHandleRegistry(
    private val random: SecureRandom = SecureRandom(),
) {
    private val bindings = LinkedHashMap<String, Binding>(MAX_BINDINGS, 0.75f, true)

    @Synchronized
    fun bindContact(taskId: String, target: ContactTarget): String {
        requireTask(taskId)
        bindings.entries.firstOrNull { (_, binding) ->
            binding is Binding.Contact &&
                binding.taskId == taskId &&
                binding.target == target
        }?.key?.let { existing ->
            bindings[existing]
            return existing
        }
        return bind("contact-", Binding.Contact(taskId, target))
    }

    @Synchronized
    fun bindCursor(taskId: String, fingerprint: String, offset: Int): String {
        requireTask(taskId)
        require(fingerprint.matches(FINGERPRINT))
        require(offset in 1..MAX_CURSOR_OFFSET)
        return bind(
            "contacts-page-",
            Binding.Cursor(taskId, fingerprint, offset),
        )
    }

    @Synchronized
    fun contact(taskId: String, handle: String): ContactTarget? =
        (bindings[handle] as? Binding.Contact)
            ?.takeIf { it.taskId == taskId }
            ?.target

    @Synchronized
    fun cursor(taskId: String, handle: String, fingerprint: String): Int? =
        (bindings[handle] as? Binding.Cursor)
            ?.takeIf { it.taskId == taskId && it.fingerprint == fingerprint }
            ?.offset

    @Synchronized
    fun clearTask(taskId: String) {
        bindings.entries.removeAll { it.value.taskId == taskId }
    }

    fun queryFingerprint(query: String): String = MessageDigest.getInstance("SHA-256")
        .digest(query.trim().lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun bind(prefix: String, binding: Binding): String {
        repeat(MAX_GENERATION_ATTEMPTS) {
            val handle = prefix + ByteArray(HANDLE_BYTES)
                .also(random::nextBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            if (handle !in bindings) {
                bindings[handle] = binding
                trim()
                return handle
            }
        }
        error("Contacts handle generation failed")
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

    private sealed interface Binding {
        val taskId: String

        data class Contact(
            override val taskId: String,
            val target: ContactTarget,
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
