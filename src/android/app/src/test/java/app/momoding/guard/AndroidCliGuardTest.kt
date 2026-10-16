package app.momoding.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: the phone's own commands ask before they change or remove the user's data. */
class AndroidCliGuardTest {
    private fun assess(cmd: String) = ShellGuard.assess(cmd)

    @Test
    fun `changing or removing the user's data asks`() {
        for (cmd in listOf(
            "android-contacts delete 42",
            "android-calendar delete --id 7",
            "android-calendar update --id 7 --title Lunch",
            "android-photos delete --ids 1,2 --confirm",
            "android-notification clear",
        )) {
            val r = assess(cmd)
            assertEquals(cmd, RiskClass.DESTRUCTIVE, r.riskClass)
            assertTrue(cmd, r.needsApproval)
        }
    }

    @Test
    fun `reading and creating stay free`() {
        for (cmd in listOf(
            "android-contacts search 王总",
            "android-calendar list --start 2026-10-16",
            "android-calendar create --title 回电话 --start 2026-10-17T15:00",
            "android-photos list --max 20",
            "android-notification list",
            "android-contacts delete --help",
        )) {
            assertFalse(cmd, assess(cmd).needsApproval)
        }
    }

    @Test
    fun `the remembered approval binds to the kind of data`() {
        assertEquals("contacts", assess("android-contacts delete 42").target)
        assertEquals("calendar", assess("android-calendar delete --id 7").target)
    }

    @Test
    fun `a delete hidden in a pipeline still asks`() {
        assertTrue(assess("android-contacts search 张 && android-contacts delete 9").needsApproval)
    }
}
