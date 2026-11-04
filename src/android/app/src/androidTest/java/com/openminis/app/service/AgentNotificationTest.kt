package com.openminis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Inspect the actual platform notifications without starting or stopping any work. */
class AgentNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun service() = AgentForegroundService().also { service ->
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, context)
    }
    private fun assertInformational(notification: Notification) {
        assertTrue("Task cancellation belongs to the conversation", notification.actions.isNullOrEmpty())
        assertNotNull("The status still opens the app", notification.contentIntent)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }
    @Test fun statusNotificationStillOpensTheAppWithoutGlobalCancellation() {
        val notification = AgentForegroundService::class.java.getDeclaredMethod(
            "buildNotification", Int::class.javaPrimitiveType, String::class.java,
        ).apply { isAccessible = true }.invoke(service(), 1, "M41 local notification fixture") as Notification
        assertInformational(notification)
    }
    @Test fun promotedNotificationHasNoGlobalCancellationWhileRunningOrCompleted() {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val content = PendingIntent.getActivity(context, 9141, Intent(context, com.openminis.app.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val method = AgentForegroundService::class.java.declaredMethods.single { it.name == "buildPromotedNotification" }
            .apply { isAccessible = true }
        try {
            for (completed in listOf(false, true)) {
                val notification = method.invoke(service(), "M41 local notification fixture", "Task status", "0:01",
                    android.R.drawable.ic_dialog_info, !completed, completed, 1000L, 2000L, content) as Notification
                assertInformational(notification)
                assertEquals(!completed, notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                assertEquals(if (completed) 2000L else 1000L, notification.`when`)
            }
        } finally { content.cancel() }
    }
}
