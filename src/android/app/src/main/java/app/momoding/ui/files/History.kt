package app.momoding.ui.files

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.momoding.files.RepoGit
import app.momoding.files.RepoGit.Kind
import app.momoding.files.RepoGit.LineKind
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.launch

const val ROUTE_HISTORY = "momoding/history?repo={repo}&path={path}"
const val ROUTE_COMMIT = "momoding/commit?repo={repo}&sha={sha}"
fun historyRoute(repo: String, path: String?) = "momoding/history?repo=${Uri.encode(repo)}&path=${Uri.encode(path.orEmpty())}"
fun commitRoute(repo: String, sha: String) = "momoding/commit?repo=${Uri.encode(repo)}&sha=$sha"

private val Red = Color(0xFFC23B2A)

/** What a version is called: the person's words that led to it, or who else made it. */
@Composable
internal fun title(c: RepoGit.Commit): String {
    val l = c.label
    return when (l.kind) {
        Kind.SAID -> "“${l.text}”"
        Kind.SELF -> stringResource(R.string.mo_files_by_self)
        Kind.YOU -> if (l.text.startsWith("undo ")) stringResource(R.string.mo_files_undid, l.text.removePrefix("undo ").let { what ->
                if (what.startsWith("edit ")) stringResource(R.string.mo_files_by_you) + " " + what.removePrefix("edit ") else what
            }) else stringResource(R.string.mo_files_by_you)
        Kind.OTHER -> l.text
    }
}

@Composable
private fun files(c: RepoGit.Commit): String {
    val names = c.files.take(2).joinToString(" · ") { it.path }
    return if (c.files.size <= 2) names else stringResource(R.string.mo_files_and_more, names, c.files.size)
}

@Composable
private fun Counts(c: RepoGit.Commit) {
    val add = c.files.sumOf { it.added }
    val del = c.files.sumOf { it.removed }
    Text(buildAnnotatedString {
        if (add > 0) withStyle(SpanStyle(color = Tones.actionText)) { append("+$add") }
        if (add > 0 && del > 0) append(" ")
        if (del > 0) withStyle(SpanStyle(color = Red)) { append("−$del") }
    }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
}

/** A repo's `git log`, by day: each version with the words behind it and the files it touched. */
@Composable
fun HistoryScreen(repo: String, path: String?, onCommit: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = resumes()
    var log by remember { mutableStateOf<List<RepoGit.Commit>?>(null) }
    LaunchedEffect(repo, path, tick) { log = RepoGit.log(context, repo, path) }
    SettingsScaffold(title = stringResource(R.string.mo_files_history), onBack = onBack) {
        Note(stringResource(R.string.mo_files_history_sub))
        val all = log ?: return@SettingsScaffold
        if (all.isEmpty()) { Note(stringResource(R.string.mo_files_history_empty)); return@SettingsScaffold }
        val days = all.groupBy { java.util.Calendar.getInstance().apply { timeInMillis = it.at }.let { c -> c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR) } }
        days.values.forEach { day ->
            SettingsSection(header = dayText(day.first().at)) {
                day.forEachIndexed { i, c ->
                    SettingsRow(
                        title = title(c), subtitle = files(c),
                        onClick = { onCommit(c.sha) }, showChevron = false, showDivider = i < day.lastIndex,
                        trailing = { Counts(c) },
                    )
                }
            }
        }
    }
}

/** A file's path over its change, as written (section headers are upper-cased, paths must not be). */
@Composable
private fun FileHeader(path: String) = Text(
    path, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp),
)

@Composable
private fun dayText(at: Long): String {
    val t = whenText(at)
    return if (t.contains(':')) stringResource(R.string.mo_files_today) else t
}

/** One version: each file's change as lines taken out and put in, and the way to undo it. */
@Composable
fun CommitScreen(repo: String, sha: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var commit by remember { mutableStateOf<RepoGit.Commit?>(null) }
    var diffs by remember { mutableStateOf<List<RepoGit.FileDiff>>(emptyList()) }
    var asking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(repo, sha) {
        commit = RepoGit.log(context, repo, limit = 1, from = sha).firstOrNull()
        diffs = RepoGit.show(context, repo, sha)
    }
    val c = commit
    SettingsScaffold(title = stringResource(R.string.mo_files_change), onBack = onBack) {
        if (c == null) return@SettingsScaffold
        Text(title(c), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp))
        Note("${whenText(c.at)} · " + stringResource(R.string.mo_files_n_files, c.files.size))
        diffs.forEach { d ->
            FileHeader(d.path)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                d.lines.forEach { l ->
                    val bg = when (l.kind) { LineKind.ADD -> Tones.actionText.copy(alpha = 0.10f); LineKind.DEL -> Red.copy(alpha = 0.08f); else -> Color.Transparent }
                    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
                        Text(
                            when (l.kind) { LineKind.ADD -> "+"; LineKind.DEL -> "−"; LineKind.GAP -> "⋯"; LineKind.CTX -> "" },
                            color = if (l.kind == LineKind.ADD) Tones.actionText else if (l.kind == LineKind.DEL) Red else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(18.dp),
                        )
                        Text(
                            l.text, style = MaterialTheme.typography.bodyMedium,
                            color = when (l.kind) { LineKind.CTX, LineKind.GAP -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface },
                            textDecoration = if (l.kind == LineKind.DEL) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
        SettingsSection {
            SettingsRow(title = stringResource(R.string.mo_files_undo), onClick = { asking = true }, showChevron = false, showDivider = false)
        }
        if (failed) Note(stringResource(R.string.mo_files_undo_failed))
    }
    if (asking && c != null) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.mo_files_undo)) },
        text = { Text(stringResource(R.string.mo_files_undo_text)) },
        confirmButton = {
            TextButton(onClick = {
                asking = false
                scope.launch { if (RepoGit.revert(context, repo, c)) onBack() else failed = true }
            }) { Text(stringResource(R.string.mo_files_undo_confirm)) }
        },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
