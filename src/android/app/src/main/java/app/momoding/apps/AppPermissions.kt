package app.momoding.apps

import com.openminis.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Each app's own permissions (plan step 2): the first time an app uses a kind of phone ability
 * — a phone command that reads the person's things or acts for them — the person is asked, on
 * the app's screen, whether this app may; the answer holds for this app only. This is on top of
 * the approvals every command already goes through, which still ask for deleting, sending, paying.
 */
object AppPermissions {
    /** Commands an app uses without asking: nothing of the person's is read, nothing is done for them. */
    private val QUIET = setOf("android-weather", "android-device", "android-speak", "android-player", "momoding-web")

    /** What the person is asked about, by command. */
    private val LABELS = mapOf(
        "android-calendar" to R.string.mo_app_cap_calendar,
        "android-contacts" to R.string.mo_app_cap_contacts,
        "android-sms" to R.string.mo_app_cap_sms,
        "android-notification" to R.string.mo_app_cap_notifications,
        "android-location" to R.string.mo_app_cap_location,
        "android-photos" to R.string.mo_app_cap_photos,
        "android-clipboard" to R.string.mo_app_cap_clipboard,
        "android-alarm" to R.string.mo_app_cap_alarm,
        "android-open" to R.string.mo_app_cap_open,
        "android-speech" to R.string.mo_app_cap_speech,
        "minis-scheduled" to R.string.mo_app_cap_routines,
    )

    fun needsAsking(command: String) = command !in QUIET

    /** The string resource naming [command] for the person; null: say the command itself. */
    fun label(command: String): Int? = LABELS[command]

    class Request(val app: MoApps.App, val command: String) {
        internal val answer = CompletableDeferred<Boolean>()
        fun answer(allowed: Boolean) { answer.complete(allowed) }
    }

    private val _pending = MutableStateFlow<Request?>(null)
    /** The question on screen now, if any: the app's host shows it. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()
    private val asking = Mutex()

    /** The person's answers live outside the sandbox; `.momoding/permissions.json` is a read-only copy for the app's Momoding. */
    // never read back from the sandbox copy: what is written there is not the person's answer
    private fun file(app: MoApps.App) = File(app.vault, "${app.name}.permissions.json")

    @Synchronized fun granted(app: MoApps.App): JSONObject =
        runCatching { JSONObject(file(app).readText()) }.getOrDefault(JSONObject())

    @Synchronized private fun record(app: MoApps.App, command: String, allowed: Boolean) {
        val all = granted(app).put(command, allowed).toString(2)
        file(app).writeText(all)
        File(app.platform, "permissions.json").let { it.setWritable(true); it.writeText(all); it.setReadOnly() }
    }

    /** Whether [app] may run [command] without asking now (nobody is there to ask). */
    fun allowedAlready(app: MoApps.App, command: String): Boolean =
        !needsAsking(command) || granted(app).opt(command) == true

    /** Whether [app] may run [command]: asked once, on its screen; no answer within a minute is no. */
    suspend fun check(app: MoApps.App, command: String): Boolean {
        if (!needsAsking(command)) return true
        granted(app).opt(command)?.let { return it == true }
        return asking.withLock {
            granted(app).opt(command)?.let { return@withLock it == true }
            val request = Request(app, command)
            _pending.value = request
            val allowed = withTimeoutOrNull(60_000) { request.answer.await() }
            _pending.value = null
            if (allowed != null) record(app, command, allowed)
            allowed == true
        }
    }
}
