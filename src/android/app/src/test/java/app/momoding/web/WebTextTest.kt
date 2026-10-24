package app.momoding.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTextTest {

    @Test fun `360 hits carry the real address, summary and date`() {
        val html = """<ul class="result"><li class="res-list" data-result-type="richsum"><h3 class="res-title">
            <a href="https://www.so.com/link?m=bs22" data-mdurl="https://www.gov.cn/zhengce/2021-04/26/content_5602132.htm"><em>国家医保局</em>:建立健全医保门诊共济保障机制_政策解读</a></h3>
            <div class='res-rich'><span class="g-c-gray">2021年4月26日&nbsp;-&nbsp;</span><span class="res-list-summary">在国新办日前举行的国务院政策例行吹风会上...</span></div></li>
            <li class="res-list"><h3 class="res-title"><a href="https://wenku.so.com/d/f909">国家医保门诊共济指导意见 - 360文库</a></h3></li></ul>"""
        val hits = WebText.hits360(html)
        assertEquals(2, hits.size)
        assertEquals("https://www.gov.cn/zhengce/2021-04/26/content_5602132.htm", hits[0].url)
        assertEquals("国家医保局:建立健全医保门诊共济保障机制_政策解读", hits[0].title)
        assertTrue(hits[0].snippet.startsWith("在国新办"))
        assertEquals("2021年4月26日", hits[0].date)
        assertEquals("https://wenku.so.com/d/f909", hits[1].url)
    }

    @Test fun `sogou hits take data-url over the link hop`() {
        val html = """<div class="results"><div class="vrwrap"><h3 class="vr-title"><a href="/link?url=hedJ">热点解析！国家医保局就门诊共济答疑</a></h3>
            <div class="text-layout"><div class="fz-mid space-txt">此次职工医保门诊共济保障机制改革…</div></div>
            <div class="r-sech" data-url="https://news.cctv.com/2023/02/25/ART.shtml"></div></div>
            <div class="vrwrap"><h3><a href="/link?url=abc">没有 data-url 的一条</a></h3></div></div>"""
        val hits = WebText.hitsSogou(html)
        assertEquals("https://news.cctv.com/2023/02/25/ART.shtml", hits[0].url)
        assertTrue(hits[0].snippet.startsWith("此次"))
        assertEquals("https://www.sogou.com/link?url=abc", hits[1].url)
    }

    @Test fun `a link hop page gives where it goes`() {
        val hop = """<meta content="always" name="referrer"><script>window.location.replace("https://news.cctv.com/a.shtml")</script><noscript><META http-equiv="refresh" content="0;URL='https://news.cctv.com/a.shtml'"></noscript>"""
        assertEquals("https://news.cctv.com/a.shtml", WebText.redirectTarget(hop))
        assertEquals(null, WebText.redirectTarget("<html><body><p>an ordinary page</p></body></html>"))
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
