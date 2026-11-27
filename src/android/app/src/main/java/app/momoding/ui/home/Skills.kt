package app.momoding.ui.home

import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.momoding.know.SiteLedger
import app.momoding.ui.files.Grey
import app.momoding.ui.files.Note
import app.momoding.ui.files.Sandbox
import app.momoding.ui.files.resumes
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ROUTE_SKILLS_HOME = "momoding/skills"
const val ROUTE_SITES = "momoding/sites"
const val ROUTE_SITE = "momoding/site?domain={domain}"
fun siteRoute(domain: String) = "momoding/site?domain=" + Uri.encode(domain)

const val KNOW_HOW = "/var/minis/memory/know-how"
const val TOOLS = "$KNOW_HOW/tools"

class SkillLinks(val folder: (String) -> Unit, val sites: () -> Unit, val packs: () -> Unit, val mcp: () -> Unit, val tools: () -> Unit)

/**
 * What Momoding can do beyond talking: what it learned by doing (its know-how notes, scripts,
 * site records — files in its memory), and what was installed (skill packs, MCP services, tools).
 */
@Composable
fun SkillsHomeScreen(links: SkillLinks, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = resumes()
    var learned by remember { mutableStateOf(Triple(emptyList<String>(), emptyList<String>(), emptyList<String>())) }
    LaunchedEffect(tick) {
        learned = withContext(Dispatchers.IO) {
            fun names(dir: String, keep: (String) -> Boolean) = Sandbox.host(context, dir).listFiles().orEmpty()
                .filter { it.isFile && keep(it.name) }.sortedByDescending { it.lastModified() }.map { it.name }
            Triple(names(KNOW_HOW) { it.endsWith(".md") }.map { it.removeSuffix(".md") }, names(TOOLS) { it != "tools.json" }, SiteLedger.all(context).map { it.domain })
        }
    }
    val (notes, scripts, sites) = learned
    fun peek(list: List<String>) = list.take(3).joinToString("、").ifEmpty { null }
    @Composable fun count(n: Int) = Text(n.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsScaffold(title = stringResource(R.string.mo_home_skills), onBack = onBack) {
        SettingsSection(header = stringResource(R.string.mo_skills_learned), footer = stringResource(R.string.mo_skills_learned_footer)) {
            SettingsRow(stringResource(R.string.mo_skills_notes), subtitle = peek(notes), icon = Icons.Outlined.TipsAndUpdates, iconColor = Grey,
                onClick = { links.folder(KNOW_HOW) }, trailing = { count(notes.size) })
            SettingsRow(stringResource(R.string.mo_skills_scripts), subtitle = peek(scripts), icon = Icons.Outlined.Code, iconColor = Grey,
                onClick = { links.folder(TOOLS) }, trailing = { count(scripts.size) })
            SettingsRow(stringResource(R.string.mo_skills_sites), subtitle = peek(sites), icon = Icons.Outlined.Language, iconColor = Grey,
                onClick = links.sites, showDivider = false, trailing = { count(sites.size) })
        }
        SettingsSection(header = stringResource(R.string.mo_skills_installed)) {
            SettingsRow(stringResource(R.string.mo_skills_packs), icon = Icons.Outlined.Extension, iconColor = Grey, onClick = links.packs)
            SettingsRow(stringResource(R.string.mo_skills_mcp), icon = Icons.Outlined.Dashboard, iconColor = Grey, onClick = links.mcp)
            SettingsRow(stringResource(R.string.settings_agent_tools), icon = Icons.Outlined.Handyman, iconColor = Grey, onClick = links.tools, showDivider = false)
        }
        SettingsSection {
            var share by remember { mutableStateOf(app.momoding.know.CommunitySkills.autoShare(context)) }
            SettingsSwitchRow(title = stringResource(R.string.mo_settings_share_knowhow), subtitle = stringResource(R.string.mo_settings_share_knowhow_sub),
                checked = share, onCheckedChange = { share = it; app.momoding.know.CommunitySkills.setAutoShare(context, it) }, showDivider = false)
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** The sites its browser has worked on, most recent first. */
@Composable
fun SitesScreen(onSite: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<SiteLedger.Entry>?>(null) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { SiteLedger.all(context) } }
    SettingsScaffold(title = stringResource(R.string.mo_skills_sites), onBack = onBack) {
        val list = all ?: return@SettingsScaffold
        if (list.isEmpty()) { Note(stringResource(R.string.mo_files_empty)); return@SettingsScaffold }
        SettingsSection {
            list.forEachIndexed { i, e ->
                SettingsRow(e.domain, subtitle = e.records.firstOrNull()?.flow, onClick = { onSite(e.domain) }, showDivider = i < list.lastIndex)
            }
        }
        Note(stringResource(R.string.mo_skills_sites_footer))
    }
}

/** One site: each thing it did there and how, in its own words. */
@Composable
fun SiteScreen(domain: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var e by remember { mutableStateOf<SiteLedger.Entry?>(null) }
    LaunchedEffect(domain) { e = withContext(Dispatchers.IO) { SiteLedger.all(context).firstOrNull { it.domain == domain } } }
    SettingsScaffold(title = domain, onBack = onBack) {
        val entry = e ?: return@SettingsScaffold
        SettingsSection {
            entry.records.forEachIndexed { i, r ->
                SettingsRow(r.flow, subtitle = r.how, showChevron = false, showDivider = i < entry.records.lastIndex,
                    trailing = { Text(app.momoding.ui.files.whenText(r.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
    }
}
