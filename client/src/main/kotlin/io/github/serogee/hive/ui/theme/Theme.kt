package io.github.serogee.hive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serogee.hive.R

/** Appearance choices offered in Accounts. */
enum class ThemePreference {
    SYSTEM,
    SOFT_LIGHT,
    SOFT_DARK,
}

// Design system palette
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

val BadgeBlue = Color(0xFF1B3882) // Number and code badges
val Amber = Color(0xFFFBBF24) // Create Stack on the join card
val AmberInk = Color(0xFF78350F) // Text on amber
val AmberInkSoft = Color(0xFF92400E) // Secondary text on amber
val AmberSoft = Color(0xFFFEF3C7) // Highlighted history card
val AmberBorder = Color(0xFFFCD34D)
val AmberStrong = Color(0xFFF59E0B)
val Orange = Color(0xFFF97316) // Host Quiz on the join card

val SuccessGreen = Color(0xFF22C55E) // Correct answers
val SuccessGreenText = Color(0xFF16A34A)
val DangerRed = Color(0xFFE53E3E) // Leave, delete, end, and wrong answers
val DangerRedText = Color(0xFFDC2626)
val SwitchTrackOff = Color(0xFFD6DFEC)
val NeutralBorder = Color(0xFFE2E8F0)

/** Colors that stacks take turns using on Home. */
val StackAccentColors = listOf(
    Color(0xFF6366F1),
    ElectricBlue,
    Color(0xFF0EA5E9),
    Color(0xFF10B981),
    AmberStrong,
    Color(0xFF8B5CF6),
)

val DarkBackground = Color(0xFF090E1D)
val DarkSurface = Color(0xFF0E162D)
val DarkSurfaceVariant = Color(0xFF16203D)
val DarkBorderSubtle = Color(0xFF1F2B4D)
val DarkOutlineVariant = Color(0xFF2D3C5E)

/** Colors that differ between light and dark but have no Material role. */
@Immutable
data class HiveColors(
    val successText: Color,
    val dangerText: Color,
    val switchTrackOff: Color,
    val neutralBorder: Color,
)

private val LightHiveColors = HiveColors(
    successText = SuccessGreenText,
    dangerText = DangerRedText,
    switchTrackOff = SwitchTrackOff,
    neutralBorder = NeutralBorder,
)

private val DarkHiveColors = HiveColors(
    successText = SuccessGreen,
    dangerText = DangerRed,
    switchTrackOff = DarkOutlineVariant,
    neutralBorder = DarkOutlineVariant,
)

private val LocalHiveColors = staticCompositionLocalOf { LightHiveColors }

val MaterialTheme.hiveColors: HiveColors
    @Composable @ReadOnlyComposable get() = LocalHiveColors.current

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
    outlineVariant = DarkOutlineVariant,
)

val HiveShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Archivo at 75% width, for headings, captions, buttons, and numbers. Text in it is all caps. */
val ArchivoCondensed = FontFamily(
    Font(R.font.archivo_condensed_extrabold, FontWeight.ExtraBold),
    Font(R.font.archivo_condensed_black, FontWeight.Black),
)

/** For body text, and for the questions, answers, and names that people write. */
val AtkinsonHyperlegible = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

/** Headline and label styles are Archivo. Title and body styles are Atkinson Hyperlegible. */
val HiveTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = 1.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 1.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.Black,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AtkinsonHyperlegible,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AtkinsonHyperlegible,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AtkinsonHyperlegible,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AtkinsonHyperlegible,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AtkinsonHyperlegible,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AtkinsonHyperlegible,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
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
    CompositionLocalProvider(LocalHiveColors provides if (dark) DarkHiveColors else LightHiveColors) {
        MaterialTheme(
            colorScheme = if (dark) SoftDark else SoftLight,
            shapes = HiveShapes,
            typography = HiveTypography,
            content = content,
        )
    }
}
