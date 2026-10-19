package app.momoding.ui.computer

import android.content.Context
import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsCardBlock
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val ROUTE_COMPUTER = "momoding/computer"

/**
 * "它的电脑" (M14): the small computer Momoding has inside the phone — what it made, the sites its
 * browser has been to, the space it takes — and the two ways to clear it, each asked first.
 * Grok Bot's "Bot's computer" settings, without what a phone does not need (images, updates, egress).
 */
@Composable
fun ComputerScreen(onBack: () -> Unit, onPreview: (File) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Computer.State?>(null) }
    LaunchedEffect(refresh) { state = withContext(Dispatchers.IO) { Computer.read(context) } }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SettingsScaffold(title = stringResource(R.string.mo_computer_title), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.mo_computer_intro)) {}
        val s = state ?: return@SettingsScaffold

        SettingsSection(header = stringResource(R.string.mo_computer_files)) {
            if (s.files.isEmpty()) {
                SettingsCardBlock { Text(stringResource(R.string.mo_computer_files_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else s.files.forEachIndexed { i, f ->
                SettingsRow(
                    title = f.name, subtitle = Formatter.formatShortFileSize(context, f.size) + " · " + Computer.age(context, f.modified),
                    icon = Icons.Outlined.Description, iconColor = Color(0xFF5856D6),
                    onClick = { onPreview(f.file) }, showDivider = i < s.files.lastIndex,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.mo_computer_browser), footer = stringResource(R.string.mo_computer_browser_footer)) {
            SettingsRow(
                title = stringResource(R.string.mo_computer_sites),
                subtitle = s.sites.takeIf { it.isNotEmpty() }?.joinToString("、") ?: stringResource(R.string.mo_computer_sites_none),
                icon = Icons.Outlined.Language, iconColor = Color(0xFF007AFF),
            )
            SettingsRow(
                title = stringResource(R.string.mo_computer_sign_out), icon = Icons.AutoMirrored.Outlined.Logout, iconColor = Color(0xFFFF9500),
                onClick = { confirm = Confirm.SIGN_OUT }, showChevron = false, showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.mo_computer_space)) {
            SettingsRow(
                title = stringResource(R.string.mo_computer_space_total, Formatter.formatShortFileSize(context, s.linux + s.made + s.browser)),
                subtitle = stringResource(
                    R.string.mo_computer_space_parts,
                    Formatter.formatShortFileSize(context, s.linux),
                    Formatter.formatShortFileSize(context, s.made),
                    Formatter.formatShortFileSize(context, s.browser),
                ),
                icon = Icons.Outlined.Storage, iconColor = Color(0xFF8E8E93),
            )
            SettingsRow(
                title = stringResource(R.string.mo_computer_clear_files), icon = Icons.Outlined.DeleteOutline, iconColor = Color(0xFFFF3B30),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { confirm = Confirm.CLEAR_FILES }, showChevron = false, showDivider = false,
            )
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (c == Confirm.SIGN_OUT) R.string.mo_computer_sign_out else R.string.mo_computer_clear_files)) },
            text = { Text(stringResource(if (c == Confirm.SIGN_OUT) R.string.mo_computer_sign_out_detail else R.string.mo_computer_clear_files_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    val sid = state?.sessionId
                    scope.launch {
                        if (c == Confirm.SIGN_OUT) Computer.signOutEverywhere() else withContext(Dispatchers.IO) { Computer.clearFiles(context, sid) }
                        refresh++
                    }
                }) { Text(stringResource(if (c == Confirm.SIGN_OUT) R.string.mo_computer_sign_out_confirm else R.string.mo_computer_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private enum class Confirm { SIGN_OUT, CLEAR_FILES }

/** What the page shows, read off the main thread. Files are the main chat's workspace. */
internal object Computer {
    data class Made(val file: File, val name: String, val size: Long, val modified: Long)
    data class State(val sessionId: String?, val files: List<Made>, val sites: List<String>, val linux: Long, val made: Long, val browser: Long)

    private const val MAX_FILES = 8

    fun read(context: Context): State {
        val sid = mainSession(context)
        val workspace = sid?.let { com.openminis.app.sandbox.SessionMounts.sessionDir(context.filesDir, it, "workspace") }
        val files = workspace?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }?.take(MAX_FILES)
            ?.map { Made(it, it.name, it.length(), it.lastModified()) }?.toList().orEmpty()
        val sites = runCatching { com.openminis.app.browser.BrowserHistoryStore.getInstance(context).uniqueDomains() }.getOrDefault(emptyList()).take(8)
        return State(
            sessionId = sid, files = files, sites = sites,
            linux = size(File(context.filesDir, "alpine-rootfs")),
            made = size(File(context.filesDir, "minis-sessions")),
            browser = size(File(context.dataDir, "app_webview")),
        )
    }

    /** The main chat, as [app.momoding.home.MainChat] pins it. */
    private fun mainSession(context: Context): String? =
        context.getSharedPreferences("momoding", Context.MODE_PRIVATE).getString("main_chat.session", null)

    private fun size(f: File): Long = if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** "今天 19:43" in the app's language, as the chat's time dividers say it. */
    fun age(context: Context, at: Long): String =
        app.momoding.ui.chat.TimeDivider.label(at, context.getString(R.string.mo_time_today), context.getString(R.string.mo_time_yesterday))

    /** Its browser forgets every sign-in: cookies and site storage. */
    fun signOutEverywhere() {
        android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    fun clearFiles(context: Context, sessionId: String?) {
        if (sessionId == null) return
        com.openminis.app.sandbox.SessionMounts.sessionDir(context.filesDir, sessionId, "workspace").listFiles()?.forEach { it.deleteRecursively() }
    }
}
