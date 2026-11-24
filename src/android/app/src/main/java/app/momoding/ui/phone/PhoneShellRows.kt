package app.momoding.ui.phone

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.momoding.phone.PairingService
import app.momoding.phone.PhoneShell
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow

/**
 * One row for advanced access (the phone shell): what it is now, and the one thing to tap.
 * Pairing takes the code from the system dialog (accessibility) or from the notification input.
 */
@Composable
fun PhoneShellRow(showDivider: Boolean = false) {
    val context = LocalContext.current
    val snap by PhoneShell.snapshot.collectAsState()
    val (subtitle, action) = when (snap.state) {
        PhoneShell.State.READY -> stringResource(R.string.mo_phone_shell_ready) to null
        PhoneShell.State.UNSUPPORTED -> stringResource(R.string.mo_phone_shell_unsupported) to null
        PhoneShell.State.OFF -> stringResource(R.string.mo_phone_shell_off) to { openWirelessDebugging(context) }
        PhoneShell.State.NOT_PAIRED -> stringResource(R.string.mo_phone_shell_not_paired) to { PairingService.start(context) }
    }
    SettingsRow(
        title = stringResource(R.string.mo_phone_shell_title),
        subtitle = subtitle,
        icon = Icons.Outlined.AdminPanelSettings,
        onClick = action,
        showDivider = showDivider,
    )
}

private fun openWirelessDebugging(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Settings → advanced access, as Mika lays it out: three numbered steps that tick themselves
 * (read from the system's own switches), each with the button that goes straight there, and
 * "I've turned it on" where a phone hides its switch from apps. Then what this phone cannot do.
 */
@Composable
fun PhoneShellScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snap by PhoneShell.snapshot.collectAsState()
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++; PhoneShell.refresh() }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var saidOn by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) } // steps the person vouched for
    val ready = snap.state == PhoneShell.State.READY
    val steps = androidx.compose.runtime.remember(tick, snap, saidOn) {
        fun global(key: String) = runCatching { Settings.Global.getInt(context.contentResolver, key, 0) == 1 }.getOrDefault(false)
        val dev = ready || saidOn >= 1 || global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        val wireless = ready || saidOn >= 2 || (dev && global("adb_wifi_enabled"))
        listOf(dev, wireless, ready || snap.state == PhoneShell.State.OFF) // OFF: paired, only switched off now
    }
    val current = steps.indexOfFirst { !it } // -1: all done
    com.openminis.app.ui.settings.SettingsScaffold(title = stringResource(R.string.mo_phone_shell_title), onBack = onBack) {
        com.openminis.app.ui.settings.SettingsSection(
            header = stringResource(if (ready) R.string.mo_phone_shell_state_ready else R.string.mo_phone_steps_left, steps.count { !it }),
            footer = stringResource(if (ready) R.string.mo_phone_shell_ready else R.string.mo_phone_shell_footer),
        ) {
            if (snap.state == PhoneShell.State.UNSUPPORTED) {
                SettingsRow(title = stringResource(R.string.mo_phone_shell_unsupported), showDivider = false)
                return@SettingsSection
            }
            val titles = listOf(R.string.mo_phone_step_dev, R.string.mo_phone_step_wireless, R.string.mo_phone_step_pair)
            val hints = listOf(R.string.mo_phone_step_dev_hint, R.string.mo_phone_step_wireless_hint, R.string.mo_phone_step_pair_hint)
            steps.forEachIndexed { i, on ->
                SettingsRow(
                    title = "${i + 1}. " + stringResource(titles[i]),
                    subtitle = if (on) stringResource(R.string.mo_phone_step_on) else stringResource(hints[i]),
                    icon = if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    iconColor = if (on) androidx.compose.ui.graphics.Color(0xFF34C759) else androidx.compose.ui.graphics.Color(0xFF8E8E93),
                    // a done step can be gone through again; the next one is the one to tap
                    onClick = { if (i == 0) openAboutPhone(context) else PairingService.start(context) },
                    showDivider = i < 2,
                )
                if (i == current && i < 2) {
                    SettingsRow(title = stringResource(R.string.mo_phone_step_said_on), onClick = { saidOn = i + 1 }, showChevron = false, showDivider = i < 2)
                }
            }
        }
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", true)) {
            com.openminis.app.ui.settings.SettingsSection(footer = stringResource(R.string.mo_phone_xiaomi_note)) {}
        }
    }
}

/** "About phone", where tapping the build number seven times turns Developer options on. */
private fun openAboutPhone(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
