package io.github.serogee.hive.ui.accounts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A date and time such as "yesterday at 7:29 PM". */
internal fun openedAt(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
    return "${historyDate(epochMillis, zone, today)} at ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
}

/** The date shown on a history card: "today", "yesterday", or the date itself. */
internal fun historyDate(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return when (date) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}
