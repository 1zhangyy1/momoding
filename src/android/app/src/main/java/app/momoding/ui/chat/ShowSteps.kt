// Adapted from nanoMuse (GPL-3.0): io/github/nanomuse/ui/chat/NmHomeChrome.kt (rememberShowSteps)
package app.momoding.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the chat shows how the work was done — tool steps, reasoning, model-switch notes, the
 * live tool bar over the composer, token usage. Off by default: the chat is what the person and
 * Momoding said, plus the cards; each turn's "做了 3 步 ›" line is how the steps are seen. There is
 * no switch for this in the app; nanoMuse ships the same view on by default.
 */
object ShowSteps {
    private const val PREFS = "momoding"
    // Not a setting any more (the person asked to keep it simple; its full step views could hang):
    // a fresh key, so a switch flipped on earlier no longer applies. Development only.
    private const val KEY = "dev.showSteps"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** Observed, so flipping the switch in Settings applies when the chat comes back. */
    @Composable
    fun remember(): State<Boolean> {
        val context = LocalContext.current
        val state = remember { mutableStateOf(get(context)) }
        DisposableEffect(Unit) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == KEY) state.value = p.getBoolean(KEY, false)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return state
    }
}
