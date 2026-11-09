package com.openminis.app.speech

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-mic-preempted] Port of iOS 3a2e3e305 (issue #283): a microphone held
 * by another app (a call, a recorder) is reported as such, translated, instead
 * of the engine's English ("AudioRecord not initialized.") or, when Android 10+
 * silences the recording, instead of listening to nothing until the timeout.
 */
class MicInUseTest {

    @Test
    fun `another app is blamed only with evidence`() {
        assertEquals(RecognitionError.MIC_IN_USE, MicInUse.classify(callActive = true, silenced = false))
        assertEquals(RecognitionError.MIC_IN_USE, MicInUse.classify(callActive = false, silenced = true))
        // No evidence: the cause is unknown (route change, hardware), so the
        // generic failure stays - as iOS keeps its generic error.
        assertEquals(RecognitionError.AUDIO_ERROR, MicInUse.classify(callActive = false, silenced = false))
    }

    @Test
    fun `call and VoIP modes count as a call, ringing and normal do not`() {
        assertTrue(MicInUse.isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(MicInUse.isCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(MicInUse.isCallMode(4)) // MODE_CALL_SCREENING
        assertTrue(MicInUse.isCallMode(5)) // MODE_CALL_REDIRECT
        assertTrue(MicInUse.isCallMode(6)) // MODE_COMMUNICATION_REDIRECT
        assertFalse(MicInUse.isCallMode(AudioManager.MODE_NORMAL))
        assertFalse(MicInUse.isCallMode(AudioManager.MODE_RINGTONE))
    }

}
