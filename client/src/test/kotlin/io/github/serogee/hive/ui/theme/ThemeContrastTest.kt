package io.github.serogee.hive.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
    private val themes = listOf(
        Triple("light", SoftLight, LightHiveColors),
        Triple("dark", SoftDark, DarkHiveColors),
    )

    @Test
    fun `text is readable on the background and on cards`() {
        themes.forEach { (name, scheme, _) ->
            listOf("background" to scheme.background, "card" to scheme.surface).forEach { (place, color) ->
                assertContrast("$name ink on $place", scheme.onSurface, color)
                assertContrast("$name muted on $place", scheme.onSurfaceVariant, color)
                assertContrast("$name honey ink on $place", scheme.primary, color)
                assertContrast("$name bad on $place", scheme.error, color)
            }
        }
    }

    @Test
    fun `text is readable on colored areas`() {
        themes.forEach { (name, scheme, hive) ->
            assertContrast("$name ink on honey", Graphite, Honey)
            assertContrast("$name accent on a raised card", hive.accentOnSolid, hive.solid)
            assertContrast("$name text on a raised card", hive.onSolid, hive.solid)
            assertContrast("$name secondary button text", hive.onSecondaryButton, hive.solid)
            assertContrast("$name text on soft honey in snackbars", scheme.inverseOnSurface, scheme.inverseSurface)
            assertContrast("$name text on soft honey", scheme.onPrimaryContainer, scheme.primaryContainer)
            assertContrast("$name text on soft bad", scheme.onErrorContainer, scheme.errorContainer)
        }
    }

    @Test
    fun `borders stand out in light and are dim in dark`() {
        assertContrast("light border on background", LightHiveColors.border, SoftLight.background, ICON_CONTRAST)
        assertContrast("light border on card", LightHiveColors.border, SoftLight.surface, ICON_CONTRAST)

        listOf(DarkHiveColors.border, DarkHiveColors.borderOnSolid, DarkHiveColors.quietBorder, SoftDark.outline).forEach { border ->
            assertTrue(contrast(border, SoftDark.background) < ICON_CONTRAST, "a dark border is too bright")
        }
        assertContrast("dark border on card", DarkHiveColors.border, SoftDark.surface, DIM_CONTRAST)
    }

    @Test
    fun `light mode has no dark fills, and dark mode is pure black`() {
        val fills = listOf(
            "background" to SoftLight.background,
            "card" to SoftLight.surface,
            "raised card" to LightHiveColors.solid,
            "secondary container" to SoftLight.secondaryContainer,
            "snackbar" to SoftLight.inverseSurface,
        )
        fills.forEach { (place, color) ->
            assertTrue(contrast(color, Color.White) < LIGHT_FILL, "light $place is a dark fill")
        }
        assertTrue(SoftDark.background == Color.Black, "the dark background is not pure black")
    }

    private companion object {
        /** Fills this close to white read as light. Graphite is far beyond it. */
        const val LIGHT_FILL = 1.4f

        /** Enough to see a border, without a bright line. */
        const val DIM_CONTRAST = 1.5f
    }
}
