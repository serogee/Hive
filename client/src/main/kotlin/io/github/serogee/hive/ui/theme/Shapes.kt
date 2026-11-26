package io.github.serogee.hive.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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

/** A pointy-top hexagon with slightly rounded corners, for progress cells, letter badges, and checks. */
val HexagonShape = GenericShape { size, _ ->
    val points = listOf(
        Offset(size.width * 0.5f, 0f),
        Offset(size.width, size.height * 0.25f),
        Offset(size.width, size.height * 0.75f),
        Offset(size.width * 0.5f, size.height),
        Offset(0f, size.height * 0.75f),
        Offset(0f, size.height * 0.25f),
    )
    addRoundedPolygon(points, size.height * HEXAGON_CORNER)
}

/** The corner radius of [HexagonShape], as a part of its height. */
const val HEXAGON_CORNER = 0.1f

/**
 * A hexagon with a flat top and bottom and points at the sides, such as under the selected tab. The slanted
 * ends keep their [endDepth] at any width, and each corner is rounded with [corner].
 */
class WideHexagonShape(private val endDepth: Dp, private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val end = with(density) { endDepth.toPx() }.coerceAtMost(size.width / 2)
        val points = listOf(
            Offset(end, 0f),
            Offset(size.width - end, 0f),
            Offset(size.width, size.height / 2),
            Offset(size.width - end, size.height),
            Offset(end, size.height),
            Offset(0f, size.height / 2),
        )
        return Outline.Generic(Path().apply { addRoundedPolygon(points, with(density) { corner.toPx() }) })
    }
}

/** One rounded corner: the line reaches [start], then curves through the corner [control] to [end]. */
data class RoundedCorner(val start: Offset, val control: Offset, val end: Offset)

/**
 * The rounded corners of the closed polygon through [points]. Each corner starts and ends [radius] away from
 * its vertex along the two edges, but at most halfway along each edge, so that neighbouring corners never cross.
 */
fun roundedCorners(points: List<Offset>, radius: Float): List<RoundedCorner> = points.indices.map { i ->
    val vertex = points[i]
    val previous = points[(i + points.size - 1) % points.size]
    val next = points[(i + 1) % points.size]
    RoundedCorner(
        start = towards(vertex, previous, radius),
        control = vertex,
        end = towards(vertex, next, radius),
    )
}

/** The point [distance] from [from] towards [to], at most halfway. */
private fun towards(from: Offset, to: Offset, distance: Float): Offset {
    val length = (to - from).getDistance()
    if (length == 0f) return from
    return from + (to - from) * (distance.coerceAtMost(length / 2) / length)
}

/** Adds the closed polygon through [points], with each corner rounded by a quadratic curve through its vertex. */
fun Path.addRoundedPolygon(points: List<Offset>, radius: Float) {
    val corners = roundedCorners(points, radius)
    moveTo(corners.last().end.x, corners.last().end.y)
    corners.forEach { corner ->
        lineTo(corner.start.x, corner.start.y)
        quadraticTo(corner.control.x, corner.control.y, corner.end.x, corner.end.y)
    }
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
