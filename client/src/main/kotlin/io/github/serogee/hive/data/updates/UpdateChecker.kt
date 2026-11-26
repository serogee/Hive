package io.github.serogee.hive.data.updates

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PublishedRelease(
    val tag: String,
    val version: ReleaseVersion,
    val prerelease: Boolean,
    val pageUrl: String,
    /** Null when the release has no APK that this app may download. */
    val apkUrl: String?,
)

fun interface ReleaseSource {
    suspend fun latest(includePrereleases: Boolean): PublishedRelease?
}

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class NoRelease(val includePrereleases: Boolean) : UpdateState

    /** [comparison] is above 0 when [release] is newer than this app, 0 when equal, and below 0 when older. */
    data class Result(val release: PublishedRelease, val comparison: Int, val includePrereleases: Boolean) : UpdateState

    data class Failed(val message: String, val reason: UpdateFailure = UpdateFailure.UNKNOWN) : UpdateState
}

enum class UpdateFailure { UNKNOWN, NETWORK, RATE_LIMIT, BLOCKED, NOT_FOUND, UNAVAILABLE, INVALID_RESPONSE, TOO_LARGE, VERSION, NO_SOURCE }

class UpdateCheckException(message: String, val reason: UpdateFailure = UpdateFailure.UNKNOWN) : IOException(message)

/** Checks for a newer release only when the user asks. It never checks by itself and never retries. */
class UpdateChecker(private val source: ReleaseSource?, private val installedVersion: String, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var revision = 0L
    private var activeIncludePrereleases = false

    fun clear() {
        revision++
        request?.cancel()
        request = null
        mutableState.value = UpdateState.Idle
    }

    fun check(includePrereleases: Boolean = false) {
        if (mutableState.value == UpdateState.Checking && activeIncludePrereleases == includePrereleases) return
        clear()
        activeIncludePrereleases = includePrereleases
        val requestRevision = revision
        fun publish(value: UpdateState) {
            // A cleared or newer check owns the state.
            if (revision == requestRevision) mutableState.value = value
        }
        mutableState.value = UpdateState.Checking
        request = scope.launch {
            try {
                val installed = ReleaseVersion.parse(installedVersion.removeSuffix("-debug"))
                    ?: throw UpdateCheckException("This version could not be compared. View the releases on GitHub.", UpdateFailure.VERSION)
                val releases = source
                    ?: throw UpdateCheckException("This app has no release page set up for updates.", UpdateFailure.NO_SOURCE)
                val release = releases.latest(includePrereleases)
                publish(
                    if (release == null) {
                        UpdateState.NoRelease(includePrereleases)
                    } else {
                        UpdateState.Result(release, release.version.compareTo(installed), includePrereleases)
                    },
                )
            } catch (error: CancellationException) {
                publish(UpdateState.Idle)
                throw error
            } catch (error: UpdateCheckException) {
                publish(UpdateState.Failed(error.message.orEmpty(), error.reason))
            } catch (_: IOException) {
                publish(UpdateState.Failed("Could not connect to GitHub. Check your connection and try again.", UpdateFailure.NETWORK))
            } catch (_: RuntimeException) {
                publish(UpdateState.Failed("The answer from GitHub could not be read. Try again later, or view the releases on GitHub.", UpdateFailure.INVALID_RESPONSE))
            }
        }
    }
}
