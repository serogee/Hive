package io.github.serogee.hive.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/** How a tap on a choice becomes an answer. Offered in Accounts, for quiz review and live questions. */
enum class AnswerInput(val label: String, val note: String) {
    CONFIRM("Confirm answer", "Tap to select, then press Confirm."),
    LOCK_ON_TAP("Lock in on tap", "The choice you tap is your answer."),
    BOTH("Confirm or double tap", "Press Confirm, or tap a choice twice to lock it in."),
    ;

    companion object {
        /** The choice saved as [name], or the default. */
        fun of(name: String?): AnswerInput = entries.firstOrNull { it.name == name } ?: CONFIRM
    }
}

val LocalAnswerInput = staticCompositionLocalOf { AnswerInput.CONFIRM }

/** Decides which taps lock an answer in. */
class AnswerTaps(private val input: AnswerInput) {
    private var lastChoice: String? = null
    private var lastAt = 0L

    /** Whether a tap on [choice] at [now], in milliseconds, locks it in. */
    fun locksIn(choice: String, now: Long): Boolean = when (input) {
        AnswerInput.CONFIRM -> false
        AnswerInput.LOCK_ON_TAP -> true
        AnswerInput.BOTH -> {
            val second = choice == lastChoice && now - lastAt <= DOUBLE_TAP_MILLIS
            // A third tap starts over, so that it never counts as the second half of another double tap.
            lastChoice = choice.takeUnless { second }
            lastAt = now
            second
        }
    }

    private companion object {
        const val DOUBLE_TAP_MILLIS = 400
    }
}
