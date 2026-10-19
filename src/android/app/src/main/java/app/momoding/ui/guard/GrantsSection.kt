// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/ui/guard/GrantsSection.kt
package app.momoding.ui.guard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import app.momoding.guard.Grant
import app.momoding.guard.Grants
import app.momoding.guard.RiskClass
import app.momoding.guard.RiskText
import app.momoding.guard.RiskTier
import java.util.Date

/**
 * Settings → Permissions: the "always allow for X" grants, grouped by danger tier — payments
 * that run without a card first, then deleting and sending remembered from the card, then the
 * installs tier (which never asks; shown for completeness when something was remembered under
 * it). One row each; tap to revoke.
 */
@Composable
fun GrantsSection() {
    val context = LocalContext.current
    val grants by Grants.always.collectAsState()
    var revoking by remember { mutableStateOf<Grant?>(null) }

    if (grants.isEmpty()) {
        SettingsSection(
            header = stringResource(R.string.mo_grants_header),
            footer = stringResource(R.string.mo_grants_footer_tiers),
        ) {
            SettingsRow(title = stringResource(R.string.mo_grants_empty), showDivider = false, showChevron = false)
        }
        return
    }

    val tiers = listOf(RiskTier.HIGHEST, RiskTier.CONFIRM, RiskTier.NOTICE)
    val grouped = grants.groupBy { it.riskClass.tier }
    val shown = tiers.filter { grouped[it].orEmpty().isNotEmpty() }
    shown.forEachIndexed { t, tier ->
        val rows = grouped[tier].orEmpty().sortedByDescending { it.grantedAt }
        SettingsSection(
            header = RiskText.tierLabel(context, tier), // momoding: the page title already says what these are
            footer = if (t == shown.lastIndex) stringResource(R.string.mo_grants_footer_tiers) else null,
        ) {
            rows.forEachIndexed { i, g ->
                SettingsRow(
                    title = RiskText.classLabel(context, g.riskClass) + " · " + (RiskText.targetLabel(context, g.target) ?: g.target ?: ""),
                    // momoding: "今天 21:44 加的", in the app's language like the chat's times
                    subtitle = stringResource(R.string.mo_grants_added, app.momoding.ui.chat.TimeDivider.label(g.grantedAt, stringResource(R.string.mo_time_today), stringResource(R.string.mo_time_yesterday), locale = context.resources.configuration.locales[0])),
                    icon = when (g.riskClass) {
                        RiskClass.DESTRUCTIVE -> Icons.Outlined.DeleteOutline
                        RiskClass.MONEY -> Icons.Outlined.Payments
                        RiskClass.INSTALL -> Icons.Outlined.Download
                        RiskClass.REMOTE -> Icons.Outlined.Devices
                        else -> Icons.AutoMirrored.Outlined.Send
                    },
                    onClick = { revoking = g },
                    showChevron = false,
                    showDivider = i < rows.size - 1,
                    minHeight = 64.dp,
                )
            }
        }
    }

    revoking?.let { g ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(RiskText.classLabel(context, g.riskClass) + " · " + (RiskText.targetLabel(context, g.target) ?: "")) },
            text = { Text(stringResource(R.string.mo_grants_revoke_confirm)) },
            confirmButton = {
                TextButton(onClick = { Grants.revoke(g.key); revoking = null }) { Text(stringResource(R.string.mo_grants_revoke)) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
