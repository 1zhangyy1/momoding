package app.momoding.ui.home

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.Identity
import app.momoding.apps.AppIcon
import app.momoding.apps.MoApps
import app.momoding.files.RepoGit
import app.momoding.memory.Memory
import app.momoding.ui.Tones
import app.momoding.ui.files.Grey
import app.momoding.ui.files.resumes
import app.momoding.ui.files.title
import app.momoding.ui.settings.PhonePermission
import app.momoding.ui.settings.rememberPhonePermissions
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ROUTE_ASSISTANT = "momoding/assistant"
const val ROUTE_OTHER_PERMISSIONS = "momoding/other-permissions"

/** Where the home page leads; every one is a page that already exists. */
class HomeLinks(
    val settings: () -> Unit,
    val persona: () -> Unit,
    val identity: () -> Unit,
    val models: () -> Unit,
    val screenModel: () -> Unit,
    val advanced: () -> Unit,
    val hands: () -> Unit,
    val otherPermissions: () -> Unit,
    val routines: () -> Unit,
    val memory: () -> Unit,
    val history: () -> Unit,
    val skills: () -> Unit,
    val computer: () -> Unit,
)

/**
 * Momoding's own page, from its face in the chat: what it thinks with, what it may do on the
 * phone, what it made, what it does on its own, what it remembers, what it can use. Settings
 * (behind the gear) are only about the app.
 */
