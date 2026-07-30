package app.momoding.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.momoding.R

class AndroidNotificationGateway private constructor(
    private val context: Context,
    private val manager: NotificationManager,
) : NotificationGateway {
    override suspend fun capability(): NotificationCapabilitySnapshot {
        val declared = declaredPermissions().contains(POST_NOTIFICATIONS_PERMISSION)
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                POST_NOTIFICATIONS_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        val channel = manager.getNotificationChannel(AGENT_NOTIFICATION_CHANNEL_ID)
        return NotificationCapabilitySnapshot(
            declared = declared,
            runtimePermissionGranted = runtimeGranted,
            appNotificationsEnabled =
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelState = when {
                channel == null -> NotificationChannelState.NOT_CREATED
                channel.importance == NotificationManager.IMPORTANCE_NONE ->
                    NotificationChannelState.BLOCKED
                else -> NotificationChannelState.ENABLED
            },
        )
    }

    override suspend fun listActive(
        namespacePrefix: String,
    ): List<ActiveAgentNotification> = manager.activeNotifications
        .asSequence()
        .filter { it.tag?.startsWith(namespacePrefix) == true }
        .mapNotNull { active ->
            val tag = active.tag ?: return@mapNotNull null
            val title = active.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                ?.take(MAX_NOTIFICATION_TITLE_LENGTH)
                ?: return@mapNotNull null
            val message = active.notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                ?.take(MAX_NOTIFICATION_MESSAGE_LENGTH)
                ?: ""
            ActiveAgentNotification(
                identity = NotificationIdentity(tag, active.id),
                title = title,
                message = message,
                postedAtMillis = active.postTime,
            )
        }
        .sortedByDescending(ActiveAgentNotification::postedAtMillis)
        .toList()

    override suspend fun post(
        identity: NotificationIdentity,
        title: String,
        message: String,
    ) {
        ensureChannel()
        manager.notify(
            identity.tag,
            identity.id,
            NotificationCompat.Builder(context, AGENT_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_momoding)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(contentIntent(identity))
                .build(),
        )
    }

    override suspend fun cancel(identity: NotificationIdentity) {
        manager.cancel(identity.tag, identity.id)
    }

    override suspend fun openAppNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Suppress("DEPRECATION")
    private fun declaredPermissions(): Set<String> =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                AGENT_NOTIFICATION_CHANNEL_ID,
                "Agent notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications explicitly created by Momoding tasks"
            },
        )
    }

    private fun contentIntent(identity: NotificationIdentity): PendingIntent? {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntent.getActivity(
            context,
            identity.tag.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val POST_NOTIFICATIONS_PERMISSION =
            "android.permission.POST_NOTIFICATIONS"

        fun create(context: Context): AndroidNotificationGateway {
            val applicationContext = context.applicationContext
            return AndroidNotificationGateway(
                context = applicationContext,
                manager = applicationContext.getSystemService(NotificationManager::class.java),
            )
        }
    }
}
