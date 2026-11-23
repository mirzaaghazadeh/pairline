package app.pairline.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand colors shared with the iPhone app and the website. */
object Brand {
    val Blue = Color(0xFF1F5FBF)
    val BlueDark = Color(0xFF9DC1FF)
    val Green = Color(0xFF12B886)
    val Amber = Color(0xFFF59F00)
    val Red = Color(0xFFE03131)
}

private val LightColors = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF545F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF006C4C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF89F8C7),
    onTertiaryContainer = Color(0xFF002114),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Brand.Red,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Brand.BlueDark,
    onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFF6CDBAC),
    onTertiary = Color(0xFF003826),
    tertiaryContainer = Color(0xFF005139),
    onTertiaryContainer = Color(0xFF89F8C7),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Status colors that Material's scheme doesn't have. */
@Immutable
data class StatusColors(
    val good: Color,
    val goodContainer: Color,
    val onGoodContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val bad: Color,
    val badContainer: Color,
    val onBadContainer: Color,
    val idle: Color,
)

private val LightStatus = StatusColors(
    good = Brand.Green,
    goodContainer = Color(0xFFD3F9E8),
    onGoodContainer = Color(0xFF00382A),
    warn = Brand.Amber,
    warnContainer = Color(0xFFFFEFCC),
    onWarnContainer = Color(0xFF4A2F00),
    bad = Brand.Red,
    badContainer = Color(0xFFFFE3E3),
    onBadContainer = Color(0xFF5C0A0A),
    idle = Color(0xFF8E9099),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF63E6BE),
    goodContainer = Color(0xFF0B3D2E),
    onGoodContainer = Color(0xFFC3FAE8),
    warn = Color(0xFFFFC94D),
    warnContainer = Color(0xFF3F2E00),
    onWarnContainer = Color(0xFFFFE8B0),
    bad = Color(0xFFFF8787),
    badContainer = Color(0xFF4A1414),
    onBadContainer = Color(0xFFFFE3E3),
    idle = Color(0xFF8E9099),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val PairlineShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val PairlineTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
    )
}

/**
 * The brand palette in light and dark. Wallpaper colors (Android 12+) are deliberately not used, so the app
 * looks the same as the iPhone app and the website.
 */
@Composable
fun PairlineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PairlineTypography,
            shapes = PairlineShapes,
            content = content,
        )
    }
}

object PairlineThemeExt {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
