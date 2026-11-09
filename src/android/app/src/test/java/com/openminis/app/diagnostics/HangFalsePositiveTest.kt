package com.openminis.app.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-hang-false-positive] Simple streaming replies showed
 * "Performance protection: simplified rendering while streaming". The notice
 * came from a render breaker keyed on the GLOBAL hang count, and on the
 * reporting Pixel 6 that count was fed by:
 *  - false positives: wall-clock gaps from deep sleep ("41509s") and a
 *    post-boot clock correction ("16521299s", main thread idle);
 *  - real stalls OUTSIDE the renderer: 21 s of the launch resolver waiting on
 *    ProviderRepository.configLock behind a saveConfig.
 * None of the 33 recorded samples were in streaming rendering.
 */
class HangFalsePositiveTest {

    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "F.kt", 1)

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `a main thread parked in the looper is idle, not hung`() {
        val idle = arrayOf(
            frame("android.os.MessageQueue", "nativePollOnce"),
            frame("android.os.MessageQueue", "next"),
            frame("android.os.Looper", "loopOnce"),
        )
        assertTrue(HangDetector.isIdleMainStack(idle))
    }

    @Test
    fun `a main thread doing work or waiting on a lock is not idle`() {
        val lockWait = arrayOf(
            frame("com.openminis.app.data.repository.ProviderRepository", "getInstances"),
            frame("com.openminis.app.ui.navigation.AppNavigationKt\$AppNavigation\$2\$1", "invokeSuspend"),
        )
        val textLayout = arrayOf(frame("android.text.StaticLayout", "generate"))
        assertFalse(HangDetector.isIdleMainStack(lockWait))
        assertFalse(HangDetector.isIdleMainStack(textLayout))
        assertFalse(HangDetector.isIdleMainStack(emptyArray()))
    }

}