@Composable
fun AssistantScreen(links: HomeLinks, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = resumes()
    SettingsScaffold(title = "", onBack = onBack, actions = {
        IconButton(onClick = links.settings) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title)) }
    }) {
        Who(links.persona, links.identity)
        Models(links)
        Permissions(links)
        Apps(tick)
        Routines(links.routines)
        LastChange(tick, links)
        SettingsSection {
            SettingsRow(stringResource(R.string.mo_home_skills), icon = Icons.Outlined.Extension, iconColor = Grey, onClick = links.skills)
            SettingsRow(stringResource(R.string.mo_home_computer), icon = Icons.Outlined.Computer, iconColor = Grey, onClick = links.computer, showDivider = false)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Who(onPersona: () -> Unit, onIdentity: () -> Unit) {
    val context = LocalContext.current
    val face = remember { Identity.face(context)?.asImageBitmap() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(CircleShape).clickable(onClick = onIdentity)) { if (face != null) Image(face, null, Modifier.size(80.dp)) }
        Text(Identity.name(context), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Row(
            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPersona).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.mo_home_persona), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Models(links: HomeLinks) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
    repo?.config?.collectAsState()?.value // re-read when the models change
    val chat = app.momoding.ui.settings.currentModelLabel(context)?.substringBefore(" · ")
    val seeing = app.momoding.hands.Hands.screenModel(context)?.label?.substringAfter(": ")?.substringBefore(" · ")
    SettingsSection(header = stringResource(R.string.mo_home_models)) {
        ModelRow(chat ?: stringResource(R.string.mo_settings_models_none), R.string.mo_home_model_chat, Icons.Outlined.Tune, links.models, true)
        ModelRow(seeing ?: stringResource(R.string.mo_models_seeing_none), R.string.mo_home_model_seeing, Icons.Outlined.RemoveRedEye, links.screenModel, false)
    }
}

@Composable
private fun ModelRow(name: String, role: Int, fallback: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit, divider: Boolean) {
    val logo = app.momoding.providers.ProviderLogos.forModel(name)
    SettingsRow(name, subtitle = stringResource(role), icon = if (logo == null) fallback else null, iconColor = Grey, logo = logo, onClick = open, showDivider = divider)
}

/** The three that decide what it can do at all, each with its state; the rest one row below. */
@Composable
private fun Permissions(links: HomeLinks) {
    val context = LocalContext.current
    val shell by app.momoding.phone.PhoneShell.snapshot.collectAsState()
    val perms = rememberPhonePermissions()
    val background = perms.first { it.key == "background" }
    val others = perms.filter { it.key != "background" }
    SettingsSection(header = stringResource(R.string.mo_home_permissions)) {
        Core(Icons.Outlined.AdminPanelSettings, R.string.mo_phone_shell_title, R.string.mo_home_advanced_sub,
            shell.state == app.momoding.phone.PhoneShell.State.READY, links.advanced)
        Core(Icons.Outlined.TouchApp, R.string.mo_hands_title, R.string.mo_home_hands_sub, app.momoding.hands.Hands.enabled(context), links.hands)
        Core(Icons.Outlined.BatteryFull, R.string.mo_perm_row_background, R.string.mo_home_background_sub, background.granted) {
            app.momoding.ui.settings.onPermissionRow(context, background)
        }
        SettingsRow(
            stringResource(R.string.mo_home_other_permissions), icon = Icons.Outlined.Tune, iconColor = Grey,
            onClick = links.otherPermissions, showDivider = false,
            trailing = { Quiet("${others.count { it.granted }} / ${others.size}") },
        )
    }
}

@Composable
private fun Core(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, sub: Int, on: Boolean, open: () -> Unit) {
    SettingsRow(
        stringResource(title), subtitle = stringResource(sub), icon = icon, iconColor = if (on) Tones.action else Grey,
        onClick = open, showChevron = false,
        trailing = {
            if (on) Text(stringResource(R.string.mo_home_on), style = MaterialTheme.typography.bodyMedium, color = Tones.actionText)
            else Text(
                stringResource(R.string.mo_home_turn_on), color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Tones.action).clickable(onClick = open).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        },
    )
}

@Composable
private fun Quiet(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Apps(tick: Int) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<MoApps.App>>(emptyList()) }
    LaunchedEffect(tick) { apps = withContext(Dispatchers.IO) { MoApps.list(context).sortedByDescending { it.dir.lastModified() } } }
    val list = { context.startActivity(android.content.Intent(context, app.momoding.apps.AppsListActivity::class.java)) }
    SettingsSection(header = stringResource(R.string.mo_home_apps)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            apps.take(3).forEach { a ->
                Tile(a.title, onClick = { runCatching { context.startActivity(app.momoding.apps.MoAppActivity.intent(context, a.name)) } }) {
                    val icon = remember(a.name) { AppIcon.bitmap(context, a, 144, adaptive = false).asImageBitmap() }
                    Image(icon, null, Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)))
                }
            }
            Tile(stringResource(if (apps.isEmpty()) R.string.mo_home_apps_new else R.string.mo_home_apps_all, apps.size), onClick = list) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Tones.fill), contentAlignment = Alignment.Center) {
                    Icon(if (apps.size > 3) Icons.Outlined.Folder else Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.size(width = 72.dp, height = 84.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Routines(open: () -> Unit) {
    val context = LocalContext.current
    val tasks by remember { ScheduledTaskStore(context).observe() }.collectAsState(initial = emptyList())
    SettingsSection(header = stringResource(R.string.mo_home_routines)) {
        tasks.take(2).forEach { t ->
            SettingsRow(
                whenText(context, t), subtitle = t.label.ifBlank { t.prompt }.take(60), onClick = open, showChevron = false,
                trailing = { SettingsSwitch(t.enabled, { on -> ScheduledTaskManager(context).setEnabled(t.id, on) }) },
            )
        }
        SettingsRow(
            if (tasks.isEmpty()) stringResource(R.string.mo_home_routines_none) else stringResource(R.string.mo_home_all, tasks.size),
            titleColor = MaterialTheme.colorScheme.onSurfaceVariant, onClick = open, showDivider = false,
        )
    }
}

/** When a routine runs, in a few words. */
private fun whenText(context: Context, t: ScheduledTask): String {
    val time = "%02d:%02d".format(t.timeOfDayHour, t.timeOfDayMinute)
    return when (t.triggerKind) {
        ScheduledTriggerKind.ON_NOTIFICATION -> context.getString(R.string.mo_home_routine_notification)
        ScheduledTriggerKind.CALENDAR -> context.getString(when (t.repeatMode) {
            ScheduledRepeatMode.DAILY -> R.string.mo_home_routine_daily
            ScheduledRepeatMode.WEEKDAYS -> R.string.mo_home_routine_weekdays
            ScheduledRepeatMode.CUSTOM -> R.string.mo_home_routine_some_days
            ScheduledRepeatMode.ONCE -> R.string.mo_home_routine_once
        }, time)
        else -> context.getString(R.string.mo_home_routine_other)
    }
}

@Composable
private fun LastChange(tick: Int, links: HomeLinks) {
    val context = LocalContext.current
    var last by remember { mutableStateOf<RepoGit.Commit?>(null) }
    LaunchedEffect(tick) { last = RepoGit.log(context, Memory.SANDBOX_DIR, limit = 1).firstOrNull() }
    SettingsSection(header = stringResource(R.string.mo_files_memory)) {
        val c = last
        if (c != null) SettingsRow(
            title(c), subtitle = app.momoding.ui.files.whenFull(c.at), onClick = links.history, showChevron = false,
        )
        SettingsRow(stringResource(R.string.mo_home_memory_files), icon = Icons.Outlined.Folder, iconColor = Grey, onClick = links.memory, showDivider = false)
    }
}

/**
 * The phone's permissions other than the three on the home page, and the rules for what it
 * asks first: the rows Settings had, moved here.
 */
@Composable
fun OtherPermissionsScreen(onRules: () -> Unit, onAll: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val perms = rememberPhonePermissions().filter { it.key != "background" }
    val rules = app.momoding.guard.Grants.always.collectAsState().value.size
    SettingsScaffold(title = stringResource(R.string.mo_home_other_permissions), onBack = onBack) {
        SettingsSection(header = stringResource(R.string.mo_settings_phone)) {
            perms.forEachIndexed { i, p: PhonePermission ->
                SettingsRow(
                    stringResource(p.title), subtitle = stringResource(p.purpose), icon = p.icon,
                    iconColor = if (p.granted) Tones.action else Grey, onClick = { app.momoding.ui.settings.onPermissionRow(context, p) },
                    showChevron = false, showDivider = i < perms.lastIndex,
                    trailing = { if (p.granted) Text(stringResource(R.string.mo_home_on), color = Tones.actionText) else Quiet(stringResource(R.string.mo_home_off)) },
                )
            }
        }
        SettingsSection(header = stringResource(R.string.mo_settings_ask_first)) {
            SettingsRow(
                stringResource(R.string.mo_settings_rules_page),
                subtitle = if (rules == 0) stringResource(R.string.mo_settings_rules_none) else stringResource(R.string.mo_settings_rules_count, rules),
                onClick = onRules, showDivider = false,
            )
        }
        SettingsSection {
            SettingsRow(stringResource(R.string.mo_caps_title), subtitle = stringResource(R.string.mo_caps_sub), onClick = onAll, showDivider = false)
        }
    }
}
