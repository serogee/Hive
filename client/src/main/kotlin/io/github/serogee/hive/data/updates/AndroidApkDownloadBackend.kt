package io.github.serogee.hive.data.updates

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import io.github.serogee.hive.BuildConfig
import io.github.serogee.hive.core.api.HiveJson
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable

/** Downloads with Android's download manager into this app's own folder, and checks the APK before it installs. */
class AndroidApkDownloadBackend(context: Context) : ApkDownloadBackend {
    private val app = context.applicationContext
    private val preferences by lazy { app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val manager: DownloadManager
        get() = app.getSystemService(DownloadManager::class.java) ?: throw ApkDownloadException(ApkDownloadFailure.UNAVAILABLE)

    @Serializable
    private data class SavedRecord(val id: Long, val repo: String, val tag: String, val url: String, val filename: String)

    override val completions: Flow<Long> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) trySend(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
            }
        }
        var registered = false
        try {
            // The download manager runs as another app. A received ID only starts a fresh query.
            ContextCompat.registerReceiver(app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        } catch (_: RuntimeException) {
            // The progress checks on screen still find the end of the download.
        }
        awaitClose { if (registered) runCatching { app.unregisterReceiver(receiver) } }
    }

    override fun restore(): ApkDownloadRecord? {
        val raw = preferences.getString(RECORD_KEY, null) ?: return null
        val record = runCatching {
            require(raw.length <= MAX_RECORD_CHARS)
            val saved = HiveJson.decodeFromString(SavedRecord.serializer(), raw)
            val repo = requireNotNull(GitHubRepo.parse(saved.repo))
            ApkDownloadRecord(saved.id, ApkTarget(repo, saved.tag, saved.url), saved.filename)
                .also { require(it.id >= 0 && it.target.valid()); file(it) }
        }.getOrNull() ?: return null
        // A download of this version or an older one is of no use any more.
        val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        val selected = ReleaseVersion.parse(record.target.tag)
        if (installed != null && selected != null && installed >= selected) {
            remove(record)
            return null
        }
        return record
    }

    override fun enqueue(target: ApkTarget): ApkDownloadRecord {
        require(target.valid())
        val folder = directory()
        if (!folder.isDirectory && !folder.mkdirs()) throw ApkDownloadException(ApkDownloadFailure.STORAGE)
        val filename = "update-${UUID.randomUUID()}.apk"
        val request = DownloadManager.Request(target.url.toUri())
            .setDestinationUri(File(folder, filename).toUri())
            .setMimeType(APK_MIME)
            .setTitle("Hive ${target.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = manager.enqueue(request)
        if (id < 0) throw ApkDownloadException(ApkDownloadFailure.UNAVAILABLE)
        val record = ApkDownloadRecord(id, target, filename)
        val saved = HiveJson.encodeToString(SavedRecord.serializer(), SavedRecord(id, target.repo.path, target.tag, target.url, filename))
        if (!preferences.edit().putString(RECORD_KEY, saved).commit()) {
            manager.remove(id)
            throw ApkDownloadException(ApkDownloadFailure.STORAGE)
        }
        return record
    }

    override fun query(record: ApkDownloadRecord): ApkTransfer {
        manager.query(DownloadManager.Query().setFilterById(record.id))?.use { cursor ->
            if (!cursor.moveToFirst()) return ApkTransfer.Failed(ApkDownloadFailure.MISSING)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_PENDING -> ApkTransfer.Active(ApkDownloadPhase.QUEUED, bytes, total)
                DownloadManager.STATUS_RUNNING -> ApkTransfer.Active(ApkDownloadPhase.DOWNLOADING, bytes, total)
                DownloadManager.STATUS_PAUSED -> ApkTransfer.Active(ApkDownloadPhase.PAUSED, bytes, total)
                DownloadManager.STATUS_SUCCESSFUL -> if (file(record).isFile) ApkTransfer.Complete else ApkTransfer.Failed(ApkDownloadFailure.MISSING)
                else -> ApkTransfer.Failed(
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        DownloadManager.ERROR_DEVICE_NOT_FOUND,
                        DownloadManager.ERROR_FILE_ERROR,
                        DownloadManager.ERROR_FILE_ALREADY_EXISTS,
                        -> ApkDownloadFailure.STORAGE
                        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_HTTP_DATA_ERROR -> ApkDownloadFailure.NETWORK
                        in 400..599 -> ApkDownloadFailure.NETWORK
                        else -> ApkDownloadFailure.UNKNOWN
                    },
                )
            }
        }
        return ApkTransfer.Failed(ApkDownloadFailure.UNAVAILABLE)
    }

    override fun validate(record: ApkDownloadRecord): ApkDownloadFailure? {
        val apk = file(record)
        if (!apk.isFile || apk.length() == 0L) return ApkDownloadFailure.MISSING
        val pm = app.packageManager
        val installed = pm.packageInfo(app.packageName).identity()
        val downloaded = pm.archiveInfo(apk.path)?.identity()
        return checkApkIdentity(installed, downloaded, record.target, BuildConfig.DEBUG)
    }

    override fun remove(record: ApkDownloadRecord?) {
        if (record != null) {
            manager.remove(record.id)
            val apk = file(record)
            if (apk.exists() && !apk.delete()) throw ApkDownloadException(ApkDownloadFailure.STORAGE)
        }
        if (!preferences.edit().remove(RECORD_KEY).commit()) throw ApkDownloadException(ApkDownloadFailure.STORAGE)
    }

    /** Opens Android's installer for a checked download. */
    fun installIntent(record: ApkDownloadRecord): Intent {
        if (query(record) != ApkTransfer.Complete) throw ApkDownloadException(ApkDownloadFailure.MISSING)
        val uri = manager.getUriForDownloadedFile(record.id) ?: throw ApkDownloadException(ApkDownloadFailure.MISSING)
        require(uri.scheme == "content")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("", uri) }
    }

    private fun directory(): File = File(
        app.getExternalFilesDirs(null).firstOrNull() ?: throw ApkDownloadException(ApkDownloadFailure.STORAGE),
        "${Environment.DIRECTORY_DOWNLOADS}/updates",
    )

    private fun file(record: ApkDownloadRecord): File {
        require(Regex("update-[0-9a-f-]{36}\\.apk").matches(record.filename))
        val directory = directory().canonicalFile
        return File(directory, record.filename).canonicalFile.also { require(it.parentFile == directory) }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.packageInfo(name: String): PackageInfo = getPackageInfo(name, SIGNATURE_FLAGS)

    @Suppress("DEPRECATION")
    private fun PackageManager.archiveInfo(path: String): PackageInfo? = getPackageArchiveInfo(path, SIGNATURE_FLAGS)

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity(): ApkIdentity {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners else this.signatures
        return ApkIdentity(
            packageName = packageName,
            versionName = versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            signers = signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet(),
        )
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val PREFERENCES = "apk_download"
        private const val RECORD_KEY = "active"
        private const val MAX_RECORD_CHARS = 8_192

        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}
