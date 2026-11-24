package app.momoding.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService

/**
 * Pairing without typing (PhoneGo's PairingService, MIT): while the person has the system's
 * "Pair device with pairing code" dialog open, read the code and the address off it with the
 * accessibility service and pair in the background. A service, not a screen: bringing an
 * activity to the front dismisses the dialog, and with it the pairing server.
 */
class PairingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mo_phone_shell_title), NotificationManager.IMPORTANCE_LOW))
        val watching = notification(getString(R.string.mo_phone_pair_watching))
        startForeground(ID, watching, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Thread({
            val deadline = System.currentTimeMillis() + WATCH_MS
            var result = getString(R.string.mo_phone_pair_not_seen)
            while (System.currentTimeMillis() < deadline) {
                val read = readDialog()
                if (read != null) {
                    result = if (PhoneShell.pair(this, read.first, read.second)) {
                        PhoneSetup.afterPaired(this)
                        getString(R.string.mo_phone_pair_done)
                    } else getString(R.string.mo_phone_pair_failed)
                    break
                }
                Thread.sleep(700)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(ID, notification(result))
            stopSelf()
        }, "phone-pairing").start()
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle(getString(R.string.mo_phone_shell_title)).setContentText(text)
        .setSmallIcon(R.drawable.ic_stat_momoding).build()

    /**
     * The code and the port on the pairing dialog, by their shape rather than words or view ids:
     * a text that is exactly six digits, and an `IPv4:port`. Only windows of the settings app
     * count (the page under the dialog shows an address too); two different codes or addresses
     * read as nothing, rather than pairing with the wrong digits.
     */
    private fun readDialog(): Pair<String, Int>? {
        val service = MinisAccessibilityService.getInstance() ?: return null
        val settings = packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"
        for (window in runCatching { service.windows }.getOrDefault(emptyList())) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != settings) continue
            val lines = buildList { collect(root, this, 0) }
            val code = lines.map { it.filterNot(Char::isWhitespace) }.filter { CODE.matches(it) }.toSet().singleOrNull() ?: continue
            val port = lines.flatMap { line -> ADDRESS.findAll(line).map { it.groupValues[1].toInt() }.toList() }
                .filter { it in 1..65535 }.toSet().singleOrNull() ?: continue
            return code to port
        }
        return null
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    companion object {
        private const val CHANNEL = "momoding_phone_shell"
        private const val ID = 9401
        /** The dialog's code lives about two minutes. */
        private const val WATCH_MS = 150_000L
        private val CODE = Regex("""\d{6}""")
        private val ADDRESS = Regex("""(?<![\w.:])\d{1,3}(?:\.\d{1,3}){3}:(\d{1,5})(?![\w.:])""")

        /**
         * The two steps: watch for the dialog, and open Developer options at Wireless debugging,
         * where the person taps "Pair device with pairing code".
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java))
            val wireless = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(wireless) }
                .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
