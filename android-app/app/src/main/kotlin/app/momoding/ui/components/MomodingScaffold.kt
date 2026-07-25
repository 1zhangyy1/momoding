package app.momoding.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.momoding.feature.settings.structuralAction
import app.momoding.feature.settings.contractAction
import app.momoding.ui.icons.MomodingFilledIcons
import app.momoding.ui.icons.MomodingIcons
import app.momoding.ui.theme.LocalMomodingBrandColors

enum class TopLevelDestination { TASKS, SETTINGS }

@Composable
fun MomodingScaffold(
    selected: TopLevelDestination,
    onTasks: () -> Unit,
    onSettings: () -> Unit,
    settingsItemDecoration: (Modifier) -> Modifier = { it },
    tasksContractAction: String? = null,
    settingsContractAction: String? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    Scaffold(
        bottomBar = {
            BottomAppBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(min = if (largeFont) 92.dp else 64.dp),
            ) {
                DestinationItem(
                    selected = selected == TopLevelDestination.TASKS,
                    onClick = onTasks,
                    icon = {
                        Icon(
                            if (selected == TopLevelDestination.TASKS) MomodingFilledIcons.Tasks else MomodingIcons.Tasks,
                            contentDescription = null,
                        )
                    },
                    label = "Tasks",
                    contractActionName = tasksContractAction,
                    modifier = Modifier,
                )
                DestinationItem(
                    selected = selected == TopLevelDestination.SETTINGS,
                    onClick = onSettings,
                    icon = {
                        Icon(
                            if (selected == TopLevelDestination.SETTINGS) MomodingFilledIcons.Settings else MomodingIcons.Settings,
                            contentDescription = null,
                        )
                    },
                    label = "Settings",
                    contractActionName = settingsContractAction,
                    modifier = settingsItemDecoration(Modifier),
                )
            }
        },
        content = content,
    )
}

@Composable
private fun RowScope.DestinationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
    contractActionName: String?,
    modifier: Modifier,
) {
    val brand = LocalMomodingBrandColors.current
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = brand.deep,
            selectedTextColor = brand.deep,
            indicatorColor = brand.soft,
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                contractActionName?.let { action ->
                    Modifier.semantics { contractAction = action }
                } ?: Modifier,
            )
            .structuralAction("Navigate$label"),
    )
}

@Composable
fun SectionHeading(text: String) {
    Text(text = text, modifier = Modifier.semantics { heading() })
}
