package app.momoding.ui.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.momoding.providers.AutoSetup
import kotlinx.coroutines.launch
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

const val ROUTE_MODELS = "momoding/models"

/**
 * "模型": which model Momoding thinks with and which one looks at the screen, in words a person
 * uses. Changing the thinking model picks from the models known to work (AutoSetup's list) and
 * keeps the old one as the fallback. Groups, routing and the rest stay under Advanced.
 */
@Composable
fun ModelsScreen(onBack: () -> Unit, onOpenHands: () -> Unit, onOpenProviders: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
    SettingsScaffold(title = stringResource(R.string.mo_models_title), onBack = onBack) {
        if (repo == null) return@SettingsScaffold
        val cfg by repo.config.collectAsState()
        val group = cfg.modelGroups.firstOrNull { it.id == cfg.defaultPrimaryGroupId } ?: cfg.modelGroups.firstOrNull()
        val members = group?.memberEntryIds?.mapNotNull { id -> cfg.modelEntries.firstOrNull { it.id == id } }.orEmpty()
        // no group (it went with a removed provider, or setup never made one): the chat runs on a model of its
        // own, as in OpenMinis — the one last picked, else the newest provider's newest text model
        var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        val currentId = if (group != null) members.firstOrNull()?.id
        else picked ?: (repo.lastUsedVisibleEntry() ?: repo.newestProviderNewestTextEntry())?.id
        val visible = cfg.modelEntries.filter { !it.isHidden }
        // ranked once per change of the list, not on every frame (a device can carry hundreds of models)
        val rankedAll = androidx.compose.runtime.remember(cfg.modelEntries) { AutoSetup.ranked(visible) }
        val offered = AutoSetup.recommended(visible.map { it.model.id }).mapNotNull { id -> visible.firstOrNull { it.model.id == id } }
            .distinctBy { AutoSetup.family(it.model.id) } // one per family, not five DeepSeek variants
            .let { list ->
                // providers off the list (OpenAI, Anthropic…): their own chat models, newest first, so there is a choice
                if (list.size >= 3) list else (list + rankedAll.filter { e -> list.none { it.id == e.id } }).take(6)
            }
            .let { list ->
                // a steady order (best first) whatever is chosen; the current and fallback are always there
                val extra = (members + visible.filter { it.id == currentId }).distinctBy { it.id }.filter { m -> list.none { it.id == m.id } }
                list.take(6 - extra.size) + extra
            }

        SettingsSection(header = stringResource(R.string.mo_models_thinking), footer = stringResource(R.string.mo_models_thinking_footer)) {
            offered.forEachIndexed { i, e ->
                val current = currentId == e.id
                val fallback = members.getOrNull(1)?.id == e.id
                SettingsRow(
                    title = Models.label(e),
                    subtitle = listOfNotNull(Models.provider(cfg.instances.firstOrNull { it.id == e.providerInstanceId }?.label),
                        if (fallback) stringResource(R.string.mo_models_fallback) else null).joinToString(" · "),
                    onClick = if (current) null else ({ Models.choose(context, repo, group?.id, e.id); picked = e.id }),
                    showChevron = false, showDivider = i < offered.lastIndex,
                    trailing = if (current) ({ Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF2E7A12)) }) else null,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.mo_models_seeing), footer = stringResource(R.string.mo_models_seeing_footer)) {
            SettingsRow(
                title = app.momoding.hands.Hands.screenModel(context)?.label?.substringAfter(": ") ?: stringResource(R.string.mo_models_seeing_none),
                icon = Icons.Outlined.RemoveRedEye, iconColor = Color(0xFF5856D6), onClick = onOpenHands, showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.mo_models_provider)) {
            SettingsRow(
                title = cfg.instances.joinToString("、") { it.label.ifBlank { it.id.take(8) } },
                subtitle = stringResource(R.string.mo_models_provider_sub),
                icon = Icons.Outlined.Key, iconColor = Color(0xFFFF9500), onClick = onOpenProviders, showDivider = false,
            )
        }
    }
}

internal object Models {
    fun label(e: ModelEntry): String = e.model.displayName.ifBlank { e.model.id }.substringAfter(": ")
    fun provider(label: String?): String? = label?.takeIf { it.isNotBlank() }

    /**
     * [entryId] thinks from now on; the one it replaces stays as the fallback. The main chat
     * remembers the model it last used, so it is rebound too, or the choice would only reach
     * chats started after it. Without a group the chat is bound to the model itself and it
     * becomes the last-used one new chats start on, the way the OpenMinis picker does it.
     */
    fun choose(context: Context, repo: com.openminis.app.data.repository.ProviderRepository, groupId: String?, entryId: String) {
        val group = groupId?.let { id -> repo.config.value.modelGroups.firstOrNull { it.id == id } }
        if (group != null) {
            val members = (listOf(entryId) + group.memberEntryIds.filter { it != entryId }).take(2)
            repo.updateGroup(group.copy(memberEntryIds = members.toMutableList()))
        } else repo.lastUsedEntryId = entryId
        val chats = (context.applicationContext as? MinisApp)?.chatRepositoryOrNull ?: return
        val modelId = repo.config.value.modelEntries.firstOrNull { it.id == entryId }?.model?.id ?: return
        kotlinx.coroutines.MainScope().launch {
            val sid = app.momoding.home.MainChat.resolve(context, chats)
            val vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(sid)
            if (group != null) vm?.selectGroup(group.id)
                ?: runCatching { chats.updateSessionBinding(sid, """{"type":"group","groupId":"${group.id}"}""", modelId) }
            else vm?.selectEntry(entryId)
                ?: runCatching { chats.updateSessionBinding(sid, """{"type":"entry","entryId":"$entryId"}""", modelId) }
        }
    }
}
