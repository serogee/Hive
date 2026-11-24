package io.github.serogee.hive.ui.accounts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
