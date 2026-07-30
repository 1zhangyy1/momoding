package app.momoding.core.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneLocalLinuxEnvironmentTest {
    @Test
    fun `resolver config keeps bounded Android DNS addresses`() {
        val rendered = renderPhoneLocalResolvConf(
            listOf(
                "10.0.2.3",
                "2001:4860:4860::8888",
                "10.0.2.3",
                "bad\nnameserver 6.6.6.6",
                "192.0.2.1",
                "192.0.2.2",
                "192.0.2.3",
            ),
        )

        assertEquals(
            """
            nameserver 10.0.2.3
            nameserver 2001:4860:4860::8888
            nameserver 192.0.2.1
            nameserver 192.0.2.2
            """.trimIndent() + "\n",
            rendered,
        )
        assertFalse(rendered.contains("6.6.6.6"))
    }

    @Test
    fun `resolver config falls back when Android has no safe DNS address`() {
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            renderPhoneLocalResolvConf(listOf("", "not-a-server")),
        )
    }

    @Test
    fun `apk hardlink compatibility is limited to standalone package mutations`() {
        assertEquals(true, requiresApkLinkCompatibility("apk add --no-cache python3 py3-pip"))
        assertEquals(true, requiresApkLinkCompatibility("/sbin/apk upgrade"))
        assertEquals(false, requiresApkLinkCompatibility("apk search python3"))
        assertEquals(false, requiresApkLinkCompatibility("apk add python3 && python3 script.py"))
        assertEquals(false, requiresApkLinkCompatibility("printf data > a && ln a b"))
    }
}
