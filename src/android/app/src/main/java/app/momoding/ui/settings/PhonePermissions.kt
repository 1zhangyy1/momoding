package app.momoding.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.MinisApp
import com.openminis.app.R

/** One row of the prototype's "手机权限": what it is for and whether it is on. */
data class PhonePermission(val key: String, @StringRes val title: Int, @StringRes val purpose: Int, val icon: ImageVector, val granted: Boolean)

/** The phone's own permissions Momoding uses, re-read whenever Settings comes back to the front. */
@Composable
fun rememberPhonePermissions(): List<PhonePermission> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return remember(tick) {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        listOf(
            PhonePermission("notifications", R.string.mo_perm_row_notifications, R.string.mo_perm_purpose_notifications, Icons.Outlined.Notifications,
                NotificationManagerCompat.from(context).areNotificationsEnabled()),
            PhonePermission("calendar", R.string.mo_perm_row_calendar, R.string.mo_perm_purpose_calendar, Icons.Outlined.CalendarMonth,
                has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR)),
            PhonePermission("contacts", R.string.mo_perm_row_contacts, R.string.mo_perm_purpose_contacts, Icons.Outlined.Contacts,
                has(Manifest.permission.READ_CONTACTS)),
            PhonePermission("location", R.string.mo_perm_row_location, R.string.mo_perm_purpose_location, Icons.Outlined.LocationOn,
                has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION)),
            // not a runtime permission, but the one that decides whether it keeps working when you leave
            PhonePermission("background", R.string.mo_perm_row_background, R.string.mo_perm_purpose_background, Icons.Outlined.BatteryFull,
                com.openminis.app.power.PowerOptimizationManager.isIgnoringBatteryOptimizations(context)),
        )
    }
}

/** Opens the system page where this permission is switched (the person decides there). */
fun openAppPermissionSettings(context: Context, p: PhonePermission) {
    if (p.key == "background") {
        // the battery saver's one-tap dialog first; once let off, the phone maker's own background page (OPPO, Xiaomi…)
        val activity = context as? android.app.Activity ?: return
        val power = com.openminis.app.power.PowerOptimizationManager
        if (!p.granted) power.requestBatteryOptimizationExemption(activity)
        else if (!power.openOemAutostartSettings(activity)) power.openAppDetailsSettings(activity)
        backgroundHint(activity)
        return
    }
    val intent = if (p.key == "notifications") Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Where the switch is on phones whose own page cannot be opened from an app (OPPO / OnePlus keep
 * "耗电管理" unexported): said once as the page opens, so nobody has to look for it.
 */
fun backgroundHint(context: Context) {
    val res = when (com.openminis.app.power.PowerOptimizationManager.Vendor.current()) {
        com.openminis.app.power.PowerOptimizationManager.Vendor.OPPO -> R.string.mo_background_hint_oppo
        com.openminis.app.power.PowerOptimizationManager.Vendor.XIAOMI -> R.string.mo_background_hint_xiaomi
        com.openminis.app.power.PowerOptimizationManager.Vendor.VIVO -> R.string.mo_background_hint_vivo
        com.openminis.app.power.PowerOptimizationManager.Vendor.HUAWEI -> R.string.mo_background_hint_huawei
        else -> return
    }
    android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_LONG).show()
}

/** "DeepSeek V4.1 Flash · OpenRouter": the default group's first model and its provider. */
fun currentModelLabel(context: Context): String? {
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return null
    val cfg = repo.config.value
    val group = cfg.modelGroups.firstOrNull { it.id == cfg.defaultPrimaryGroupId } ?: cfg.modelGroups.firstOrNull() ?: return null
    val entry = group.memberEntryIds.firstNotNullOfOrNull { id -> cfg.modelEntries.firstOrNull { it.id == id } } ?: return null
    val inst = cfg.instances.firstOrNull { it.id == entry.providerInstanceId }
    val model = entry.model.displayName.ifBlank { entry.model.id }.substringAfter(": ")
    return listOfNotNull(model, inst?.label?.takeIf { it.isNotBlank() }).joinToString(" · ")
}
