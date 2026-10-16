package app.momoding.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: the USER.md instruction is in the prompt even before the file exists. */
class UserFileTest {
    @Test fun `empty file still tells the model to create it`() {
        val p = UserFile.render("")
        assertTrue(p.contains("/var/minis/memory/USER.md"))
        assertTrue(p.contains("empty so far"))
    }

    @Test fun `content is included and capped`() {
        val p = UserFile.render("- Call me 老王\n" + "x".repeat(UserFile.MAX_CHARS * 2))
        assertTrue(p.contains("Call me 老王"))
        assertTrue(p.contains("tidy it"))
        assertFalse(p.contains("empty so far"))
        assertTrue(p.length < UserFile.MAX_CHARS + 1500)
    }
}
