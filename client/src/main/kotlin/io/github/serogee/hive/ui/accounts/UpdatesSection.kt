package io.github.serogee.hive.ui.accounts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.BuildConfig
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.updates.AndroidApkDownloadBackend
import io.github.serogee.hive.data.updates.ApkDownloadController
import io.github.serogee.hive.data.updates.ApkDownloadFailure
import io.github.serogee.hive.data.updates.ApkDownloadPhase
import io.github.serogee.hive.data.updates.ApkDownloadState
import io.github.serogee.hive.data.updates.ApkTarget
import io.github.serogee.hive.data.updates.GitHubReleaseSource
import io.github.serogee.hive.data.updates.GitHubRepo
import io.github.serogee.hive.data.updates.PublishedRelease
import io.github.serogee.hive.data.updates.UpdateChecker
import io.github.serogee.hive.data.updates.UpdateState
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.ProgressCells
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.theme.Honey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Check for updates, and the download and install of a newer release from GitHub. */
class UpdatesViewModel(context: Context, private val preferences: AppPreferences) : ViewModel() {
    /** Null when the app was built without a valid hive.updatesRepo. */
    val repo: GitHubRepo? = GitHubRepo.parse(BuildConfig.UPDATES_REPO)
    private val checker = UpdateChecker(repo?.let { GitHubReleaseSource(it) }, BuildConfig.VERSION_NAME, viewModelScope)
    private val backend = AndroidApkDownloadBackend(context)
    private val downloads = ApkDownloadController(backend, viewModelScope)

    val state: StateFlow<UpdateState> = checker.state
    val download: StateFlow<ApkDownloadState> = downloads.state
    val includePrereleases = preferences.includePrereleases.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun check() {
        _message.value = null
        checker.check(includePrereleases.value)
    }

    fun setIncludePrereleases(include: Boolean) {
        if (include == includePrereleases.value) return
        // A result of the other choice would be misleading.
        checker.clear()
        viewModelScope.launch { preferences.setIncludePrereleases(include) }
    }

    fun download(release: PublishedRelease) {
        val repo = repo ?: return
        release.apkUrl?.let { downloads.download(ApkTarget(repo, release.tag, it)) }
    }

    fun retry() = downloads.retry()

    fun cancel() {
        downloads.cancel()
    }

    suspend fun observeDownload() = downloads.observe()

    fun tell(text: String) {
        _message.value = text
    }

    /** Checks the APK again, then opens Android's installer with [launch]. */
    fun install(expectedUrl: String, launch: (Intent) -> Unit) {
        if (_preparing.value) return
        _preparing.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                val record = downloads.prepareInstall() ?: return@launch
                if (record.target.url != expectedUrl) {
                    tell("The download changed. Tap Install update again.")
                    return@launch
                }
                launch(withContext(Dispatchers.IO) { backend.installIntent(record) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                tell(INSTALLER_FAILED)
            } finally {
                _preparing.value = false
            }
        }
    }

    companion object {
        const val INSTALLER_FAILED = "The installer could not open. Try again, or open the release notes."
        const val ACCESS_NEEDED = "Allow Hive under Install unknown apps, then tap Install update again."
    }
}

