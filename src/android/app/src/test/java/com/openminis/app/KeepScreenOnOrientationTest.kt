package com.openminis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-orientation-user] (OpenMinis#401) A shell_execute run rotated
 * the screen against a portrait lock on OnePlus / Android 16. MainActivity now
 * declares screenOrientation="user", and the keep-screen-on flag is only
 * written when it actually changes.
 */
class KeepScreenOnOrientationTest {

    /** WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON. */
    private val keepOn = 0x00000080

    @Test
    fun `the window is touched only when the flag changes`() {
        assertFalse("already on", keepScreenOnNeedsChange(keepOn, want = true))
        assertFalse("already off", keepScreenOnNeedsChange(0, want = false))
        assertTrue("turn on", keepScreenOnNeedsChange(0, want = true))
        assertTrue("turn off", keepScreenOnNeedsChange(keepOn, want = false))
        // Other flags do not matter.
        assertFalse(keepScreenOnNeedsChange(keepOn or 0x01000000, want = true))
        assertTrue(keepScreenOnNeedsChange(0x01000000, want = true))
    }

}
