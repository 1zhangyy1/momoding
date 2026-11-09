package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-android-skill-scan-parity] Sending a message must not touch the skill
 * directory, and the scan that does run must be linear.
 *
 * Field report (realme RMX5010, 1.14): every send froze the UI for 1.7–2.7 s.
 * buildSystemPrompt() called reloadFromDisk() on the main thread — twice per
 * send — and with 283 of 490 skills missing, the orphan circuit breaker
 * re-queried every row and stat()ed every SKILL.md once per missing skill.
 */
class SkillScanParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun skill(root: File, id: String, body: String = "---\nname: $id\n---\n"): File =
        File(root, id).apply { mkdirs() }.let { dir -> File(dir, "SKILL.md").apply { writeText(body) } }

    // -- Disk listing --

    @Test fun `only directories holding a SKILL md count as skills on disk`() {
        val root = tmp.newFolder("skills")
        skill(root, "a")
        skill(root, "b")
        File(root, "empty-dir").mkdirs()
        File(root, "stray.txt").writeText("x")
        File(root, "c").mkdirs(); File(root, "c/README.md").writeText("no skill md")

        assertEquals(setOf("a", "b"), SkillDiskSignature.skillIdsOnDisk(root))
    }

    @Test fun `a missing skills directory lists nothing and signs as zero`() {
        val gone = File(tmp.root, "never-created")
        assertEquals(emptySet<String>(), SkillDiskSignature.skillIdsOnDisk(gone))
        assertEquals(0L, SkillDiskSignature.compute(gone))
    }

    // -- Signature: what a shell command can do to /var/minis/skills --

    @Test fun `signature is stable when nothing changed`() {
        val root = tmp.newFolder("skills")
        skill(root, "a"); skill(root, "b")
        assertEquals(SkillDiskSignature.compute(root), SkillDiskSignature.compute(root))
    }

    @Test fun `git clone of a new skill changes the signature`() {
        val root = tmp.newFolder("skills")
        skill(root, "a")
        val before = SkillDiskSignature.compute(root)
        skill(root, "cloned")
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `rm -rf of a skill changes the signature`() {
        val root = tmp.newFolder("skills")
        skill(root, "a"); skill(root, "b")
        val before = SkillDiskSignature.compute(root)
        File(root, "b").deleteRecursively()
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `editing SKILL md in place changes the signature`() {
        val root = tmp.newFolder("skills")
        val md = skill(root, "a", "---\nname: a\ndescription: old\n---\n")
        md.setLastModified(1_000_000L)
        val before = SkillDiskSignature.compute(root)
        md.writeText("---\nname: a\ndescription: a much longer new description\n---\n")
        md.setLastModified(2_000_000L)
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `a same-size rewrite is caught by mtime alone`() {
        val root = tmp.newFolder("skills")
        val md = skill(root, "a", "---\nname: a\ndescription: 1111\n---\n")
        md.setLastModified(1_000_000L)
        val before = SkillDiskSignature.compute(root)
        md.writeText("---\nname: a\ndescription: 2222\n---\n")
        md.setLastModified(2_000_000L)
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `files other than SKILL md do not trigger a reload`() {
        val root = tmp.newFolder("skills")
        skill(root, "a")
        val before = SkillDiskSignature.compute(root)
        File(root, "a/scripts").mkdirs()
        File(root, "a/scripts/run.py").writeText("print(1)")
        assertEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `signature does not depend on listing order`() {
        val r1 = tmp.newFolder("r1"); val r2 = tmp.newFolder("r2")
        for (id in listOf("x", "a", "m")) skill(r1, id).setLastModified(5_000_000L)
        for (id in listOf("m", "x", "a")) skill(r2, id).setLastModified(5_000_000L)
        assertEquals(SkillDiskSignature.compute(r1), SkillDiskSignature.compute(r2))
    }

    // -- Circuit breaker rule (decided once per load) --

    @Test fun `the reporter's library trips the breaker`() {
        assertTrue(SkillDiskSignature.breakerTripped(totalCustom = 490, missingCustom = 283))
    }

    @Test fun `a few deliberate deletions still prune`() {
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 490, missingCustom = 5))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 10, missingCustom = 3))
    }

    @Test fun `tiny libraries always prune`() {
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 2, missingCustom = 2))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 5, missingCustom = 2))
    }

    @Test fun `the forty percent boundary trips`() {
        assertTrue(SkillDiskSignature.breakerTripped(totalCustom = 10, missingCustom = 4))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 100, missingCustom = 39))
    }

}
