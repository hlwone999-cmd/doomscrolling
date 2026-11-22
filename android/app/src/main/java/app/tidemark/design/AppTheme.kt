// Hand-written. Maps the generated role tokens onto Material 3 so stock M3
// components (buttons, switches, sliders, sheets) pick up the right colours.
// Screens use LocalAppColors / AppType for anything M3 has no role for
// (severity, overlay, muted text). Never use raw hex in a screen.
package app.tidemark.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ThemeMode { SYSTEM, LIGHT, DARK }   // stored in DataStore key "theme"

private fun AppColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = surface2, onPrimaryContainer = text,
        secondary = accent, onSecondary = onAccent,
        background = bg, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = surface2, onSurfaceVariant = textMuted,
        surfaceContainer = surface, surfaceContainerHigh = surface2,
        outline = borderInput, outlineVariant = border,
        error = danger, onError = onDanger,
    )
}

private val AppTypography = Typography(
    displayLarge = AppType.display,
    headlineSmall = AppType.headline,
    titleLarge = AppType.title,
    bodyLarge = AppType.body,
    bodyMedium = AppType.bodySm,
    labelLarge = AppType.bodySm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)),
    labelMedium = AppType.labelSm,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Dynamic colour (Material You) is deliberately off: severity colours carry
    // meaning and were contrast-checked; wallpaper colours were not.
    val colors = if (dark) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
