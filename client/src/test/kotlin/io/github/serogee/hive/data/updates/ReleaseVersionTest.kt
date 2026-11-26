package io.github.serogee.hive.data.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {
    private fun version(value: String) = requireNotNull(ReleaseVersion.parse(value))

    @Test
    fun `versions compare by number, and build metadata is not an update`() {
        assertTrue(version("v0.1.10") > version("0.1.9"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertEquals(version("v0.1.1"), version("0.1.1+build.9"))
        assertEquals(0, version("0.1.1").compareTo(version("V0.1.1")))
    }

    @Test
    fun `a release follows its pre-releases, and numbers in pre-releases compare as numbers`() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta", "1.0.0-rc.2", "1.0.0-rc.10", "1.0.0")
        versions.zipWithNext().forEach { (older, newer) -> assertTrue(version(newer) > version(older), "$newer must follow $older") }
        assertTrue(version("1.0.0-rc.999999999999999999999") > version("1.0.0-rc.10"))
    }

    @Test
    fun `tags that are not versions cannot be compared`() {
        listOf("latest", "0.1", "0.1.1.2", "00.1.1", "0.1.1-01", "0.1.1-", "0.1.1/evil", "999999999999.1.1", " v0.1.1").forEach {
            assertNull(ReleaseVersion.parse(it), it)
        }
    }
}
