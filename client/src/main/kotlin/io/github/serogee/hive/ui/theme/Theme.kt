package io.github.serogee.hive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Appearance choices offered in Accounts. */
enum class ThemePreference {
    SYSTEM,
    SOFT_LIGHT,
    SOFT_DARK,
}

// Design System Palette matching the reference design in the photo
val MidnightNavy = Color(0xFF0C1838) // Deep midnight navy for hero cards & headers
val MidnightNavyDark = Color(0xFF070E22)
val MidnightNavyLight = Color(0xFF14244E)
val ElectricBlue = Color(0xFF2558E8) // Vibrant royal blue accent
val ElectricBlueLight = Color(0xFF4A7BFF)
val ElectricBlueDark = Color(0xFF1742BC)
val SoftBlueContainer = Color(0xFFEEF3FF) // Soft blue tint for secondary pills
val OnSoftBlueContainer = Color(0xFF2558E8)

val LightBackground = Color(0xFFF5F7FB) // Clean cool off-white background
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEFF2F8)
val DarkSlate = Color(0xFF0D1730)
val MutedSlate = Color(0xFF75839C)
val BorderSubtle = Color(0xFFE8EEF8)

val DarkBackground = Color(0xFF090E1D)
val DarkSurface = Color(0xFF0E162D)
val DarkSurfaceVariant = Color(0xFF16203D)
val DarkBorderSubtle = Color(0xFF1F2B4D)

private val SoftLight = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = SoftBlueContainer,
    onPrimaryContainer = OnSoftBlueContainer,
    secondary = MidnightNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAFA),
    onSecondaryContainer = MidnightNavy,
    tertiary = Color(0xFF3B82F6),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = DarkSlate,
    surface = LightSurface,
    onSurface = DarkSlate,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = MutedSlate,
    outline = BorderSubtle,
    outlineVariant = Color(0xFFE0E6F0),
)

private val SoftDark = darkColorScheme(
    primary = ElectricBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF152A5E),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF8BA5EE),
    onSecondary = Color(0xFF001B54),
    secondaryContainer = MidnightNavyLight,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF60A5FA),
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = Color(0xFFE2E8F0),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = DarkBorderSubtle,
    outlineVariant = Color(0xFF2D3C5E),
)

val HiveShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val HiveTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp,
    ),
)

@Composable
fun HiveTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.SOFT_LIGHT -> false
        ThemePreference.SOFT_DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) SoftDark else SoftLight,
        shapes = HiveShapes,
        typography = HiveTypography,
        content = content,
    )
}
