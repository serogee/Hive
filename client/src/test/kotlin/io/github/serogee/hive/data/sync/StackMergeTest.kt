package io.github.serogee.hive.data.sync

import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.SyncedStack
import kotlin.test.Test
import kotlin.test.assertEquals

class StackMergeTest {
    private val mine = StackDocument("Mine")
    private val theirs = StackDocument("Theirs")

    private fun local(version: Long?, pending: Boolean, localOnly: Boolean = false, document: StackDocument = mine) =
        LocalStack("s1", document, version, pending, localOnly, updatedAt = 1)

    private fun remote(version: Long, document: StackDocument? = theirs) =
        SyncedStack("s1", version, updatedAt = 2, deleted = document == null, document = document)

    @Test
    fun `a version the phone already has changes nothing`() {
        assertEquals(PullDecision.SKIP, StackMerge.onPulled(local(3, pending = true), remote(3)))
        assertEquals(PullDecision.SKIP, StackMerge.onPulled(local(3, pending = false), remote(2)))
    }

    @Test
    fun `a newer version replaces a copy without changes`() {
        assertEquals(PullDecision.TAKE, StackMerge.onPulled(local(1, pending = false), remote(2)))
        assertEquals(PullDecision.TAKE, StackMerge.onPulled(local(1, pending = false), remote(2, document = null)))
    }

    @Test
    fun `a newer version over changes keeps both`() {
        assertEquals(PullDecision.KEEP_BOTH, StackMerge.onPulled(local(1, pending = true), remote(2)))
        assertEquals(PullDecision.KEEP_BOTH, StackMerge.onPulled(local(1, pending = true), remote(2, document = null)))
        assertEquals(PullDecision.KEEP_BOTH, StackMerge.onPulled(local(null, pending = true), remote(1)))
    }

    @Test
    fun `the same content needs no copy`() {
        assertEquals(PullDecision.TAKE, StackMerge.onPulled(local(1, pending = true, document = theirs), remote(2)))
        assertEquals(PullDecision.TAKE, StackMerge.onPulled(local(null, pending = true, document = theirs), remote(1)))
    }

    @Test
    fun `a stack kept on this phone is never replaced`() {
        assertEquals(PullDecision.KEEP_BOTH, StackMerge.onPulled(local(null, pending = false, localOnly = true), remote(1)))
        assertEquals(PullDecision.SKIP, StackMerge.onPulled(local(null, pending = false, localOnly = true), remote(2, document = null)))
    }

    @Test
    fun `the copy says where it came from`() {
        assertEquals("Biology (from this phone)", StackMerge.copyTitle("Biology"))
        assertEquals("Untitled stack (from this phone)", StackMerge.copyTitle(" "))
    }
}