/** The Updates part of Settings. */
@Composable
internal fun UpdatesSection() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel { UpdatesViewModel(context.applicationContext, container.preferences) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val includePrereleases by viewModel.includePrereleases.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val releasesPage = viewModel.repo?.releasesPage

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("No browser could open $url")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption("Updates")
        Note("This is Hive ${BuildConfig.VERSION_NAME}.")
        ToggleRow(label = "Include pre-releases", checked = includePrereleases, onChange = viewModel::setIncludePrereleases)
        Note("Test versions are offered too. They can have problems that releases do not.")
        HiveButton(
            text = if (state == UpdateState.Checking) "Checking" else "Check for updates",
            onClick = viewModel::check,
            enabled = state != UpdateState.Checking,
            modifier = Modifier.fillMaxWidth(),
            style = HiveButtonStyle.SECONDARY,
        )

        when (val result = state) {
            UpdateState.Idle -> Note("Hive checks GitHub only when you tap Check for updates.")
            UpdateState.Checking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Honey)
            is UpdateState.NoRelease -> {
                Note(if (result.includePrereleases) "No published release was found." else "No release was found. Turn on Include pre-releases to see test versions.")
                releasesPage?.let { ViewButton("View releases") { open(it) } }
            }
            is UpdateState.Failed -> {
                Note(result.message)
                releasesPage?.let { ViewButton("View releases") { open(it) } }
            }
            is UpdateState.Result -> {
                Text(
                    when {
                        result.comparison > 0 -> "Update available: ${result.release.tag}"
                        result.comparison == 0 -> "You have the latest: ${result.release.tag}"
                        else -> "This build is newer than ${result.release.tag}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (result.release.prerelease) Note("This is a pre-release.")
                if (result.comparison > 0) {
                    when {
                        BuildConfig.DEBUG -> Note(DEBUG_NOTE)
                        result.release.apkUrl == null -> Note("This release has no Hive APK. Open its release notes to see its files.")
                        download.target?.url != result.release.apkUrl || download == ApkDownloadState.Idle -> HiveButton(
                            text = "Download update",
                            onClick = { viewModel.download(result.release) },
                            enabled = download !is ApkDownloadState.Active,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                ViewButton("View release notes") { open(result.release.pageUrl) }
                releasesPage?.let { ViewButton("View releases") { open(it) } }
            }
        }
        DownloadControls(viewModel)
        message?.let { Note(it) }
    }
}

/** Progress, Cancel, Retry, and Install of the downloaded update. */
@Composable
private fun DownloadControls(viewModel: UpdatesViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.download.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    var permissionTarget by rememberSaveable { mutableStateOf<String?>(null) }

    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Android reports the result of the installation itself.
    }

    fun launchInstaller(intent: Intent) {
        try {
            installer.launch(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.tell(UpdatesViewModel.INSTALLER_FAILED)
        } catch (_: SecurityException) {
            viewModel.tell(UpdatesViewModel.INSTALLER_FAILED)
        }
    }

    // Comes back from Install unknown apps. The user must allow Hive there before Android installs an update.
    val sourceAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val expected = permissionTarget
        permissionTarget = null
        if (expected != null && context.packageManager.canRequestPackageInstalls()) {
            viewModel.install(expected, ::launchInstaller)
        } else if (expected != null) {
            viewModel.tell(UpdatesViewModel.ACCESS_NEEDED)
        }
    }

    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.observeDownload() }
    }

    val download = state
    val target = download.target ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hive ${target.versionName}", style = MaterialTheme.typography.titleSmall)
        when (download) {
            ApkDownloadState.Idle -> Unit
            is ApkDownloadState.Active -> {
                Note(
                    when (download.phase) {
                        ApkDownloadPhase.QUEUED -> "Waiting to download."
                        ApkDownloadPhase.DOWNLOADING -> "Downloading."
                        ApkDownloadPhase.PAUSED -> "Download paused. Android continues when the connection comes back."
                        ApkDownloadPhase.VERIFYING -> "Checking the APK."
                    },
                )
                if (download.total > 0 && download.phase != ApkDownloadPhase.VERIFYING) {
                    val percent = (download.bytes * 100 / download.total).toInt().coerceIn(0, 100)
                    ProgressCells(done = percent, total = 100, modifier = Modifier.fillMaxWidth())
                    Note("$percent%")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Honey)
                }
                HiveButton("Cancel", onClick = viewModel::cancel, enabled = !preparing, style = HiveButtonStyle.SECONDARY)
            }
            is ApkDownloadState.Ready -> {
                Note("Downloaded and checked. Tap Install update to continue.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HiveButton(
                        text = if (preparing) "Checking" else "Install update",
                        enabled = !preparing && permissionTarget == null,
                        onClick = {
                            if (context.packageManager.canRequestPackageInstalls()) {
                                viewModel.install(download.target.url, ::launchInstaller)
                            } else {
                                permissionTarget = download.target.url
                                try {
                                    sourceAccess.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                                } catch (_: ActivityNotFoundException) {
                                    permissionTarget = null
                                    viewModel.tell(UpdatesViewModel.ACCESS_NEEDED)
                                } catch (_: SecurityException) {
                                    permissionTarget = null
                                    viewModel.tell(UpdatesViewModel.ACCESS_NEEDED)
                                }
                            }
                        },
                    )
                    HiveButton("Remove", onClick = viewModel::cancel, enabled = !preparing, style = HiveButtonStyle.SECONDARY)
                }
            }
            is ApkDownloadState.Failed -> {
                Note(download.reason.message())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (download.target?.valid() == true && download.reason != ApkDownloadFailure.DEBUG_BUILD) {
                        HiveButton("Retry", onClick = viewModel::retry, style = HiveButtonStyle.SECONDARY)
                    }
                    HiveButton("Remove", onClick = viewModel::cancel, style = HiveButtonStyle.SECONDARY)
                }
            }
        }
    }
}

private const val DEBUG_NOTE = "This is a debug app, which is signed with another key, so it cannot install updates. Install the release APK from GitHub instead."

internal fun ApkDownloadFailure.message(): String = when (this) {
    ApkDownloadFailure.UNAVAILABLE -> "The download could not start. Check that Android's Download Manager is on, or open the release notes."
    ApkDownloadFailure.STORAGE -> "The APK could not be saved. Check that the phone has free space, then retry."
    ApkDownloadFailure.NETWORK -> "The download failed. Check your connection, then retry."
    ApkDownloadFailure.MISSING -> "The downloaded APK is missing. Download it again."
    ApkDownloadFailure.INVALID_APK -> "The file is not an APK that Android can read. Download it again."
    ApkDownloadFailure.PACKAGE -> "This APK is not a Hive update. Open the release notes to check its files."
    ApkDownloadFailure.VERSION -> "This APK is not the version of the release, or it is not newer than this app."
    ApkDownloadFailure.SIGNATURE -> "This APK is signed with another key than this app. Open the release notes to check its files."
    ApkDownloadFailure.DEBUG_BUILD -> DEBUG_NOTE
    ApkDownloadFailure.UNKNOWN -> "The download failed. Retry, or open the release notes."
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ViewButton(text: String, onClick: () -> Unit) {
    HiveButton(text = text, onClick = onClick, modifier = Modifier.fillMaxWidth(), style = HiveButtonStyle.SECONDARY)
}
