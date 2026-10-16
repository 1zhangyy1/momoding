// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/hands/HandsTraces.kt
package app.momoding.hands

import java.io.File

/**
 * What a run on the screen leaves behind, and for how long. [HandsOperator] writes every step's
 * screenshot to `attachments/hands/<run>/step-N.jpg` next to the run's `trace.jsonl`; the chat
 * shows the last screenshot of a run. Those pictures are the person's screen, so they are not
 * kept without bound: once a run is older than the newest [KEEP_FULL_RUNS] runs it keeps its
 * trace and the one screenshot the chat shows, and beyond [KEEP_RUNS] runs the oldest run goes
 * altogether (the runtime keeps 200 traces and 400 screenshots, docs/gui.md). Pure file work, so
 * the unit tests run it on a temporary directory.
 */
object HandsTraces {
    /** Runs that keep every step's screenshot. */
    const val KEEP_FULL_RUNS = 10

    /** Runs that keep anything at all. */
    const val KEEP_RUNS = 200

    private val STEP_RX = Regex("""step-(\d+)\.jpg""")

    /**
     * Trims `hands/` (the parent of the run directories) before a new run starts. Run names are
     * timestamps (`yyyyMMdd-HHmmss`), so their order is their age. Never touches anything but
     * `step-N.jpg` files and whole run directories; a failure to delete is ignored.
     */
    fun prune(hands: File?, keepFull: Int = KEEP_FULL_RUNS, keepRuns: Int = KEEP_RUNS) {
        val runs = hands?.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        if (runs.size > keepRuns) {
            runs.take(runs.size - keepRuns).forEach { runCatching { it.deleteRecursively() } }
        }
        val remaining = runs.takeLast(minOf(runs.size, keepRuns))
        if (remaining.size > keepFull) {
            remaining.take(remaining.size - keepFull).forEach { keepLastScreenOnly(it) }
        }
    }

    /** Deletes every `step-N.jpg` of [run] but the one with the highest N (the chat's). */
    fun keepLastScreenOnly(run: File) {
        val shots = run.listFiles()?.mapNotNull { f ->
            STEP_RX.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to f }
        } ?: return
        if (shots.size <= 1) return
        val last = shots.maxOf { it.first }
        shots.filter { it.first != last }.forEach { runCatching { it.second.delete() } }
    }
}
