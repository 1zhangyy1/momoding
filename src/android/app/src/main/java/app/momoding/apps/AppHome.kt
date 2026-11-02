package app.momoding.apps

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * An app on the person's home screen: its icon and its widget. Only the person puts them there, with
 * a tap on the app's card in the chat (ui/chat/AppCard), and the launcher asks them to confirm; they
 * are there once the launcher says so, never because they were asked for.
 */
object AppHome {
    enum class What { ICON, WIDGET }

    /** The person can add it here: the launcher allows it, and a widget has something to show (`momoding.widget`). */
    fun can(context: Context, app: MoApps.App, what: What): Boolean = app.hasApp && when (what) {
        What.ICON -> ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        What.WIDGET -> AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported && AppWidget.state(app).length() > 0
    }

    fun there(context: Context, app: MoApps.App, what: What): Boolean = when (what) {
        What.ICON -> MoAppActivity.onHome(context, app)
        What.WIDGET -> AppWidget.added(context, app)
    }

    /** The person's tap: the launcher shows its own confirmation, over the chat they are in. */
    fun add(context: Context, app: MoApps.App, what: What) {
        when (what) {
            What.ICON -> MoAppActivity.pin(context, app)
            What.WIDGET -> AppWidget.requestPin(context, app)
        }
    }
}
