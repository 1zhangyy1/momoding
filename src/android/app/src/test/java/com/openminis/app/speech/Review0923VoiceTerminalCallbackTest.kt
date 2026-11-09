package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — provider-ASR VAD path must always end a take with a
 * terminal callback (onFinal or onError).
 *
 * Guards 759e60d6f and 5ec5f50d3 (T-android-vad-manual-stop). 759e60d6f fixed
 * the manual-stop hang by flushing on stop(), and stated the invariant:
 * "Every path out of here MUST end in onFinal or onError". 5ec5f50d3 then
 * reworked the same stop() for audio quality. The engine binds AudioRecord and
 * cannot be built on the JVM, so these are source invariants over
 * ProviderSpeechRecognitionEngine.kt plus the extracted decision.
 *
 * [BUG] one path still breaks it: the hold-expiry job. A sub-2 s segment is
 * held with the mic open; after HOLD_FLUSH_MS the job calls stopVad() (the
 * detector goes null) and reports NO_MATCH only when the held audio is above
 * TOO_SHORT_TOAST_FLOOR. For a cough (<= 0.3 s) it delivers nothing, so
 * SpeechRecognitionManager stays in RECORDING with a dead mic. When the user
 * then taps stop, stop() sees `detector == null`, skips the flush, and the
 * panel spins on "Recognizing..." forever — the exact 759e60d6f symptom.
 */
class Review0923VoiceTerminalCallbackTest {

    @Test
    fun `a manual stop with a live session always resolves to a terminal outcome`() {
        // Every non-idle outcome must lead to onFinal (TRANSCRIBE) or onError.
        for (held in listOf(0f, 0.3f, 0.31f, 5f)) {
            val o = VadManualStopOutcome.decide(hasSession = true, heldSeconds = held, floorSeconds = 0.3f)
            assertTrue("held=$held gave $o", o == VadManualStopOutcome.TRANSCRIBE || o == VadManualStopOutcome.NO_MATCH)
        }
        assertEquals(
            VadManualStopOutcome.NOTHING_TO_DO,
            VadManualStopOutcome.decide(hasSession = false, heldSeconds = 5f, floorSeconds = 0.3f),
        )
    }

}
