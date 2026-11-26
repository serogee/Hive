package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.PushOutcome
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.model.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The same rules for every database. [alex] and [sam] are saved accounts. */
abstract class SyncStoreContract {
    protected abstract val store: SyncStore
    protected abstract val alex: AccountId
    protected abstract val sam: AccountId

    private fun doc(title: String) = StackDocument(title, "About $title")

    private fun record(id: String, at: Long = 1) = SyncRecord(id, at, buildJsonObject { put("score", JsonPrimitive(id.length)) })

    private suspend fun push(account: AccountId, vararg changes: StackChange, generation: Long = 0) =
        store.pushStacks(account, generation, changes.toList(), now = 1_000)

    @Test
    fun pagesOfChangesFollowTheCursorWithoutGapsOrRepeats() = runBlocking {
        repeat(5) { push(alex, StackChange("s$it", document = doc("Stack $it"))) }
        store.append(alex, RecordKind.REVIEW, listOf(record("r1"), record("r2")))
        store.append(alex, RecordKind.RESULT, listOf(record("x1")))

        val seen = mutableListOf<String>()
        var cursor = 0L
        var pages = 0
        do {
            val page = store.changes(alex, cursor, limit = 3)
            seen += page.stacks.map { it.stackId } + page.reviews.map { it.id } + page.results.map { it.id }
            assertTrue(page.cursor > cursor)
            cursor = page.cursor
            pages++
        } while (page.more)

        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "r1", "r2", "x1"), seen)
        assertEquals(3, pages)
        val after = store.changes(alex, cursor, limit = 3)
        assertEquals(cursor, after.cursor)
        assertTrue(after.stacks.isEmpty() && after.reviews.isEmpty() && !after.more)
    }

    @Test
    fun aChangedStackComesBackOnceWithItsNewVersion() = runBlocking {
        push(alex, StackChange("s1", document = doc("Biology")))
        val start = store.changes(alex, 0, 100).cursor
        val result = push(alex, StackChange("s1", baseVersion = 1, document = doc("Biology 2"))).results.single()

        assertEquals(PushOutcome.APPLIED, result.outcome)
        assertEquals(2, result.stack.version)
        val changes = store.changes(alex, 0, 100)
        assertEquals(listOf("Biology 2"), changes.stacks.map { it.document?.title })
        assertTrue(changes.cursor > start)
    }

    @Test
    fun aDeletedStackStaysAsATombstone() = runBlocking {
        push(alex, StackChange("s1", document = doc("Biology")))
        val cursor = store.changes(alex, 0, 100).cursor
        push(alex, StackChange("s1", baseVersion = 1, deleted = true))

        val tombstone = store.changes(alex, cursor, 100).stacks.single()
        assertTrue(tombstone.deleted)
        assertNull(tombstone.document)
        assertEquals(2, tombstone.version)
    }

    @Test
    fun aConflictKeepsTheAccountCopyAndSendsItBack() = runBlocking {
        push(alex, StackChange("s1", document = doc("Biology")))
        push(alex, StackChange("s1", baseVersion = 1, document = doc("From the first phone")))
        val result = push(alex, StackChange("s1", baseVersion = 1, document = doc("From the second phone"))).results.single()

        assertEquals(PushOutcome.CONFLICT, result.outcome)
        assertEquals("From the first phone", result.stack.document?.title)
        assertEquals(2, result.stack.version)
        assertEquals("From the first phone", store.changes(alex, 0, 100).stacks.single().document?.title)
    }

    @Test
    fun recordsAreStoredOnceWhenTheyAreSentAgain() = runBlocking {
        assertEquals(2, store.append(alex, RecordKind.REVIEW, listOf(record("r1"), record("r2"))))
        assertEquals(1, store.append(alex, RecordKind.REVIEW, listOf(record("r2"), record("r3"), record("r3"))))
        // The same ID of another kind is another record.
        assertEquals(1, store.append(alex, RecordKind.RESULT, listOf(record("r1"))))

        val changes = store.changes(alex, 0, 100)
        assertEquals(listOf("r1", "r2", "r3"), changes.reviews.map { it.id })
        assertEquals(listOf("r1"), changes.results.map { it.id })
    }

    @Test
    fun anAccountSeesOnlyItsOwnData() = runBlocking {
        push(alex, StackChange("s1", document = doc("Alex's stack")))
        store.append(alex, RecordKind.REVIEW, listOf(record("r1")))
        // The same stack ID in another account is another stack.
        val sams = push(sam, StackChange("s1", document = doc("Sam's stack"))).results.single()

        assertEquals(1, sams.stack.version)
        val forSam = store.changes(sam, 0, 100)
        assertEquals(listOf("Sam's stack"), forSam.stacks.map { it.document?.title })
        assertTrue(forSam.reviews.isEmpty())
        assertEquals(listOf("Alex's stack"), store.changes(alex, 0, 100).stacks.map { it.document?.title })

        store.deleteAll(sam)
        assertEquals(1, store.changes(alex, 0, 100).stacks.size)
    }

    @Test
    fun deletingTheAccountDataStartsANewGeneration() = runBlocking {
        push(alex, StackChange("s1", document = doc("Biology")))
        store.append(alex, RecordKind.REVIEW, listOf(record("r1")))
        store.deleteAll(alex)

        val changes = store.changes(alex, 0, 100)
        assertEquals(1, changes.generation)
        assertTrue(changes.stacks.isEmpty() && changes.reviews.isEmpty())

        // A phone that has not seen the deletion changes nothing.
        val stale = push(alex, StackChange("s1", baseVersion = 1, document = doc("Old phone")), generation = 0)
        assertTrue(stale.results.isEmpty())
        assertEquals(1, stale.generation)
        assertTrue(store.changes(alex, 0, 100).stacks.isEmpty())

        val fresh = push(alex, StackChange("s1", document = doc("Biology")), generation = 1)
        assertEquals(PushOutcome.APPLIED, fresh.results.single().outcome)
        assertFalse(store.changes(alex, 0, 100).more)
        // A record sent before the deletion can come again.
        assertEquals(1, store.append(alex, RecordKind.REVIEW, listOf(record("r1"))))
    }
}
