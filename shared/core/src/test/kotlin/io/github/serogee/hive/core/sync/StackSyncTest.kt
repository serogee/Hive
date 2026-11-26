package io.github.serogee.hive.core.sync

import io.github.serogee.hive.core.api.PushOutcome
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.SyncedStack
import kotlin.test.Test
import kotlin.test.assertEquals

class StackSyncTest {
    private val first = StackDocument("Biology")
    private val second = StackDocument("Biology 2")
    private val stored = SyncedStack("s1", version = 3, updatedAt = 10, document = first)

    @Test
    fun `a new stack starts at version 1`() {
        assertEquals(
            StackDecision.Apply(SyncedStack("s1", 1, 50, document = first)),
            StackSync.decide(null, StackChange("s1", null, document = first), now = 50),
        )
    }

    @Test
    fun `a change to the latest version is applied`() {
        assertEquals(
            StackDecision.Apply(SyncedStack("s1", 4, 50, document = second)),
            StackSync.decide(stored, StackChange("s1", 3, document = second), now = 50),
        )
    }

    @Test
    fun `a change to an older version is a conflict, and the account keeps its copy`() {
        assertEquals(
            StackDecision.Keep(stored, PushOutcome.CONFLICT),
            StackSync.decide(stored, StackChange("s1", 2, document = second), now = 50),
        )
        assertEquals(
            StackDecision.Keep(stored, PushOutcome.CONFLICT),
            StackSync.decide(stored, StackChange("s1", null, document = second), now = 50),
        )
    }

    @Test
    fun `a change sent again is not a conflict`() {
        assertEquals(StackDecision.Keep(stored, PushOutcome.APPLIED), StackSync.decide(stored, StackChange("s1", 2, document = first), now = 50))
    }

    @Test
    fun `a deletion leaves a tombstone without the document`() {
        assertEquals(
            StackDecision.Apply(SyncedStack("s1", 4, 50, deleted = true)),
            StackSync.decide(stored, StackChange("s1", 3, deleted = true, document = first), now = 50),
        )
        val tombstone = SyncedStack("s1", 4, 50, deleted = true)
        assertEquals(StackDecision.Keep(tombstone, PushOutcome.APPLIED), StackSync.decide(tombstone, StackChange("s1", 3, deleted = true), now = 60))
        assertEquals(StackDecision.Keep(tombstone, PushOutcome.CONFLICT), StackSync.decide(tombstone, StackChange("s1", 3, document = second), now = 60))
    }
}
