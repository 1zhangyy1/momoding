package app.momoding.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.core.appearance.AppearanceMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D0D0D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF8E7),
    onPrimaryContainer = Color(0xFF176B2A),
    secondary = Color(0xFF0D0D0D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF0D0D0D),
    tertiary = Color(0xFF0D0D0D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7F7F7),
    onTertiaryContainer = Color(0xFF0D0D0D),
    background = Color(0xFFFFFDF5),
    onBackground = Color(0xFF17221A),
    surface = Color(0xFFFFFDF5),
    onSurface = Color(0xFF17221A),
    surfaceVariant = Color(0xFFF7F5EC),
    surfaceContainer = Color(0xFFF7F5EC),
    surfaceContainerLow = Color(0xFFFBFAF3),
    surfaceContainerHigh = Color(0xFFEEECE3),
    onSurfaceVariant = Color(0xFF5C665F),
    outline = Color(0xFF6C766F),
    outlineVariant = Color(0xFFDCE3D8),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFFEDEA),
    onErrorContainer = Color(0xFF7A1A12),
    inverseSurface = Color(0xFF181818),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFF5F5F5),
    surfaceTint = Color.Transparent,
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF181818),
    primaryContainer = Color(0xFF263A2A),
    onPrimaryContainer = Color(0xFFB9F5B4),
    secondary = Color(0xFFF5F5F5),
    onSecondary = Color(0xFF181818),
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color(0xFFF5F5F5),
    onTertiary = Color(0xFF181818),
    tertiaryContainer = Color(0xFF242424),
    onTertiaryContainer = Color(0xFFF5F5F5),
    background = Color(0xFF111712),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF18201A),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF212A23),
    surfaceContainer = Color(0xFF212A23),
    surfaceContainerLow = Color(0xFF18201A),
    surfaceContainerHigh = Color(0xFF2A342C),
    onSurfaceVariant = Color(0xFFB8C1BA),
    outline = Color(0xFF8B968D),
    outlineVariant = Color(0xFF3B463D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5B1E1A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF181818),
    inversePrimary = Color(0xFF0D0D0D),
    surfaceTint = Color.Transparent,
    scrim = Color.Black,
)

private val MomodingTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

private val MomodingShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Immutable
data class MomodingSpacing(
    val x1: androidx.compose.ui.unit.Dp = 4.dp,
    val x2: androidx.compose.ui.unit.Dp = 8.dp,
    val x3: androidx.compose.ui.unit.Dp = 12.dp,
    val x4: androidx.compose.ui.unit.Dp = 16.dp,
    val x5: androidx.compose.ui.unit.Dp = 20.dp,
    val x6: androidx.compose.ui.unit.Dp = 24.dp,
)

val LocalMomodingSpacing = staticCompositionLocalOf { MomodingSpacing() }

@Immutable
data class MomodingStatusColors(
    val success: Color,
    val successContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
)

private val LightStatusColors = MomodingStatusColors(
    success = Color(0xFF15803D),
    successContainer = Color(0xFFECF8F0),
    info = Color(0xFF1769AA),
    infoContainer = Color(0xFFEEF6FF),
    warning = Color(0xFFA2480D),
    warningContainer = Color(0xFFFFF4E8),
    danger = Color(0xFFB42318),
    dangerContainer = Color(0xFFFFF1F0),
)

private val DarkStatusColors = MomodingStatusColors(
    success = Color(0xFF40C977),
    successContainer = Color(0xFF20372A),
    info = Color(0xFF63AEE8),
    infoContainer = Color(0xFF20303D),
    warning = Color(0xFFFF995F),
    warningContainer = Color(0xFF3A2C24),
    danger = Color(0xFFFA5A55),
    dangerContainer = Color(0xFF3D2525),
)

val LocalMomodingStatusColors = staticCompositionLocalOf { LightStatusColors }

@Immutable
data class MomodingBrandColors(
    val primary: Color,
    val onPrimary: Color,
    val deep: Color,
    val soft: Color,
    val fold: Color,
)

private val LightMomodingBrandColors = MomodingBrandColors(
    primary = Color(0xFF49C947),
    onPrimary = Color(0xFF17221A),
    deep = Color(0xFF176B2A),
    soft = Color(0xFFEAF8E7),
    fold = Color(0xFFFFC928),
)

private val DarkMomodingBrandColors = MomodingBrandColors(
    primary = Color(0xFF6DDD68),
    onPrimary = Color(0xFF111712),
    deep = Color(0xFFB9F5B4),
    soft = Color(0xFF263A2A),
    fold = Color(0xFFFFD65C),
)

val LocalMomodingBrandColors = staticCompositionLocalOf { LightMomodingBrandColors }

@Composable
fun MomodingTheme(
    appearance: AppearanceMode,
    systemDark: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = resolvesToDark(appearance, systemDark)
    CompositionLocalProvider(
        LocalMomodingSpacing provides MomodingSpacing(),
        LocalMomodingStatusColors provides if (dark) DarkStatusColors else LightStatusColors,
        LocalMomodingBrandColors provides if (dark) DarkMomodingBrandColors else LightMomodingBrandColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = MomodingTypography,
            shapes = MomodingShapes,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            ) { content() }
        }
    }
}

fun resolvesToDark(appearance: AppearanceMode, systemDark: Boolean): Boolean = when (appearance) {
    AppearanceMode.SYSTEM -> systemDark
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
}
