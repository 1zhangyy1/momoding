package app.momoding.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** "今天 16:30" centred above an exchange that starts after a pause (iMessage; Grok Bot's "Today {time}"). */
@Composable
fun TimeDivider(at: Long) {
    val context = LocalContext.current
    val text = remember(at) { TimeDivider.label(at, context.getString(R.string.mo_time_today), context.getString(R.string.mo_time_yesterday)) }
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = ChatColors.secondaryText)
    }
}

object TimeDivider {
    private const val PAUSE_MS = 60 * 60 * 1000L

    /** A divider goes above a message sent after an hour's quiet, on another day, or first in the chat. */
    fun shows(at: Long, previous: Long?, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        previous == null || at - previous >= PAUSE_MS || day(at, zone) != day(previous, zone)

    /** "今天 16:30", "昨天 21:05", "周六 09:12" within a week, else "10月12日 09:12". */
    fun label(at: Long, today: String, yesterday: String, now: Long = System.currentTimeMillis(),
              zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(zone))
        val days = ChronoUnit.DAYS.between(day(at, zone), day(now, zone))
        val zh = locale.language == "zh"
        return when {
            days == 0L -> today.format(time)
            days == 1L -> yesterday.format(time)
            days in 2..6 -> DateTimeFormatter.ofPattern("EEEE HH:mm", locale).format(Instant.ofEpochMilli(at).atZone(zone))
            else -> DateTimeFormatter.ofPattern(if (zh) "M月d日 HH:mm" else "MMM d, HH:mm", locale).format(Instant.ofEpochMilli(at).atZone(zone))
        }
    }

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
}
