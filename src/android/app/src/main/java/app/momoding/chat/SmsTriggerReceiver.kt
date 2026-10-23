package app.momoding.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledNotificationTriggers
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Routines that wait for a text (`minis-scheduled --app sms --match …`) hear it here, from the
 * SMS itself. The notification path cannot be trusted for texts: since Android 15 the system
 * hides the content of notifications that look like one-time codes from listeners without a
 * privileged permission, and a pickup code ("取件码 3-2-1045") looks like one. The same text may
 * still arrive through the notification listener; a routine's one-minute cooldown keeps it to
 * one run.
 */
class SmsTriggerReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        if (parts.isEmpty()) return
        val sender = parts.firstNotNullOfOrNull { it.displayOriginatingAddress }.orEmpty()
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val pending = goAsync()
        scope.launch {
            try {
                val watching = ScheduledTaskManager(app).list().any { it.enabled && it.triggerKind == ScheduledTriggerKind.ON_NOTIFICATION }
                if (!watching) return@launch
                val pkg = runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull() ?: "sms"
                val posted = ScheduledNotificationTriggers.Posted(pkg, app.getString(R.string.mo_sms_app_name), sender, body)
                val fired = ScheduledNotificationTriggers.dispatch(app, posted)
                AppLogger.info("SmsTrigger", "text from ${sender.take(4)}… fired $fired routine(s)")
            } finally {
                pending.finish()
            }
        }
    }
}
