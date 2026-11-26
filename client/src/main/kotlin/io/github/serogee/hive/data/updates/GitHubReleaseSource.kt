package io.github.serogee.hive.data.updates

import io.github.serogee.hive.core.api.HiveJson
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The GitHub repository whose releases hold Hive's APKs, such as serogee/Hive. */
@ConsistentCopyVisibility
data class GitHubRepo private constructor(val owner: String, val name: String) {
    val path: String get() = "$owner/$name"
    val releasesPage: String get() = "https://github.com/$path/releases"
    val apiUrl: String get() = "https://api.github.com/repos/$path/releases?per_page=100"

    /** The file that run app release attaches to the release [tag]. */
    fun apkFilename(tag: String): String = "hive-${tag.removePrefix("v").removePrefix("V")}.apk"

    /** Whether [url] is exactly the APK of the release [tag] in this repository. */
    fun isApkUrl(url: String, tag: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.query == null && uri.fragment == null &&
            uri.path == "/$path/releases/download/$tag/${apkFilename(tag)}"
    } catch (_: URISyntaxException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        private val part = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?")

        /** Null when [value] is not owner/name. */
        fun parse(value: String): GitHubRepo? {
            val (owner, name) = value.trim().split('/').takeIf { it.size == 2 } ?: return null
            if (!part.matches(owner) || !part.matches(name) || ".." in name) return null
            return GitHubRepo(owner, name)
        }
    }
}

/** Reads the releases of [repo] from the GitHub API, without an account. */
class GitHubReleaseSource(
    private val repo: GitHubRepo,
    private val connectionFactory: () -> HttpURLConnection = { URI(repo.apiUrl).toURL().openConnection() as HttpURLConnection },
) : ReleaseSource {
    override suspend fun latest(includePrereleases: Boolean): PublishedRelease? = withContext(Dispatchers.IO) {
        val connection = connectionFactory()
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Hive-Android")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            when (val code = connection.responseCode) {
                200 -> Unit
                429 -> throw UpdateCheckException(RATE_LIMITED, UpdateFailure.RATE_LIMIT)
                403 -> {
                    val limited = connection.getHeaderField("X-RateLimit-Remaining") == "0"
                    throw if (limited) {
                        UpdateCheckException(RATE_LIMITED, UpdateFailure.RATE_LIMIT)
                    } else {
                        UpdateCheckException("GitHub blocked the update check. Try again later, or view the releases on GitHub.", UpdateFailure.BLOCKED)
                    }
                }
                404 -> throw UpdateCheckException("Hive's public releases could not be found on GitHub.", UpdateFailure.NOT_FOUND)
                else -> throw if (code >= 500) {
                    UpdateCheckException("GitHub is unavailable. Try again later.", UpdateFailure.UNAVAILABLE)
                } else {
                    UpdateCheckException("GitHub could not complete the update check. View the releases on GitHub.", UpdateFailure.BLOCKED)
                }
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_RESPONSE_CHARS) {
                        throw UpdateCheckException("GitHub's answer was too large. View the releases on GitHub.", UpdateFailure.TOO_LARGE)
                    }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            parseReleases(body, repo, includePrereleases)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 10_000
        private const val MAX_RESPONSE_CHARS = 1_048_576
        private const val RATE_LIMITED = "GitHub's limit for update checks was reached. Try again later."
        private const val UNREADABLE = "GitHub's release data could not be read. Try again later, or view the releases on GitHub."

        /**
         * The newest published release, and its APK when the release has the file that run app release
         * attaches. Drafts, unpublished releases, and tags that are not versions are skipped.
         */
        internal fun parseReleases(body: String, repo: GitHubRepo, includePrereleases: Boolean = false): PublishedRelease? {
            val releases = try {
                HiveJson.parseToJsonElement(body) as? JsonArray
            } catch (_: SerializationException) {
                null
            } ?: throw UpdateCheckException(UNREADABLE, UpdateFailure.INVALID_RESPONSE)

            var latest: PublishedRelease? = null
            for (element in releases) {
                val release = element as? JsonObject ?: continue
                if (release.flag("draft") != false || release.text("published_at").isNullOrBlank()) continue
                val tag = release.text("tag_name") ?: continue
                val version = ReleaseVersion.parse(tag) ?: continue
                val prerelease = release.flag("prerelease") != false || version.isPrerelease
                if (prerelease && !includePrereleases) continue
                val apkUrl = (release["assets"] as? JsonArray).orEmpty().firstNotNullOfOrNull { asset ->
                    val file = asset as? JsonObject ?: return@firstNotNullOfOrNull null
                    val url = file.text("browser_download_url").orEmpty()
                    url.takeIf { file.text("name") == repo.apkFilename(tag) && file.text("state") == "uploaded" && repo.isApkUrl(it, tag) }
                }
                val candidate = PublishedRelease(tag, version, prerelease, "${repo.releasesPage}/tag/$tag", apkUrl)
                val current = latest
                if (current == null || version > current.version || (version == current.version && !prerelease && current.prerelease)) {
                    latest = candidate
                }
            }
            return latest
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull && it.isString }?.contentOrNull

        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
