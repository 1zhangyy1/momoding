package app.momoding.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.chat.Drafts
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject
import app.momoding.ui.Design

/**
 * Every `momoding-*` fence in a reply goes through here (the way nanoMuse's NanoMuseBlock does):
 * the model reports structure, the chat draws the matching piece of the prototype.
 *   momoding-draft    → the draft card (Send / Edit / Discard)
 *   momoding-ask      → "needs your say": the question and 2–4 options; a tap answers it
 *   momoding-next     → suggested next steps as buttons; a tap sends one
 *   momoding-remember → one quiet line "Remembered: …"
 *   momoding-permission → "To do this I need X · Turn on / Not now" (prototype perm card)
 *   momoding-handoff  → "Sign in to X for me" on the page its browser left open (Grok's sign-in handoff)
 *   momoding-notify and anything unknown → nothing (app protocol, not for the eye)
 */
@Composable
fun MomodingBlock(language: String, code: String) {
    val json = remember(code) { runCatching { JSONObject(code.trim()) }.getOrNull() }
    when (language) {
        Drafts.BLOCK -> DraftCard(code)
        "momoding-ask" -> if (json != null) AskCard(json, code)
        "momoding-next" -> if (json != null) NextSteps(json)
        "momoding-remember" -> json?.optString("text")?.replace(Regex("""^\s*(记下了|记住了|已记住|Remembered|Noted)[:：,，\s]*"""), "")
            ?.takeIf { it.isNotBlank() }?.let { QuietLine(stringResource(R.string.mo_remembered, it)) }
        "momoding-permission" -> if (json != null) PermissionCard(json, code)
        "momoding-handoff" -> if (json != null) HandoffCard(json, code)
        else -> Unit
    }
}

/**
 * The message a card belongs to. A card's answered state is kept per message and content, so the
 * same card in a later reply (the model asking again) is a new card, not the old answered one.
 */
val LocalMomodingMessage = androidx.compose.runtime.staticCompositionLocalOf { "" }

/** False for a card in an earlier turn: if it was never answered, it has expired (Grok's "This ask expired"). */
val LocalMomodingLive = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun cardId(code: String): String {
    val message = LocalMomodingMessage.current
    return remember(message, code) { Integer.toHexString((message + "\u0000" + code).hashCode()) }
}

/** Where a tap on a chip or an option goes: the chat on screen sends it as the person's message. */
object MomodingSend {
    @Volatile var handler: ((String) -> Unit)? = null
    /** Opens Momoding's own browser on the page it left, with the controls handed to the person. */
    @Volatile var openBrowser: (() -> Unit)? = null
    fun send(text: String) { handler?.invoke(text) }
}

private fun options(o: JSONObject, key: String): List<String> =
    o.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it).trim() } }?.filter { it.isNotEmpty() }?.take(4).orEmpty()

@Composable
private fun AskCard(o: JSONObject, code: String) {
    val question = o.optString("question").trim()
    val opts = options(o, "options")
    if (opts.isEmpty()) return
    val context = LocalContext.current
    val id = cardId(code)
    var chosen by remember(id) { mutableStateOf(AskState.chosen(context, id)) }
    val expired = chosen == null && !LocalMomodingLive.current
    QuietCard {
        if (question.isNotEmpty()) CardTitle(question)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEachIndexed { i, opt ->
                OptionRow(i + 1, opt, picked = chosen == opt, settled = chosen != null || expired) {
                    AskState.choose(context, id, opt); chosen = opt; MomodingSend.send(opt)
                }
            }
        }
        if (chosen == null) Text(stringResource(if (expired) R.string.mo_card_expired else R.string.mo_ask_hint), fontSize = Design.Size.Caption, color = ChatColors.secondaryText)
    }
}

@Composable
private fun NextSteps(o: JSONObject) {
    val steps = options(o, "steps")
    if (steps.isEmpty()) return
    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) { Chips(steps) { MomodingSend.send(it) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<String>, onPick: (String) -> Unit) {
    FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(Design.Space.Gap), verticalArrangement = Arrangement.spacedBy(Design.Space.Gap)) {
        items.forEach { s -> Pill(s, PillKind.SUGGEST) { onPick(s) } }
    }
}

/** The prototype's one quiet line: remembered, added to your rules, saved as a skill. */
@Composable
fun QuietLine(text: String) = MetaLine(text)

/** Which option was picked on an ask card (per card content), so the card stays answered. */
private object AskState {
    private const val PREFS = "momoding"
    private const val KEY = "ask.chosen."
    fun chosen(context: Context, id: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY + id, null)
    fun choose(context: Context, id: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY + id, value).apply()
    }
}

/**
 * The prototype's permission card. Phone data (calendar, contacts, location, photos,
 * notifications) is asked with the system dialog right here; when granted the chat is told to
 * carry on. The hands need accessibility and drawing over apps, which live in Settings → Hands:
 * the card opens it and offers "Done, carry on" for when the person comes back.
 */
