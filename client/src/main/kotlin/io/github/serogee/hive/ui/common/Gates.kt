package io.github.serogee.hive.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.data.server.Gate
import io.github.serogee.hive.data.server.GateLook
import io.github.serogee.hive.data.server.OnlineFacts

/** Which online features work now. The server is checked regularly while the screen is open. */
@Composable
fun watchOnline(): OnlineFacts {
    val container = LocalAppContainer.current
    LifecycleStartEffect(container) {
        container.server.startWatching()
        onStopOrDispose { container.server.stopWatching() }
    }
    val online by container.online.collectAsStateWithLifecycle()
    return online
}

/** Greys out [content] and says why while [gate] is closed. [content] gets whether it can be used. */
@Composable
fun Gated(gate: Gate?, modifier: Modifier = Modifier, content: @Composable (enabled: Boolean) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(if (gate != null) Modifier.alpha(GREY_ALPHA) else Modifier) { content(gate == null) }
        if (gate != null) GateNote(gate)
    }
}

/** Why a feature cannot be used: a lock, or a crossed-out cloud in a dashed outline for a lost connection. */
@Composable
fun GateNote(gate: Gate, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when (gate.look) {
        GateLook.LOCKED -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = color,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            GateText(gate)
        }
        GateLook.DISCONNECTED -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .dashedOutline(color),
            color = Color.Transparent,
            contentColor = color,
        ) {
            GateText(gate)
        }
    }
}

@Composable
private fun GateText(gate: Gate) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (gate.look == GateLook.LOCKED) Icons.Outlined.Lock else Icons.Outlined.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(gate.reason, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Modifier.dashedOutline(color: Color): Modifier = drawBehind {
    val dash = 6.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}

private const val GREY_ALPHA = 0.45f
