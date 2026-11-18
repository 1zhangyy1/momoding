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
        assertTrue(Memory.render("# Memory\n", listOf("people/妈妈.md")).contains("Other memory files: people/妈妈.md"))
        assertFalse(p.contains("<memory>"))
    }

    @Test fun `content is included and capped`() {
        val p = Memory.render("- 叫我老王 [added: 2026-11-17]\n" + "x".repeat(Memory.MAX_CHARS * 2))
        assertTrue(p.contains("叫我老王"))
        assertTrue(p.contains("move details into topic files"))
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

/** momoding: the background reflection's input, output and how it writes. */
class MemoryReflectTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `only paths inside the person's memory`() {
        assertEquals("people/妈妈.md", MemoryReflect.safePath("people/妈妈.md"))
        assertEquals("MEMORY.md", MemoryReflect.safePath("/var/minis/memory/MEMORY.md"))
        listOf("SOUL.md", "know-how/x.md", "../x.md", "/etc/x.md", "a.txt", "days/2026-01-01.md", "").forEach {
            assertEquals(it, null, MemoryReflect.safePath(it))
        }
    }

    @Test fun `parse takes the files and nothing else`() {
        val w = MemoryReflect.parse("""ok {"files":[{"path":"MEMORY.md","content":"# Memory\n- 住望京"},{"path":"SOUL.md","content":"x"}]}""")
        assertEquals(listOf("MEMORY.md" to "# Memory\n- 住望京"), w)
        assertTrue(MemoryReflect.parse("""{"files":[]}""").isEmpty())
        assertTrue(MemoryReflect.parse("no json").isEmpty())
    }

    @Test fun `a file the chat changed meanwhile is left alone`() {
        val dir = tmp.root
        dir.resolve("MEMORY.md").writeText("# Memory\n")
        val before = MemoryReflect.files(dir)
        dir.resolve("MEMORY.md").writeText("# Memory\n- 叫我晓晓\n")
        val wrote = MemoryReflect.apply(dir, before, listOf("MEMORY.md" to "# Memory\n- 住望京", "places/家.md" to "- 望京"))
        assertEquals(listOf("places/家.md"), wrote)
        assertEquals("# Memory\n- 叫我晓晓\n", dir.resolve("MEMORY.md").readText())
    }

    @Test fun `the question holds only the person's words, oldest first, with when`() {
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).parse("2026-11-17 14:02")!!.time
        val said = listOf(MemoryReflect.Said(at, "s1", "我住望京"), MemoryReflect.Said(at + 60_000, "s2", "谢谢"))
        val q = MemoryReflect.question(mapOf("MEMORY.md" to "# Memory"), said, "2026-11-17 星期二")
        assertTrue(q.contains("The person said:\n[2026-11-17 14:02] 我住望京\n[2026-11-17 14:03] 谢谢\n"))
    }

    @Test fun `the reflect commit lists each thing said with its chat`() {
        val m = MemoryReflect.commitMessage(listOf(MemoryReflect.Said(1, "s1", "我住望京"), MemoryReflect.Said(2, "s1", "谢谢"), MemoryReflect.Said(3, "s2", "我一般坐二等座")))
        assertEquals("reflect: what the person said in 2 chats\n\nchat s1: 我住望京\nchat s1: 谢谢\nchat s2: 我一般坐二等座", m)
    }

    @Test fun `what was heard waits in order, and only what was looked at is dropped`() {
        val f = tmp.root.resolve("heard.jsonl")
        assertTrue(MemoryReflect.Heard.read(f).isEmpty())
        MemoryReflect.Heard.add(f, MemoryReflect.Said(1, "s1", "我住望京\n第二行"))
        MemoryReflect.Heard.add(f, MemoryReflect.Said(2, "s1", "我一般坐二等座"))
        val looked = MemoryReflect.Heard.read(f)
        MemoryReflect.Heard.add(f, MemoryReflect.Said(3, "s2", "谢谢")) // said while looking
        assertEquals(listOf("我住望京\n第二行", "我一般坐二等座"), looked.map { it.words })
        MemoryReflect.Heard.drop(f, looked.size)
        assertEquals(listOf("谢谢"), MemoryReflect.Heard.read(f).map { it.words })
        MemoryReflect.Heard.drop(f, 1)
        assertFalse(f.exists())
    }
}
