package io.github.serogee.hive.ui.accounts

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryDateTest {
    private val zone = ZoneId.of("Asia/Manila")
    private val today = LocalDate.of(2026, 11, 24)
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun useEnglish() = Locale.setDefault(Locale.US)

    @AfterTest
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun label(date: LocalDate, hour: Int) =
        historyDate(date.atTime(hour, 30).atZone(zone).toInstant().toEpochMilli(), zone, today)

    @Test
    fun `days follow the phone's time zone`() {
        assertEquals("today", label(today, hour = 0))
        assertEquals("today", label(today, hour = 23))
        assertEquals("yesterday", label(today.minusDays(1), hour = 23))
    }

    @Test
    fun `older reviews show their date`() {
        assertEquals("Nov 22, 2026", label(today.minusDays(2), hour = 12))
    }
}
