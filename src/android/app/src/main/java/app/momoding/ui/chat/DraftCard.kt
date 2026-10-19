package app.momoding.ui.chat

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.momoding.chat.Drafts
import app.momoding.ui.Tones
import com.openminis.app.R

/**
 * The draft card for a `momoding-draft` fence (Grok Bot's draft bubble): what it is and who it
 * goes to, the exact words — editable in place — then Discard and Send. Once sent or discarded
 * it folds into one line that quotes the first line of the message.
 */
@Composable
fun DraftCard(code: String) {
    val draft = remember(code) { Drafts.parse(code) } ?: return // still streaming, or not a draft
    val context = LocalContext.current
    val states by Drafts.all.collectAsState()
    val state = states[draft.id] ?: remember(draft.id) { Drafts.state(context, draft.id) }
    var text by remember(draft.id) { mutableStateOf(state.text ?: draft.text) }
    val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().let { if (it.length > 40) it.take(40) + "…" else it }

    when (state.status) {
        Drafts.Status.SENT -> SettledLine(
            if (draft.to.isNotBlank()) stringResource(R.string.mo_draft_settled_sent, draft.to, firstLine)
            else stringResource(R.string.mo_draft_settled_sent_plain, firstLine),
        )
        Drafts.Status.DISCARDED -> SettledLine(stringResource(R.string.mo_draft_settled_discarded, firstLine), done = false)
        else -> DecisionCard {
            val who = when {
                draft.to.isNotBlank() && draft.app.isNotBlank() -> stringResource(R.string.mo_draft_to_in, draft.to, draft.app)
                draft.to.isNotBlank() -> stringResource(R.string.mo_draft_to, draft.to)
                else -> draft.app
            }
            CardTitlebar(stringResource(R.string.mo_draft_label), who)
            val sending = state.status == Drafts.Status.SENDING
            BasicTextField(
                value = text, onValueChange = { text = it }, enabled = !sending,
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Tones.action),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.status == Drafts.Status.FAILED) Status(failedLine(state.note), MaterialTheme.colorScheme.error)
            CardActions {
                if (!sending) PillButton(stringResource(R.string.mo_draft_discard), primary = false) {
                    Drafts.set(context, draft.id, Drafts.State(Drafts.Status.DISCARDED, text))
                }
                PillButton(
                    stringResource(
                        when (state.status) {
                            Drafts.Status.SENDING -> R.string.mo_draft_sending
                            Drafts.Status.FAILED -> R.string.mo_draft_retry
                            else -> R.string.mo_draft_send
                        },
                    ),
                    primary = true, enabled = !sending && text.isNotBlank(),
                ) { Drafts.start(context, draft, text.trim()) }
            }
        }
    }
}

@Composable
private fun Status(line: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(line, fontSize = 13.sp, color = color)
}

@Composable
private fun failedLine(note: String?): String = when (note) {
    null, "" -> stringResource(R.string.mo_draft_failed_plain)
    "hands_off" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_hands))
    "busy" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_busy))
    "stopped" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_stopped))
    else -> stringResource(R.string.mo_draft_failed, note.take(120))
}
