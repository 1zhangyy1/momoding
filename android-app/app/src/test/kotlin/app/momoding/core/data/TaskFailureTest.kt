package app.momoding.core.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskFailureTest {
    @Test
    fun `known Provider failures get typed safe recovery`() {
        val auth = classifyTaskFailure("OpenRouter API key is invalid")
        val rateLimit = classifyTaskFailure("OpenRouter rate limit reached")

        assertEquals(TaskFailureKind.PROVIDER_AUTH, auth.kind)
        assertEquals(TaskFailureRecovery.FIX_PROVIDER, auth.recovery)
        assertEquals("OpenRouter API key is invalid. Update it in Settings.", auth.message)
        assertEquals(TaskFailureKind.PROVIDER_RATE_LIMIT, rateLimit.kind)
        assertEquals(TaskFailureRecovery.RETRY, rateLimit.recovery)
    }

    @Test
    fun `latest Pi assistant error becomes the durable task failure`() {
        val messages = listOf(
            Json.parseToJsonElement(
                """{"role":"assistant","stopReason":"error","errorMessage":"OpenRouter request timed out"}""",
            ),
            Json.parseToJsonElement(
                """{"role":"assistant","stopReason":"error","errorMessage":"OpenRouter model was not found"}""",
            ),
        )

        val failure = taskFailureForRunState("FAILED", messages)

        assertEquals(TaskFailureKind.PROVIDER_MODEL, failure?.kind)
        assertEquals(TaskFailureRecovery.FIX_PROVIDER, failure?.recovery)
    }

    @Test
    fun `non-failed run clears an earlier failure and interrupted is explicit`() {
        val previous = classifyTaskFailure("OpenRouter API key is invalid")

        assertNull(taskFailureForRunState("RUNNING", previous = previous))
        val interrupted = taskFailureForRunState("INTERRUPTED", previous = previous)
        assertEquals(TaskFailureKind.INTERRUPTED, interrupted?.kind)
        assertEquals(TaskFailureRecovery.OPEN_TASK, interrupted?.recovery)
    }

    @Test
    fun `unknown failure never claims the Provider caused it`() {
        val failure = taskFailureForRunState("FAILED")

        assertEquals(TaskFailureKind.UNKNOWN, failure?.kind)
        assertEquals("Task failed — open for details", failure?.homeDetail)
        assertEquals(TaskFailureRecovery.OPEN_TASK, failure?.recovery)
    }
}
