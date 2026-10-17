package app.momoding.ui.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.chat.Drafts
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

/**
 * Every `momoding-*` fence in a reply goes through here (the way nanoMuse's NanoMuseBlock does):
 * the model reports structure, the chat draws the matching piece of the prototype.
 *   momoding-draft    → the draft card (Send / Edit / Discard)
 *   momoding-ask      → "needs your say": the question and 2–4 options; a tap answers it
 *   momoding-next     → suggested next steps as buttons; a tap sends one
 *   momoding-remember → one quiet line "Remembered: …"
 *   momoding-notify and anything unknown → nothing (app protocol, not for the eye)
 */
@Composable
fun MomodingBlock(language: String, code: String) {
    val json = remember(code) { runCatching { JSONObject(code.trim()) }.getOrNull() }
    when (language) {
        Drafts.BLOCK -> DraftCard(code)
        "momoding-ask" -> if (json != null) AskCard(json, code)
        "momoding-next" -> if (json != null) NextSteps(json)
        "momoding-remember" -> json?.optString("text")?.takeIf { it.isNotBlank() }?.let { QuietLine(stringResource(R.string.mo_remembered, it)) }
        else -> Unit
    }
}

/** Where a tap on a chip or an option goes: the chat on screen sends it as the person's message. */
object MomodingSend {
    @Volatile var handler: ((String) -> Unit)? = null
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
    val id = remember(code) { Integer.toHexString(code.hashCode()) }
    var chosen by remember(id) { mutableStateOf(AskState.chosen(context, id)) }
    Surface(
        shape = RoundedCornerShape(16.dp), color = Tones.surface, border = BorderStroke(1.dp, Tones.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (question.isNotEmpty()) {
                Text(question, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
            }
            val c = chosen
            if (c != null) {
                Text(stringResource(R.string.mo_ask_chosen, c), fontSize = 13.sp, color = ChatColors.secondaryText)
            } else {
                Chips(opts, primaryFirst = true) { pick ->
                    AskState.choose(context, id, pick); chosen = pick; MomodingSend.send(pick)
                }
            }
        }
    }
}

@Composable
private fun NextSteps(o: JSONObject) {
    val steps = options(o, "steps")
    if (steps.isEmpty()) return
    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) { Chips(steps, primaryFirst = false) { MomodingSend.send(it) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<String>, primaryFirst: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, s ->
            val primary = primaryFirst && i == 0
            Text(
                s, fontSize = 14.sp,
                color = if (primary) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(CircleShape).background(if (primary) Tones.action else Tones.fill)
                    .clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The prototype's one quiet line: remembered, added to your rules, saved as a skill. */
@Composable
fun QuietLine(text: String) {
    Row(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        Text(text, fontSize = 12.sp, color = ChatColors.secondaryText)
    }
}

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
