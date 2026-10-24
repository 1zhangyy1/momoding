package app.momoding.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.R
import com.openminis.app.data.UpdateChecker
import com.openminis.app.ui.settings.UpdateDialog
import kotlinx.coroutines.launch

/**
 * The new version, offered where the person is: when the app opens and a newer release is out,
 * the same dialog as About → Check for updates (what is new, Download, Later), downloading and
 * installing from here. "Later" holds it until the next version.
 */
@Composable
fun UpdateOffer() {
    val offer by AutoUpdate.offer.collectAsState()
    val u = offer ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember(u.versionName) { mutableStateOf<Float?>(null) }
    var error by remember(u.versionName) { mutableStateOf<String?>(null) }
    var needsPerm by remember(u.versionName) { mutableStateOf(false) }

    // back from the "install unknown apps" setting: install what was downloaded
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, u.versionName) {
        owner.lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e != Lifecycle.Event.ON_RESUME || !needsPerm || !UpdateChecker.canInstall(context)) return@LifecycleEventObserver
            needsPerm = false
            UpdateChecker.resumablePendingFile(context)?.let { if (UpdateChecker.installApk(context, it)) AutoUpdate.close() }
        })
    }

    UpdateDialog(
        update = u,
        downloadProgress = progress,
        downloadError = error,
        needsInstallPerm = needsPerm,
        onDownload = {
            error = null
            progress = 0f
            scope.launch {
                when (val r = UpdateChecker.download(context = context, url = u.apkUrl, versionName = u.versionName) { progress = it }) {
                    is UpdateChecker.DownloadResult.Success -> {
                        progress = null
                        if (!UpdateChecker.canInstall(context)) needsPerm = true
                        else if (UpdateChecker.installApk(context, r.file)) AutoUpdate.close()
                        else error = context.getString(R.string.check_update_install_launch_failed)
                    }
                    is UpdateChecker.DownloadResult.Error -> { progress = null; error = r.message }
                }
            }
        },
        onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
        onDismiss = { if (progress == null) AutoUpdate.close() },
    )
}
