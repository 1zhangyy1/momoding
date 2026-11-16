package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App-level persisted "auto-compact on threshold" toggle.
 *
 * Mirrors iOS `AIChatViewModel.autoCompactEnabled`, whose backing store is the
 * UserDefaults key `autoCompactOnThreshold` (AIChatViewModel.swift:853). The
 * key name is reused verbatim so the two platforms stay greppable as one
 * feature.
 *
 * Semantics, matching iOS:
 *   - false (the default — iOS uses a bare `UserDefaults.bool`, which is false
 *     when unset): crossing the compact threshold before a send PROMPTS the
 *     user.
 *   - true: the same crossing compacts silently and then sends.
 *
 * Global rather than per-session on purpose: iOS persists it so "future
 * conversations inherit it", which is the whole point of the one-tap opt-in
 * offered on the prompt.
 */
object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"

    @Volatile
    private var appContext: Context? = null

    /** momoding: on unless the person turns it off — a long chat is compacted, never paused on a dialog (调研/M50 H3). */
    private const val DEFAULT_ENABLED = true

    @Volatile
    private var cachedEnabled: Boolean = DEFAULT_ENABLED

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [isEnabled] is safe from call sites that have no Context — the same
     * arrangement [FastModePrefs] uses.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    /** Context-free read. [DEFAULT_ENABLED] before [prime] runs, matching a fresh install. */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the cache so the current process behaves as asked.
     */
    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }
}
