package app.momoding.ui.guard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsScaffold

const val ROUTE_RULES = "momoding/rules"

/** The prototype's "允许规则" page: what was remembered from the cards, tap one to revoke it. */
@Composable
fun RulesScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.mo_settings_rules_page), onBack = onBack) {
        GrantsSection()
    }
}
