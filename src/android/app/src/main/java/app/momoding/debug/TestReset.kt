package app.momoding.debug

import android.content.Context
import app.momoding.chat.Drafts
import app.momoding.chat.RoutineWakes
import app.momoding.guard.Grants
import app.momoding.guard.RuleHits
import com.openminis.app.scheduled.ScheduledTaskManager
import org.json.JSONObject
import java.io.File

/**
 * Debug builds: put Momoding's own state back to a fresh install's, keeping the provider, the key
 * and the system grants — for walkthroughs (`momoding.reset` over the debug RPC). Chats are
 * deleted separately with `chat.session.delete`. Done inside the app so in-memory state
 * (remembered approvals, rule hits, card states, routine alarms) goes with what is on disk.
 */
object TestReset {
    private const val PREFS = "momoding"
    private val PREF_PREFIXES = listOf("drafts.", "ask.", "rules.", "routines.", "main_chat.", "firstRun.")

    fun reset(context: Context): JSONObject {
        val scheduled = ScheduledTaskManager(context)
        val routines = scheduled.list().onEach { scheduled.delete(it.id) }.size

        val memoryDir = File(context.filesDir, "minis-global/memory")
        val notes = memoryDir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name !in setOf("SOUL.md", "GLOBAL.md") }
            .orEmpty().count { it.delete() }

        val rules = Grants.always.value.onEach { Grants.revoke(it.key) }.size
        RuleHits.clear()
        Drafts.clear()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = prefs.all.keys.filter { k -> PREF_PREFIXES.any { k.startsWith(it) } }
        prefs.edit().apply { keys.forEach { remove(it) } }.commit()
        RoutineWakes.init(context)

        return JSONObject().put("routines", routines).put("notes", notes).put("rules", rules).put("prefs", keys.size)
    }
}
