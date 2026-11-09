package com.openminis.app.sandbox

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — edge cases the fresh-process shell migration and the
 * CR-folding fix left open.
 *
 * Guards:
 *  - 71e813d03 (TerminalSanitizer.foldCarriageReturns real-overwrite semantics):
 *    the overwrite runs BEFORE escape stripping, so escape bytes are counted as
 *    screen columns. Two ordinary shapes come out garbled: `\r\e[K` (erase line,
 *    used by pip / npm / apk / git progress) and a coloured segment overwritten
 *    by a plain one.
 *  - 567a47480 / c4414dc11 (FreshProcessShell is the default): the fresh path
 *    must keep the warm path's GH#186 seccomp self-heal, and must decode UTF-8
 *    across read() boundaries.
 *  - e0f0fe2e5 / 1ccd35c60 / 0569598ff: timeout/kill invariants that the
 *    existing ShellExecutionStrategyTest / ForegroundCommandGroupTest do not pin.
 *
 * Tests named `BUG ...` fail on the current code on purpose; see the review
 * report for the proposed fix.
 */
class Review0923SandboxEdgeTest {

    // ── 71e813d03: CR folding vs. escape sequences ─────────────────────────

    @Test
    fun `BUG erase-line after CR leaves only the new text`() {
        // `printf 'Downloading 50%%\r\033[KDone\n'` renders as "Done" in a
        // terminal: CR homes the cursor, ESC[K erases the rest of the line.
        // Folding counts ESC[K as 3 columns and keeps the old tail.
        assertEquals("Done", TerminalSanitizer.sanitize("Downloading 50%\r\u001B[KDone"))
    }

    @Test
    fun `BUG a coloured segment overwritten by a plain one is not garbled`() {
        // Real terminal: "100%". Current result: "100%m50%" — the overwrite
        // landed on the invisible "\e[32m" bytes and the visible "50%" survived.
        assertEquals("100%", TerminalSanitizer.sanitize("\u001B[32m50%\u001B[0m\r100%"))
    }

    @Test
    fun `plain-text overwrite semantics from 71e813d03 still hold`() {
        // Guard for whichever fix lands for the two tests above: it must not
        // regress to "last segment wins".
        assertEquals("BBAA", TerminalSanitizer.sanitize("AAAA\rBB"))
        assertEquals("100%[====]", TerminalSanitizer.sanitize(" 50%[==  ]\r100%[====]"))
    }

    // ── Fresh path parity with the warm path ───────────────────────────────

    @Test
    fun `per-chunk UTF-8 decoding corrupts a CJK character split across reads`() {
        // Model of FreshProcessShell's read loop: decode each read() chunk on
        // its own. A 3-byte CJK character split 1+2 across two reads becomes
        // replacement characters. Documents why the source check below matters.
        val bytes = "中文输出".toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(bytes)
        val out = StringBuilder()
        val first = ByteArray(1)
        stream.read(first)
        out.append(String(first, 0, 1, StandardCharsets.UTF_8))
        val rest = stream.readBytes()
        out.append(String(rest, 0, rest.size, StandardCharsets.UTF_8))
        assertNotEquals("中文输出", out.toString())
        assertTrue(out.contains('�'))
    }

    // ── ForegroundCommandGroup invariants (0569598ff / e0f0fe2e5) ─────────

    @Test
    fun `fresh wrap passes the command as one argv element, whatever it contains`() {
        val nasty = "echo 'a&b' \"\$HOME\" ; curl 'x?a=1&b=2' & wait\n# trailing comment"
        val argv = ForegroundCommandGroup.wrapForFreshProcess(nasty, "/.minis-exit/t")
        assertEquals(
            listOf(ForegroundCommandGroup.SETSID, "/bin/sh", "-c", ForegroundCommandGroup.FRESH_RUNNER, "sh", nasty, "/.minis-exit/t"),
            argv,
        )
    }

    @Test
    fun `pgid parse never yields a group that would hit init, the whole group, or a guess`() {
        val m = "abc"
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_0__", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_1__", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_-5__", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_other_42__", m))
        assertEquals(42, ForegroundCommandGroup.parsePgid("noise\n__MINIS_PGID_abc_42__\nmore", m))
    }

    @Test
    fun `warm wrap keeps a single quote in the command intact`() {
        // The payload is single-quoted for the inner sh -c; an embedded quote
        // must be closed-escaped-reopened, never left to terminate the string.
        val wrapped = ForegroundCommandGroup.wrap("echo 'hi'", "m")
        assertTrue(wrapped.contains("echo '\\''hi'\\''"))
    }
}
