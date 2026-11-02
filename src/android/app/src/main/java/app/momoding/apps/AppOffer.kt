package app.momoding.apps

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.momoding.ui.guard.BannerPill
import com.openminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What an app's Momoding offers to put on the home screen: its icon, its widget. The person
 * brings up the launcher's confirmation themselves, with a tap on the app's own page, one at a
 * time; it is there only once the launcher says so ([onHome], [AppWidget.added]), never because it
 * was asked for.
 */
object AppOffer {
    enum class What { HOME, WIDGET }

    private const val PREFS = "momoding"
    private fun key(app: MoApps.App) = "apps.offer.${app.name}"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun offered(context: Context, app: MoApps.App) = prefs(context).getStringSet(key(app), emptySet())!!

    /** Bumped when what to show may have changed (an offer came, the launcher answered): the page looks again. */
    val changed = MutableStateFlow(0L)
    fun touch() { changed.value = System.nanoTime() }

    fun offer(context: Context, app: MoApps.App, what: What) {
        prefs(context).edit().putStringSet(key(app), offered(context, app) + what.name).apply()
        touch()
    }

    /** The one thing to show the person now: the icon first, then the widget; none once it is there. */
    fun next(context: Context, app: MoApps.App): What? = What.entries.firstOrNull { it.name in offered(context, app) && !there(context, app, it) }

    /** The person tapped it: the launcher shows its own confirmation. The offer is used up, yes or no. */
    fun take(context: Context, app: MoApps.App, what: What) {
        prefs(context).edit().putStringSet(key(app), offered(context, app) - what.name).apply()
        touch()
        when (what) {
            What.HOME -> MoAppActivity.pin(context, app)
            What.WIDGET -> AppWidget.requestPin(context, app)
        }
    }

    fun there(context: Context, app: MoApps.App, what: What): Boolean = when (what) {
        What.HOME -> MoAppActivity.onHome(context, app)
        What.WIDGET -> AppWidget.added(context, app)
    }
}

/** On the app's page: the one thing its Momoding offers for the home screen, added with a tap. */
@Composable
fun OfferBanner(app: MoApps.App) {
    val context = LocalContext.current
    val changed by AppOffer.changed.collectAsState()
    val what = remember(changed) { AppOffer.next(context, app) } ?: return
    val (text, action) = when (what) {
        AppOffer.What.HOME -> R.string.mo_app_offer_home to R.string.mo_app_offer_home_action
        AppOffer.What.WIDGET -> R.string.mo_app_offer_widget to R.string.mo_app_offer_widget_action
    }
    BannerPill(stringResource(text, app.title), stringResource(action)) { AppOffer.take(context, app, what) }
}
