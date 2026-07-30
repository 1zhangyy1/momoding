package app.momoding.core.media

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaHandleRegistryTest {
    @Test
    fun `handle is stable within one task and cannot cross tasks`() {
        val handles = MediaHandleRegistry(DeterministicSecureRandom())

        val first = handles.bind("task-a", 42L)
        val repeated = handles.bind("task-a", 42L)
        val otherTask = handles.bind("task-b", 42L)

        assertEquals(first, repeated)
        assertNotEquals(first, otherTask)
        assertEquals(42L, handles.resolve("task-a", first))
        assertNull(handles.resolve("task-b", first))
    }

    @Test
    fun `forgotten handle cannot be reused`() {
        val handles = MediaHandleRegistry(DeterministicSecureRandom())
        val handle = handles.bind("task-a", 7L)

        handles.forget("task-a", handle)

        assertNull(handles.resolve("task-a", handle))
    }

    private class DeterministicSecureRandom : SecureRandom() {
        private var next = 1

        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index ->
                bytes[index] = (next + index).toByte()
            }
            next += bytes.size
        }
    }
}
