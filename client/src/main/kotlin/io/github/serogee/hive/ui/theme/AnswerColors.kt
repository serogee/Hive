package io.github.serogee.hive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors of a right or a wrong answer. They are the same in light and dark. */
@Immutable
data class AnswerTone(
    val background: Color,
    val border: Color,
    /** Behind the check or the cross. */
    val chip: Color,
    val onChip: Color,
    val text: Color,
)

@Immutable
data class AnswerColors(val right: AnswerTone, val wrong: AnswerTone)

private val RedWrong = AnswerTone(background = BadSoft, border = Bad, chip = Bad, onChip = Color.White, text = Bad)

/** Answer colors offered in Accounts. */
enum class AnswerColorChoice(val label: String, val note: String? = null) {
    APP_HONEY("App honey"),
    GREEN_RED("Green and red"),
    BLUE_ORANGE("Blue and orange", "Easiest for colour-blind players"),
    ;

    companion object {
        /** The choice saved as [name], or the default. */
        fun of(name: String?): AnswerColorChoice = entries.firstOrNull { it.name == name } ?: APP_HONEY
    }
}

val AnswerColorChoice.colors: AnswerColors
    get() = when (this) {
        AnswerColorChoice.APP_HONEY -> AnswerColors(
            right = AnswerTone(background = HoneySoft, border = Graphite, chip = Honey, onChip = Graphite, text = HoneyInk),
            wrong = RedWrong,
        )
        AnswerColorChoice.GREEN_RED -> AnswerColors(
            right = AnswerTone(
                background = Color(0xFFDDF3E4),
                border = Color(0xFF1E7A3E),
                chip = Color(0xFF1E7A3E),
                onChip = Color.White,
                text = Color(0xFF145C2C),
            ),
            wrong = RedWrong,
        )
        AnswerColorChoice.BLUE_ORANGE -> AnswerColors(
            right = AnswerTone(
                background = Color(0xFFDCE8FF),
                border = Color(0xFF2558E8),
                chip = Color(0xFF2558E8),
                onChip = Color.White,
                text = Color(0xFF1742BC),
            ),
            wrong = AnswerTone(
                background = Color(0xFFFDE3D3),
                border = Color(0xFFC2410C),
                chip = Color(0xFFC2410C),
                onChip = Color.White,
                text = Color(0xFF9A3412),
            ),
        )
    }

internal val LocalAnswerColors = staticCompositionLocalOf { AnswerColorChoice.APP_HONEY.colors }

val MaterialTheme.answerColors: AnswerColors
    @Composable @ReadOnlyComposable get() = LocalAnswerColors.current
