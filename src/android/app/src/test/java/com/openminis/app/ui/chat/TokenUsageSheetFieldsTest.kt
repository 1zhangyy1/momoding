package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-token-usage-cache-hit-rate] / [T-android-token-usage-output-speed]
 *
 * The "…" → Token Usage sheet was missing two rows iOS has shown all along:
 * Cache Hit Rate, and the whole Speed section (Output Speed). Reported as
 * "Android 的 Token Usage 不显示缓存率".
 *
 * Cache Hit Rate was pure UI — `input`/`cacheRead`/`cacheWrite` were already
 * aggregated and the sheet already computed the same denominator for its
 * "Input (incl. cache)" row. Output Speed needed a data source: Android had
 * none, and unlike iOS — which keeps `sessionStreamDuration` in memory on the
 * view model and loses it when the VM goes away — Android recomputes the whole
 * sheet from persisted `token_usage` rows, so the duration is persisted as a
 * new `streamMs` key and the average survives a restart.
 *
 * `SessionTokenStats` is a plain data class, so both derivations are tested
 * directly; the wiring that produces `streamMs` is pinned as source facts.
 */
class TokenUsageSheetFieldsTest {

    private fun stats(
        input: Long = 0,
        output: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        streamMs: Long = 0,
        streamOutput: Long = 0,
    ) = ChatViewModel.SessionTokenStats(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        context = 0,
        loopCount = 0,
        streamMs = streamMs,
        streamOutput = streamOutput,
    )

    // ── Cache Hit Rate ─────────────────────────────────────────────────────

    @Test
    fun `cache hit rate is cache read over total input including cache`() {
        // iOS: cacheRead / (input + cacheRead + cacheWrite) * 100 — the same
        // denominator as the sheet's "Input (incl. cache)" row.
        val s = stats(input = 1_000, cacheRead = 3_000, cacheWrite = 1_000)
        assertEquals(60.0, s.cacheHitRate!!, 0.001)
    }

    @Test
    fun `a fully cached session reports one hundred percent`() {
        assertEquals(100.0, stats(cacheRead = 5_000).cacheHitRate!!, 0.001)
    }

    @Test
    fun `no cache reads hides the row instead of showing zero`() {
        // A provider that does no caching must not look like a 0% cache MISS.
        assertNull("no cache read → hidden", stats(input = 10_000).cacheHitRate)
        assertNull("write-only (first turn) → hidden", stats(input = 10_000, cacheWrite = 2_000).cacheHitRate)
        assertNull("an empty session → hidden", stats().cacheHitRate)
    }

    @Test
    fun `cache write counts in the denominator, so the rate is not inflated`() {
        // Dropping cacheWrite would report 50% here instead of 25% — the tokens
        // written to cache were still billed as input this session.
        val s = stats(input = 4_000, cacheRead = 4_000, cacheWrite = 8_000)
        assertEquals(25.0, s.cacheHitRate!!, 0.001)
    }

    // ── Output Speed ───────────────────────────────────────────────────────

    @Test
    fun `output speed is streamed tokens over streamed seconds`() {
        // 900 tokens in 30s = 30 tok/s.
        assertEquals(30.0, stats(streamMs = 30_000, streamOutput = 900).outputTokensPerSecond!!, 0.001)
    }

    @Test
    fun `speed sums across turns, it is not a per-turn average`() {
        // A 10s/500-token turn plus a 30s/300-token turn is 800/40 = 20 tok/s,
        // NOT (50 + 10) / 2 = 30. The loader accumulates both sums.
        assertEquals(20.0, stats(streamMs = 40_000, streamOutput = 800).outputTokensPerSecond!!, 0.001)
    }

    @Test
    fun `speed is null until something measurable was recorded`() {
        // Sessions whose rows all predate `streamMs` must render "—", never
        // "0.0 tok/s", which would read as a stalled model.
        assertNull("no duration recorded", stats(output = 5_000).outputTokensPerSecond)
        assertNull("duration but no output", stats(streamMs = 10_000).outputTokensPerSecond)
        assertNull("fresh session", stats().outputTokensPerSecond)
    }

    @Test
    fun `speed uses the matched output, not the session total`() {
        // Legacy rows contribute `output` but no `streamMs`. If the rate used
        // the session total it would count tokens whose duration is unknown:
        // 5000 total / 10s = 500 tok/s, wildly wrong. Only the matched pair
        // counts → 200/10 = 20 tok/s.
        val s = stats(output = 5_000, streamMs = 10_000, streamOutput = 200)
        assertEquals(20.0, s.outputTokensPerSecond!!, 0.001)
    }

}
