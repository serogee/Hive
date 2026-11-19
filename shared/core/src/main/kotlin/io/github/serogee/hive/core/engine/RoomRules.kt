package io.github.serogee.hive.core.engine

import kotlin.random.Random

/** Supplies the current time, so that rules can be tested without waiting. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

object DisplayNames {
    const val MAX_LENGTH = 30

    /** Trims and collapses spaces. Returns null when the result is empty or too long. */
    fun normalize(raw: String?): String? {
        val name = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return name.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }
}

object RoomCodes {
    const val LENGTH = 6

    /** Letters and digits that are hard to confuse when read aloud or typed. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(random: Random): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Uppercases a typed code and removes spaces and dashes. */
    fun normalize(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }
}
