package io.github.serogee.hive.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.ui.theme.hiveColors

/**
 * Top-bar music-only toggle. Controls background music for the current session without changing
 * sound effects or saved settings.
 */
@Composable
fun MusicToggleButton(
    musicOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
) {
    val icon = if (musicOn) Icons.Outlined.MusicNote else Icons.Outlined.MusicOff
    val label = if (musicOn) "Turn music off" else "Turn music on"
    val stateText = if (musicOn) "Music on" else "Music off"
    val semanticsModifier = modifier.semantics { stateDescription = stateText }

    if (framed) {
        val borderColor = if (musicOn) MaterialTheme.hiveColors.border else MaterialTheme.colorScheme.outline
        val contentColor = if (musicOn) MaterialTheme.hiveColors.onSecondaryButton else MaterialTheme.colorScheme.onSurfaceVariant
        Button(
            onClick = onToggle,
            modifier = semanticsModifier.heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.hiveColors.solid,
                contentColor = contentColor,
            ),
            border = BorderStroke(2.dp, borderColor),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    } else {
        IconButton(
            onClick = onToggle,
            modifier = semanticsModifier.size(40.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (musicOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
