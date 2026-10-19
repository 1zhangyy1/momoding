// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/ui/chat/NmRoutineWakeRow.kt
package app.momoding.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import app.momoding.chat.RoutineWakes
import app.momoding.ui.Design

/**
 * A user row that a routine sent (`RoutineWakes`) is one quiet line on the person's side,
 * "Routine · Morning brief · 08:00", not a bubble in their voice; any other row is [bubble].
 * [messageId] is the database row's id.
 */
@Composable
fun RoutineWakeRow(messageId: String, bubble: @Composable () -> Unit) {
    val wakes by RoutineWakes.wakes.collectAsState()
    val wake = wakes[messageId]
    if (wake == null) {
        bubble()
        return
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.mo_routine_wake_caption, wake.label, wake.time),
            fontSize = Design.Size.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** A routine's turn as the prototype draws it: one centred line with a clock, "例行 · name · time". */
@Composable
fun RoutineLine(label: String, time: String?) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (time != null) stringResource(R.string.mo_routine_wake_caption, label, time)
            else stringResource(R.string.mo_routine_wake_caption_no_time, label),
            fontSize = Design.Size.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
