package app.momoding.ui.chat

import kotlinx.coroutines.launch
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Stable persisted source for actions; the live bubble id may still be temporary. */
val LocalMomodingSource = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/** False for a card in an earlier turn: if it was never answered, it has expired (Grok's "This ask expired"). */
val LocalMomodingLive = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun cardId(code: String): String {
    // the stored message when there is one: the bubble's id is temporary while the reply streams
    val message = LocalMomodingSource.current ?: LocalMomodingMessage.current
    return remember(message, code) { Integer.toHexString((message + "\u0000" + code).hashCode()) }
}

/** Where a tap on a chip or an option goes: the chat on screen sends it as the person's message. */
object MomodingSend {
    @Volatile var handler: ((String) -> Unit)? = null
    /** Opens Momoding's own browser on the page it left, with the controls handed to the person. */
    @Volatile var openBrowser: (() -> Unit)? = null
    /** The browser of the chat on screen, for a card that watches its page. */
    @Volatile var browser: (() -> com.openminis.app.browser.BrowserTabPool)? = null
    fun send(text: String) { handler?.invoke(text) }

    /** Puts [file] (a login QR code) in the album, where the app that scans it can pick it up. */
    fun saveToAlbum(context: android.content.Context, file: java.io.File) {
        val ok = runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "momoding-qr-${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Momoding")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        }.isSuccess
        android.widget.Toast.makeText(context, if (ok) R.string.mo_handoff_qr_saved else R.string.mo_handoff_qr_save_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
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
 * The one card for switching something on (M61: asked when first needed, never up front). "Turn
 * on" switches it on right here where it can: Momoding itself when advanced access allows, else
 * the system dialog, else the one system page with the switch (advanced access: its two steps).
 * Back from there it checks by itself and carries on; "Done" is there for what it cannot see.
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
        "background" -> stringResource(R.string.mo_perm_background)
        "files" -> stringResource(R.string.mo_perm_files)
        "read-notifications" -> stringResource(R.string.mo_perm_read_notifications)
        "sms" -> stringResource(R.string.mo_perm_sms)
        "system" -> stringResource(R.string.mo_phone_shell_title)
        else -> if (need.startsWith("android.permission.")) need.substringAfterLast('.').replace('_', ' ') else return
    }
    val carryOn = stringResource(R.string.mo_perm_carry_on_msg, label)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun settle(to: String) { AskState.choose(context, "perm.$id", to); state = to }
    fun done() {
        if (state == "granted") return
        settle("granted"); MomodingSend.send(carryOn)
        // all files: the whole storage is mounted for the commands as soon as the switch is on
        if (need == "files") scope.launch { app.momoding.files.PhoneFiles.ensureMounted(context) }
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) done()
        else { app.momoding.phone.PhoneNeeds.openAppDetails(context); settle("opened") } // refused for good: only its settings page can now
    }
    val turnOn: () -> Unit = {
        scope.launch {
            val on = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.momoding.phone.PhoneNeeds.switchOn(context, need) }
            when {
                on -> done()
                app.momoding.phone.PhoneNeeds.open(context, need) -> settle("opened")
                else -> launcher.launch(app.momoding.phone.PhoneNeeds.runtime(need))
            }
        }
    }
    if (state == null && !LocalMomodingLive.current) {
        SettledLine(stringResource(R.string.mo_card_expired_line, stringResource(R.string.mo_perm_ask, label)), done = false)
        return
    }
    // back from the system page (or paired in the background): carry on by itself once it is on
    var handsStep by remember(id) { mutableStateOf(if (need == "hands") app.momoding.hands.HandsSetup.next(context) else null) }
    if (state != null && state != "granted" && state != "declined") {
        val shell by app.momoding.phone.PhoneShell.snapshot.collectAsState()
        LaunchedEffect(shell.state) { if (need == "system" && shell.state == app.momoding.phone.PhoneShell.State.READY) done() }
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(owner, id) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    if (need == "hands") handsStep = app.momoding.hands.HandsSetup.next(context)
                    if (app.momoding.phone.PhoneNeeds.granted(context, need)) done()
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
    }
    when (state) {
        "granted" -> SettledLine(stringResource(R.string.mo_perm_settled_granted, label))
        "declined" -> SettledLine(stringResource(R.string.mo_perm_settled_declined, label), done = false)
        else -> DecisionCard {
            CardTitle(when (need) {
                "background" -> stringResource(R.string.mo_perm_background_ask)
                "files" -> stringResource(R.string.mo_perm_files_ask)
                "read-notifications" -> stringResource(R.string.mo_perm_read_notifications_ask)
                "sms" -> stringResource(R.string.mo_perm_sms_ask)
                "hands" -> stringResource(R.string.mo_perm_hands_ask)
                else -> stringResource(R.string.mo_perm_ask, label)
            })
            val detail = when {
                need == "system" -> stringResource(R.string.mo_phone_shell_not_paired)
                handsStep == app.momoding.hands.HandsSetup.Step.ACCESSIBILITY -> stringResource(R.string.mo_perm_hands_a11y, stringResource(R.string.a11y_service_label))
                handsStep == app.momoding.hands.HandsSetup.Step.UNLOCK -> stringResource(R.string.mo_perm_hands_unlock)
                handsStep == app.momoding.hands.HandsSetup.Step.OVERLAY -> stringResource(R.string.mo_perm_hands_overlay)
                else -> stringResource(R.string.mo_perm_detail)
            }
            Text(detail, fontSize = Design.Size.Note, lineHeight = Design.Line.Note, color = ChatColors.secondaryText)
            CardActions {
                if (state == null) {
                    PillButton(stringResource(R.string.mo_perm_no), primary = false) {
                        settle("declined"); MomodingSend.send(context.getString(R.string.mo_perm_no_msg))
                    }
                    PillButton(stringResource(R.string.mo_perm_open), primary = true, onClick = turnOn)
                } else {
                    // opened: the way there again (a step left, or the page closed), and "done" for what the check cannot see
                    if (need == "background" && com.openminis.app.power.PowerOptimizationManager.needsOemAutostartGuidance()) {
                        PillButton(stringResource(R.string.mo_perm_background_oem), primary = false) {
                            (context as? android.app.Activity)?.let { com.openminis.app.power.PowerOptimizationManager.openOemAutostartSettings(it) }
                        }
                    } else {
                        PillButton(stringResource(R.string.mo_perm_open), primary = false, onClick = turnOn)
                    }
                    PillButton(stringResource(R.string.mo_perm_done), primary = true) { done() }
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
    // a login by QR code: the code itself in the card — saved to the album, scanned from there in the app that reads it
    val sessionId = com.openminis.app.ui.chat.LocalMarkdownSessionId.current
    val qrFile = remember(code, sessionId) {
        o.optString("qr").trim().removePrefix("minis://browser/").takeIf { it.isNotEmpty() && !it.contains('/') }?.let { name ->
            // this chat's browser folder; a chat just started may not know its id here yet, and the
            // name carries a timestamp, so any chat's folder with that name is the one
            sessionId?.let { java.io.File(com.openminis.app.sandbox.SessionMounts.sessionDir(context.filesDir, it, "browser"), name) }?.takeIf { it.isFile }
                ?: java.io.File(context.filesDir, "minis-sessions").listFiles()?.asSequence()
                    ?.map { java.io.File(it, "browser/$name") }?.firstOrNull { it.isFile }
        }
    }
    val scanApp = o.optString("app").trim()
    if (state == null && !LocalMomodingLive.current) {
        SettledLine(stringResource(R.string.mo_card_expired_line, title), done = false)
        return
    }
    // while the code is up, watch the page: the code cut out and kept current, and carry on once it has been scanned
    val signedInMsg = stringResource(R.string.mo_handoff_qr_signed_in_msg)
    var liveQr by remember(id) { mutableStateOf<java.io.File?>(null) }
    if (qrFile != null) LaunchedEffect(id) {
        val pool = MomodingSend.browser?.invoke() ?: return@LaunchedEffect
        var first: QrWatch.Look? = null
        var shownKey: String? = null
        var gone = 0
        val until = System.currentTimeMillis() + 10 * 60_000
        while (state != "done" && System.currentTimeMillis() < until) {
            val now = QrWatch.look(pool)
            if (now != null) {
                // only a page that shows the code counts as the start: a browser still reopening is not a scan
                val start = first ?: now.takeIf { it.key != null }?.also { first = it }
                gone = if (now.key == null) gone + 1 else 0
                if (start == null) { kotlinx.coroutines.delay(2000); continue }
                if (QrWatch.signedIn(start, now, gone)) {
                    AskState.choose(context, "handoff.$id", "done"); state = "done"; MomodingSend.send(signedInMsg)
                    break
                }
                if (now.key != null && now.key != shownKey) QrWatch.crop(context, pool, now)?.let { liveQr = it; shownKey = now.key }
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    DecisionCard {
        CardTitle(title)
        if (qrFile != null) {
            val shown = liveQr ?: qrFile
            val bmp = remember(shown) { runCatching { android.graphics.BitmapFactory.decodeFile(shown.absolutePath)?.asImageBitmap() }.getOrNull() }
            bmp?.let {
                androidx.compose.foundation.Image(it, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Design.Radius.Row)))
            }
            Text(
                if (scanApp.isNotEmpty()) stringResource(R.string.mo_handoff_qr_detail, scanApp) else stringResource(R.string.mo_handoff_qr_detail_plain),
                fontSize = Design.Size.Note, lineHeight = Design.Line.Note, color = ChatColors.secondaryText,
            )
            CardActions {
                PillButton(stringResource(R.string.mo_handoff_qr_save), primary = false) { MomodingSend.saveToAlbum(context, shown) }
                app.momoding.hands.HandsApps.resolve(context, scanApp)?.let { a ->
                    PillButton(stringResource(R.string.mo_handoff_qr_open, a.label), primary = false) { app.momoding.hands.HandsApps.open(context, a) }
                }
                PillButton(stringResource(R.string.mo_handoff_done), primary = true) {
                    AskState.choose(context, "handoff.$id", "done"); state = "done"; MomodingSend.send(carryOn)
                }
            }
            return@DecisionCard
        }
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
