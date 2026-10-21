// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/ui/hands/HandsScreen.kt
package app.momoding.ui.hands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import app.momoding.hands.Hands
import app.momoding.ui.Tones
import app.momoding.ui.Design

const val ROUTE_HANDS = "momoding/hands"

/**
 * Settings → Hands. The switch (off by default), the three things the hands need — the
 * accessibility service, drawing over other apps, a model that sees pictures — each with
 * the button that fixes it, the screen model (a row that opens the picker of Settings →
 * Models), and the plain words on what the hands will and will not do.
 */
@Composable
fun HandsScreen(onBack: () -> Unit, onOpenProviders: () -> Unit, onOpenHandsModel: () -> Unit = onOpenProviders) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
    val config = repo?.config?.collectAsState()?.value
    // Follows the preference itself, so a change made elsewhere while this page is open shows here.
    val enabled by remember(context) { Hands.enabledFlow(context) }.collectAsState(initial = Hands.enabled(context))
    var tick by remember { mutableIntStateOf(0) }
    val active by Hands.active.collectAsState()

    // Permissions are granted in other apps; re-read when the user comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var pickModel by remember { mutableStateOf(false) }
    val readiness = remember(tick, config) { Hands.readiness(context) }
    val visionEntries = remember(config) { Hands.visionEntries(context) }

    SettingsScaffold(title = stringResource(R.string.mo_hands_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.mo_hands_intro),
            fontSize = Design.Size.Label,
            lineHeight = Design.Line.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
        )

        SettingsSection(footer = stringResource(R.string.mo_hands_switch_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.mo_hands_switch),
                subtitle = when {
                    active -> stringResource(R.string.mo_hands_status_working)
                    enabled && readiness.ready -> stringResource(R.string.mo_hands_status_ready)
                    enabled -> stringResource(R.string.mo_hands_status_missing)
                    else -> stringResource(R.string.mo_hands_status_off)
                },
                checked = enabled,
                onCheckedChange = { Hands.setEnabled(context, it) },
                icon = Icons.Outlined.PhoneAndroid,
                iconColor = if (enabled) Tones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                showDivider = active,
            )
            if (active) {
                SettingsRow(
                    title = stringResource(R.string.mo_hands_stop_run),
                    onClick = { Hands.stopCurrent() },
                    showChevron = false,
                    showDivider = false,
                    titleColor = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ── what it needs ──
        // C11: with no provider that has a model that sees, the footer is the one sentence that says which would
        SettingsSection(
            header = stringResource(R.string.mo_hands_section_needs),
            footer = if (visionEntries.isEmpty()) stringResource(R.string.mo_cap_hands_unavailable) else stringResource(R.string.mo_hands_needs_footer),
        ) {
            NeedRow(
                icon = Icons.Outlined.Accessibility,
                title = stringResource(R.string.mo_hands_need_a11y),
                ok = readiness.serviceOn,
                okText = stringResource(R.string.mo_hands_need_on),
                fixText = stringResource(R.string.mo_hands_need_a11y_fix),
                onFix = { openAccessibilitySettings(context) },
            )
            NeedRow(
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.mo_hands_need_overlay),
                ok = readiness.overlayOk,
                okText = stringResource(R.string.mo_hands_need_granted),
                fixText = stringResource(R.string.mo_hands_need_overlay_fix),
                onFix = { openOverlaySettings(context) },
            )
            NeedRow(
                icon = Icons.Outlined.Visibility,
                title = stringResource(R.string.mo_hands_need_model),
                ok = readiness.model != null,
                // the model and how it was arrived at, so "why qwen-vl-max?" has its answer here
                okText = readiness.model?.let { m -> m.label + " · " + stringResource(whyText(m.why)) } ?: "",
                fixText = stringResource(R.string.mo_hands_need_model_fix),
                onFix = onOpenProviders,
                showDivider = !readiness.androidOk,
            )
            if (!readiness.androidOk) {
                SettingsRow(
                    title = stringResource(R.string.mo_hands_need_android),
                    subtitle = stringResource(R.string.mo_hands_need_android_sub),
                    showDivider = false,
                )
            }
        }

        // ── a greyed-out switch ── Android locks accessibility for apps installed from an APK
        // ("restricted setting"); as Minis does, say why and open the app page where it is unlocked
        val restricted = remember(tick) { !readiness.serviceOn && com.openminis.app.accessibility.RestrictedSettingsManager.isRestricted(context) }
        if (restricted) {
            SettingsSection(
                header = stringResource(R.string.system_permissions_a11y_restricted_header),
                footer = stringResource(R.string.system_permissions_a11y_restricted_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.system_permissions_a11y_restricted_manual),
                    subtitle = stringResource(R.string.system_permissions_a11y_restricted_manual_sub),
                    icon = Icons.Outlined.Accessibility,
                    iconColor = Color(0xFFFF9500),
                    onClick = { app.momoding.hands.HandsSetup.openAppDetails(context) },
                    showDivider = false,
                )
            }
        }

        // ── the screen model ── one row; the choice itself is the picker of Settings → Models
        // (0.1.41), so the hands' model has one place, not two.
        if (visionEntries.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.mo_hands_section_model), footer = stringResource(R.string.mo_hands_model_footer)) {
                SettingsRow(
                    title = stringResource(R.string.mo_models_hands_title),
                    subtitle = readiness.model?.let { m -> m.label + " · " + stringResource(whyText(m.why)) },
                    onClick = { pickModel = true },
                    showDivider = false,
                )
            }
        }
        if (pickModel) ScreenModelPicker(current = Hands.modelEntryId(context), onDismiss = { pickModel = false }) { id ->
            Hands.setModelEntryId(context, id); pickModel = false; tick++
        }

        // ── the rules ──
        // ── in the background ── the recommended way when Shizuku is there: the hands get their own
        // hidden screen and the person keeps theirs (RikkaHub's virtual display, through Shizuku)
        val shizuku by com.openminis.app.offload.ShizukuManager.snapshot.collectAsState()
        var background by remember { mutableStateOf(Hands.background(context)) }
        SettingsSection(header = stringResource(R.string.mo_hands_section_background), footer = stringResource(R.string.mo_hands_background_footer)) {
            when (shizuku.state) {
                com.openminis.app.offload.ShizukuManager.State.READY -> SettingsSwitchRow(
                    title = stringResource(R.string.mo_hands_background),
                    subtitle = stringResource(if (background) R.string.mo_hands_background_on else R.string.mo_hands_background_off),
                    checked = background,
                    onCheckedChange = { Hands.setBackground(context, it); background = it },
                    icon = Icons.Outlined.Layers,
                    iconColor = if (background) Tones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
                com.openminis.app.offload.ShizukuManager.State.NEED_PERMISSION -> SettingsRow(
                    title = stringResource(R.string.mo_hands_background),
                    subtitle = stringResource(R.string.mo_hands_background_need_permission),
                    icon = Icons.Outlined.Layers,
                    onClick = { com.openminis.app.offload.ShizukuManager.requestPermission() },
                    showDivider = false,
                )
                com.openminis.app.offload.ShizukuManager.State.NOT_RUNNING -> SettingsRow(
                    title = stringResource(R.string.mo_hands_background),
                    subtitle = stringResource(R.string.mo_hands_background_not_running),
                    icon = Icons.Outlined.Layers,
                    onClick = { com.openminis.app.offload.ShizukuManager.openShizukuApp(context) },
                    showDivider = false,
                )
                else -> SettingsRow(
                    title = stringResource(R.string.mo_hands_background),
                    subtitle = stringResource(R.string.mo_hands_background_not_installed),
                    icon = Icons.Outlined.Layers,
                    onClick = { com.openminis.app.offload.ShizukuManager.openInstallPage(context) },
                    showDivider = false,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.mo_hands_section_rules)) {
            RuleRow(stringResource(R.string.mo_hands_rule_ladder))
            RuleRow(stringResource(R.string.mo_hands_rule_secrets))
            RuleRow(stringResource(R.string.mo_hands_rule_approvals))
            RuleRow(stringResource(R.string.mo_hands_rule_stop))
            RuleRow(stringResource(R.string.mo_hands_rule_screenshots), last = true)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NeedRow(
    icon: ImageVector,
    title: String,
    ok: Boolean,
    okText: String,
    fixText: String,
    onFix: () -> Unit,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = if (ok) okText else fixText,
        icon = icon,
        iconColor = if (ok) Tones.action else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = if (ok) null else onFix,
        showChevron = false,
        showDivider = showDivider,
        minHeight = 64.dp,
        trailing = {
            Text(
                text = stringResource(if (ok) R.string.mo_hands_need_ok else R.string.mo_hands_need_set_up),
                fontSize = Design.Size.Label,
                fontWeight = FontWeight.Medium,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else Tones.action,
            )
        },
    )
}

@Composable
private fun RuleRow(text: String, last: Boolean = false) {
    SettingsRow(title = text, showDivider = !last, minHeight = 48.dp)
}

private fun openAccessibilitySettings(context: Context) = app.momoding.hands.HandsSetup.openAccessibility(context)

/** The sentence for how the screen model was arrived at ([Hands.screenModel]). */
private fun whyText(why: Hands.Why): Int = when (why) {
    Hands.Why.CHOSEN -> R.string.mo_hands_why_chosen
    Hands.Why.DEFAULT -> R.string.mo_hands_why_default
    Hands.Why.CHAT -> R.string.mo_hands_why_chat
    Hands.Why.GROUP -> R.string.mo_hands_why_group
    Hands.Why.VISION_GROUP -> R.string.mo_hands_why_vision_group
    Hands.Why.ANY -> R.string.mo_hands_why_any
}

private fun openOverlaySettings(context: Context) = app.momoding.hands.HandsSetup.openOverlay(context)


/** Choose the screen model: automatic, or one of the few that do well on screens (one per family). */
@Composable
private fun ScreenModelPicker(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    val options = remember { Hands.recommendedScreenModels(context) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mo_models_hands_title)) },
        text = {
            Column {
                PickRow(stringResource(R.string.mo_hands_pick_auto), stringResource(R.string.mo_hands_pick_auto_sub), current == null) { onPick(null) }
                options.forEach { (inst, e) ->
                    PickRow(e.model.displayName.ifBlank { e.model.id }.substringAfter(": "), inst.label, current == e.id) { onPick(e.id) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(app.momoding.ui.Design.Radius.Row))
            .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = app.momoding.ui.Design.Size.Body)
            Text(subtitle, fontSize = app.momoding.ui.Design.Size.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, tint = app.momoding.ui.Tones.action)
    }
}
