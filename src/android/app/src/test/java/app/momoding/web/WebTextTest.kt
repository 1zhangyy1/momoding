package app.momoding.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTextTest {

    @Test fun `exa answer becomes hits with passages and dates`() {
        val text = "Title: 票价查询\nURL: https://kyfw.12306.cn/otn/leftTicketPrice/init\nPublished: N/A\nAuthor: N/A\nHighlights:\n| 车次 | 二等座 |\n\n---\n\n" +
            "Title: 京沪高铁公布票价上浮 — 新京报\nURL: https://www.bjnews.com.cn/detail/1778661142129703.html\nPublished: 2026-05-13T00:00:00.000Z\nAuthor: N/A\nHighlights:\n公布票价将上浮20%"
        val answer = "event: message\ndata: " + org.json.JSONObject().put("result", org.json.JSONObject()
            .put("content", org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", text)))).toString() + "\n"
        val hits = WebText.exaHits(answer)
        assertEquals(2, hits.size)
        assertEquals("票价查询", hits[0].title)
        assertEquals("https://kyfw.12306.cn/otn/leftTicketPrice/init", hits[0].url)
        assertEquals(null, hits[0].date)
        assertEquals("| 车次 | 二等座 |", hits[0].snippet)
        assertEquals("2026-05-13", hits[1].date)
        assertEquals("公布票价将上浮20%", hits[1].snippet)
    }

    @Test(expected = IllegalStateException::class) fun `an exa error is said, not taken for no results`() {
        WebText.exaHits("""{"jsonrpc":"2.0","id":1,"error":{"code":429,"message":"rate limited"}}""")
    }

    @Test fun `page text keeps paragraphs and drops the chrome`() {
        val doc = Jsoup.parse("""<html><head><title>门诊共济 政策解读</title><style>p{}</style></head><body>
            <nav>首页 | 政策 | 互动</nav><header>中国政府网</header>
            <ul class="menu"><li><a href="/a">资讯</a></li><li><a href="/b">社保</a></li><li><a href="/c">公积金</a></li></ul>
            <div class="content"><h1>建立健全门诊共济保障机制</h1><p>一、  普通门诊费用纳入统筹基金支付范围。</p>
            <ul><li>起付标准</li><li>支付比例 50%</li></ul><table><tr><td>在职</td><td>50%</td></tr></table></div>
            <script>var x = 1;</script><footer>版权所有</footer></body></html>""")
        val page = WebText.page(doc)
        assertEquals("门诊共济 政策解读", page.title)
        val lines = page.text.lines()
        assertTrue(lines.contains("# 建立健全门诊共济保障机制"))
        assertTrue(lines.contains("一、 普通门诊费用纳入统筹基金支付范围。"))
        assertTrue(lines.contains("- 支付比例 50%"))
        assertTrue(page.text.contains("在职"))
        assertFalse(page.text.contains("首页"))
        assertFalse(page.text.contains("公积金"))
        assertFalse(page.text.contains("var x"))
        assertFalse(page.text.contains("版权所有"))
    }
}
