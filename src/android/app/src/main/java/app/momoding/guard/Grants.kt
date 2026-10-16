// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/guard/Grants.kt
package app.momoding.guard

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class GrantScope { SESSION, ALWAYS }

/**
 * One approval the user chose to remember. A grant is a capability bound to a class of
 * action and, for standing grants, to one object (a folder, a host, a recipient) — never
 * "anything of this kind, anywhere, forever".
 */
data class Grant(
    val key: String,
    val riskClass: RiskClass,
    val target: String?,
    val scope: GrantScope,
    val sessionId: String? = null,
    val label: String = "",
    val grantedAt: Long = System.currentTimeMillis(),
)

/**
 * Remembered approvals. Session grants live in memory and die with the chat (cleared when
 * the chat is cleared or deleted, and on process exit); since 0.1.31 one is bound to the
 * object it was given for when the call had one (the app a tap lands in, the host a command
 * sends to), so "for this chat" on WeChat says nothing about Alipay — the same rule the
 * runtime's "for this conversation" follows. "Always" grants are written to
 * `minis-global/momoding/grants.json` and listed under Settings → Permissions, where any of
 * them can be revoked.
 */
object Grants {
    private const val TAG = "Grants"
    private var file: File? = null
    private val session = ArrayList<Grant>()
    private val _always = MutableStateFlow<List<Grant>>(emptyList())
    val always: StateFlow<List<Grant>> = _always.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, "minis-global/momoding").apply { mkdirs() }
        file = File(dir, "grants.json")
        load()
    }

    fun key(riskClass: RiskClass, target: String?): String =
        riskClass.name.lowercase() + ":" + (target ?: "*")

    /**
     * True when a remembered approval covers this class for this target in this session.
     * Money is never covered by a chat-wide grant: only "always for X", chosen on the card
     * and confirmed with the screen lock, and only for exactly X.
     */
    @Synchronized
    fun allows(riskClass: RiskClass, target: String?, sessionId: String?): Boolean {
        if (riskClass != RiskClass.MONEY && sessionId != null &&
            session.any { it.sessionId == sessionId && it.riskClass == riskClass && (it.target == null || it.target == target) }
        ) return true
        if (target == null) return false
        return _always.value.any { it.riskClass == riskClass && it.target == target }
    }

    /** "Always" grants of the highest tier — payments that run without a card. */
    fun highest(): List<Grant> = _always.value.filter { it.riskClass.tier == RiskTier.HIGHEST }

    /**
     * "Allow for this chat": this kind of action on [target] (any object when the call had
     * none), until the chat is cleared.
     */
    @Synchronized
    fun grantSession(riskClass: RiskClass, sessionId: String, label: String, target: String? = null) {
        if (session.any { it.sessionId == sessionId && it.riskClass == riskClass && (it.target == null || it.target == target) }) return
        session.add(Grant(key(riskClass, target), riskClass, target, GrantScope.SESSION, sessionId, label))
        AppLogger.info(TAG, "session grant ${key(riskClass, target)} for $sessionId")
    }

    /** "Always allow for X": this kind of action on exactly this object, until revoked. */
    @Synchronized
    fun grantAlways(riskClass: RiskClass, target: String, label: String) {
        val k = key(riskClass, target)
        if (_always.value.any { it.key == k }) return
        _always.value = _always.value + Grant(k, riskClass, target, GrantScope.ALWAYS, null, label)
        save()
        AppLogger.info(TAG, "always grant $k")
    }

    @Synchronized
    fun revoke(key: String) {
        if (_always.value.none { it.key == key }) return
        _always.value = _always.value.filterNot { it.key == key }
        save()
    }

    @Synchronized
    fun revokeAll() {
        _always.value = emptyList()
        save()
    }

    @Synchronized
    fun clearSession(sessionId: String) {
        session.removeAll { it.sessionId == sessionId }
    }

    @Synchronized
    fun sessionGrants(sessionId: String): List<Grant> = session.filter { it.sessionId == sessionId }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val list = ArrayList<Grant>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cls = runCatching { RiskClass.valueOf(o.getString("class").uppercase()) }.getOrNull() ?: continue
                val target = o.optString("target").ifEmpty { null } ?: continue
                list.add(Grant(key(cls, target), cls, target, GrantScope.ALWAYS, null, o.optString("label"), o.optLong("grantedAt", System.currentTimeMillis())))
            }
            _always.value = list
        } catch (e: Exception) {
            AppLogger.warning(TAG, "grants.json unreadable: ${e.message}")
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            _always.value.forEach { g ->
                arr.put(JSONObject().apply {
                    put("class", g.riskClass.name.lowercase())
                    put("target", g.target)
                    put("label", g.label)
                    put("grantedAt", g.grantedAt)
                })
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString(2))
            if (!tmp.renameTo(f)) { f.writeText(arr.toString(2)); tmp.delete() }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "grants.json not saved: ${e.message}")
        }
    }
}
