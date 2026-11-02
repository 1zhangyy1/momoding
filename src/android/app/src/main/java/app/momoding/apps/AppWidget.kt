package app.momoding.apps

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.RemoteViews
import com.openminis.app.R
import org.json.JSONObject
import java.io.File

/**
 * A Momoding App on the home screen as a widget (plan step 3): a glance without opening it. The
 * app says what it shows from its own code, `momoding.widget({title, value, lines})` — also when it
 * wakes in the background for a text — and the widget is drawn from that: a title, one big value,
 * up to three lines, in the app's colour; a tap opens the app. Which app a widget shows is chosen
 * when it is added (AppWidgetConfigure), or set when the app's Momoding asks for one.
 */
class AppWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        ids.forEach { edit.remove(KEY + it) }
        edit.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        // the launcher added a widget the app's Momoding asked for: it shows that app
        if (intent.action == ACTION_PINNED) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val name = intent.getStringExtra(EXTRA_NAME)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && name != null) {
                bind(context, id, name)
                return
            }
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val PREFS = "momoding"
        private const val KEY = "apps.widget."
        const val ACTION_PINNED = "app.momoding.apps.WIDGET_PINNED"
        const val EXTRA_NAME = "momoding_app"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** What [app] said to show, kept outside the sandbox. */
        private fun stateFile(app: MoApps.App) = File(app.vault, "${app.name}.widget.json")

        fun state(app: MoApps.App): JSONObject = runCatching { JSONObject(stateFile(app).readText()) }.getOrDefault(JSONObject())

        /** The app's page set what its widget shows (`momoding.widget`): every widget of the app is redrawn. */
        fun set(context: Context, app: MoApps.App, content: JSONObject) {
            stateFile(app).writeText(content.toString())
            refresh(context, app)
        }

        fun bind(context: Context, id: Int, name: String) {
            prefs(context).edit().putString(KEY + id, name).apply()
            render(context, AppWidgetManager.getInstance(context), id)
        }

        fun refresh(context: Context, app: MoApps.App) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, AppWidget::class.java))
                .filter { prefs(context).getString(KEY + it, null) == app.name }
                .forEach { render(context, manager, it) }
        }

        /** A widget of [app] is on the home screen: the launcher added it and told us ([ACTION_PINNED]). */
        fun added(context: Context, app: MoApps.App): Boolean = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AppWidget::class.java)).any { prefs(context).getString(KEY + it, null) == app.name }

        /** Asks the launcher to add a widget for [app]; it shows its own confirmation (only from the person's tap: [AppOffer]). */
        fun requestPin(context: Context, app: MoApps.App): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val callback = PendingIntent.getBroadcast(context, app.name.hashCode(),
                Intent(context, AppWidget::class.java).setAction(ACTION_PINNED).putExtra(EXTRA_NAME, app.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            return manager.requestPinAppWidget(ComponentName(context, AppWidget::class.java), null, callback)
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.momoding_app_widget)
            val app = prefs(context).getString(KEY + id, null)?.let { MoApps.get(context, it) }
            if (app == null) {
                views.setTextViewText(R.id.mo_widget_title, context.getString(R.string.mo_app_widget_label))
                views.setTextViewText(R.id.mo_widget_value, "")
                views.setTextViewText(R.id.mo_widget_lines, "")
                manager.updateAppWidget(id, views)
                return
            }
            val s = state(app)
            views.setTextViewText(R.id.mo_widget_title, s.optString("title").ifBlank { app.title })
            views.setTextViewText(R.id.mo_widget_value, s.optString("value"))
            val lines = s.optJSONArray("lines")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            views.setTextViewText(R.id.mo_widget_lines,
                if (s.length() == 0) context.getString(R.string.mo_app_widget_empty) else lines.take(3).joinToString("\n"))
            val color = runCatching { Color.parseColor(s.optString("color").ifBlank { app.themeColor }) }.getOrNull()
            color?.let {
                // tinted, so the card keeps its rounded corners; older phones get the colour flat
                if (android.os.Build.VERSION.SDK_INT >= 31) views.setColorStateList(R.id.mo_widget_root, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(it))
                else views.setInt(R.id.mo_widget_root, "setBackgroundColor", it)
            }
            val open = PendingIntent.getActivity(context, id, MoAppActivity.intent(context, app.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.mo_widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}

/** Choosing which app a new widget shows. */
class AppWidgetConfigure : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val apps = MoApps.list(this).filter { it.index.isFile }
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || apps.isEmpty()) { finish(); return }
        title = getString(R.string.mo_app_widget_pick)
        setContentView(ListView(this).apply {
            adapter = ArrayAdapter(this@AppWidgetConfigure, android.R.layout.simple_list_item_1, apps.map { it.title })
            setOnItemClickListener { _, _, position, _ ->
                AppWidget.bind(this@AppWidgetConfigure, id, apps[position].name)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
        })
    }
}
