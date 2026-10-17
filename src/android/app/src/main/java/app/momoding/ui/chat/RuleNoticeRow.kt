package app.momoding.ui.chat

import android.net.Uri
import androidx.compose.foundation.clickable
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
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/** One quiet line under a reply a standing rule let through: why nobody asked, and where to change it. */
@Composable
fun RuleNoticeRow(labels: List<String>) {
    val context = LocalContext.current
    val what = labels.filter { it.isNotBlank() }.map { app.momoding.guard.RiskText.targetLabel(context, it) ?: it }.joinToString("、")
    Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (what.isEmpty()) stringResource(R.string.mo_rule_notice) else stringResource(R.string.mo_rule_notice_for, what),
            fontSize = 12.sp, color = ChatColors.secondaryText,
        )
        Text(
            " · " + stringResource(R.string.mo_rule_notice_edit),
            fontSize = 12.sp, color = Tones.action,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(OwnScheme.intent(context, Uri.parse("minis://settings/permissions"), 0)) }
            },
        )
    }
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
