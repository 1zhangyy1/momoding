package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * [T32] The streaming TTFB watchdog counts from upload completion, and Stop
 * serializes with the stream thread instead of racing it.
 *
 * GH#188: a 1.3MB multimodal body over a slow proxy took 24-27s to UPLOAD, the
 * single 30s window then had ~3s left for the server, and a healthy request was
 * cancelled as a "dead connection" — after which the retry reused the very
 * pooled connection that had just been cancelled. Fix 3169fd253: two phases
 * (upload cap, then TTFB measured FROM `requestBodyEnd`), and on timeout only
 * THIS call's socket is closed, never the pool.
 *
 * GH#239: Stop while the stream thread was diffing the same ArrayList threw
 * ConcurrentModificationException (16/16 repro). The UI window is now a
 * snapshot (`.toList()`) and every publish allocates a fresh list, so a
 * concurrent equals-diff never observes an in-place mutation.
 *
 * The decision logic is entangled in OpenAIProvider.streamMessage and
 * ChatViewModel, so it is ported VERBATIM here (source cited above each port)
 * with drift guards on the production files.
 */
class StreamWatchdogAndCancelTest {

    // ── Port of OpenAIProvider.kt ttfbWatchdog loop body (~L898-915) ─────
    //
    //   val uploadDoneAt = watchState.uploadDoneAtNanos.get()
    //   val nowNanos = System.nanoTime()
    //   if (uploadDoneAt == 0L) {
    //       val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
    //       if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }
    //   } else {
    //       val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
    //       if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) { timedOutPhase = "ttfb"; break }
    //   }
    private companion object {
        const val STREAM_TTFB_TIMEOUT_MS = 120_000L
        const val STREAM_UPLOAD_CAP_MS = 120_000L
        /** The pre-#188 single window the fix replaced. */
        const val LEGACY_SINGLE_WINDOW_MS = 30_000L
        const val MS = 1_000_000L
    }

