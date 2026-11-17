package app.momoding.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** momoding: the memory paragraph, the facts a turn added, and the commit step. */
class MemoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `empty memory still tells the model where it is and how to keep it`() {
        val p = Memory.render("# Memory\n")
        assertTrue(p.contains("/var/minis/memory"))
        assertTrue(p.contains("nothing yet"))
        assertFalse(p.contains("<memory>"))
    }

    @Test fun `content is included and capped`() {
        val p = Memory.render("- 叫我老王 [added: 2026-11-17]\n" + "x".repeat(Memory.MAX_CHARS * 2))
        assertTrue(p.contains("叫我老王"))
        assertTrue(p.contains("topic files and leave a link"))
        assertTrue(p.length < Memory.MAX_CHARS + 2000)
    }

    @Test fun `facts come from every topic file but not from identity, know-how or old notes`() {
        val dir = tmp.root
        dir.resolve("MEMORY.md").writeText("# Memory\n\n- 叫我老王\n\n## Index\n- [[people/妈妈]]\n")
        dir.resolve("people").mkdirs(); dir.resolve("people/妈妈.md").writeText("- 生日 3 月 5 日 [added: 2026-11-17]\n")
        dir.resolve("SOUL.md").writeText("- I am Momoding\n")
        dir.resolve("know-how").mkdirs(); dir.resolve("know-how/12306.md").writeText("- use the API\n")
        dir.resolve("days").mkdirs(); dir.resolve("days/2026-11-01.md").writeText("- went out\n")
        assertEquals(setOf("叫我老王", "[[people/妈妈]]", "生日 3 月 5 日 [added: 2026-11-17]"), Memory.facts(dir))
    }

    @Test fun `a fact reads plain`() {
        assertEquals("对花生过敏", Memory.plain("对花生过敏 [added: 2026-11-17]"))
        assertEquals("会议放下午", Memory.plain("会议放下午（2026-11-02）"))
        assertEquals("妈妈 生日 3 月 5 日", Memory.plain("[[people/妈妈]] 生日 3 月 5 日"))
    }

    @Test fun `the commit names the chat and what the person said, quoted for the shell`() {
        assertEquals("chat s1: 我对花生过敏，记一下", MemoryGit.message("s1", "我对花生过敏，记一下\n第二行"))
        assertEquals("chat s1", MemoryGit.message("s1", null))
        val script = MemoryGit.script("chat s1: it's mine")
        assertTrue(script.contains("-m 'chat s1: it'\\''s mine'"))
        assertTrue(script.contains("git diff --cached --quiet && exit 0"))
    }
}
