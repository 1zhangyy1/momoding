package app.momoding.know

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowToolsTest {
    private val trains = KnowTools.Tool("12306-trains", "12306-trains.py", "京沪等线路某天的高铁车次和票价", "python3 <path> 2026-11-21 BJP SHH", "kyfw.12306.cn", 1)

    @Test fun `a tool is found by its site, its name or what it does`() {
        assertEquals(listOf(trains), KnowTools.match(listOf(trains), "12306"))
        assertEquals(listOf(trains), KnowTools.match(listOf(trains), "kyfw.12306.cn 高铁"))
        assertEquals(listOf(trains), KnowTools.match(listOf(trains), "高铁车次"))
        assertTrue(KnowTools.match(listOf(trains), "ctrip 酒店").isEmpty())
    }

    @Test fun `a tool name is a safe file name`() {
        assertEquals("12306-trains", KnowTools.slug("12306 Trains!"))
        assertNull(KnowTools.slug("高铁"))
    }
}
