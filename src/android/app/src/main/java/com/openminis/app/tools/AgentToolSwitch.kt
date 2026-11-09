package com.openminis.app.tools

import android.content.Context

/**
 * [T-tools-granular-switches] Per-tool switches for the agent's optional
 * capabilities, surfaced as Settings › Agent Runtime › Agent Tools.
 *
 * Port of iOS `AgentToolSwitch` (Views/Settings/ToolsSettingsView.swift). The
 * key strings are deliberately byte-identical to iOS so a future settings sync
 * carries a user's choice across platforms unchanged.
 *
 * The gate lives HERE, at the read, rather than only in the UI: the tool
 * schema builder and the loop's dispatcher both read it.
 */
enum class AgentToolSwitch(val key: String, val defaultValue: Boolean) {
    /** browser_use — mature, on by default. */
    BROWSER("agent.tools.browser.enabled", true),
    ;

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(key, defaultValue)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    companion object {
        const val PREFS = "agent_settings"

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
