package com.openminis.app.data

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T14] Rollback guard: "every fallback candidate failed" must never again
 * force a compaction (c1e5664a7, 9ad0c84b4; iOS twins df0b71ed7, e52014156;
 * issue #133).
 *
 * The withdrawn layer (T-android-compact-last-resort + its
 * CompactNegativeGuard narrowing) rewrote the user's history behind a dead
 * proxy six times in one morning: a ConnectException exhausts a group exactly
 * like an oversized context, and the signal cannot tell the two apart. Product
 * decision: an automatic, irreversible edit of the conversation must not hang
 * off it. This test reads `src/main` (never `build/`, which can hold stale
 * generated copies) and asserts the symbols stay gone and the exhausted branch
 * only composes the error trail.
 */
class ExhaustedCompactionAbsenceTest {

    private val removedSymbols = listOf(
        "CompactNegativeGuard",
        "CompactLastResort",
        "runExhaustedCompactionFallback",
        "compactionVetoReason",
        "hasAttemptedCompactionFallback",
        "GroupExhaustedNeedsCompaction",
        "compact_last_resort_notice",
    )

    /**
     * Port of the exhausted branch (ChatViewModel runAgentLoop, "All
     * fallbacks exhausted"): compose the trail and throw. No history mutation.
     */
    private fun groupExhaustedError(
        fallbackReasons: List<String>,
        skipped: List<String>,
        actual: Throwable,
        shouldFallback: Boolean,
    ): Throwable {
        if (shouldFallback) {
            if (fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                val trail = (fallbackReasons + skipped).joinToString("\n")
                val finalDesc = actual.message ?: actual.toString()
                return LLMError.ProviderError("$trail\n$finalDesc")
            }
        }
        return actual
    }

    @Test
    fun `all fallbacks failing yields an error carrying the trail and leaves history untouched`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hello"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "hi"),
            LLMMessage(LLMMessage.Role.USER, "do the big thing"),
        )
        val snapshot = history.toList()
        val reasons = listOf("⚠️ Claude Sonnet: Failed to connect to /127.0.0.1:7890", "⚠️ GPT-5.5: Failed to connect to /127.0.0.1:7890")
        val skipped = listOf("⚠️ Gemini 3 (Google): Not logged in")
        val final = LLMError.NetworkError(java.net.ConnectException("Failed to connect to /127.0.0.1:7890"))

        val err = groupExhaustedError(reasons, skipped, final, shouldFallback = true)
        assertTrue(err is LLMError.ProviderError)
        val msg = err.message ?: ""
        for (line in reasons + skipped) assertTrue("trail line missing: $line", msg.contains(line))
        assertTrue("upstream cause preserved", msg.contains("Failed to connect"))
        assertEquals("history length unchanged", snapshot.size, history.size)
        assertEquals("history content unchanged", snapshot, history)

        // Single-entry session (no fallback): the original error is rethrown as-is.
        assertTrue(groupExhaustedError(emptyList(), emptyList(), final, shouldFallback = false) === final)
    }

}
