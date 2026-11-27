package app.momoding.ui.files

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.momoding.files.RepoGit
import app.momoding.know.KnowTools
import app.momoding.memory.Memory
import com.openminis.app.R
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val ROUTE_FOLDER = "momoding/folder?path={path}"
const val ROUTE_DOC = "momoding/doc?path={path}"
fun folderRoute(path: String) = "momoding/folder?path=" + Uri.encode(path)
fun docRoute(path: String) = "momoding/doc?path=" + Uri.encode(path)

/**
 * Where Momoding's own files are, by their sandbox path (what it writes and what the person sees
 * named), and which git repo holds each: its memory, and each app.
 */
object Sandbox {
    private const val ROOT = "/var/minis/"
    const val APPS = "/var/minis/apps"

    fun host(context: Context, path: String): File = File(context.filesDir, "minis-global/" + path.removePrefix(ROOT))

    fun repoOf(path: String): String? = when {
        path == Memory.SANDBOX_DIR || path.startsWith(Memory.SANDBOX_DIR + "/") -> Memory.SANDBOX_DIR
        path.startsWith("$APPS/") -> "$APPS/" + path.removePrefix("$APPS/").substringBefore('/')
        else -> null
    }

    /** Files that have a page of their own (the persona, Momoding's know-how), kept out of the memory list. */
    private val OWN_PAGE = setOf("SOUL.md", "know-how", "sites.json")

    fun hidden(dir: String, name: String): Boolean =
        name.startsWith(".") || (dir == Memory.SANDBOX_DIR && name in OWN_PAGE)
}

/** The first line of prose in a note: what it is about, in Momoding's own words. */
internal fun summary(text: String): String = text.lineSequence().map { it.trim() }
    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("---") }
    ?.removePrefix("- ")?.removePrefix("* ")?.replace(TAG, "")?.take(80).orEmpty()

/** Bookkeeping at a line's end, like `[added: 2026-11-27]`: for Momoding, not for the eye. */
private val TAG = Regex("""\s*\[[a-z]+: [^\]]*]\s*$""")

@Composable
internal fun whenText(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val days = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR) +
        365 * (now.get(java.util.Calendar.YEAR) - then.get(java.util.Calendar.YEAR))
    val locale = LocalContext.current.resources.configuration.locales[0]
    return when (days) {
        0 -> DateFormat.format("HH:mm", at).toString()
        1 -> stringResource(R.string.mo_files_yesterday)
        else -> DateFormat.format(DateFormat.getBestDateTimePattern(locale, "MMMd"), at).toString()
    }
}

/** A note's `---` block at the top (name: …), as pairs, and the rest; most notes have none. */
internal fun frontMatter(text: String): Pair<List<Pair<String, String>>, String> {
    val lines = text.lines()
    if (lines.firstOrNull()?.trim() != "---") return emptyList<Pair<String, String>>() to text
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: return emptyList<Pair<String, String>>() to text
    val pairs = lines.subList(1, end).mapNotNull { l ->
        l.indexOf(':').takeIf { it > 0 }?.let { i -> l.take(i).trim() to l.drop(i + 1).trim().trim('"', '\'') }
    }
    return pairs to lines.drop(end + 1).joinToString("\n")
}

/** Bumps each time the screen comes back, so a list shows what changed on the page above it. */
@Composable
internal fun resumes(): Int {
    var n by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) n++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return n
}

private data class Entry(val name: String, val dir: Boolean, val at: Long, val sub: String, val count: Int)

private fun list(context: Context, path: String): List<Entry> {
    val files = Sandbox.host(context, path).listFiles().orEmpty().filter { !Sandbox.hidden(path, it.name) }
    // a folder of scripts says what each does in its tools.json
    val said = if (path == KnowTools.SANDBOX_DIR) KnowTools.all(context).associate { it.file to it.what } else emptyMap()
    return files.map { f ->
        if (f.isDirectory) {
            val kids = f.listFiles().orEmpty().filter { !it.name.startsWith(".") }.sortedByDescending { it.lastModified() }
            Entry(f.name, true, f.lastModified(), kids.take(3).joinToString(" · ") { it.name }, kids.size)
        } else {
            val sub = said[f.name] ?: if (f.name.endsWith(".md")) runCatching { summary(f.readText()) }.getOrDefault("") else ""
            Entry(f.name, false, f.lastModified(), sub, 0)
        }
    }.sortedWith(compareByDescending<Entry> { it.name == "MEMORY.md" }.thenBy { it.dir }.thenByDescending { it.at })
}

/**
 * A folder of Momoding's, as it is: every file it made with its first line, every folder it made.
 * Nothing here knows what memory or know-how is; it shows whatever Momoding keeps.
 */
