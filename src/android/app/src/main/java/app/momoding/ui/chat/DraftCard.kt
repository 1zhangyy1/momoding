package app.momoding.ui.chat

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.momoding.ui.Design

/**
 * The draft card for a `momoding-draft` fence (Grok Bot's draft bubble): what it is and who it
 * goes to, the exact words — editable in place — then Discard and Send. Once sent or discarded
 * it folds into one line that quotes the first line of the message.
 */
@Composable
fun DraftCard(code: String) {
    val draft = remember(code) { Drafts.parse(code) } ?: return // still streaming, or not a draft
    val context = LocalContext.current
    val conversation = com.openminis.app.ui.chat.LocalMarkdownSessionId.current
    val source = LocalMomodingSource.current.orEmpty()
    val key = Drafts.key(conversation.orEmpty(), source, draft.id)
    val states by Drafts.all.collectAsState()
    val cached = states[key] ?: remember(key) { Drafts.state(context, key, draft.id) }
    var managed by remember(key) { mutableStateOf<Drafts.State?>(null) }
    var loaded by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (conversation != null && source.isNotBlank()) {
            app.momoding.task.TaskEngine.dao(context).draftFlow(conversation, source, Drafts.needle(draft.id)).collect { task ->
                managed = task?.let { Drafts.project(context, it) }
                loaded = true
            }
        }
    }
    val state = managed ?: cached
    var text by remember(key) { mutableStateOf(state.text ?: draft.text) }
    LaunchedEffect(managed?.text) { managed?.text?.let { text = it } }
    // an SMS goes out through the phone's SMS service: ask for it once, right as Send is tapped;
    // refused, the card still works — the Messages app opens with the text filled in
    val smsAsk = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { if (conversation != null && source.isNotBlank()) Drafts.start(context, draft, text.trim(), conversation, source) }
    val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().let { if (it.length > 40) it.take(40) + "…" else it }

    when (state.status) {
        Drafts.Status.SENT -> SettledLine(
            if (draft.to.isNotBlank()) stringResource(R.string.mo_draft_settled_sent, draft.to, firstLine)
            else stringResource(R.string.mo_draft_settled_sent_plain, firstLine),
        )
        Drafts.Status.DISCARDED -> SettledLine(stringResource(R.string.mo_draft_settled_discarded, firstLine), done = false)
        Drafts.Status.OPENED -> SettledLine(stringResource(R.string.mo_draft_settled_opened, firstLine), done = false)
        Drafts.Status.WAITING -> SettledLine(stringResource(R.string.mo_draft_attention), done = false)
        else -> DecisionCard {
            val who = when {
                draft.to.isNotBlank() && draft.app.isNotBlank() -> stringResource(R.string.mo_draft_to_in, draft.to, draft.app)
                draft.to.isNotBlank() -> stringResource(R.string.mo_draft_to, draft.to)
                else -> draft.app
            }
            CardTitlebar(stringResource(R.string.mo_draft_label), who)
            val sending = state.status == Drafts.Status.SENDING
            BasicTextField(
                value = text, onValueChange = { text = it; Drafts.set(context, key, Drafts.State(Drafts.Status.PENDING, it)) }, enabled = loaded && !sending,
                textStyle = TextStyle(fontSize = Design.Size.Body, lineHeight = Design.Line.Body, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Tones.action),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.status == Drafts.Status.FAILED) Status(failedLine(state.note), MaterialTheme.colorScheme.error)
            CardActions {
                if (!sending) PillButton(stringResource(R.string.mo_draft_discard), primary = false) {
                    Drafts.set(context, key, Drafts.State(Drafts.Status.DISCARDED, text))
                }
                // A draft the conversation has moved past is not sent again; a new card comes instead.
                if (state.note != "replied") PillButton(
                    stringResource(
                        when (state.status) {
                            Drafts.Status.SENDING -> R.string.mo_draft_sending
                            Drafts.Status.FAILED -> R.string.mo_draft_retry
                            else -> R.string.mo_draft_send
                        },
                    ),
                    primary = true, enabled = loaded && !sending && text.isNotBlank(),
                ) {
                    if (Drafts.isSms(draft) && !app.momoding.chat.SmsSend.canSend(context)) smsAsk.launch(android.Manifest.permission.SEND_SMS)
                    else if (conversation != null && source.isNotBlank()) Drafts.start(context, draft, text.trim(), conversation, source)
                }
            }
        }
    }
}

@Composable
private fun Status(line: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(line, fontSize = Design.Size.Note, color = color)
}

@Composable
private fun failedLine(note: String?): String = when {
    note.isNullOrEmpty() -> stringResource(R.string.mo_draft_failed_plain)
    note == "hands_off" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_hands))
    note == "busy" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_busy))
    note == "stopped" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_stopped))
    note == "replied" -> stringResource(R.string.mo_draft_replied)
    note == "sms_failed" -> stringResource(R.string.mo_draft_failed, stringResource(R.string.mo_draft_failed_sms))
    note.startsWith("ambiguous:") -> stringResource(R.string.mo_draft_ambiguous, note.removePrefix("ambiguous:"))
    else -> stringResource(R.string.mo_draft_failed, note.take(120))
}
