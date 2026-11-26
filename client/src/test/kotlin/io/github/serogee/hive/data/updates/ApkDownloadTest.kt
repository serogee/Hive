package io.github.serogee.hive.data.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ApkDownloadTest {
    private val repo = requireNotNull(GitHubRepo.parse("serogee/Hive"))
    private val target = ApkTarget(repo, "v0.3.2", "https://github.com/serogee/Hive/releases/download/v0.3.2/hive-0.3.2.apk")
    private val installed = ApkIdentity(HIVE_PACKAGE, "0.3.1", 301, setOf("release-key"))
    private val update = installed.copy(versionName = "0.3.2", versionCode = 302)

    private fun TestScope.controller(backend: Backend) = ApkDownloadController(backend, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun `a newer Hive APK with the same key is accepted`() {
        assertNull(checkApkIdentity(installed, update, target, debugBuild = false))
    }

    @Test
    fun `another package, version, or version code is refused`() {
        assertEquals(ApkDownloadFailure.INVALID_APK, checkApkIdentity(installed, null, target, debugBuild = false))
        assertEquals(ApkDownloadFailure.PACKAGE, checkApkIdentity(installed, update.copy(packageName = "another.app"), target, debugBuild = false))
        listOf(update.copy(versionName = "0.3.3"), update.copy(versionName = null), update.copy(versionCode = 301), update.copy(versionCode = 300))
            .forEach { assertEquals(ApkDownloadFailure.VERSION, checkApkIdentity(installed, it, target, debugBuild = false), it.toString()) }
    }

    @Test
    fun `the signing key must be the key of this app`() {
        listOf(emptySet(), setOf("different-key"), setOf("release-key", "different-key")).forEach {
            assertEquals(ApkDownloadFailure.SIGNATURE, checkApkIdentity(installed, update.copy(signers = it), target, debugBuild = false))
        }
        assertEquals(ApkDownloadFailure.SIGNATURE, checkApkIdentity(installed.copy(signers = emptySet()), update, target, debugBuild = false))
    }

    @Test
    fun `a debug app never installs an update`() {
        assertEquals(ApkDownloadFailure.DEBUG_BUILD, checkApkIdentity(installed, update, target, debugBuild = true))
        assertEquals(ApkDownloadFailure.DEBUG_BUILD, checkApkIdentity(installed.copy(packageName = "$HIVE_PACKAGE.debug"), update, target, debugBuild = false))
    }

    @Test
    fun `only the release's own APK in this repository is a valid target`() {
        assertTrue(target.valid())
        assertTrue(ApkTarget(repo, "v0.3.2+build.1", "https://github.com/serogee/Hive/releases/download/v0.3.2+build.1/hive-0.3.2+build.1.apk").valid())
        listOf(
            target.copy(url = target.url.replace("https:", "http:")),
            target.copy(url = target.url.replace("github.com", "github.com.evil.example")),
            target.copy(url = target.url.replace("hive-0.3.2.apk", "other.apk")),
            target.copy(url = target.url.replace("serogee/Hive", "serogee/Nudge")),
            target.copy(url = target.url + "?download=1"),
            target.copy(url = target.url + "#fragment"),
            target.copy(url = target.url.replace("hive-0.3.2.apk", "%2E%2E%2Fother.apk")),
            target.copy(tag = "../../file"),
            target.copy(url = "not a URL"),
            target.copy(repo = requireNotNull(GitHubRepo.parse("someone/Hive"))),
        ).forEach { assertFalse(it.valid(), it.toString()) }
    }

    @Test
    fun `two taps start one download, and progress shows`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        controller.download(target)
        controller.download(target)
        runCurrent()
        assertEquals(1, backend.enqueues)
        backend.transfer = ApkTransfer.Active(ApkDownloadPhase.DOWNLOADING, 20, 100)
        controller.refresh()
        runCurrent()
        assertEquals(ApkDownloadState.Active(target, ApkDownloadPhase.DOWNLOADING, 20, 100), controller.state.value)
        assertEquals(0, backend.validations)
    }

    @Test
    fun `reopening finds the running download without starting another`() = runTest {
        val backend = Backend()
        controller(backend).download(target)
        runCurrent()
        val second = controller(backend)
        second.download(target)
        runCurrent()
        assertEquals(1, backend.enqueues)
        backend.transfer = ApkTransfer.Complete
        second.refresh()
        runCurrent()
        assertEquals(ApkDownloadState.Ready(target), second.state.value)
        assertEquals(1, backend.validations)
    }

    @Test
    fun `a finished download is checked before it is ready, and again before it installs`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        controller.download(target)
        runCurrent()
        backend.transfer = ApkTransfer.Complete
        controller.refresh()
        runCurrent()
        assertEquals(ApkDownloadState.Ready(target), controller.state.value)
        controller.refresh()
        runCurrent()
        assertEquals(1, backend.validations)

        assertNotNull(controller.prepareInstall())
        assertEquals(2, backend.validations)
        backend.invalid = ApkDownloadFailure.SIGNATURE
        assertNull(controller.prepareInstall())
        assertEquals(ApkDownloadState.Failed(target, ApkDownloadFailure.SIGNATURE), controller.state.value)
    }

    @Test
    fun `a failure stays on screen, and retry replaces only that download`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        controller.download(target)
        runCurrent()
        backend.transfer = ApkTransfer.Failed(ApkDownloadFailure.NETWORK)
        controller.refresh()
        runCurrent()
        assertEquals(ApkDownloadState.Failed(target, ApkDownloadFailure.NETWORK), controller.state.value)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(1, backend.enqueues)

        controller.retry()
        runCurrent()
        assertEquals(2, backend.enqueues)
        assertEquals(listOf(1L), backend.removed)
        assertIs<ApkDownloadState.Active>(controller.state.value)
    }

    @Test
    fun `cancel removes the download and what was saved about it`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        controller.download(target)
        runCurrent()
        controller.cancel()
        runCurrent()
        assertEquals(ApkDownloadState.Idle, controller.state.value)
        assertNull(backend.saved)
        assertEquals(listOf(1L), backend.removed)
        val reopened = controller(backend)
        reopened.refresh()
        runCurrent()
        assertEquals(ApkDownloadState.Idle, reopened.state.value)
    }

    @Test
    fun `progress is read only while on screen, and not after the download ends`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        controller.download(target)
        runCurrent()
        val observer = backgroundScope.launch { controller.observe() }
        runCurrent()
        assertEquals(1, backend.completions.subscriptionCount.value)
        val start = backend.queries
        advanceTimeBy(2_001)
        runCurrent()
        assertTrue(backend.queries > start)

        observer.cancelAndJoin()
        assertEquals(0, backend.completions.subscriptionCount.value)
        val hidden = backend.queries
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(hidden, backend.queries)

        backend.transfer = ApkTransfer.Complete
        val resumed = backgroundScope.launch { controller.observe() }
        runCurrent()
        assertIs<ApkDownloadState.Ready>(controller.state.value)
        val completed = backend.queries
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(completed, backend.queries)
        resumed.cancelAndJoin()
    }

    @Test
    fun `a new release cannot replace a running download`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        controller.download(target)
        runCurrent()
        controller.download(ApkTarget(repo, "v0.3.3", "https://github.com/serogee/Hive/releases/download/v0.3.3/hive-0.3.3.apk"))
        runCurrent()
        assertEquals(1, backend.enqueues)
        assertEquals(target, controller.state.value.target)
    }

    @Test
    fun `an invalid link never reaches the download manager`() = runTest {
        val backend = Backend()
        val controller = controller(backend)
        val invalid = target.copy(url = "https://unrelated.example/file.apk")
        controller.download(invalid)
        runCurrent()
        assertEquals(0, backend.enqueues)
        assertEquals(ApkDownloadState.Failed(invalid, ApkDownloadFailure.UNAVAILABLE), controller.state.value)
    }

    @Test
    fun `storage errors and missing files are not success`() = runTest {
        val backend = Backend()
        backend.startError = ApkDownloadFailure.STORAGE
        val controller = controller(backend)
        controller.download(target)
        runCurrent()
        assertEquals(ApkDownloadState.Failed(target, ApkDownloadFailure.STORAGE), controller.state.value)
        backend.startError = null
        controller.retry()
        runCurrent()
        backend.transfer = ApkTransfer.Failed(ApkDownloadFailure.MISSING)
        assertNull(controller.prepareInstall())
        assertEquals(ApkDownloadState.Failed(target, ApkDownloadFailure.MISSING), controller.state.value)
    }

    private class Backend : ApkDownloadBackend {
        override val completions = MutableSharedFlow<Long>()
        var saved: ApkDownloadRecord? = null
        var enqueues = 0
        var queries = 0
        var validations = 0
        val removed = mutableListOf<Long>()
        var transfer: ApkTransfer = ApkTransfer.Active(ApkDownloadPhase.QUEUED)
        var invalid: ApkDownloadFailure? = null
        var startError: ApkDownloadFailure? = null

        override fun restore() = saved

        override fun enqueue(target: ApkTarget): ApkDownloadRecord {
            startError?.let { throw ApkDownloadException(it) }
            enqueues++
            transfer = ApkTransfer.Active(ApkDownloadPhase.QUEUED)
            return ApkDownloadRecord(enqueues.toLong(), target, "test.apk").also { saved = it }
        }

        override fun query(record: ApkDownloadRecord): ApkTransfer {
            queries++
            return transfer
        }

        override fun validate(record: ApkDownloadRecord): ApkDownloadFailure? {
            validations++
            return invalid
        }

        override fun remove(record: ApkDownloadRecord?) {
            record?.let { removed += it.id }
            saved = null
        }
    }
}
