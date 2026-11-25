package io.github.serogee.hive.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.ui.theme.AnswerColors
import io.github.serogee.hive.ui.theme.AnswerTone
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.answerColors
import io.github.serogee.hive.ui.theme.hiveColors

enum class AnswerTileState {
    IDLE,
    SELECTED,
    RIGHT,
    WRONG,
}

/** How a choice looks. [correct] is null until the correct answer is known. */
fun answerTileState(selected: Boolean, correct: Boolean?): AnswerTileState = when {
    correct == null -> if (selected) AnswerTileState.SELECTED else AnswerTileState.IDLE
    correct -> AnswerTileState.RIGHT
    selected -> AnswerTileState.WRONG
    else -> AnswerTileState.IDLE
}

/** The letter of the choice at [index]: A, B, C, and so on. */
fun choiceLetter(index: Int): String = ('A' + index % 26).toString()

/** The letter and the check have equal room, so that the answer between them is centered. */
private val SideWidth = 34.dp

/** A choice, with its letter on the left, its text in the center, and a check or a cross on the right. */
@Composable
fun AnswerTile(
    letter: String,
    text: String,
    state: AnswerTileState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tone = when (state) {
        AnswerTileState.RIGHT -> MaterialTheme.answerColors.right
        AnswerTileState.WRONG -> MaterialTheme.answerColors.wrong
        else -> null
    }
    val selected = state == AnswerTileState.SELECTED
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = selected || state == AnswerTileState.WRONG,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = tone?.background ?: if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = tone?.text ?: if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(2.dp, tone?.border ?: if (selected) MaterialTheme.hiveColors.border else scheme.outline),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(SideWidth), contentAlignment = Alignment.CenterStart) {
                HexBadge(
                    text = letter,
                    size = 34.dp,
                    color = when {
                        tone != null -> tone.chip
                        selected -> MaterialTheme.hiveColors.solid
                        else -> scheme.background
                    },
                    contentColor = when {
                        tone != null -> tone.onChip
                        selected -> Honey
                        else -> scheme.onBackground
                    },
                )
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.width(SideWidth), contentAlignment = Alignment.CenterEnd) {
                if (tone != null) VerdictBadge(right = state == AnswerTileState.RIGHT, tone = tone)
            }
        }
    }
}

/** A check for a right answer, or a cross for a wrong one. */
@Composable
fun VerdictBadge(
    right: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    tone: AnswerTone = MaterialTheme.answerColors.tone(right),
) {
    HexBadge(modifier, size, tone.chip) {
        Icon(
            imageVector = if (right) Icons.Outlined.Check else Icons.Outlined.Close,
            contentDescription = if (right) "Right answer" else "Wrong answer",
            tint = tone.onChip,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

/** A short verdict, such as Correct or Missed, with its check or cross. */
@Composable
fun VerdictTag(
    right: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    tone: AnswerTone = MaterialTheme.answerColors.tone(right),
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = tone.background,
        contentColor = tone.text,
        border = BorderStroke(2.dp, tone.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VerdictBadge(right, size = 22.dp, tone = tone)
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** A box about a right or a wrong answer, with a check or a cross beside its caps [title]. */
@Composable
fun VerdictBox(
    right: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tone = MaterialTheme.answerColors.tone(right)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = tone.background,
        contentColor = tone.text,
        border = BorderStroke(2.dp, tone.border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VerdictBadge(right, size = 24.dp, tone = tone)
                Caption(title, color = tone.text)
            }
            content()
        }
    }
}

/** A box for something to notice that is neither right nor wrong. */
@Composable
fun NoteBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.hiveColors.border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

private fun AnswerColors.tone(right: Boolean): AnswerTone = if (right) this.right else wrong
