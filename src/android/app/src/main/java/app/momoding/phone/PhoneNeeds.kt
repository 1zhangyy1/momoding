package app.momoding.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.momoding.files.PhoneFiles
import app.momoding.guard.SelfGrant
import app.momoding.hands.Hands
import app.momoding.hands.HandsSetup
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.offload.MinisNotificationListenerService
import com.openminis.app.power.PowerOptimizationManager
import kotlinx.coroutines.runBlocking

/**
 * What the permission card asks for ("need": calendar, location, hands, system…), one way for
 * all of them (M61): is it on, can Momoding switch it on itself (advanced access is ready and
 * the phone lets the shell), and else the one system page or dialog the person goes to.
 */
object PhoneNeeds {

    /** The runtime permissions behind a need; empty when it is a switch in Settings instead. */
    fun runtime(need: String): Array<String> = when (need) {
        "calendar" -> arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        "contacts" -> arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        "location" -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        "photos" -> if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        "notifications" -> if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        "sms" -> arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.RECEIVE_SMS)
        else -> if (need.startsWith("android.permission.")) arrayOf("android.permission." + need.removePrefix("android.permission.").uppercase()) else emptyArray()
    }

    fun granted(context: Context, need: String): Boolean = when (need) {
        "system" -> PhoneShell.isReady()
        "hands" -> Hands.enabled(context) && HandsSetup.next(context) == HandsSetup.Step.NONE
        "files" -> PhoneFiles.allowed()
        "read-notifications" -> MinisNotificationListenerService.isEnabled(context)
        "background" -> PowerOptimizationManager.isIgnoringBatteryOptimizations(context)
        else -> runtime(need).let { p -> p.isNotEmpty() && p.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    }

    /**
     * Switches [need] on without the person, through advanced access; true when it is on after.
     * Blocking (the shell): not on the main thread. Phones that do not let the shell (Xiaomi
     * without its security setting) just fall through to the system page.
     */
    fun switchOn(context: Context, need: String): Boolean {
        if (need == "hands") Hands.setEnabled(context, true) // Momoding's own switch: saying yes on the card is enough
        if (granted(context, need)) return true
        if (need == "system" || !PhoneShell.ensure()) return false
        val pkg = context.packageName
        when (need) {
            "hands" -> {
                runBlocking { AccessibilityRecoveryManager.repairWithShizuku(context) }
                shell("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
            }
            "files" -> shell("appops", "set", pkg, "MANAGE_EXTERNAL_STORAGE", "allow")
            "read-notifications" -> shell("cmd", "notification", "allow_listener",
                ComponentName(context, MinisNotificationListenerService::class.java).flattenToString())
            "background" -> shell("cmd", "deviceidle", "whitelist", "+$pkg")
            else -> SelfGrant.grant(context, runtime(need).toList())
        }
        return granted(context, need)
    }

    /**
     * Opens the system page for what is still missing; false when the need is a runtime
     * permission (the caller shows the system dialog instead).
     */
    fun open(context: Context, need: String): Boolean {
        when (need) {
            // Developer options still off: the system page would close at once, so the three steps instead
            "system" -> if (Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0)
                start(context, Intent(Intent.ACTION_VIEW, Uri.parse("momoding://settings/advanced")).setPackage(context.packageName))
                else PairingService.start(context)
            "hands" -> { Hands.setEnabled(context, true); HandsSetup.open(context, HandsSetup.next(context)) }
            "files" -> PhoneFiles.openSwitch(context)
            "read-notifications" -> start(context, Intent(MinisNotificationListenerService.SETTINGS_ACTION))
            "background" -> {
                val activity = context as? android.app.Activity
                val power = PowerOptimizationManager
                if (activity != null && !power.isIgnoringBatteryOptimizations(context)) power.requestBatteryOptimizationExemption(activity)
                else if (activity != null && power.needsOemAutostartGuidance() && !power.openOemAutostartSettings(activity)) power.openAppDetailsSettings(activity)
            }
            else -> return false
        }
        return true
    }

    /** Momoding's own page in Settings: where a runtime permission refused for good is switched on. */
    fun openAppDetails(context: Context) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    private fun start(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun shell(vararg argv: String) = PhoneShell.runProcess(arrayOf(*argv), timeoutMs = 10_000)
}
