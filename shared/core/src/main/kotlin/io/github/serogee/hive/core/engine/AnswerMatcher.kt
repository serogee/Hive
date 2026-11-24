package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.model.AnswerMatching
import kotlin.math.abs

/** Compares a typed answer with one accepted answer, using the author's options. */
object AnswerMatcher {
    private val symbols = Regex("[^\\p{L}\\p{N}\\s]+")

    fun matches(expected: String, given: String, matching: AnswerMatching): Boolean {
        val givenForms = forms(given, matching)
        return forms(expected, matching).any { form -> givenForms.any { closeEnough(form, it, matching) } }
    }

    /**
     * The normalized text. When symbols are ignored, also without them and with a space in their
     * place, so that "TCP/IP" matches "TCPIP" and "TCP IP". Text made only of symbols stays as written.
     */
    private fun forms(text: String, matching: AnswerMatching): Set<String> {
        val written = Grading.normalize(text, matching.ignoreCase)
        if (!matching.ignoreSymbols || symbols.replace(written, "").isBlank()) return setOf(written)
        return setOf(symbols.replace(written, ""), symbols.replace(written, " "))
            .mapTo(mutableSetOf()) { Grading.normalize(it, matching.ignoreCase) }
    }

    private fun closeEnough(expected: String, given: String, matching: AnswerMatching): Boolean {
        if (given.isEmpty()) return false
        if (expected == given) return true
        val allowed = matching.typos.allowed(expected.length)
        if (allowed == 0 || abs(expected.length - given.length) > allowed) return false
        // A typo never changes a number, so 443 and 444 stay different answers.
        if (expected.filter(Char::isDigit) != given.filter(Char::isDigit)) return false
        val typos = distance(expected, given)
        if (typos > allowed) return false
        // A wrong capital letter is not a typo when capital letters count.
        return matching.ignoreCase || typos == distance(expected.lowercase(), given.lowercase())
    }

    /** Wrong, missing, extra, or swapped neighboring characters between two texts. */
    internal fun distance(a: String, b: String): Int {
        var beforeLast = IntArray(b.length + 1)
        var last = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                val replaced = last[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                row[j] = minOf(last[j] + 1, row[j - 1] + 1, replaced)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    row[j] = minOf(row[j], beforeLast[j - 2] + 1)
                }
            }
            beforeLast = last
            last = row
        }
        return last[b.length]
    }
}
