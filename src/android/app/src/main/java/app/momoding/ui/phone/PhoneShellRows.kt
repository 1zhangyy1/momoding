package app.momoding.ui.phone

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** Settings → advanced access: the row, and what it is. */
@Composable
fun PhoneShellScreen(onBack: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { PhoneShell.refresh() }
    com.openminis.app.ui.settings.SettingsScaffold(title = stringResource(R.string.mo_phone_shell_title), onBack = onBack) {
        com.openminis.app.ui.settings.SettingsSection(footer = stringResource(R.string.mo_phone_shell_footer)) {
            PhoneShellRow()
        }
    }
}
