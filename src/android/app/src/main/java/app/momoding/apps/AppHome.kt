package app.momoding.apps

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * An app on the person's home screen: its icon and its widget. Only the person puts them there, with
 * a tap on the app's card in the chat (ui/chat/AppCard), and the launcher asks them to confirm; they
 * are there once the launcher says so, never because they were asked for.
 *
 * Some phones say they allow it and then refuse without a word (ColorOS keeps widgets to its own
 * partners). Such a refusal is remembered, so the card offers only what this phone can do, and the
 * app's Momoding speaks only of that (`momoding-app list`).
 */
object AppHome {
    enum class What { ICON, WIDGET }

    /** This phone lets the person add it: the launcher says so and has not refused it before. */
    fun possible(context: Context, what: What): Boolean = !prefs(context).getBoolean(what.name, false) && when (what) {
        What.ICON -> ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        What.WIDGET -> AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    }

    /** The person can add it to [app] here: the phone allows it, and a widget has something to show (`momoding.widget`). */
    fun can(context: Context, app: MoApps.App, what: What): Boolean = app.hasApp && possible(context, what) &&
        (what != What.WIDGET || AppWidget.state(app).length() > 0)

    /** The phone turned the person's tap down before they could answer (nothing came up, or it went at once). */
    fun refused(context: Context, what: What) = prefs(context).edit().putBoolean(what.name, true).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("momoding_home_refused", Context.MODE_PRIVATE)

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
