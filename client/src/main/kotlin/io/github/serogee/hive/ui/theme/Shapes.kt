package io.github.serogee.hive.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

val HiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

/** Bottom sheets. */
val SheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

/** Width of a regular pointy-top hexagon for a height of 1. */
const val HEXAGON_RATIO = 0.866f

/** A pointy-top hexagon, for progress cells, letter badges, and checks. */
val HexagonShape = GenericShape { size, _ ->
    moveTo(size.width * 0.5f, 0f)
    lineTo(size.width, size.height * 0.25f)
    lineTo(size.width, size.height * 0.75f)
    lineTo(size.width * 0.5f, size.height)
    lineTo(0f, size.height * 0.75f)
    lineTo(0f, size.height * 0.25f)
    close()
}

private val ShadowOffset = 6.dp

/** The solid honey shadow of the active card and the main hero card. Keeps room for itself below and to the right. */
fun Modifier.honeyShadow(shape: Shape): Modifier = this
    .padding(end = ShadowOffset, bottom = ShadowOffset)
    .drawBehind {
        translate(ShadowOffset.toPx(), ShadowOffset.toPx()) {
            drawOutline(shape.createOutline(size, layoutDirection, this), Honey)
        }
    }
