package app.momoding.know

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySkillsTest {
    private val index = """{"version":1,"skills":[
      {"name":"cn-train-flight-search","description":"查国内高铁和机票","sites":["kyfw.12306.cn","m.ly.com"],"topics":["travel","高铁","机票"],"verified":"2026-10-23","path":"skills/cn-train-flight-search/SKILL.md"},
      {"name":"../evil","description":"x","sites":[],"topics":[],"verified":"","path":"skills/../evil/SKILL.md"},
      {"name":"odd-path","description":"x","sites":[],"topics":[],"verified":"","path":"elsewhere/SKILL.md"}]}"""

    @Test
    fun `the index keeps only well-formed skills at their own path`() {
        val items = CommunitySkills.parseIndex(index)
        assertEquals(listOf("cn-train-flight-search"), items.map { it.name })
        assertTrue(CommunitySkills.parseIndex("not json").isEmpty())
    }

    @Test
    fun `lookup matches by site, topic or words`() {
        val items = CommunitySkills.parseIndex(index)
        assertEquals(1, CommunitySkills.match(items, "kyfw.12306.cn").size)
        assertEquals(1, CommunitySkills.match(items, "机票").size)
        assertEquals(1, CommunitySkills.match(items, "m.ly.com").size)
        assertTrue(CommunitySkills.match(items, "外卖").isEmpty())
    }

    @Test
    fun `the share link opens a new file in the community repo`() {
        val url = CommunitySkills.shareUrl("my-skill", "---\nname: my-skill\n---\n# x")
        assertTrue(url, url.startsWith("https://github.com/1zhangyy1/momoding-skills/new/main?filename=skills%2Fmy-skill%2FSKILL.md&value="))
    }
}
