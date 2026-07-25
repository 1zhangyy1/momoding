package app.momoding.ui.navigation

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test
    fun `attention focus identity is exact collision resistant and route rejects blanks`() {
        val first = taskDetailAttentionFocusKey("task:a", "call")
        val second = taskDetailAttentionFocusKey("task", "a:call")

        assertNotEquals(first, second)
        assertTrue(first.contains("task:a"))
        assertTrue(first.contains("call"))

        assertFails { AttentionRoute("", "call", "focus") }
        assertFails { AttentionRoute("task", "", "focus") }
        assertFails { AttentionRoute("task", "call", "") }
        assertFails { taskDetailAttentionFocusKey("", "call") }
        assertFails { taskDetailAttentionFocusKey("task", "") }
    }

    private fun assertFails(block: () -> Unit) {
        runCatching(block).onSuccess { error("Expected exact route validation failure") }
    }
}
