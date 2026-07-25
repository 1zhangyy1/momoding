package app.momoding.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.momoding.ui.icons.MomodingIcons
import app.momoding.ui.theme.LocalMomodingStatusColors
import app.momoding.ui.theme.LocalMomodingBrandColors

@Composable
fun ProductTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    presence: MomodingPresence = MomodingPresence.READY,
    onBack: (() -> Unit)? = null,
    backModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    showDivider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = backModifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = MomodingIcons.Back,
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    MomodingMark(size = 34.dp, presence = presence)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier.semantics { heading() },
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        colors = momodingPrimaryButtonColors(),
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp, pressedElevation = 0.dp),
    ) {
        leading?.invoke() ?: icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(19.dp))
        }
        if (leading != null || icon != null) Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        leading?.invoke() ?: icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(19.dp))
        }
        if (leading != null || icon != null) Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DangerAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

enum class StatusLineTone { NEUTRAL, BRAND, SUCCESS, INFO, WARNING, DANGER }

@Composable
fun StatusLine(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: String? = null,
    icon: ImageVector? = null,
    tone: StatusLineTone = StatusLineTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
) {
    val status = LocalMomodingStatusColors.current
    val brand = LocalMomodingBrandColors.current
    val toneColor = when (tone) {
        StatusLineTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusLineTone.BRAND -> brand.deep
        StatusLineTone.SUCCESS -> status.success
        StatusLineTone.INFO -> status.info
        StatusLineTone.WARNING -> status.warning
        StatusLineTone.DANGER -> status.danger
    }
    val container = when (tone) {
        StatusLineTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
        StatusLineTone.BRAND -> brand.soft
        StatusLineTone.SUCCESS -> status.successContainer
        StatusLineTone.INFO -> status.infoContainer
        StatusLineTone.WARNING -> status.warningContainer
        StatusLineTone.DANGER -> status.dangerContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(container, RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.74f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(18.dp))
            } else {
                Spacer(Modifier.size(8.dp).background(toneColor, CircleShape))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = actionModifier.heightIn(min = 48.dp),
            ) {
                Text(actionLabel, color = toneColor)
            }
        }
    }
}

enum class ProductIconTone { NEUTRAL, BRAND, SUCCESS, WARNING, DANGER }

@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 1,
    detail: String? = null,
    meta: String? = null,
    icon: ImageVector? = null,
    iconTone: ProductIconTone = ProductIconTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val status = LocalMomodingStatusColors.current
    val brand = LocalMomodingBrandColors.current
    val iconColor = when (iconTone) {
        ProductIconTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ProductIconTone.BRAND -> brand.deep
        ProductIconTone.SUCCESS -> status.success
        ProductIconTone.WARNING -> status.warning
        ProductIconTone.DANGER -> status.danger
    }
    val iconContainer = when (iconTone) {
        ProductIconTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
        ProductIconTone.BRAND -> brand.soft
        ProductIconTone.SUCCESS -> status.successContainer
        ProductIconTone.WARNING -> status.warningContainer
        ProductIconTone.DANGER -> status.dangerContainer
    }
    val interaction = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier = modifier.fillMaxWidth().then(interaction)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let {
                Box(
                    modifier = Modifier.size(34.dp).background(iconContainer, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(it, contentDescription = null, tint = iconColor, modifier = Modifier.size(19.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    meta?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content?.invoke(this)
            }
            trailing?.invoke()
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (icon == null) 11.dp else 55.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

enum class WorkBlockTone { NEUTRAL, ACTIVE, SUCCESS, WARNING, DANGER }

@Composable
fun WorkBlock(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    eyebrow: String? = null,
    statusLabel: String? = null,
    icon: ImageVector? = null,
    tone: WorkBlockTone = WorkBlockTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val brand = LocalMomodingBrandColors.current
    val status = LocalMomodingStatusColors.current
    val accent = when (tone) {
        WorkBlockTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        WorkBlockTone.ACTIVE -> brand.deep
        WorkBlockTone.SUCCESS -> status.success
        WorkBlockTone.WARNING -> status.warning
        WorkBlockTone.DANGER -> status.danger
    }
    val container = when (tone) {
        WorkBlockTone.ACTIVE -> brand.soft
        WorkBlockTone.WARNING -> status.warningContainer
        WorkBlockTone.DANGER -> status.dangerContainer
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    }
    val interaction = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .border(
                1.dp,
                if (tone == WorkBlockTone.ACTIVE) brand.primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .then(interaction),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            icon?.let {
                Box(
                    modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(it, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                eyebrow?.let {
                    Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
                }
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            statusLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            trailing?.invoke()
        }
        content?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), content = it)
        }
    }
}

enum class MessageRole { USER, MOMODING }

@Composable
fun MessageGroup(
    role: MessageRole,
    modifier: Modifier = Modifier,
    presence: MomodingPresence = MomodingPresence.READY,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (role == MessageRole.USER) {
        val brand = LocalMomodingBrandColors.current
        val bubbleShape = RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 6.dp,
            bottomStart = 18.dp,
        )
        Column(
            modifier = modifier
                .background(brand.soft, bubbleShape)
                .border(1.dp, brand.primary.copy(alpha = 0.16f), bubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            content = content,
        )
    } else {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MomodingMark(size = 20.dp, presence = presence)
                Text("Momoding", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun ComposerDock(
    modifier: Modifier = Modifier,
    meta: (@Composable () -> Unit)? = null,
    editor: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        meta?.let {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 9.dp, end = 10.dp)) { it() }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), content = editor)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            content = footer,
        )
    }
}

@Composable
fun ComposerMenuItem(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    loading: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .alpha(if (enabled || loading) 1f else 0.5f),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Immutable
data class DecisionFact(val label: String, val value: String)

@Composable
fun DecisionSheet(
    maxHeight: Dp,
    paneTitle: String,
    modifier: Modifier = Modifier,
    showSheetChrome: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .then(
                if (showSheetChrome) {
                    Modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .semantics { this.paneTitle = paneTitle },
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            if (showSheetChrome) {
                Spacer(
                    Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = footer,
            )
        }
        overlay()
    }
}

@Composable
fun DecisionFacts(
    facts: List<DecisionFact>,
    modifier: Modifier = Modifier,
) {
    if (facts.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        facts.forEachIndexed { index, fact ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    fact.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.28f),
                )
                Text(fact.value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.72f))
            }
            if (index != facts.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun DecisionSheet(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String = "Review required",
    intro: String? = null,
    facts: List<DecisionFact> = emptyList(),
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    footnote: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            )
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 14.dp),
    ) {
        Spacer(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            MomodingMark(size = 38.dp, presence = MomodingPresence.PERMISSION)
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalMomodingBrandColors.current.deep)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 11.dp).semantics { heading() },
        )
        intro?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (facts.isNotEmpty()) {
            DecisionFacts(facts = facts, modifier = Modifier.padding(top = 14.dp))
        }
        content?.let {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = it,
            )
        }
        PrimaryAction(
            label = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (secondaryLabel != null && onSecondary != null) {
            SecondaryAction(
                label = secondaryLabel,
                onClick = onSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        footnote?.let {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MomodingIcons.Permission, contentDescription = null, modifier = Modifier.size(15.dp), tint = LocalMomodingBrandColors.current.deep)
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