@Composable
private fun PermissionCard(o: JSONObject, code: String) {
    val need = o.optString("need").trim().lowercase()
    val context = LocalContext.current
    val id = cardId(code)
    var state by remember(id) { mutableStateOf(AskState.chosen(context, "perm.$id")) }
    val label = when (need) {
        "hands" -> stringResource(R.string.mo_perm_hands)
        "calendar" -> stringResource(R.string.mo_risk_target_calendar)
        "contacts" -> stringResource(R.string.mo_risk_target_contacts)
        "photos" -> stringResource(R.string.mo_risk_target_photos)
        "notifications" -> stringResource(R.string.mo_risk_target_notifications)
        "location" -> stringResource(R.string.mo_perm_location)
        else -> return
    }
    val permissions = PermissionNeeds.androidPermissions(need)
    val carryOn = stringResource(R.string.mo_perm_carry_on_msg)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            AskState.choose(context, "perm.$id", "granted"); state = "granted"; MomodingSend.send(carryOn)
        } else {
            // asked before and refused for good: the system no longer shows the dialog — its settings page does it
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            AskState.choose(context, "perm.$id", "settings"); state = "settings"
        }
    }
    val allow: (String) -> Unit = { pick ->
        if (pick == context.getString(R.string.mo_perm_no)) {
            AskState.choose(context, "perm.$id", "declined"); state = "declined"
            MomodingSend.send(context.getString(R.string.mo_perm_no_msg))
        } else if (need == "hands") {
            runCatching { context.startActivity(app.momoding.deeplink.OwnScheme.intent(context, android.net.Uri.parse("minis://settings/hands"), 0)) }
            AskState.choose(context, "perm.$id", "hands"); state = "hands"
        } else {
            launcher.launch(permissions)
        }
    }
    if (state == null && !LocalMomodingLive.current) {
        SettledLine(stringResource(R.string.mo_card_expired_line, stringResource(R.string.mo_perm_ask, label)), done = false)
        return
    }
    when (state) {
        "granted" -> SettledLine(stringResource(R.string.mo_perm_settled_granted, label))
        "declined" -> SettledLine(stringResource(R.string.mo_perm_settled_declined, label), done = false)
        else -> DecisionCard {
            CardTitle(stringResource(R.string.mo_perm_ask, label))
            Text(stringResource(R.string.mo_perm_detail), fontSize = Design.Size.Note, lineHeight = Design.Line.Note, color = ChatColors.secondaryText)
            CardActions {
                if (state == "settings" || state == "hands") {
                    PillButton(stringResource(R.string.mo_perm_done), primary = true) {
                        AskState.choose(context, "perm.$id", "granted"); state = "granted"; MomodingSend.send(carryOn)
                    }
                } else {
                    val no = stringResource(R.string.mo_perm_no)
                    val open = stringResource(R.string.mo_perm_open)
                    PillButton(no, primary = false) { allow(no) }
                    PillButton(open, primary = true) { allow(open) }
                }
            }
        }
    }
}

/**
 * Grok Bot's sign-in handoff on the phone: a page in Momoding's own browser needs the person (a
 * login, a code, a captcha). Go opens that page with the controls handed over; Done tells the chat
 * to carry on from there. The sign-in stays in its browser for next time.
 */
@Composable
private fun HandoffCard(o: JSONObject, code: String) {
    val site = o.optString("site").trim()
    val what = o.optString("what").trim().ifEmpty { stringResource(R.string.mo_handoff_login) }
    val context = LocalContext.current
    val id = cardId(code)
    var state by remember(id) { mutableStateOf(AskState.chosen(context, "handoff.$id")) }
    val carryOn = stringResource(R.string.mo_handoff_done_msg)
    if (state == "done") {
        // handed back, not "signed in": the person tapped Done, and only the next look at the page can tell
        SettledLine(if (site.isNotEmpty()) stringResource(R.string.mo_handoff_settled, site, what) else stringResource(R.string.mo_handoff_settled_plain, what), done = false)
        return
    }
    val title = if (site.isNotEmpty()) stringResource(R.string.mo_handoff_title, site, what) else stringResource(R.string.mo_handoff_title_plain, what)
    if (state == null && !LocalMomodingLive.current) {
        SettledLine(stringResource(R.string.mo_card_expired_line, title), done = false)
        return
    }
    DecisionCard {
        CardTitle(title)
        Text(stringResource(R.string.mo_handoff_detail), fontSize = Design.Size.Note, lineHeight = Design.Line.Note, color = ChatColors.secondaryText)
        CardActions {
            if (state == "opened") PillButton(stringResource(R.string.mo_handoff_open_again), primary = false) { MomodingSend.openBrowser?.invoke() }
            PillButton(stringResource(if (state == "opened") R.string.mo_handoff_done else R.string.mo_handoff_open), primary = true) {
                if (state == "opened") {
                    AskState.choose(context, "handoff.$id", "done"); state = "done"; MomodingSend.send(carryOn)
                } else {
                    AskState.choose(context, "handoff.$id", "opened"); state = "opened"; MomodingSend.openBrowser?.invoke()
                }
            }
        }
    }
}

internal object PermissionNeeds {
    fun androidPermissions(need: String): Array<String> = when (need) {
        "calendar" -> arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        "contacts" -> arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        "location" -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        "photos" -> if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        "notifications" -> if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        else -> emptyArray()
    }
}

