package io.github.serogee.hive.data.rooms

import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.localhost.NearbyService
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RoomFinderTest {
    private val phone = "http://192.168.43.1:8090"

    private fun room(id: String) = RoomSummary(
        id = RoomId(id),
        code = "ABC234",
        title = "Friday quiz",
        hostName = "Ms. Reyes",
        mode = RoomMode.DISCUSSION,
        admission = AdmissionPolicy.EVERYONE,
        status = RoomStatus.LOBBY,
        questionCount = 3,
    )

    @Test
    fun `a room nearby is found while the server cannot be reached`() = runTest {
        val finder = RoomFinder(
            lookup = { _, address -> if (address == null) throw IOException("offline") else room("near") },
            nearby = {
                delay(2_000)
                phone
            },
        )
        assertEquals(FoundRoom(room("near"), phone), finder.find("ABC234"))
    }

    @Test
    fun `a room online does not wait for the search nearby`() = runTest {
        val finder = RoomFinder(
            lookup = { _, _ ->
                delay(100)
                room("online")
            },
            nearby = { awaitCancellation() },
        )
        assertEquals(FoundRoom(room("online"), null), finder.find("ABC234"))
        assertEquals(100, currentTime)
    }

    @Test
    fun `a room online is found after the search nearby finds nothing`() = runTest {
        val finder = RoomFinder(
            lookup = { _, _ ->
                delay(8_000)
                room("online")
            },
            nearby = { null },
        )
        assertEquals(FoundRoom(room("online"), null), finder.find("ABC234"))
    }

    @Test
    fun `a phone that does not answer does not hide the room online`() = runTest {
        val finder = RoomFinder(
            lookup = { _, address ->
                if (address != null) throw IOException("gone")
                delay(1_000)
                room("online")
            },
            nearby = { phone },
        )
        assertEquals(FoundRoom(room("online"), null), finder.find("ABC234"))
    }

    @Test
    fun `without a room, the failure of the server is shown`() = runTest {
        val finder = RoomFinder(lookup = { _, _ -> throw IOException("offline") }, nearby = { null })
        assertEquals("offline", assertFailsWith<IOException> { finder.find("ABC234") }.message)
    }

    @Test
    fun `a typed address is the only place to look`() = runTest {
        val finder = RoomFinder(
            lookup = { _, address -> room(checkNotNull(address)) },
            nearby = { error("must not search") },
        )
        assertEquals(FoundRoom(room(phone), phone), finder.find("ABC234", phone))
    }

    @Test
    fun `typed addresses get a scheme and a port`() {
        assertEquals(phone, HostAddress.normalize("192.168.43.1"))
        assertEquals(phone, HostAddress.normalize(" 192.168.43.1:8090 "))
        assertEquals(phone, HostAddress.normalize("http://192.168.43.1:8090/"))
        assertEquals("https://quiz.example.com", HostAddress.normalize("https://quiz.example.com/"))
        assertEquals("https://quiz.example.com:8443/sub", HostAddress.normalize("https://quiz.example.com:8443/sub/"))
        assertEquals("http://192.168.43.1:8090/foo", HostAddress.normalize("192.168.43.1/foo/"))
        assertEquals("http://httpbin.org:8090", HostAddress.normalize("httpbin.org"))
        assertNull(HostAddress.normalize("  "))
        assertNull(HostAddress.normalize("http://"))
    }

    @Test
    fun `server urls normalize schemes and reject blank or hostless input`() {
        assertNull(HostAddress.normalizeServerUrl(""))
        assertNull(HostAddress.normalizeServerUrl("   "))
        assertNull(HostAddress.normalizeServerUrl("http://"))
        assertEquals("http://httpbin.org", HostAddress.normalizeServerUrl("httpbin.org"))
        assertEquals("http://10.0.2.2:8080", HostAddress.normalizeServerUrl(" 10.0.2.2:8080/ "))
        assertEquals("https://hive.example.com", HostAddress.normalizeServerUrl("HTTPS://hive.example.com/"))
    }

    @Test
    fun `a name that Android changed still belongs to its code`() {
        val name = NearbyService.nameFor("ABC234")
        assertTrue(NearbyService.mayBe(name, "ABC234"))
        assertTrue(NearbyService.mayBe("$name (2)", "ABC234"))
        assertFalse(NearbyService.mayBe(name, "XYZ789"))
    }
}
