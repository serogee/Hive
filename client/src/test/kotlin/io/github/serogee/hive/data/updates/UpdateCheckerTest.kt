package io.github.serogee.hive.data.updates

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {
    private fun release(tag: String = "v0.1.2", prerelease: Boolean = false) =
        PublishedRelease(tag, requireNotNull(ReleaseVersion.parse(tag)), prerelease, "https://github.com/serogee/Hive/releases/tag/$tag", null)

    @Test
    fun `nothing is checked until the tap, and two taps share one request`() = runTest {
        var requests = 0
        val response = CompletableDeferred<PublishedRelease>()
        val checker = UpdateChecker(ReleaseSource { requests++; response.await() }, "0.1.1", backgroundScope)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(UpdateState.Idle, checker.state.value)
        assertEquals(0, requests)

        checker.check()
        checker.check()
        assertEquals(UpdateState.Checking, checker.state.value)
        runCurrent()
        assertEquals(1, requests)
        response.complete(release())
        runCurrent()
        assertTrue(assertIs<UpdateState.Result>(checker.state.value).comparison > 0)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun `the same or a newer build is never offered an older one, and a debug suffix does not count`() = runTest {
        listOf("0.1.2" to 0, "0.1.2-debug" to 0, "0.1.3-debug" to -1, "0.1.1" to 1).forEach { (installed, expected) ->
            val checker = UpdateChecker(ReleaseSource { release() }, installed, backgroundScope)
            checker.check()
            runCurrent()
            val result = assertIs<UpdateState.Result>(checker.state.value)
            assertEquals(expected, result.comparison.coerceIn(-1, 1), installed)
            assertFalse(result.release.prerelease)
        }
    }

    @Test
    fun `a failed check stays failed until the next tap`() = runTest {
        var requests = 0
        val checker = UpdateChecker(ReleaseSource { if (++requests == 1) throw IOException("offline") else release() }, "0.1.1", backgroundScope)
        checker.check()
        runCurrent()
        assertEquals(UpdateFailure.NETWORK, assertIs<UpdateState.Failed>(checker.state.value).reason)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(1, requests)

        checker.check()
        runCurrent()
        assertTrue(assertIs<UpdateState.Result>(checker.state.value).comparison > 0)
        assertEquals(2, requests)
    }

    @Test
    fun `no release, an unknown version, and no repository are not up to date`() = runTest {
        val missing = UpdateChecker(ReleaseSource { null }, "0.1.1", backgroundScope)
        missing.check()
        runCurrent()
        assertEquals(UpdateState.NoRelease(false), missing.state.value)

        val unknown = UpdateChecker(ReleaseSource { error("An unknown version must not start a request") }, "local", backgroundScope)
        unknown.check()
        runCurrent()
        assertEquals(UpdateFailure.VERSION, assertIs<UpdateState.Failed>(unknown.state.value).reason)

        val unset = UpdateChecker(null, "0.1.1", backgroundScope)
        unset.check()
        runCurrent()
        assertEquals(UpdateFailure.NO_SOURCE, assertIs<UpdateState.Failed>(unset.state.value).reason)
    }

    @Test
    fun `a cancelled check is not an error`() = runTest {
        val checkScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val checker = UpdateChecker(ReleaseSource { awaitCancellation() }, "0.1.1", checkScope)
        checker.check()
        runCurrent()
        checkScope.cancel()
        runCurrent()
        assertEquals(UpdateState.Idle, checker.state.value)
    }

    @Test
    fun `changing the pre-release choice clears the result, and an older check cannot overwrite the new one`() = runTest {
        val choices = mutableListOf<Boolean>()
        val stable = CompletableDeferred<PublishedRelease>()
        val testing = CompletableDeferred<PublishedRelease>()
        val checker = UpdateChecker(
            ReleaseSource { include ->
                choices += include
                if (include) testing.await() else withContext(NonCancellable) { stable.await() }
            },
            "0.1.1",
            backgroundScope,
        )
        checker.check()
        runCurrent()
        checker.clear()
        assertEquals(UpdateState.Idle, checker.state.value)
        checker.check(true)
        runCurrent()
        stable.complete(release())
        runCurrent()
        assertEquals(UpdateState.Checking, checker.state.value)
        testing.complete(release("v0.1.3", prerelease = true))
        runCurrent()
        val result = assertIs<UpdateState.Result>(checker.state.value)
        assertEquals("v0.1.3", result.release.tag)
        assertTrue(result.includePrereleases)
        assertEquals(listOf(false, true), choices)
    }
}
