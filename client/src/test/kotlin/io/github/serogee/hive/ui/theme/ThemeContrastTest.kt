package io.github.serogee.hive.ui.theme

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
            assertContrast("$name honey on graphite", Honey, hive.solid)
            assertContrast("$name text on graphite", hive.onSolid, hive.solid)
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

    private companion object {
        /** Enough to see a border, without a bright line. */
        const val DIM_CONTRAST = 1.5f
    }
}
