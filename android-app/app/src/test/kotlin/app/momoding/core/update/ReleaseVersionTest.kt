package app.momoding.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun `orders prereleases using semantic version rules`() {
        val alpha2 = checkNotNull(ReleaseVersion.parse("v0.1.0-alpha.2"))
        val alpha10 = checkNotNull(ReleaseVersion.parse("0.1.0-alpha.10"))
        val stable = checkNotNull(ReleaseVersion.parse("0.1.0"))
        val nextMinor = checkNotNull(ReleaseVersion.parse("0.2.0-alpha.1"))

        assertTrue(alpha10 > alpha2)
        assertTrue(stable > alpha10)
        assertTrue(nextMinor > stable)
    }

    @Test
    fun `accepts build metadata but ignores it for ordering`() {
        assertEquals(
            ReleaseVersion.parse("1.2.3-alpha.1"),
            ReleaseVersion.parse("v1.2.3-alpha.1+build.42"),
        )
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(ReleaseVersion.parse("alpha.2"))
        assertNull(ReleaseVersion.parse("01.2.3"))
        assertNull(ReleaseVersion.parse("1.2"))
    }
}