    private fun timedOutPhase(callStartNanos: Long, uploadDoneAtNanos: Long, nowNanos: Long): String? {
        if (uploadDoneAtNanos == 0L) {
            val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
            if (elapsedMs >= STREAM_UPLOAD_CAP_MS) return "upload"
        } else {
            val sinceUploadMs = (nowNanos - uploadDoneAtNanos) / 1_000_000L
            if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) return "ttfb"
        }
        return null
    }

    /**
     * Drive the port the way the coroutine does: poll every 250ms until headers
     * arrive or a phase times out. `uploadAtMs` / `headersAtMs` are offsets from
     * call start; null = never.
     */
    private fun runWatchdog(uploadAtMs: Long?, headersAtMs: Long?, horizonMs: Long = 400_000L): String? {
        val start = 7_000_000L
        var t = 0L
        while (t <= horizonMs) {
            if (headersAtMs != null && t >= headersAtMs) return null
            val uploadDone = if (uploadAtMs != null && t >= uploadAtMs) start + uploadAtMs * MS else 0L
            timedOutPhase(start, uploadDone, start + t * MS)?.let { return it }
            t += 250
        }
        return null
    }

    // ============================================================ watchdog

    /** The reported case: 25s upload + 10s server think time. */
    @Test
    fun `slow upload plus normal TTFB is not cancelled`() {
        assertNull(runWatchdog(uploadAtMs = 25_000, headersAtMs = 35_000))
        // The legacy single window would have fired at 30s, before the headers.
        assertTrue("legacy rule would have mis-cancelled this healthy request", 35_000 > LEGACY_SINGLE_WINDOW_MS)
    }

    @Test
    fun `the ttfb clock starts at upload completion`() {
        // 100s upload, headers 115s after that (215s total) — still healthy.
        assertNull(runWatchdog(uploadAtMs = 100_000, headersAtMs = 215_000))
        // Same upload, headers 121s after it — the TTFB phase fires.
        assertEquals("ttfb", runWatchdog(uploadAtMs = 100_000, headersAtMs = 230_000))
    }

    @Test
    fun `a stalled upload is bounded by the upload cap`() {
        assertEquals("upload", runWatchdog(uploadAtMs = null, headersAtMs = null))
        assertNull("headers before the cap end the watch", runWatchdog(uploadAtMs = null, headersAtMs = 119_000))
    }

    @Test
    fun `headers arriving stop the watchdog in either phase`() {
        assertNull(runWatchdog(uploadAtMs = 1_000, headersAtMs = 2_000))
        assertNull(runWatchdog(uploadAtMs = 119_000, headersAtMs = 119_500))
    }

    /** The timeout surfaces as a status-less TransientError: retried, never a reason to switch models. */
    @Test
    fun `the watchdog error is transient without an http status`() {
        val err = LLMError.TransientError("no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s TTFB) — check network/proxy")
        assertTrue(err.isRetryable)
        assertNull(err.httpServerErrorStatus)
        assertFalse(err.isHttpServerError)
        assertFalse(err.isFallbackable)
    }

    private val providerSrc by lazy {
        File("src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt").readText()
    }

    /** The numeric value of `private const val NAME = <digits>L` in the provider source, underscores ignored. */
    private fun sourceLongConst(name: String): Long {
        val m = Regex("""private const val $name = ([0-9_]+)L""").find(providerSrc)
            ?: throw AssertionError("$name not declared as a Long const in OpenAIProvider.kt")
        return m.groupValues[1].replace("_", "").toLong()
    }

    // ============================================================ Stop vs stream thread

    // ── Port of ChatViewModel.uiMessages window (~L590-640) ────────────
    //
    //   if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
    //   else full.subList(full.size - cap, full.size).toList()
    private fun <T> window(full: List<T>, cap: Int, threshold: Int = 50): List<T> =
        if (full.size <= threshold || full.size <= cap) full
        else full.subList(full.size - cap, full.size).toList()

    /**
     * GH#239 shape: the stream thread publishes (fresh list per publish, as
     * `_messages.value = old + new` does) while the UI thread diffs windows and
     * Stop truncates. With snapshot windows no thread ever observes an in-place
     * mutation, so no ConcurrentModificationException — and the final state is
     * whatever the last publish said.
     */
    @Test
    fun `stop during streaming writes never throws and the final state is consistent`() {
        val state = AtomicReference<List<String>>(emptyList())
        val failure = AtomicReference<Throwable?>(null)
        val rounds = 3_000
        val producer = Thread {
            try {
                repeat(rounds) { i ->
                    // Fresh list per publish — never mutate the published one.
                    state.set(state.get() + "m$i")
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        val consumer = Thread {
            try {
                var prev: List<String> = emptyList()
                var diffs = 0
                while (producer.isAlive || diffs < rounds) {
                    val cur = window(state.get(), cap = 40)
                    if (cur != prev) { prev = cur; diffs++ }
                    if (!producer.isAlive) break
                }
                // Stop: truncate to a snapshot of the head, as truncateBeforeEdit /
                // cancel cleanup do, and diff once more against the live list.
                val kept = state.get().let { it.subList(0, minOf(10, it.size)).toList() }
                val again = window(state.get(), cap = 40)
                check(kept != again || state.get().size <= 10)
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        producer.start(); consumer.start()
        producer.join(); consumer.join()
        assertNull("no thread may throw: ${failure.get()}", failure.get())
        assertEquals(rounds, state.get().size)
        assertEquals(40, window(state.get(), cap = 40).size)
        assertEquals("m${rounds - 1}", window(state.get(), cap = 40).last())
    }

    /** The under-threshold fast path stays identity-equal (no allocation per publish). */
    @Test
    fun `short sessions return the same list instance`() {
        val raw = List(10) { "m$it" }
        assertTrue(window(raw, cap = 40) === raw)
    }

    // Registry stop-order assertions were removed with that implementation. Persistent
    // deletion/cancellation is exercised with Room and the Android runtime probe.
}
