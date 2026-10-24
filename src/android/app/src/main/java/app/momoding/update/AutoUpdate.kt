package app.momoding.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * New versions reach people without them looking: at most once a day, when the app comes to
 * the front, the releases are checked (Gitee, then GitHub) and a newer one is offered once, in
 * the app ([UpdateOffer]: what is new, Download, Later), or by a notification that opens About
 * → Check for updates when the person has already left the app.
 */
object AutoUpdate {
    private const val PREFS = "momoding"
    private const val KEY_CHECKED = "update.checkedAt"
    private const val KEY_ANNOUNCED = "update.announced"
    private const val CHANNEL = "momoding_updates"
    private const val NOTIFICATION_ID = 9301
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A newer release to offer in the app ([UpdateOffer]); null once it is taken or put off. */
    val offer = kotlinx.coroutines.flow.MutableStateFlow<UpdateChecker.CheckResult.UpdateAvailable?>(null)

    fun close() { offer.value = null }

    fun onForeground(context: Context) {
        if (BuildConfig.DEBUG) return // the dev build is not the one on the releases page
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED, 0L) < DAY_MS) return
        prefs.edit().putLong(KEY_CHECKED, now).apply()
        scope.launch {
            val r = runCatching { UpdateChecker.check() }.getOrNull() as? UpdateChecker.CheckResult.UpdateAvailable ?: return@launch
            if (prefs.getString(KEY_ANNOUNCED, null) == r.versionName) return@launch // said once per version
            prefs.edit().putString(KEY_ANNOUNCED, r.versionName).apply()
            offer.value = r // shown by the chat as soon as it is on screen
            // left the app before the answer came: a notification instead
            val inFront = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (!inFront) announce(app, r.versionName)
        }
    }

    private fun announce(context: Context, version: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.mo_update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            app.momoding.deeplink.OwnScheme.intent(context, Uri.parse("minis://settings/about"), flags = 0),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_momoding)
            .setContentTitle(context.getString(R.string.mo_update_title, version))
            .setContentText(context.getString(R.string.mo_update_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}
