package app.momoding.apps

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.launch

/**
 * Momoding Apps, all in one place (plan step 4): each app with its icon; open it, see and take
 * back what it may use, delete it. Deleting takes everything of the app: its folder, the person's
 * data, its own Momoding's chat; its home-screen icon stops working and it leaves the share sheet.
 */
class AppsListActivity : ComponentActivity() {
    private var apps by mutableStateOf<List<MoApps.App>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MinisTheme(darkTheme = isSystemInDarkTheme()) { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        apps = MoApps.list(this).filter { it.index.isFile }
    }

    @Composable
    private fun Screen() {
        Scaffold { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.mo_apps_title), fontSize = 22.sp, modifier = Modifier.padding(vertical = 16.dp))
                if (apps.isEmpty()) Text(stringResource(R.string.mo_apps_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(apps, key = { it.name }) { AppRow(it) } }
            }
        }
    }

    @Composable
    private fun AppRow(app: MoApps.App) {
        var open by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = !open }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = remember(app.name) { AppIcon.bitmap(app, 96, adaptive = false).asImageBitmap() }
                Image(icon, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.title, fontSize = 16.sp)
                    val granted = AppPermissions.granted(app)
                    val allowed = granted.keys().asSequence().filter { granted.optBoolean(it) }.mapNotNull { AppPermissions.label(it) }.toList()
                    Text(if (allowed.isEmpty()) stringResource(R.string.mo_apps_uses_nothing) else allowed.map { stringResource(it) }.joinToString("、"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { startActivity(MoAppActivity.intent(this@AppsListActivity, app.name)) }) { Text(stringResource(R.string.mo_apps_open)) }
            }
            if (open) {
                val granted = AppPermissions.granted(app)
                granted.keys().asSequence().toList().forEach { command ->
                    var on by remember { mutableStateOf(granted.optBoolean(command)) }
                    Row(Modifier.fillMaxWidth().padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(AppPermissions.label(command)?.let { stringResource(it) } ?: command, Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = on, onCheckedChange = { on = it; AppPermissions.set(app, command, it) })
                    }
                }
                TextButton(onClick = { confirmDelete(app) }, modifier = Modifier.padding(start = 44.dp)) {
                    Text(stringResource(R.string.mo_apps_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    private fun confirmDelete(app: MoApps.App) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.mo_apps_delete_confirm, app.title))
            .setPositiveButton(R.string.mo_apps_delete) { _, _ -> delete(app) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete(app: MoApps.App) {
        val id = "momoding_app_${app.name}"
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(id)) }
        runCatching { ShortcutManagerCompat.disableShortcuts(this, listOf(id), getString(R.string.mo_apps_deleted_icon)) }
        val session = app.botSession
        lifecycleScope.launch {
            session?.let { runCatching { (application as MinisApp).chatRepository.deleteSession(it) } }
            MoApps.remove(this@AppsListActivity, app)
            AppWidget.refresh(this@AppsListActivity, app)
            apps = MoApps.list(this@AppsListActivity).filter { it.index.isFile }
        }
    }
}
