package app.momoding.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What a run on the screen leaves behind: older runs keep the trace and the last screen, the oldest go. */
class HandsTracesTest {
    private fun hands(): File = Files.createTempDirectory("nm-hands").toFile().resolve("hands").apply { mkdirs() }

    private fun run(hands: File, name: String, steps: Int): File = File(hands, name).apply {
        mkdirs()
        for (i in 1..steps) File(this, "step-$i.jpg").writeBytes(byteArrayOf(1))
        File(this, "trace.jsonl").writeText("{}\n")
    }

    private fun shots(run: File): List<String> = run.listFiles()!!.map { it.name }.filter { it.endsWith(".jpg") }.sorted()

    @Test fun `the newest runs keep every screenshot`() {
        val h = hands()
        val runs = (1..3).map { run(h, "20261008-00000$it", 4) }
        HandsTraces.prune(h, keepFull = 10, keepRuns = 200)
        runs.forEach { assertEquals(4, shots(it).size) }
    }

    @Test fun `an older run keeps its trace and the last screen only`() {
        val h = hands()
        val old = run(h, "20261001-120000", 5)
        val recent = run(h, "20261008-120000", 3)
        HandsTraces.prune(h, keepFull = 1, keepRuns = 200)
        assertEquals(listOf("step-5.jpg"), shots(old))
        assertTrue(File(old, "trace.jsonl").exists())
        assertEquals(3, shots(recent).size)
    }

    @Test fun `beyond the cap the oldest runs go altogether`() {
        val h = hands()
        val names = (1..6).map { "20261008-10000$it" }
        names.forEach { run(h, it, 2) }
        HandsTraces.prune(h, keepFull = 2, keepRuns = 4)
        assertFalse(File(h, names[0]).exists())
        assertFalse(File(h, names[1]).exists())
        assertEquals(listOf("step-2.jpg"), shots(File(h, names[2])))
        assertEquals(listOf("step-2.jpg"), shots(File(h, names[3])))
        assertEquals(2, shots(File(h, names[4])).size)
        assertEquals(2, shots(File(h, names[5])).size)
    }

    @Test fun `nothing to prune is fine, and the step numbers are read as numbers`() {
        HandsTraces.prune(null)
        HandsTraces.prune(File("/nonexistent/nm-hands"))
        val h = hands()
        val r = run(h, "20261008-100001", 12) // step-12 outlives step-9 although "step-9" sorts after it
        File(r, "notes.txt").writeText("kept")
        HandsTraces.keepLastScreenOnly(r)
        assertEquals(listOf("step-12.jpg"), shots(r))
        assertTrue(File(r, "notes.txt").exists())
    }
}
