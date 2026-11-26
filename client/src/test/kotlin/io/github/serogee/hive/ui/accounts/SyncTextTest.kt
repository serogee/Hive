package io.github.serogee.hive.ui.accounts

import io.github.serogee.hive.data.sync.SyncStatus
import io.github.serogee.hive.data.sync.Unlinked
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncTextTest {
    private val zone = ZoneId.of("Asia/Manila")
    private val today = LocalDate.of(2026, 11, 26)
    private val defaultLocale = Locale.getDefault()
    private val tenThirty = today.atTime(10, 30).atZone(zone).toInstant().toEpochMilli()

    /** Newer Java versions put a narrow space before AM, so the test asks the same formatter. */
    private val time get() = LocalTime.of(10, 30).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    @BeforeTest
    fun useEnglish() = Locale.setDefault(Locale.US)

    @AfterTest
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun text(status: SyncStatus) = syncStatusText(status, zone, today)

    @Test
    fun `each status has its words`() {
        assertNull(text(SyncStatus.Off))
        assertEquals("Syncing", text(SyncStatus.Syncing))
        assertEquals("Up to date", text(SyncStatus.UpToDate(tenThirty)))
        assertEquals("Last synced today at $time", text(SyncStatus.Waiting(tenThirty)))
        assertEquals("Not synced yet", text(SyncStatus.Waiting(null)))
        assertEquals("Offline. Last synced today at $time", text(SyncStatus.Offline(tenThirty)))
        assertEquals("Offline", text(SyncStatus.Offline(null)))
        assertEquals("This server does not sync accounts", text(SyncStatus.Unavailable))
    }

    @Test
    fun `the first sign-in question lists what the phone has`() {
        assertEquals(
            "This phone has 1 stack that your account does not have. Upload it to sync with your other phones, " +
                "or keep it on this phone only. Stacks you make from now on sync.",
            choiceMessage(Unlinked(stacks = 1, reviews = 0, results = 0)),
        )
        assertEquals(true, choiceMessage(Unlinked(stacks = 2, reviews = 3, results = 1)).startsWith(
            "This phone has 2 stacks, 3 finished reviews, and 1 quiz result that",
        ))
        assertEquals(true, choiceMessage(Unlinked(stacks = 0, reviews = 1, results = 2)).startsWith(
            "This phone has 1 finished review and 2 quiz results that",
        ))
    }
}
