package io.github.serogee.hive.data.updates

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GitHubReleaseSourceTest {
    private val repo = requireNotNull(GitHubRepo.parse("serogee/Hive"))

    private fun release(
        tag: String = "v0.1.1",
        draft: Boolean = false,
        published: Boolean = true,
        url: String? = null,
        prerelease: Boolean = true,
        assets: Boolean = true,
    ): JsonObject {
        val filename = "hive-${tag.removePrefix("v")}.apk"
        val download = url ?: "https://github.com/serogee/Hive/releases/download/$tag/$filename"
        return buildJsonObject {
            put("tag_name", tag)
            put("draft", draft)
            put("prerelease", prerelease)
            put("published_at", if (published) JsonPrimitive("2026-11-26T11:07:54Z") else JsonNull)
            put("html_url", "https://unrelated.example/ignored")
            put(
                "assets",
                if (!assets) {
                    JsonArray(emptyList())
                } else {
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", filename)
                                put("state", "uploaded")
                                put("browser_download_url", download)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun body(vararg releases: JsonObject) = JsonArray(releases.toList()).toString()

    private fun parse(body: String, includePrereleases: Boolean = false) = GitHubReleaseSource.parseReleases(body, repo, includePrereleases)

    @Test
    fun `the highest published version wins in any order`() {
        val latest = requireNotNull(
            parse(
                body(release("v0.1.9"), release("v9.0.0", draft = true), release("v8.0.0", published = false), release("nightly"), release("v0.1.10")),
                includePrereleases = true,
            ),
        )
        assertEquals("v0.1.10", latest.tag)
        assertTrue(latest.prerelease)
        assertEquals("https://github.com/serogee/Hive/releases/tag/v0.1.10", latest.pageUrl)
        assertEquals("https://github.com/serogee/Hive/releases/download/v0.1.10/hive-0.1.10.apk", latest.apkUrl)
    }

    @Test
    fun `a release without an APK and an empty list differ from unreadable data`() {
        assertNull(parse("[]"))
        assertNull(requireNotNull(parse(body(release(assets = false)), includePrereleases = true)).apkUrl)
        listOf("{}", "<html>unavailable</html>").forEach { body ->
            assertFailsWith<UpdateCheckException> { parse(body) }
        }
    }

    @Test
    fun `an APK link must be the file of the same release in this repository`() {
        listOf(
            "http://github.com/serogee/Hive/releases/download/v0.1.1/hive-0.1.1.apk",
            "https://github.com.evil.example/serogee/Hive/releases/download/v0.1.1/hive-0.1.1.apk",
            "https://github.com@evil.example/serogee/Hive/releases/download/v0.1.1/hive-0.1.1.apk",
            "https://github.com/another/repo/releases/download/v0.1.1/hive-0.1.1.apk",
            "https://github.com/serogee/Hive/releases/download/v0.1.0/hive-0.1.1.apk",
            "https://github.com/serogee/Hive/releases/download/v0.1.1/hive-0.1.1.apk?x=1",
        ).forEach { url ->
            val latest = requireNotNull(parse(body(release(url = url)), includePrereleases = true))
            assertNull(latest.apkUrl, url)
            assertEquals("https://github.com/serogee/Hive/releases/tag/v0.1.1", latest.pageUrl)
        }
    }

    @Test
    fun `releases are the default, and pre-releases come only when asked`() {
        val all = body(release("v0.1.0", prerelease = false), release("v0.1.2"), release("v0.1.3-rc.1", prerelease = false))
        assertEquals("v0.1.0", parse(all)?.tag)
        val testing = requireNotNull(parse(all, includePrereleases = true))
        assertEquals("v0.1.3-rc.1", testing.tag)
        assertTrue(testing.prerelease)
        val onlyTesting = body(release())
        assertNull(parse(onlyTesting))
        assertEquals("v0.1.1", parse(onlyTesting, includePrereleases = true)?.tag)
    }

    @Test
    fun `the repository comes from the build and must be owner and name`() {
        assertEquals("https://api.github.com/repos/serogee/Hive/releases?per_page=100", repo.apiUrl)
        assertEquals("https://github.com/serogee/Hive/releases", repo.releasesPage)
        assertNotNull(GitHubRepo.parse("some-one/hive.app"))
        listOf("", "serogee", "serogee/Hive/extra", "../Hive", "serogee/..", "serogee/Hive?x", "https://github.com/serogee/Hive").forEach {
            assertNull(GitHubRepo.parse(it), it)
        }
    }

    @Test
    fun `a check has short timeouts, follows no redirects, and closes the connection`() = runTest {
        val connection = FakeConnection(body(release()))
        val latest = GitHubReleaseSource(repo) { connection }.latest(true)
        assertEquals("v0.1.1", latest?.tag)
        assertTrue(connection.disconnected)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("GET", connection.requestMethod)
        assertEquals("Hive-Android", connection.getRequestProperty("User-Agent"))
    }

    @Test
    fun `limits, a missing repository, and server errors fail and close the connection`() = runTest {
        mapOf(403 to UpdateFailure.RATE_LIMIT, 429 to UpdateFailure.RATE_LIMIT, 404 to UpdateFailure.NOT_FOUND, 500 to UpdateFailure.UNAVAILABLE)
            .forEach { (status, reason) ->
                val connection = FakeConnection("", status)
                try {
                    GitHubReleaseSource(repo) { connection }.latest(true)
                    fail("HTTP $status must not report this version as current")
                } catch (error: UpdateCheckException) {
                    assertEquals(reason, error.reason)
                    assertTrue(error.message.orEmpty().isNotBlank())
                }
                assertTrue(connection.disconnected)
                assertFalse(connection.streamOpened)
            }
        val blocked = FakeConnection("", 403, rateLimited = false)
        assertEquals(UpdateFailure.BLOCKED, assertFailsWith<UpdateCheckException> { GitHubReleaseSource(repo) { blocked }.latest(true) }.reason)

        val timeout = FakeConnection("", failure = SocketTimeoutException("timeout"))
        assertFailsWith<IOException> { GitHubReleaseSource(repo) { timeout }.latest(true) }
        assertTrue(timeout.disconnected)
    }

    @Test
    fun `an answer that is too large or unreadable fails and closes everything`() = runTest {
        mapOf("x".repeat(1_048_577) to UpdateFailure.TOO_LARGE, "not JSON" to UpdateFailure.INVALID_RESPONSE).forEach { (body, reason) ->
            val connection = FakeConnection(body)
            val error = assertFailsWith<UpdateCheckException> { GitHubReleaseSource(repo) { connection }.latest(true) }
            assertEquals(reason, error.reason)
            assertTrue(connection.disconnected)
            assertTrue(connection.streamClosed)
        }
    }

    private class FakeConnection(
        body: String,
        private val status: Int = 200,
        private val failure: IOException? = null,
        private val rateLimited: Boolean = true,
    ) : HttpURLConnection(URI("https://api.github.com/repos/serogee/Hive/releases").toURL()) {
        var disconnected = false
        var streamOpened = false
        var streamClosed = false
        private val headers = mutableMapOf<String, String>()
        private val stream = object : ByteArrayInputStream(body.toByteArray()) {
            override fun close() {
                streamClosed = true
                super.close()
            }
        }

        override fun connect() = Unit

        override fun usingProxy() = false

        override fun disconnect() {
            disconnected = true
        }

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getRequestProperty(key: String): String? = headers[key]

        override fun getResponseCode(): Int {
            failure?.let { throw it }
            return status
        }

        override fun getHeaderField(name: String?): String? = if (name == "X-RateLimit-Remaining" && status == 403 && rateLimited) "0" else null

        override fun getInputStream() = stream.also { streamOpened = true }
    }
}
