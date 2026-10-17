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
 * Momoding said, plus the cards (Grok Bot keeps the process in a separate "full conversation";
 * here it is one switch in Settings). nanoMuse ships the same switch on by default.
 */
object ShowSteps {
    private const val PREFS = "momoding"
    private const val KEY = "chat.showSteps"

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
