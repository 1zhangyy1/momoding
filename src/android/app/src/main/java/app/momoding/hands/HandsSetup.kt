package app.momoding.hands

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager

/**
 * The few system pages the hands need, opened as close to the switch as each phone allows, and
 * which one is next. Shared by Settings → Hands and the chat's permission card.
 */
object HandsSetup {
    enum class Step { ACCESSIBILITY, UNLOCK, OVERLAY, NONE }

    /** What is still missing, in the order the person meets it. UNLOCK: the accessibility switch is greyed out (an APK install). */
    fun next(context: Context): Step {
        val r = Hands.readiness(context)
        return when {
            !r.serviceOn && RestrictedSettingsManager.isRestricted(context) -> Step.UNLOCK
            !r.serviceOn -> Step.ACCESSIBILITY
            !r.overlayOk -> Step.OVERLAY
            else -> Step.NONE
        }
    }

    fun open(context: Context, step: Step) = when (step) {
        Step.ACCESSIBILITY -> openAccessibility(context)
        Step.UNLOCK -> openAppDetails(context)
        Step.OVERLAY -> openOverlay(context)
        Step.NONE -> Unit
    }

    /**
     * The accessibility list with Momoding's row highlighted (AOSP and most phones honour the
     * fragment-args key; the page with the switch itself needs a system-only permission), else Settings.
     */
    fun openAccessibility(context: Context) {
        val key = ComponentName(context.packageName, MinisAccessibilityService::class.java.name).flattenToString()
        start(context, listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", key)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", key) }),
            Intent(Settings.ACTION_SETTINGS),
        ))
    }

    fun openOverlay(context: Context) = start(context, listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    ))

    /** App info, where "Allow restricted settings" sits in the ⋮ menu. */
    fun openAppDetails(context: Context) = start(context, listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_SETTINGS),
    ))

    private fun start(context: Context, intents: List<Intent>) {
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
