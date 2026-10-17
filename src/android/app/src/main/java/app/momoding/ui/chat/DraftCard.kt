package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.chat.Drafts
import app.momoding.ui.Tones
import com.openminis.app.R

/**
 * The draft card for a `momoding-draft` fence: who it goes to, the exact words, Send / Discard.
 * The words can be edited in place; Send sends what is shown. After that the card only reports.
 */
@Composable
fun DraftCard(code: String) {
    val draft = remember(code) { Drafts.parse(code) } ?: return // still streaming, or not a draft
    val context = LocalContext.current
    val states by Drafts.all.collectAsState()
    val state = states[draft.id] ?: remember(draft.id) { Drafts.state(context, draft.id) }
    var text by remember(draft.id) { mutableStateOf(state.text ?: draft.text) }
    var editing by remember(draft.id) { mutableStateOf(false) }
    val open = state.status == Drafts.Status.PENDING || state.status == Drafts.Status.FAILED

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Tones.surface,
        border = BorderStroke(1.dp, Tones.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            val who = when {
                draft.to.isNotBlank() && draft.app.isNotBlank() -> stringResource(R.string.mo_draft_to_in, draft.to, draft.app)
                draft.to.isNotBlank() -> stringResource(R.string.mo_draft_to, draft.to)
                else -> draft.app
            }
            Row {
                Text(stringResource(R.string.mo_draft_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tones.action)
                if (who.isNotBlank()) Text("  ·  $who", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            if (editing) {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            when (state.status) {
                Drafts.Status.SENDING -> Status(stringResource(R.string.mo_draft_sending))
                Drafts.Status.SENT -> Status("✓ " + stringResource(R.string.mo_draft_sent), Tones.action)
                Drafts.Status.DISCARDED -> Status(stringResource(R.string.mo_draft_discarded))
                Drafts.Status.FAILED -> Status(failedLine(state.note), MaterialTheme.colorScheme.error)
                Drafts.Status.PENDING -> Unit
            }
            if (open) {
                if (state.status == Drafts.Status.FAILED) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            editing = false
                            val sendText = text.trim()
                            Drafts.start(context, draft, sendText)
                        },
                        enabled = text.isNotBlank(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Tones.action, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Text(stringResource(if (state.status == Drafts.Status.FAILED) R.string.mo_draft_retry else R.string.mo_draft_send), fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { editing = !editing }, modifier = Modifier.height(42.dp)) {
                        Text(stringResource(if (editing) R.string.mo_draft_done_edit else R.string.mo_draft_edit))
                    }
                    TextButton(
                        onClick = { Drafts.set(context, draft.id, Drafts.State(Drafts.Status.DISCARDED, text)) },
                        modifier = Modifier.height(42.dp),
                    ) { Text(stringResource(R.string.mo_draft_discard), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
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
