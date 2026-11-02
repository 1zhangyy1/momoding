package app.momoding.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.momoding.guard.RiskGate
import app.momoding.ui.Design
import app.momoding.ui.chat.CardActions
import app.momoding.ui.chat.CardTitle
import app.momoding.ui.chat.CardTitlebar
import app.momoding.ui.chat.DecisionCard
import app.momoding.ui.chat.PillButton
import app.momoding.ui.guard.RiskApprovalCard
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * What an app's page asks the person, one at a time: an approval, what the app may use, a key it
 * lacks, a page that broke. The same cards as in the chat (调研/M40), at the bottom of the page over
 * a dimmed app — never the system's dialog. Only the person answers them (AppScreen's finger does not
 * reach them).
 */
object AppQuestions {
    /** Something is being asked on [app]'s page now. */
    fun asking(context: android.content.Context, app: MoApps.App, trouble: String?): Boolean =
        approval(context, app) != null || AppPermissions.pending.value?.app?.name == app.name ||
            AppKeys.pending.value?.app?.name == app.name || trouble != null

    /** An approval this app's Momoding, or the app's own commands, wait on. */
    fun approval(context: android.content.Context, app: MoApps.App) =
        RiskGate.open.value.firstOrNull { BotStatus.botOf(context, it.sessionId) == app.botSession }
}

@Composable
fun AppQuestionsHost(app: MoApps.App, trouble: String?, onFix: () -> Unit, onDismissTrouble: () -> Unit) {
    val context = LocalContext.current
    val open by RiskGate.open.collectAsState()
    val permission by AppPermissions.pending.collectAsState()
    val key by AppKeys.pending.collectAsState()
    val approval = open.firstOrNull { BotStatus.botOf(context, it.sessionId) == app.botSession }
    val perm = permission?.takeIf { it.app.name == app.name }
    val k = key?.takeIf { it.app.name == app.name }
    val any = approval != null || perm != null || k != null || trouble != null
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(any, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable(enabled = false) {})
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            when {
                approval != null -> RiskApprovalCard(approval)
                perm != null -> DecisionCard {
                    CardTitlebar(app.title)
                    CardTitle(stringResource(R.string.mo_app_cap_ask, app.title, AppPermissions.label(perm.command)?.let { stringResource(it) } ?: perm.command))
                    CardActions {
                        PillButton(stringResource(R.string.mo_app_cap_deny), primary = false) { perm.answer(false) }
                        PillButton(stringResource(R.string.mo_app_cap_allow), primary = true) { perm.answer(true) }
                    }
                }
                k != null -> DecisionCard {
                    // typed into Momoding's own field: the page never has it (AppKeys)
                    var value by remember(k) { mutableStateOf("") }
                    CardTitlebar(app.title)
                    CardTitle(stringResource(R.string.mo_app_key_ask, app.title, k.name))
                    Text(stringResource(R.string.mo_app_key_why, k.why.ifEmpty { k.name }, k.site), fontSize = Design.Size.Note, color = ChatColors.secondaryText)
                    OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(k.name) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    CardActions {
                        PillButton(stringResource(android.R.string.cancel), primary = false) { k.answer(null) }
                        PillButton(stringResource(R.string.mo_app_key_save), primary = true, enabled = value.isNotBlank()) { k.answer(value) }
                    }
                }
                trouble != null -> DecisionCard {
                    CardTitlebar(app.title)
                    CardTitle(stringResource(R.string.mo_app_broken))
                    CardActions {
                        PillButton(stringResource(android.R.string.cancel), primary = false, onClick = onDismissTrouble)
                        PillButton(stringResource(R.string.mo_app_fix), primary = true, onClick = onFix)
                    }
                }
            }
        }
    }
}
