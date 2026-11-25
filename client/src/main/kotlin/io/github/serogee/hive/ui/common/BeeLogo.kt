package io.github.serogee.hive.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.Wax

/** The side of the box that the logo's coordinates are in. */
private const val BOX = 100f

private val WingOnGraphite = Color(0xFF3A3E44)

private val Trail = Path().apply {
    moveTo(10f, 86f)
    cubicTo(20f, 92f, 26f, 72f, 38f, 74f)
}
private val TrailStroke = Stroke(width = 3.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 7f)))
private val WingStroke = Stroke(width = 3.5f, join = StrokeJoin.Round)

private val BackWing = polygon(44f to 17.75f, 54.61f to 23.88f, 54.61f to 36.12f, 44f to 42.25f, 33.39f to 36.12f, 33.39f to 23.88f)
private val FrontWing = polygon(73f to 21.75f, 83.61f to 27.88f, 83.61f to 40.12f, 73f to 46.25f, 62.39f to 40.12f, 62.39f to 27.88f)
private val Body = polygon(57f to 32f, 78.65f to 44.5f, 78.65f to 69.5f, 57f to 82f, 35.35f to 69.5f, 35.35f to 44.5f)
private val BodyOutline = polygon(57f to 33.5f, 77.35f to 45.25f, 77.35f to 68.75f, 57f to 80.5f, 36.65f to 68.75f, 36.65f to 45.25f)

/** The bee in flight. The launcher icon and the splash screen draw the same mark in `ic_launcher_foreground`. */
@Composable
fun BeeLogo(size: Dp, modifier: Modifier = Modifier, onGraphite: Boolean = false) {
    val line = if (onGraphite) Wax else Graphite
    val wing = if (onGraphite) WingOnGraphite else Color.White
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Hive logo" },
    ) {
        scale(this.size.minDimension / BOX, pivot = Offset.Zero) {
            drawPath(Trail, line, style = TrailStroke)
            listOf(BackWing to Offset(44f, 30f), FrontWing to Offset(73f, 34f)).forEach { (path, center) ->
                rotate(-20f, pivot = center) {
                    drawPath(path, wing)
                    drawPath(path, line, style = WingStroke)
                }
            }
            rotate(-20f, pivot = Offset(57f, 57f)) {
                drawPath(Body, Honey)
                clipPath(Body) {
                    drawRect(Graphite, topLeft = Offset(32f, 52f), size = Size(50f, 8f))
                    drawRect(Graphite, topLeft = Offset(32f, 67f), size = Size(50f, 8f))
                }
                drawPath(BodyOutline, Honey, style = Stroke(width = 3f))
            }
        }
    }
}

private fun polygon(vararg points: Pair<Float, Float>) = Path().apply {
    points.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x, y) else lineTo(x, y) }
    close()
}
