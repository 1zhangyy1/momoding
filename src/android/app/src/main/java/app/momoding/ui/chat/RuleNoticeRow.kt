package app.momoding.ui.chat

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.deeplink.OwnScheme
import app.momoding.guard.RuleHits
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * What was decided about the steps in this reply, one quiet line per kind (Grok Bot's settled
 * approval): approved by your rule · edit rules; allowed once; from now on · edit rules; denied;
 * no answer in time.
 */
@Composable
fun RuleNoticeRow(entries: List<String>) {
    val context = LocalContext.current
    Column { entries.map { app.momoding.guard.RuleHits.decode(it) }.groupBy({ it.first }, { it.second }).forEach { (kind, labels) ->
        val what = labels.filter { it.isNotBlank() }.map { app.momoding.guard.RiskText.targetLabel(context, it) ?: it }
            .distinct().joinToString("、")
        val line = when (kind) {
            RuleHits.ONCE -> stringResource(R.string.mo_decided_once, what)
            RuleHits.SESSION -> stringResource(R.string.mo_decided_session, what)
            RuleHits.ALWAYS -> stringResource(R.string.mo_decided_always, what)
            RuleHits.DENIED -> stringResource(R.string.mo_decided_denied, what)
            RuleHits.TIMEOUT -> stringResource(R.string.mo_decided_timeout, what)
            RuleHits.REMEMBER -> stringResource(R.string.mo_remembered, what)
            else -> if (what.isEmpty()) stringResource(R.string.mo_rule_notice) else stringResource(R.string.mo_rule_notice_for, what)
        }.replace("（）", "").replace("()", "").replace(" ()", "")
        if (kind == RuleHits.REMEMBER) { QuietLine(line); return@forEach }
        val done = kind != RuleHits.DENIED && kind != RuleHits.TIMEOUT
        Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓ " else "– ", fontSize = 12.sp, color = if (done) Tones.action else ChatColors.secondaryText)
            Text(line, fontSize = 12.sp, color = ChatColors.secondaryText)
            if (kind == RuleHits.RULE || kind == RuleHits.ALWAYS) Text(
                " · " + stringResource(R.string.mo_rule_notice_edit),
                fontSize = 12.sp, color = Tones.action,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(OwnScheme.intent(context, Uri.parse("minis://settings/permissions"), 0)) }
                },
            )
        }
    } }
}

/** "看过程 / 收起" under a finished turn: the steps it took, folded by default (prototype vRes). */
@Composable
fun StepsToggle(expanded: Boolean, onToggle: () -> Unit) {
    Text(
        stringResource(if (expanded) R.string.mo_steps_hide else R.string.mo_steps_show),
        fontSize = 12.sp, color = ChatColors.secondaryText,
        modifier = Modifier.clickable(onClick = onToggle).padding(top = 2.dp, bottom = 6.dp, end = 12.dp),
    )
}
