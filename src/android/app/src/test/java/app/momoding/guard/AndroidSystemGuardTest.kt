package app.momoding.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `android-system` (the phone shell): trusted, except what loses data or reaches another person. */
class AndroidSystemGuardTest {
    private fun asks(cmd: String) = ShellGuard.assess(cmd).needsApproval

    @Test fun `settings, toggles, apps and input just run`() {
        for (cmd in listOf(
            "android-system settings set system screen_brightness 128",
            "android-system exec cmd notification set_dnd priority",
            "android-system activity force-stop com.android.chrome",
            "android-system permission grant app.momoding android.permission.READ_CALL_LOG",
            "android-system exec dumpsys battery",
            "android-system exec \"settings put global airplane_mode_on 0\"",
            "android-system input tap 100 200",
            "android-system package list -3",
            "android-shizuku-cli exec pm list packages",
        )) assertFalse(cmd, asks(cmd))
    }

    @Test fun `losing the person's data asks`() {
        for (cmd in listOf(
            "android-system package uninstall com.android.chrome",
            "android-system package clear com.android.chrome",
            "android-system file rm /sdcard/DCIM/a.jpg",
            "android-system exec pm uninstall com.android.chrome",
            "android-system exec pm clear com.android.chrome",
            "android-system exec rm -rf /sdcard/Download",
            "android-system exec sh -c 'cd /sdcard && rm a.txt'",
            "android-shizuku-cli exec cmd package uninstall com.x",
        )) assertEquals(cmd, RiskClass.DESTRUCTIVE, ShellGuard.assess(cmd).riskClass)
    }

    @Test fun `reaching another person asks`() {
        assertTrue(asks("android-system exec am start -a android.intent.action.CALL -d tel:10086"))
    }
}
