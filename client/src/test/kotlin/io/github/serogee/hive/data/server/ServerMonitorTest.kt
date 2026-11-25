package io.github.serogee.hive.data.server

import io.github.serogee.hive.core.api.API_VERSION
import io.github.serogee.hive.core.api.HealthAuth
import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.core.api.HealthResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ServerMonitorTest {
    private val healthy = HealthResponse(HealthResponse.OK, API_VERSION, HealthMode.PRODUCTION, HealthAuth.CONFIGURED)
    private val url = MutableStateFlow("https://hive.example")
    private var answer: suspend () -> HealthResponse = { healthy }
    private var checks = 0

    private fun TestScope.monitor() = ServerMonitor(
        serverUrl = url,
        check = {
            checks++
            answer()
        },
        scope = backgroundScope,
        now = { currentTime + 1_000_000 },
    )

    @Test
    fun `starts with a check and says what the server is`() = runTest {
        answer = {
            delay(100)
            healthy
        }
        val monitor = monitor()
        runCurrent()
        assertEquals(ServerState.CHECKING, monitor.status.value.state)

        advanceTimeBy(200)
        assertEquals(ServerStatus(ServerState.ONLINE, HealthMode.PRODUCTION, HealthAuth.CONFIGURED), monitor.status.value)
    }

    @Test
    fun `without an address the server is not set up, and nothing is checked`() = runTest {
        url.value = " "
        val monitor = monitor()
        runCurrent()
        assertEquals(ServerState.NOT_CONFIGURED, monitor.status.value.state)
        assertEquals(0, checks)

        url.value = "https://hive.example"
        runCurrent()
        assertEquals(ServerState.ONLINE, monitor.status.value.state)

        url.value = ""
        runCurrent()
        assertEquals(ServerState.NOT_CONFIGURED, monitor.status.value.state)
    }

    @Test
    fun `a failure, a wrong answer, or no answer makes the server unreachable`() = runTest {
        val monitor = monitor()
        runCurrent()

        answer = { throw IOException("offline") }
        monitor.checkNow()
        runCurrent()
        assertEquals(ServerState.UNREACHABLE, monitor.status.value.state)

        answer = { healthy }
        monitor.checkNow()
        runCurrent()
        assertEquals(ServerState.ONLINE, monitor.status.value.state)

        answer = { healthy.copy(status = "starting") }
        monitor.checkNow()
        runCurrent()
        assertEquals(ServerState.UNREACHABLE, monitor.status.value.state)

        answer = { healthy }
        monitor.checkNow()
        runCurrent()
        answer = { awaitCancellation() }
        monitor.checkNow()
        runCurrent()
        assertEquals(ServerState.ONLINE, monitor.status.value.state, "keeps its state until the answer is late")
        advanceTimeBy(ServerMonitor.TIMEOUT_MILLIS + 1)
        assertEquals(ServerState.UNREACHABLE, monitor.status.value.state)
    }

    @Test
    fun `Retry shows that the server is being checked`() = runTest {
        answer = { throw IOException("offline") }
        val monitor = monitor()
        runCurrent()
        answer = {
            delay(100)
            healthy
        }
        monitor.retry()
        runCurrent()
        assertEquals(ServerState.CHECKING, monitor.status.value.state)
        advanceTimeBy(200)
        assertEquals(ServerState.ONLINE, monitor.status.value.state)
    }

    @Test
    fun `checks regularly only while a screen watches`() = runTest {
        val monitor = monitor()
        runCurrent()
        assertEquals(1, checks)
        advanceTimeBy(10 * ServerMonitor.INTERVAL_MILLIS)
        assertEquals(1, checks)

        monitor.startWatching()
        runCurrent()
        assertEquals(2, checks, "the last answer is old")
        advanceTimeBy(ServerMonitor.INTERVAL_MILLIS + 1)
        assertEquals(3, checks)

        monitor.stopWatching()
        advanceTimeBy(10 * ServerMonitor.INTERVAL_MILLIS)
        assertEquals(3, checks)
    }

    @Test
    fun `checks less often while the server stays down`() = runTest {
        answer = { throw IOException("offline") }
        val monitor = monitor()
        monitor.startWatching()
        runCurrent()
        val start = checks
        val at = mutableListOf<Long>()
        answer = {
            at += currentTime
            throw IOException("offline")
        }
        advanceTimeBy(200_000)
        val pauses = at.zipWithNext { a, b -> b - a }
        assertEquals(true, start >= 1)
        assertEquals(pauses.sorted(), pauses)
        assertEquals(ServerMonitor.LONGEST_PAUSE_MILLIS, pauses.last())
        assertEquals(listOf(45_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..6).map(ServerMonitor::pauseAfter))
    }

    @Test
    fun `another address is checked at once`() = runTest {
        val monitor = monitor()
        runCurrent()
        answer = { throw IOException("offline") }
        url.value = "https://other.example"
        runCurrent()
        assertEquals(ServerState.UNREACHABLE, monitor.status.value.state)
        assertEquals(2, checks)
    }
}
