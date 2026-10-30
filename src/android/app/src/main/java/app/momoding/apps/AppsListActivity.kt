package app.momoding.apps

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import app.momoding.ui.Tones
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The person's Bots and their apps, all in one place (调研/M32 第十一节): each Bot a row with its
 * state; a tap unfolds it — its name, its notices, its app (what the app may use, delete it, or have
 * one made) — and folds it again. Deleting the app keeps the Bot; deleting the Bot takes its chat
 * and its app. Either waits a few seconds, and can be taken back until then.
 */
class AppsListActivity : ComponentActivity() {
    /** Deletions not done yet: the Bot's or the app's name. */
    private var going by mutableStateOf<Set<String>>(emptySet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = intent.getStringExtra(EXTRA_NAME)
        setContent { MinisTheme(darkTheme = isSystemInDarkTheme()) { Screen(first) } }
    }

    override fun onResume() {
        super.onResume()
        BotStatus.touch()
    }

    @Composable
    private fun Screen(first: String?) {
        val bots by BotStatus.all(this).collectAsState()
        val snack = remember { SnackbarHostState() }
        // the page's, not a row's: a deleted row leaves, and the line offering it back must stay
        val scope = rememberCoroutineScope()
        var unfolded by remember { mutableStateOf(first) }
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.mo_apps_title), fontSize = 22.sp, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.mo_apps_sub), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                val shown = bots.filter { it.app != null && it.app.name !in going }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isEmpty()) item { Text(stringResource(R.string.mo_apps_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(shown, key = { it.session }) { b ->
                        BotCard(b, unfolded == b.app!!.name, snack, scope) { unfolded = if (unfolded == b.app.name) null else b.app.name }
                    }
                    item { NewBot(bots.firstOrNull()) }
                }
            }
        }
    }

    @Composable
    private fun BotCard(b: BotStatus.Bot, open: Boolean, snack: SnackbarHostState, scope: CoroutineScope, onToggle: () -> Unit) {
        val app = b.app!!
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tones.surface).animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = remember(app.name, app.title) { AppIcon.bitmap(app, 96, adaptive = false).asImageBitmap() }
                Image(icon, null, Modifier.size(40.dp).clip(CircleShape))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.title, fontSize = 16.sp)
                    Text(line(b), fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!open) return@Column
            HorizontalDivider(color = Tones.hairline)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { AppChat.home(this@AppsListActivity, b.session, app, raise = true) }) { Text(stringResource(if (app.hasApp && "app:${app.name}" !in going) R.string.mo_bot_open_app else R.string.mo_bot_open_chat)) }
                    TextButton(onClick = { rename(app, b.session) }) { Text(stringResource(R.string.mo_bot_rename)) }
                }
                var notify by remember { mutableStateOf(!BotStatus.quiet(this@AppsListActivity, b.session)) }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.mo_bot_notify), fontSize = 15.sp)
                        Text(stringResource(R.string.mo_bot_notify_sub), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = notify, onCheckedChange = { notify = it; BotStatus.setQuiet(this@AppsListActivity, b.session, !it) })
                }
                Text(stringResource(R.string.mo_bot_app), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
                if (app.hasApp && "app:${app.name}" !in going) {
                    val granted = AppPermissions.granted(app)
                    val commands = granted.keys().asSequence().toList()
                    if (commands.isEmpty()) Text(stringResource(R.string.mo_apps_uses_nothing), fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                    commands.forEach { command ->
                        var on by remember { mutableStateOf(granted.optBoolean(command)) }
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(AppPermissions.label(command)?.let { stringResource(it) } ?: command, Modifier.weight(1f), fontSize = 14.sp)
                            Switch(checked = on, onCheckedChange = { on = it; AppPermissions.set(app, command, it) })
                        }
                    }
                    val said = stringResource(R.string.mo_bot_app_deleted, app.title)
                    TextButton(onClick = { scope.launch { later("app:${app.name}", said, snack) { removeApp(app) } } }) {
                        Text(stringResource(R.string.mo_bot_delete_app), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    val ask = stringResource(R.string.mo_bot_make_app_ask)
                    TextButton(onClick = { AppChat.ask(this@AppsListActivity, b.session, null, ask) }) { Text(stringResource(R.string.mo_bot_make_app)) }
                }
                HorizontalDivider(color = Tones.hairline, modifier = Modifier.padding(vertical = 4.dp))
                val said = stringResource(R.string.mo_bot_deleted, app.title)
                TextButton(onClick = { scope.launch { later(app.name, said, snack) { removeBot(app, b.session) } } }) {
                    Text(stringResource(R.string.mo_bot_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @Composable
    private fun line(b: BotStatus.Bot): String {
        val text = b.status.line.orEmpty()
        return when (b.status.kind) {
            BotStatus.Kind.WAITING -> stringResource(R.string.mo_bot_waiting, text)
            BotStatus.Kind.WORKING -> stringResource(R.string.mo_bot_working, text)
            else -> b.app?.what?.ifBlank { null } ?: text
        }
    }

    /** A new Bot is the main Momoding's to make, as from the header. */
    @Composable
    private fun NewBot(main: BotStatus.Bot?) {
        val ask = stringResource(R.string.mo_bots_new_ask)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { main?.let { AppChat.ask(this@AppsListActivity, it.session, null, ask) } }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Add, null, tint = Tones.action)
            Text(stringResource(R.string.mo_bots_new), color = Tones.action, fontSize = 16.sp)
        }
    }

    private fun rename(app: MoApps.App, session: String) {
        val field = EditText(this).apply { setText(app.title); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.mo_bot_rename)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = field.text.toString().trim().ifEmpty { return@setPositiveButton }
                MoApps.rename(app, title)
                background.launch { runCatching { (application as MinisApp).chatRepository.updateSessionTitle(session, title) }; BotStatus.touch() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * [work] in a few seconds, unless the person takes it back from the line that says it; it runs
     * even if they leave this page first.
     */
    private suspend fun later(key: String, said: String, snack: SnackbarHostState, work: suspend () -> Unit) {
        going = going + key
        val job: Job = background.launch { delay(UNDO_MS); work(); BotStatus.touch() }
        val shown = kotlinx.coroutines.MainScope().launch { delay(UNDO_MS); snack.currentSnackbarData?.dismiss() }
        if (snack.showSnackbar(said, getString(R.string.mo_bot_undo), duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) {
            job.cancel()
            going = going - key
        }
        shown.cancel()
    }

    private fun removeApp(app: MoApps.App) {
        unpin(app)
        MoApps.removeApp(app)
        AppShare.publish(this, app)
        AppWidget.refresh(this, app)
    }

    private suspend fun removeBot(app: MoApps.App, session: String) {
        unpin(app)
        runCatching { (application as MinisApp).chatRepository.deleteSession(session) }
        MoApps.remove(app)
        AppWidget.refresh(this, app)
    }

    /** Its home-screen icon stops working (a launcher keeps pinned icons; they can only be disabled). */
    private fun unpin(app: MoApps.App) {
        val id = "momoding_app_${app.name}"
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(id)) }
        runCatching { ShortcutManagerCompat.disableShortcuts(this, listOf(id), getString(R.string.mo_apps_deleted_icon)) }
    }

    companion object {
        const val EXTRA_NAME = "momoding_bot"
        private const val UNDO_MS = 6_000L
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
