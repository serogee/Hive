package io.github.serogee.hive.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.assertTrue

/** The smallest WCAG AA contrast for text, and for icons and large shapes. */
const val TEXT_CONTRAST = 4.5f
const val ICON_CONTRAST = 3f

fun contrast(a: Color, b: Color): Float {
    val light = maxOf(a.luminance(), b.luminance())
    val dark = minOf(a.luminance(), b.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}

fun assertContrast(name: String, content: Color, background: Color, least: Float = TEXT_CONTRAST) {
    val actual = contrast(content, background)
    assertTrue(actual >= least, "$name has contrast $actual, below $least")
}
