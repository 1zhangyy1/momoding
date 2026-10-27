package app.momoding.guard

import android.content.Context
import android.content.pm.PackageManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A permission Momoding needs, it turns on itself where it can; only where it can't is the person
 * asked. With Shizuku on, `pm grant` runs as the shell and nobody has to answer a dialog — also in
 * the background, when nobody could. What was turned on this way is said on the output of the
 * command that needed it, so Momoding tells the person in a line: nothing is turned on unseen.
 * Without Shizuku the usual path follows (the system dialog when the app is in front, else the card).
 */
object SelfGrant {
    private const val TAG = "SelfGrant"
    private val turnedOn = ConcurrentLinkedQueue<String>()

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Turns [permissions] on through Shizuku; true when all of them are on afterwards. */
    fun grant(context: Context, permissions: List<String>): Boolean {
        val missing = permissions.filterNot { granted(context, it) }
        if (missing.isEmpty()) return true
        if (!ShizukuManager.isReady()) return false
        for (p in missing) {
            val r = ShizukuManager.runProcess(arrayOf("pm", "grant", context.packageName, p))
            if (r.exitCode != 0) AppLogger.warning(TAG, "pm grant $p: exit=${r.exitCode} ${r.combined}")
        }
        val now = missing.filter { granted(context, it) }
        turnedOn += now.map { it.substringAfterLast('.') }
        if (now.isNotEmpty()) AppLogger.info(TAG, "turned on through Shizuku: ${now.joinToString()}")
        return now.size == missing.size
    }

    /** What was turned on since last asked, as a line for the command's output; empty if nothing. */
    fun notice(): String {
        val names = generateSequence { turnedOn.poll() }.toList()
        if (names.isEmpty()) return ""
        return "\n(Momoding turned on ${names.distinct().joinToString(", ")} itself through Shizuku — tell the person in a line.)\n"
    }
}
