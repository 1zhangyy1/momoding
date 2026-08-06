package app.momoding.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.momoding.R
import app.momoding.ui.theme.LocalMomodingBrandColors
import app.momoding.ui.theme.LocalMomodingStatusColors

enum class MomodingPresence { READY, WORKING, PERMISSION }

@Composable
fun MomodingMark(
    size: Dp,
    modifier: Modifier = Modifier,
    presence: MomodingPresence = MomodingPresence.READY,
    contentDescription: String? = null,
) {
    MomodingArtwork(
        artwork = R.drawable.momoding_portrait,
        size = size,
        modifier = modifier,
        presence = presence,
        contentDescription = contentDescription,
    )
}

@Composable
fun MomodingConversationAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    presence: MomodingPresence = MomodingPresence.READY,
    contentDescription: String? = null,
) {
    MomodingArtwork(
        artwork = R.drawable.momoding_conversation_avatar,
        size = size,
        modifier = modifier,
        presence = presence,
        contentDescription = contentDescription,
    )
}

@Composable
private fun MomodingArtwork(
    @DrawableRes artwork: Int,
    size: Dp,
    modifier: Modifier,
    presence: MomodingPresence,
    contentDescription: String?,
) {
    val status = LocalMomodingStatusColors.current
    Box(modifier = modifier.size(size)) {
        Image(
            painter = painterResource(artwork),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        if (presence != MomodingPresence.READY) {
            val dotSize = (size * 0.25f).coerceIn(7.dp, 12.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(
                        when (presence) {
                            MomodingPresence.READY -> Color.Transparent
                            MomodingPresence.WORKING -> status.info
                            MomodingPresence.PERMISSION -> LocalMomodingBrandColors.current.fold
                        },
                    )
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
fun MomodingIdentity(
    modifier: Modifier = Modifier,
    label: String = "Momoding",
    markSize: Dp = 20.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MomodingMark(size = markSize)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun momodingPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = LocalMomodingBrandColors.current.primary,
    contentColor = LocalMomodingBrandColors.current.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
fun MomodingTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ProductTopBar(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        onBack = onBack,
        backModifier = backModifier,
        titleModifier = titleModifier,
        showDivider = false,
        actions = actions,
    )
}

enum class MomodingBannerTone { NEUTRAL, WARNING, DANGER }

@Composable
fun MomodingStatusBanner(
    title: String,
    body: String,
    icon: ImageVector,
    iconColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    tone: MomodingBannerTone = MomodingBannerTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor, RoundedCornerShape(12.dp))
            .border(1.dp, iconColor.copy(alpha = if (tone == MomodingBannerTone.NEUTRAL) 0.18f else 0.28f), RoundedCornerShape(12.dp))
            .padding(start = 11.dp, top = 10.dp, end = 7.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = iconColor)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.TextButton(
                onClick = onAction,
                modifier = actionModifier.heightIn(min = 48.dp),
            ) { Text(actionLabel, color = iconColor) }
        }
    }
}

@Composable
fun MomodingReadOnlyChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.size(7.dp).background(color, CircleShape))
}
