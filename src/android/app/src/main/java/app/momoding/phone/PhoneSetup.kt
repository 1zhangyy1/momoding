package app.momoding.phone

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.MinisNotificationListenerService
import kotlinx.coroutines.runBlocking

/**
 * What Momoding sets up for itself once it has the shell's rights, without a dialog for each
 * (本期计划: trust the agent; what was turned on is said, not asked): the runtime permissions it
 * declares, reading notifications, staying out of battery saving, starting screens from the
 * background on ROMs that block it, and its accessibility service back on whenever it drops.
 */
object PhoneSetup {
    private const val TAG = "PhoneSetup"

    /** Right after pairing: everything at once. Blocking; not on the main thread. */
    fun afterPaired(context: Context) {
        grantDeclared(context)
        val pkg = context.packageName
        run("cmd", "notification", "allow_listener", ComponentName(context, MinisNotificationListenerService::class.java).flattenToString())
        run("cmd", "deviceidle", "whitelist", "+$pkg")
        // what scripts may need beyond runtime permissions: usage stats, system settings, Do Not Disturb
        run("appops", "set", pkg, "GET_USAGE_STATS", "allow")
        run("appops", "set", pkg, "WRITE_SETTINGS", "allow")
        run("cmd", "notification", "allow_dnd", pkg)
        // MIUI / HyperOS: "display pop-up windows while running in the background"
        if (Build.MANUFACTURER.equals("Xiaomi", true)) run("appops", "set", pkg, "10021", "allow")
        keepAlive(context)
    }

    /** Whenever the shell is ready: the accessibility service back on if Android dropped it. */
    fun keepAlive(context: Context) {
        AccessibilityRecoveryManager.invalidateCache()
        if (AccessibilityRecoveryManager.hasEverBeenGranted(context) && AccessibilityRecoveryManager.isGrantRevoked(context)) {
            runBlocking { AccessibilityRecoveryManager.repairWithShizuku(context) }
        }
    }

    /** Every dangerous permission the manifest asks for that is not on yet. */
    private fun grantDeclared(context: Context) {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions.orEmpty().filter { p ->
            context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED &&
                runCatching { @Suppress("DEPRECATION") (pm.getPermissionInfo(p, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
        }.forEach { run("pm", "grant", context.packageName, it) }
    }

    private fun run(vararg argv: String) {
        val r = PhoneShell.runProcess(arrayOf(*argv), timeoutMs = 10_000)
        if (r.exitCode != 0) AppLogger.info(TAG, "${argv.joinToString(" ").take(80)}: exit=${r.exitCode} ${r.combined.take(160)}")
    }
}
