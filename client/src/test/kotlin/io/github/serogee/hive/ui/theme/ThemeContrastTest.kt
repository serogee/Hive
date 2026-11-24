package io.github.serogee.hive.ui.theme

import kotlin.test.Test

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
    fun `ink borders stand out`() {
        themes.forEach { (name, scheme, hive) ->
            assertContrast("$name border on background", hive.ink, scheme.background, ICON_CONTRAST)
            assertContrast("$name border on card", hive.ink, scheme.surface, ICON_CONTRAST)
        }
    }
}
