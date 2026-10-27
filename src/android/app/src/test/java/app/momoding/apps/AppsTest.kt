package app.momoding.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun touch(f: File, text: String, at: Long) { f.writeText(text); f.setLastModified(at) }

    @Test fun `a command line splits as the shell would`() {
        assertEquals(listOf("android-calendar", "list", "--next", "2"), AppBridge.words("android-calendar list --next 2"))
        assertEquals(listOf("momoding-web", "search", "上海 迪士尼"), AppBridge.words("momoding-web search \"上海 迪士尼\""))
        assertEquals(listOf("a", "it's", "b c"), AppBridge.words("a it\\'s 'b c'"))
        assertEquals(listOf("x", ""), AppBridge.words("x \"\""))
    }

    @Test fun `only phone commands run from an app`() {
        assertTrue(AppBridge.runnable("android-sms"))
        assertTrue(AppBridge.runnable("momoding-web"))
        assertFalse(AppBridge.runnable("sh"))
        assertFalse(AppBridge.runnable("momoding-hands"))
        assertFalse(AppBridge.runnable("momoding-app"))
    }

    @Test fun `values go back to the page as JSON literals`() {
        assertEquals("\"他说 \\\"好\\\"\"", AppBridge.literal("他说 \"好\""))
        assertEquals("{\"a\":1}", AppBridge.literal(org.json.JSONObject().put("a", 1)))
        assertEquals("null", AppBridge.literal(null))
        assertEquals("3", AppBridge.literal(3))
    }

    @Test fun `the bridge is the page's first script`() {
        val app = MoApps.App("ledger", tmp.root)
        val withHead = AppPage.withBridge(app, "<!doctype html><html><head><title>x</title><script src=a.js></script></head></html>")
        assertTrue(withHead.indexOf("window.momoding") < withHead.indexOf("a.js"))
        assertTrue(withHead.indexOf("<head>") < withHead.indexOf("window.momoding"))
        val bare = AppPage.withBridge(app, "<p>hi</p>")
        assertTrue(bare.startsWith("<script>"))
        // each load gets its own token, and the token names the app
        val token = Regex("T = \"([0-9a-f]{32})\"").find(bare)!!.groupValues[1]
        assertEquals("ledger", AppPage.appFor(token))
        assertNotEquals(token, Regex("T = \"([0-9a-f]{32})\"").find(withHead)!!.groupValues[1])
    }

    @Test fun `app names are short lowercase words`() {
        assertTrue(MoApps.validName("ledger"))
        assertTrue(MoApps.validName("water-2"))
        assertFalse(MoApps.validName("Ledger"))
        assertFalse(MoApps.validName("记账"))
        assertFalse(MoApps.validName("../x"))
        assertFalse(MoApps.validName("a"))
    }

    @Test fun `a change to the app's own files is seen, git's and the platform's are not`() {
        val dir = tmp.newFolder("ledger")
        touch(File(dir, "index.html"), "a", 1_000_000)
        val before = MoAppActivity.stamp(dir)
        File(dir, ".git").mkdirs(); touch(File(dir, ".git/HEAD"), "x", 9_000_000)
        File(dir, ".momoding").mkdirs(); touch(File(dir, ".momoding/log"), "x", 9_000_000)
        assertEquals(before, MoAppActivity.stamp(dir))
        touch(File(dir, "app.js"), "b", 2_000_000)
        assertEquals(2_000_000, MoAppActivity.stamp(dir))
    }

    @Test fun `an app listens the way routines do`() {
        val manifest = org.json.JSONObject("""{"name":"记账","momoding":{"listen":[{"app":"sms","match":"消费|支付"},{"app":"支付宝"},{}]}}""")
        val rules = AppEvents.listens(manifest)
        assertEquals(2, rules.size)
        assertTrue(AppEvents.matches(rules[0], "短信", "com.google.android.apps.messaging", isSms = true, body = "招商银行\n您尾号1234的卡消费56.80元"))
        assertFalse(AppEvents.matches(rules[0], "短信", "com.google.android.apps.messaging", isSms = true, body = "您的验证码是 1234"))
        assertFalse(AppEvents.matches(rules[0], "微信", "com.tencent.mm", isSms = false, body = "消费提醒"))
        assertTrue(AppEvents.matches(rules[1], "支付宝", "com.eg.android.AlipayGphone", isSms = false, body = "任何内容"))
        assertTrue(AppEvents.listens(org.json.JSONObject("{}")).isEmpty())
    }

    @Test fun `what an arrival needs the person's OK for`() {
        assertEquals("android-sms", AppEvents.ability(org.json.JSONObject().put("sms", true)))
        assertEquals("android-notification", AppEvents.ability(org.json.JSONObject().put("sms", false)))
        assertEquals(null, AppEvents.ability(org.json.JSONObject().put("type", "share")))
    }

    @Test fun readsTellsLookingFromChanging() {
        assertTrue(AppBridge.reads(listOf("android-calendar", "list", "--days", "7")))
        assertTrue(AppBridge.reads(listOf("android-weather")))
        assertTrue(AppBridge.reads(listOf("momoding-web", "https://example.com")))
        assertFalse(AppBridge.reads(listOf("android-calendar", "add", "--title", "x")))
        assertFalse(AppBridge.reads(listOf("android-sms", "send", "10086", "hi")))
        assertFalse(AppBridge.reads(listOf("android-speak", "你好")))
    }
}
