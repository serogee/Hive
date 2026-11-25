package io.github.serogee.hive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import io.github.serogee.hive.R

/** Appearance choices offered in Accounts. */
enum class ThemePreference {
    SYSTEM,
    SOFT_LIGHT,
    SOFT_DARK,
}

// Wax and Graphite, light
val Wax = Color(0xFFE4E6E0) // Background, and text on graphite
val WaxCard = Color(0xFFFBFBF8)
val Graphite = Color(0xFF202327) // Text, borders, and solid areas. Also the text on honey, in light and dark
val WaxMuted = Color(0xFF555B62)
val WaxLine = Color(0xFFCDD0C8)
val Honey = Color(0xFFF2A900) // The same in light and dark
val HoneyInk = Color(0xFF5C3D00) // Text on soft honey, links, and icons
val HoneySoft = Color(0xFFFFF1C7)
val Bad = Color(0xFFA3301B) // Wrong answers, errors, leave, delete, and end
val BadSoft = Color(0xFFF8DDD6)
val EmptyCell = Color(0xFFD9CFA8) // Progress cells that are not filled yet

// Wax and Graphite, dark
val Night = Color(0xFF16181B)
val NightCard = Color(0xFF202327)
val NightInk = Color(0xFFECEDE8)
val NightMuted = Color(0xFFA9AEA5)
val NightLine = Color(0xFF363A40)
val NightBorder = Color(0xFF4A4F56) // Borders and bars, dimmer than the text
val NightSolid = Color(0xFF2B2E33)
val NightHoneyInk = Color(0xFFFFD66B)
val NightHoneySoft = Color(0xFF3A2F12)
val NightBad = Color(0xFFFF9B85)
val NightBadSoft = Color(0xFF45201A)
val NightEmptyCell = Color(0xFF4A4430)

/** Colors that differ between light and dark but have no Material role. */
@Immutable
data class HiveColors(
    val isDark: Boolean,
    /** Borders of buttons, bars, and the main cards. Dimmed in dark, where a near-white line glares. */
    val border: Color,
    /** Hero cards, secondary buttons, and the selected segment or tab. Its accent is [Honey]. */
    val solid: Color,
    val onSolid: Color,
    /** Borders of buttons and fields on a graphite card. */
    val borderOnSolid: Color,
    /** Borders of fields and switches that are not in use. */
    val quietBorder: Color,
    val emptyCell: Color,
)

internal val LightHiveColors = HiveColors(
    isDark = false,
    border = Graphite,
    solid = Graphite,
    onSolid = Wax,
    borderOnSolid = Wax,
    quietBorder = WaxMuted,
    emptyCell = EmptyCell,
)

internal val DarkHiveColors = HiveColors(
    isDark = true,
    border = NightBorder,
    solid = NightSolid,
    onSolid = Wax,
    borderOnSolid = NightBorder,
    quietBorder = NightBorder,
    emptyCell = NightEmptyCell,
)

private val LocalHiveColors = staticCompositionLocalOf { LightHiveColors }

val MaterialTheme.hiveColors: HiveColors
    @Composable @ReadOnlyComposable get() = LocalHiveColors.current

internal val SoftLight = lightColorScheme(
    primary = HoneyInk,
    onPrimary = HoneySoft,
    primaryContainer = HoneySoft,
    onPrimaryContainer = HoneyInk,
    secondary = Graphite,
    onSecondary = Honey,
    secondaryContainer = Graphite,
    onSecondaryContainer = Honey,
    tertiary = Honey,
    onTertiary = Graphite,
    tertiaryContainer = HoneySoft,
    onTertiaryContainer = HoneyInk,
    background = Wax,
    onBackground = Graphite,
    surface = WaxCard,
    onSurface = Graphite,
    surfaceVariant = Wax,
    onSurfaceVariant = WaxMuted,
    surfaceTint = WaxCard,
    inverseSurface = Graphite,
    inverseOnSurface = Wax,
    inversePrimary = Honey,
    error = Bad,
    onError = WaxCard,
    errorContainer = BadSoft,
    onErrorContainer = Bad,
    outline = WaxLine,
    outlineVariant = WaxLine,
    surfaceBright = WaxCard,
    surfaceDim = Wax,
    surfaceContainerLowest = WaxCard,
    surfaceContainerLow = WaxCard,
    surfaceContainer = WaxCard,
    surfaceContainerHigh = WaxCard,
    surfaceContainerHighest = WaxCard,
)

internal val SoftDark = darkColorScheme(
    primary = NightHoneyInk,
    onPrimary = Graphite,
    primaryContainer = NightHoneySoft,
    onPrimaryContainer = NightHoneyInk,
    secondary = NightSolid,
    onSecondary = Honey,
    secondaryContainer = NightSolid,
    onSecondaryContainer = Honey,
    tertiary = Honey,
    onTertiary = Graphite,
    tertiaryContainer = NightHoneySoft,
    onTertiaryContainer = NightHoneyInk,
    background = Night,
    onBackground = NightInk,
    surface = NightCard,
    onSurface = NightInk,
    surfaceVariant = Night,
    onSurfaceVariant = NightMuted,
    surfaceTint = NightCard,
    inverseSurface = NightSolid,
    inverseOnSurface = Wax,
    inversePrimary = Honey,
    error = NightBad,
    onError = Night,
    errorContainer = NightBadSoft,
    onErrorContainer = NightBad,
    outline = NightLine,
    outlineVariant = NightLine,
    surfaceBright = NightCard,
    surfaceDim = Night,
    surfaceContainerLowest = NightCard,
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCard,
    surfaceContainerHighest = NightCard,
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
@ReadOnlyComposable
fun ThemePreference.isDark(): Boolean = when (this) {
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
    ThemePreference.SOFT_LIGHT -> false
    ThemePreference.SOFT_DARK -> true
}

@Composable
fun HiveTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    answerColors: AnswerColorChoice = AnswerColorChoice.APP_HONEY,
    content: @Composable () -> Unit,
) {
    val dark = preference.isDark()
    CompositionLocalProvider(
        LocalHiveColors provides if (dark) DarkHiveColors else LightHiveColors,
        LocalAnswerColors provides answerColors.colors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) SoftDark else SoftLight,
            shapes = HiveShapes,
            typography = HiveTypography,
            content = content,
        )
    }
}
