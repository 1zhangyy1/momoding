package app.momoding.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.view.accessibility.AccessibilityNodeInfo
import app.momoding.phone.adb.AdbPorts
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService

/**
 * Pairing while the person has the system's "Pair device with pairing code" dialog open
 * (PhoneGo's PairingService, MIT; Mika's notification input). Two ways to the code, whichever
 * comes first: the accessibility service reads it off the dialog, or the person types it into
 * this service's notification, which they can do without leaving the dialog. The dialog's port
 * is read off the dialog too, or taken from the pairing service Android advertises while the
 * dialog shows. A service, not a screen: bringing an activity to the front dismisses the
 * dialog, and with it the pairing server.
 */
class PairingService : Service() {

    /** A code typed into the notification, waiting for the loop. */
    @Volatile private var typed: String? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CODE) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.let { typed = it.filter(Char::isDigit).toString() }
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mo_phone_shell_title), NotificationManager.IMPORTANCE_HIGH))
        startForeground(ID, ask(getString(R.string.mo_phone_pair_watching)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (running) return START_NOT_STICKY
        running = true
        Thread({
            val deadline = System.currentTimeMillis() + WATCH_MS
            var result = getString(R.string.mo_phone_pair_not_seen)
            var dialog: Int? = null // the pairing dialog's port, once it is seen open
            while (System.currentTimeMillis() < deadline) {
                val read = readDialog()
                val code = read?.first ?: typed?.also { typed = null }
                if (code == null) {
                    // the dialog just opened: pop the notification up, with its input showing
                    if (dialog == null) dialog = AdbPorts.pairing(this, 1500)?.also { nm.notify(ID, ask(getString(R.string.mo_phone_pair_enter))) }
                    else Thread.sleep(700)
                    continue
                }
                val port = read?.second ?: dialog ?: AdbPorts.pairing(this)
                if (code.length != 6 || port == null) {
                    nm.notify(ID, ask(getString(if (code.length != 6) R.string.mo_phone_pair_six_digits else R.string.mo_phone_pair_no_dialog)))
                    continue
                }
                if (PhoneShell.pair(this, code, port)) {
                    PhoneSetup.afterPaired(this)
                    result = getString(R.string.mo_phone_pair_done)
                    break
                }
                dialog = AdbPorts.pairing(this, 1500) ?: dialog // the person may have opened a new dialog
                nm.notify(ID, ask(getString(R.string.mo_phone_pair_failed)))
                Thread.sleep(700)
            }
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(ID, Notification.Builder(this, CHANNEL).setContentTitle(getString(R.string.mo_phone_shell_title))
                .setContentText(result).setSmallIcon(R.drawable.ic_stat_momoding).setTimeoutAfter(60_000).build())
            stopSelf()
        }, "phone-pairing").start()
        return START_NOT_STICKY
    }

    /** The notification with a place to type the six digits. */
    private fun ask(text: String): Notification {
        val reply = PendingIntent.getService(this, 0, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.mo_phone_pair_code_hint)).build()
        val action = Notification.Action.Builder(null, getString(R.string.mo_phone_pair_code_action), reply).addRemoteInput(input).build()
        return Notification.Builder(this, CHANNEL).setContentTitle(getString(R.string.mo_phone_shell_title)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setSmallIcon(R.drawable.ic_stat_momoding)
            .setOnlyAlertOnce(false).addAction(action)
            .setGroup(CHANNEL) // its own group: not folded in with "Momoding is running", one tap from the input
            .build()
    }

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
        private const val CHANNEL = "momoding_phone_pairing"
        private const val ID = 9401
        private const val ACTION_CODE = "app.momoding.phone.PAIRING_CODE"
        private const val KEY_CODE = "code"
        /** Long enough to find Wireless debugging in Settings; each dialog's code is new anyway. */
        private const val WATCH_MS = 300_000L
        private val CODE = Regex("""\d{6}""")
        private val ADDRESS = Regex("""(?<![\w.:])\d{1,3}(?:\.\d{1,3}){3}:(\d{1,5})(?![\w.:])""")

        /**
         * The steps for the person: open Developer options at Wireless debugging, switch it on,
         * tap "Pair device with pairing code"; the code is read, or typed into the notification.
         */
        fun start(context: Context) {
            // the code goes in through the notification, so it has to be allowed to show first
            if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                val activity = context as? android.app.Activity
                if (activity != null && android.os.Build.VERSION.SDK_INT >= 33 &&
                    activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
                } else {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return
            }
            context.startForegroundService(Intent(context, PairingService::class.java))
            val wireless = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless") // scrolls to the row and highlights it
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(wireless) }
                .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
