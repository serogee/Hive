package io.github.serogee.hive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Appearance choices offered in Accounts. */
enum class ThemePreference {
    SYSTEM,
    SOFT_LIGHT,
    SOFT_DARK,
}

private val Teal = Color(0xFF1F8A8A)
private val TealLight = Color(0xFF7FD3CF)
private val Amber = Color(0xFFE9A21B)
private val AmberLight = Color(0xFFF6C65B)

private val SoftLight = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Amber,
    onSecondary = Color(0xFF2B1D00),
    background = Color(0xFFFBF8F1),
    surface = Color(0xFFFBF8F1),
    surfaceVariant = Color(0xFFEDE6D6),
)

private val SoftDark = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003735),
    secondary = AmberLight,
    onSecondary = Color(0xFF3F2D00),
    background = Color(0xFF1B1C1A),
    surface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFF34332D),
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
        content = content,
    )
}
