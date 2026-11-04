package app.momoding.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.momoding.apps.AppIcon
import app.momoding.apps.MoApps
import app.momoding.task.TaskEngine
import app.momoding.task.TaskEntity
import app.momoding.ui.Design
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * One task, one line, where it was handed on (调研/M39): "交给 记账 · 做月度图 · 在办". While it is
 * at work the line shows the hand's latest progress; it never becomes a message. Read from the
 * task table, so it is the same after a restart.
 */
@Composable
fun TaskRow(taskId: String, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val row by remember(taskId) { TaskEngine.dao(context).taskFlow(taskId) }.collectAsState(initial = null)
    val task = row ?: return
    val app = remember(task.hand) { MoApps.ofSession(context, task.hand) }
    val state = when (task.state) {
        TaskEntity.State.RUNNING.name -> task.progress ?: stringResource(R.string.mo_task_running)
        TaskEntity.State.QUEUED.name -> stringResource(R.string.mo_task_queued)
        TaskEntity.State.WAITING.name -> stringResource(R.string.mo_task_waiting)
        else -> stringResource(when (task.outcome) {
            TaskEntity.Outcome.FAILED.name -> R.string.mo_task_failed
            TaskEntity.Outcome.STOPPED.name -> R.string.mo_task_stopped
            else -> R.string.mo_task_done
        })
    }
    val head = if (task.hand == task.conversation) "" else stringResource(R.string.mo_task_handed, app?.title ?: stringResource(R.string.mo_task_helper))
    Row(
        Modifier.clip(RoundedCornerShape(Design.Radius.Row))
            .clickable(enabled = app != null) { app?.let { onOpen(botHome(it)) } }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (app != null) BotFace(app, 18)
        Text(
            listOf(head, task.title, state).filter { it.isNotBlank() }.distinct().joinToString(" · "),
            Modifier.padding(start = 8.dp), fontSize = Design.Size.Caption,
            color = if (task.state == TaskEntity.State.WAITING.name && app != null) Color(AppIcon.color(app)) else ChatColors.secondaryText,
        )
    }
}
