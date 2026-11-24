package app.momoding.phone

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import app.momoding.phone.adb.AdbConnection
import app.momoding.phone.adb.AdbPairing
import app.momoding.phone.adb.AdbPorts
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The shell's rights on this phone (uid 2000: settings, apps, permissions, input, other displays),
 * through wireless debugging paired with the phone itself (调研/M59; PhoneGo's way). Replaces
 * Shizuku: nothing else to install, two switches for the person — wireless debugging on, then
 * Pair, where the code is read off the system dialog ([PairingService]).
 *
 * Wireless debugging is the way in; the commands then run in a resident shell process
 * ([ShellServer]) that stays when Android switches wireless debugging off on a Wi-Fi change, until
 * the phone restarts. After a restart Momoding switches wireless debugging on itself (it granted
 * itself WRITE_SECURE_SETTINGS when paired), starts the resident shell again and switches it back
 * off; Android allows that on a Wi-Fi it was told to always allow. Every privileged call in the app goes through [runProcess] / [isReady]: the system command
 * (`android-system`), granting Momoding its own permissions ([app.momoding.guard.SelfGrant]), the
 * hands' hidden screen ([app.momoding.hands.HandsDisplay]) and the accessibility repair.
 */
object PhoneShell {
    private const val TAG = "PhoneShell"
    private const val ADB_WIFI = "adb_wifi_enabled"

    enum class State {
        /** Android 10 or older: no wireless debugging. */
        UNSUPPORTED,
        /** Never paired, or the phone forgot Momoding's key. */
        NOT_PAIRED,
        /** Paired, but wireless debugging is off now (most phones switch it off on restart). */
        OFF,
        READY,
    }

    data class Snapshot(val state: State, val detail: String = "")

    data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr".trimEnd()
    }

    private val _snapshot = MutableStateFlow(Snapshot(State.NOT_PAIRED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile private var app: Context? = null
    @Volatile private var connection: AdbConnection? = null
    private val lock = Any()

    fun init(application: Application) {
        app = application.applicationContext
        refresh()
        // after a restart Wi-Fi comes up later than Momoding: try again once it does
        val wifi = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            application.getSystemService(android.net.ConnectivityManager::class.java).registerNetworkCallback(wifi, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = refresh()
            })
        }
    }

    fun isReady(): Boolean = _snapshot.value.state == State.READY

    fun dir(context: Context): File = File(context.filesDir, "phone-shell")

    /** Rechecks the state in the background (a screen coming back to the front). */
    fun refresh() { Thread({ ensure() }, "phone-shell-refresh").start() }

    /** Ready now, connecting if it can. Blocking (network, mDNS): not on the main thread. */
    fun ensure(): Boolean {
        val context = app ?: return false
        return synchronized(lock) { resident(context) || connection?.isOpen == true }
    }

    /**
     * The resident shell ([ShellServer]) is up, starting it through wireless debugging when it is
     * not. Once up it outlives wireless debugging, which Android switches off on Wi-Fi changes.
     */
    private fun resident(context: Context): Boolean {
        val (port, token) = residentAddress(context)
        ShellServer.call(port, token, org.json.JSONObject().put("ping", true), 2_000)?.let { pong ->
            if (pong.optInt("version") == ShellServer.VERSION) { set(State.READY); return true }
            ShellServer.call(port, token, org.json.JSONObject().put("command", "pkill -f ${ShellServer::class.java.name}"), 2_000) // an older one
        }
        val switched = !wirelessDebuggingOn(context) && switchOn(context)
        val up = startResident(context)
        if (switched) { // back off as it was: the resident shell does not need it
            drop()
            runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI, 0) }
            if (!up) set(State.OFF)
        }
        return up
    }

    private fun startResident(context: Context): Boolean {
        val c = connect(context) ?: return false
        // a fresh port and secret each start: an old server, or another app, cannot answer for it
        val fresh = (20000..60000).random() to java.util.UUID.randomUUID().toString().replace("-", "")
        residentFile(context).writeText("${fresh.first} ${fresh.second}")
        runCatching { c.shell(ShellServer.startCommand(context.applicationInfo.sourceDir, fresh.first, fresh.second), 5_000) }
        repeat(30) {
            Thread.sleep(100)
            if (ShellServer.call(fresh.first, fresh.second, org.json.JSONObject().put("ping", true), 1_000) != null) {
                Thread({ PhoneSetup.keepAlive(context) }, "phone-keepalive").start() // a new one: the phone restarted, or Momoding was updated
                return true
            }
        }
        AppLogger.warning(TAG, "the resident shell did not start; commands go over wireless debugging")
        return false
    }

    /** Runs [argv] as the shell. Same contract as the Shizuku process it replaces. */
    fun runProcess(argv: Array<String>, env: Array<String>? = null, cwd: String? = null, timeoutMs: Long = 5_000L): ProcessResult {
        val context = app ?: return ProcessResult(126, "", "the phone shell is not set up")
        val command = buildString {
            cwd?.let { append("cd ").append(quote(it)).append(" && ") }
            env?.takeIf { it.isNotEmpty() }?.let { append("env ").append(it.joinToString(" ") { e -> quote(e) }).append(' ') }
            append(argv.joinToString(" ") { quote(it) })
        }
        synchronized(lock) {
            if (resident(context)) {
                val (port, token) = residentAddress(context)
                ShellServer.call(port, token, org.json.JSONObject().put("command", command).put("timeoutMs", timeoutMs), timeoutMs)
                    ?.let { return ProcessResult(it.optInt("exit", 126), it.optString("stdout"), it.optString("stderr")) }
            }
            repeat(2) { attempt ->
                val c = connection?.takeIf { it.isOpen } ?: connect(context) ?: return ProcessResult(126, "", notReady())
                try {
                    val r = c.shell(command, timeoutMs.coerceIn(1_000L, 600_000L).toInt())
                    return ProcessResult(r.exitCode, r.stdout, r.stderr)
                } catch (e: java.net.SocketTimeoutException) {
                    drop() // the stream is mid-command; a fresh connection for the next one
                    return ProcessResult(124, "", "timed out after ${timeoutMs / 1000}s")
                } catch (t: Throwable) {
                    drop() // adbd restarted or wireless debugging was switched off: reconnect once
                    if (attempt == 1) return ProcessResult(126, "", "${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        return ProcessResult(126, "", notReady())
    }

    /** Pairs with [code] at the pairing dialog's [port], then connects. */
    fun pair(context: Context, code: String, port: Int): Boolean {
        val r = AdbPairing.pair("127.0.0.1", port, code, dir = dir(context))
        val ok = r.ok || AdbPairing.pair(wifiAddress() ?: return false.also { log(r.detail) }, port, code, dir = dir(context)).ok
        if (!ok) { log(r.detail); return false }
        AdbPorts.forget(context)
        synchronized(lock) { drop(); resident(context) || connect(context) != null }
        return isReady()
    }

    private fun connect(context: Context): AdbConnection? {
        connection?.takeIf { it.isOpen }?.let { set(State.READY); return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { set(State.UNSUPPORTED); return null }
        val (cert, key) = AdbPairing.identity(dir(context)) ?: run { set(State.NOT_PAIRED); return null }
        if (!wirelessDebuggingOn(context)) { set(State.OFF); return null }
        val port = AdbPorts.find(context) ?: run { set(State.OFF, "no port"); return null }
        return try {
            AdbConnection.open(cert, key, port, wifiAddress()).also { connection = it; set(State.READY) }
        } catch (t: Throwable) {
            AdbPorts.forget(context)
            // a refused key means the phone forgot the pairing (or it was revoked in Settings)
            set(if (t.message?.contains("pair again") == true) State.NOT_PAIRED else State.OFF, t.message.orEmpty())
            null
        }
    }

    private fun residentFile(context: Context) = File(dir(context).apply { mkdirs() }, "resident")

    private fun residentAddress(context: Context): Pair<Int, String> = runCatching {
        residentFile(context).readText().trim().split(' ').let { it[0].toInt() to it[1] }
    }.getOrDefault(0 to "")

    private fun drop() { connection?.close(); connection = null }

    private fun set(state: State, detail: String = "") {
        val prev = _snapshot.value
        _snapshot.value = Snapshot(state, detail)
        if (prev.state == state) return
        AppLogger.info(TAG, "state ${prev.state} → $state ${detail.take(120)}")
        // newly connected (after a restart, or wireless debugging switched back on): the
        // accessibility service back on if Android dropped it meanwhile
        if (state == State.READY) app?.let { c -> Thread({ PhoneSetup.keepAlive(c) }, "phone-keepalive").start() }
    }

    private fun notReady() = when (_snapshot.value.state) {
        State.UNSUPPORTED -> "needs Android 11 or newer (wireless debugging)"
        State.NOT_PAIRED -> "not paired: Settings → Advanced access in Momoding, two steps"
        State.OFF -> "wireless debugging is off: switch it on in Developer options (no need to pair again)"
        State.READY -> "the phone shell dropped; try again"
    }

    private fun log(detail: String) = AppLogger.warning(TAG, "pairing failed: $detail")

    private fun wirelessDebuggingOn(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, ADB_WIFI, 0) == 1 }.getOrDefault(true)

    /**
     * Wireless debugging on, without the person: needs WRITE_SECURE_SETTINGS (granted after
     * pairing) and a Wi-Fi the person allowed; Android switches it straight back off otherwise.
     */
    private fun switchOn(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        if (AdbPairing.identity(dir(context)) == null || wifiAddress() == null) return false
        if (runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI, 1) }.isFailure) return false
        Thread.sleep(1_500)
        return wirelessDebuggingOn(context).also { AppLogger.info(TAG, "switched wireless debugging on: $it") }
    }

    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) s else "'" + s.replace("'", "'\\''") + "'"
}