@Composable
fun FolderScreen(path: String, onOpen: (String) -> Unit, onHistory: (repo: String, path: String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = resumes()
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var versions by remember { mutableIntStateOf(0) }
    val repo = Sandbox.repoOf(path)
    LaunchedEffect(path, tick) {
        entries = withContext(Dispatchers.IO) { list(context, path) }
        if (repo == path) versions = RepoGit.count(context, repo)
    }
    val title = if (path == Memory.SANDBOX_DIR) stringResource(R.string.mo_files_memory) else path.substringAfterLast('/')
    SettingsScaffold(title = title, onBack = onBack) {
        if (path == Memory.SANDBOX_DIR) Note(stringResource(R.string.mo_files_memory_sub))
        val all = entries ?: return@SettingsScaffold
        if (all.isEmpty()) Note(stringResource(R.string.mo_files_empty))
        else SettingsSection {
            all.forEachIndexed { i, e ->
                SettingsRow(
                    title = e.name,
                    subtitle = e.sub.ifEmpty { null },
                    icon = when {
                        e.name == "MEMORY.md" -> Icons.Outlined.PushPin
                        e.dir -> Icons.Outlined.Folder
                        e.name.endsWith(".md") || e.name.endsWith(".txt") -> Icons.Outlined.Description
                        e.name.endsWith(".json") -> Icons.Outlined.DataObject
                        else -> Icons.Outlined.Code
                    },
                    iconColor = Grey,
                    onClick = { onOpen("$path/${e.name}") },
                    showChevron = e.dir,
                    showDivider = i < all.lastIndex,
                    trailing = { Text(if (e.dir) e.count.toString() else whenText(e.at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
        if (repo == path && versions > 0) SettingsSection {
            SettingsRow(
                title = stringResource(R.string.mo_files_history), icon = Icons.Outlined.History,
                iconColor = Grey, onClick = { onHistory(repo, null) }, showDivider = false,
                trailing = { Text(stringResource(R.string.mo_files_versions, versions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

internal val Grey = androidx.compose.ui.graphics.Color(0xFF8E8E93)

@Composable
internal fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
)

/**
 * One file as Momoding wrote it: Markdown laid out, anything else as its text. The person can
 * edit it; in a repo that is a commit of theirs, so Momoding sees it in the history too.
 */
@Composable
fun DocScreen(path: String, onHistory: (repo: String, path: String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { Sandbox.host(context, path) }
    val repo = Sandbox.repoOf(path)
    val tick = resumes()
    var text by remember { mutableStateOf<String?>(null) }
    var at by remember { mutableStateOf(0L) }
    var draft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, tick) {
        text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
        at = file.lastModified()
    }
    val name = path.substringAfterLast('/')
    SettingsScaffold(
        title = name.substringBeforeLast('.').ifEmpty { name },
        onBack = { if (draft != null) draft = null else onBack() },
        actions = {
            val editing = draft
            if (editing != null) TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val rel = repo?.let { path.removePrefix("$it/") } ?: name
                        if (repo != null) RepoGit.saveEdit(context, repo, file, editing, rel) else file.writeText(editing)
                    }
                    text = editing; at = file.lastModified(); draft = null
                }
            }) { Text(stringResource(R.string.mo_files_save)) }
            else {
                if (repo != null) IconButton(onClick = { onHistory(repo, path.removePrefix("$repo/")) }) {
                    Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.mo_files_history))
                }
                IconButton(onClick = { draft = text.orEmpty() }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.mo_files_edit)) }
            }
        },
    ) {
        val body = text ?: return@SettingsScaffold
        Note("$name · ${whenText(at)}")
        val card = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)
        val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        val editing = draft
        when {
            editing != null -> BasicTextField(
                value = editing, onValueChange = { draft = it }, textStyle = mono,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = card.heightIn(min = 240.dp),
            )
            name.endsWith(".md") -> {
                val (head, rest) = remember(body) { frontMatter(body) }
                Column(card) {
                    head.forEach { (k, v) ->
                        androidx.compose.foundation.layout.Row(Modifier.padding(bottom = 6.dp)) {
                            Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                            Text(v.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (head.isNotEmpty() && rest.isNotBlank()) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    MarkdownText(remember(rest) { rest.lines().joinToString("\n") { it.replace(TAG, "") }.trim() })
                }
            }
            else -> Column(card.horizontalScroll(rememberScrollState())) { Text(body, style = mono) }
        }
        if (editing == null && path.startsWith(KnowTools.SANDBOX_DIR + "/")) TryRun(context, name)
    }
}

/**
 * A kept script, run once by hand: the command from tools.json, which the person fills in and
 * starts, and what it printed. Nothing runs until they tap.
 */
@Composable
private fun TryRun(context: Context, file: String) {
    val tool = remember(file) { KnowTools.all(context).firstOrNull { it.file == file } }
    var command by remember(file) { mutableStateOf(tool?.usage ?: "./$file") }
    var output by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    tool?.what?.let { Note(it) }
    val field = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(command, { command = it }, textStyle = mono, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = field)
    SettingsSection {
        SettingsRow(stringResource(if (running) R.string.mo_files_running else R.string.mo_files_try_run), showChevron = false, showDivider = false,
            onClick = if (running) null else ({
                running = true
                scope.launch {
                    if (!com.openminis.app.sandbox.PRootKernel.isBooted) com.openminis.app.sandbox.PRootKernel.boot(context)
                    val r = com.openminis.app.sandbox.ShellExecutor.execute(context, "cd ${KnowTools.SANDBOX_DIR} && $command", timeout = 60_000)
                    output = r.output.trim().ifEmpty { "exit ${r.exitCode}" }.take(4000); running = false
                }
            }))
    }
    output?.let { Column(field.horizontalScroll(rememberScrollState())) { Text(it, style = mono) } }
}
