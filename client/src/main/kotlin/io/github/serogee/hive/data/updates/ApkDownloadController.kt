package io.github.serogee.hive.data.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The APK of one release. Only the exact file that run app release publishes in [repo] is [valid]. */
data class ApkTarget(val repo: GitHubRepo, val tag: String, val url: String) {
    val versionName: String get() = tag.removePrefix("v").removePrefix("V")

    fun valid(): Boolean = ReleaseVersion.parse(tag) != null && repo.isApkUrl(url, tag)
}

/** What a downloaded APK says about itself, or what the installed app says. [signers] are certificate hashes. */
data class ApkIdentity(val packageName: String, val versionName: String?, val versionCode: Long, val signers: Set<String>)

const val HIVE_PACKAGE = "io.github.serogee.hive"

/**
 * Null when [downloaded] may update [installed]: the same package, the version of [target] with a higher
 * version code, and the same signing certificates. A debug app is signed with another key, so it never updates.
 */
internal fun checkApkIdentity(installed: ApkIdentity, downloaded: ApkIdentity?, target: ApkTarget, debugBuild: Boolean): ApkDownloadFailure? = when {
    debugBuild || installed.packageName != HIVE_PACKAGE -> ApkDownloadFailure.DEBUG_BUILD
    downloaded == null -> ApkDownloadFailure.INVALID_APK
    downloaded.packageName != installed.packageName -> ApkDownloadFailure.PACKAGE
    downloaded.versionName != target.versionName || downloaded.versionCode <= installed.versionCode -> ApkDownloadFailure.VERSION
    installed.signers.isEmpty() || downloaded.signers != installed.signers -> ApkDownloadFailure.SIGNATURE
    else -> null
}

data class ApkDownloadRecord(val id: Long, val target: ApkTarget, val filename: String)

enum class ApkDownloadPhase { QUEUED, DOWNLOADING, PAUSED, VERIFYING }

enum class ApkDownloadFailure { UNAVAILABLE, STORAGE, NETWORK, MISSING, INVALID_APK, PACKAGE, VERSION, SIGNATURE, DEBUG_BUILD, UNKNOWN }

class ApkDownloadException(val reason: ApkDownloadFailure) : Exception()

/** What Android's download manager says about a download. */
sealed interface ApkTransfer {
    data class Active(val phase: ApkDownloadPhase, val bytes: Long = 0, val total: Long = -1) : ApkTransfer

    data object Complete : ApkTransfer

    data class Failed(val reason: ApkDownloadFailure) : ApkTransfer
}

sealed interface ApkDownloadState {
    val target: ApkTarget? get() = null

    data object Idle : ApkDownloadState

    data class Active(override val target: ApkTarget, val phase: ApkDownloadPhase, val bytes: Long = 0, val total: Long = -1) : ApkDownloadState

    /** Downloaded and checked. Installing still needs the user's tap. */
    data class Ready(override val target: ApkTarget) : ApkDownloadState

    data class Failed(override val target: ApkTarget?, val reason: ApkDownloadFailure) : ApkDownloadState
}

interface ApkDownloadBackend {
    val completions: Flow<Long>

    fun restore(): ApkDownloadRecord?

    fun enqueue(target: ApkTarget): ApkDownloadRecord

    fun query(record: ApkDownloadRecord): ApkTransfer

    fun validate(record: ApkDownloadRecord): ApkDownloadFailure?

    fun remove(record: ApkDownloadRecord?)
}

/**
 * Owns one download. Android downloads the file, so it continues when the app closes. Progress is read only
 * while the update section is on screen.
 */
class ApkDownloadController(
    private val backend: ApkDownloadBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val state: StateFlow<ApkDownloadState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false
    @Volatile
    private var record: ApkDownloadRecord? = null
    private var refreshJob: Job? = null

    fun download(target: ApkTarget) = scope.launch {
        mutex.withLock {
            try {
                restore()
                if (record != null) refreshLocked()
                val current = mutableState.value
                if (current is ApkDownloadState.Active || (current is ApkDownloadState.Ready && record?.target == target)) return@withLock
                if (!target.valid()) {
                    mutableState.value = ApkDownloadState.Failed(target, ApkDownloadFailure.UNAVAILABLE)
                    return@withLock
                }
                mutableState.value = ApkDownloadState.Active(target, ApkDownloadPhase.QUEUED)
                withContext(io) { backend.remove(record) }
                record = null
                record = withContext(io) { backend.enqueue(target) }
                refreshLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = ApkDownloadState.Failed(target, (e as? ApkDownloadException)?.reason ?: ApkDownloadFailure.UNAVAILABLE)
            }
        }
    }

    fun retry() {
        mutableState.value.target?.let(::download)
    }

    fun cancel() = scope.launch {
        mutex.withLock {
            try {
                restore()
                withContext(io) { backend.remove(record) }
                record = null
                mutableState.value = ApkDownloadState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = ApkDownloadState.Failed(record?.target, ApkDownloadFailure.STORAGE)
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            mutex.withLock {
                try {
                    restore()
                    refreshLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableState.value = ApkDownloadState.Failed(record?.target, ApkDownloadFailure.UNAVAILABLE)
                }
            }
        }
    }

    /** Follows the download while the caller runs, such as while the update section is on screen. */
    suspend fun observe(): Unit = coroutineScope {
        launch { backend.completions.collect { id -> if (id == record?.id) refresh() } }
        refresh()
        state.map { it is ApkDownloadState.Active }.distinctUntilChanged().collectLatest { active ->
            if (active) {
                while (isActive) {
                    delay(PROGRESS_MILLIS)
                    refresh()
                }
            }
        }
    }

    /** Checks the file again at the Install tap, also after the user returns from the install permission. */
    suspend fun prepareInstall(): ApkDownloadRecord? = mutex.withLock {
        try {
            restore()
            refreshLocked(forceValidation = true)
            record.takeIf { mutableState.value is ApkDownloadState.Ready }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = ApkDownloadState.Failed(record?.target, ApkDownloadFailure.INVALID_APK)
            null
        }
    }

    private suspend fun restore() {
        if (!loaded) {
            record = withContext(io) { backend.restore() }
            loaded = true
        }
    }

    private suspend fun refreshLocked(forceValidation: Boolean = false) {
        val current = record ?: return
        when (val transfer = withContext(io) { backend.query(current) }) {
            is ApkTransfer.Active -> mutableState.value = ApkDownloadState.Active(current.target, transfer.phase, transfer.bytes, transfer.total)
            is ApkTransfer.Failed -> mutableState.value = ApkDownloadState.Failed(current.target, transfer.reason)
            ApkTransfer.Complete -> {
                if (!forceValidation && mutableState.value is ApkDownloadState.Ready) return
                mutableState.value = ApkDownloadState.Active(current.target, ApkDownloadPhase.VERIFYING)
                val failure = withContext(io) { backend.validate(current) }
                mutableState.value = if (failure == null) ApkDownloadState.Ready(current.target) else ApkDownloadState.Failed(current.target, failure)
            }
        }
    }

    private companion object {
        const val PROGRESS_MILLIS = 1_000L
    }
}
