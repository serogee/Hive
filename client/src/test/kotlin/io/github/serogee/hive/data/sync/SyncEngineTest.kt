package io.github.serogee.hive.data.sync

import io.github.serogee.hive.data.server.ServerState
import io.github.serogee.hive.data.server.ServerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SyncEngineTest {
    private val account = FakeAccount()

    /** A phone that signed in with nothing of its own, so it is not asked anything. */
    private suspend fun signedIn() = Device(account).also { it.sync() }

    @Test
    fun `coming back to the foreground syncs at most every 30 seconds`() = runTest {
        val phone = Device(account)
        assertTrue(phone.engine.onForeground())
        phone.clock += 29_000
        assertFalse(phone.engine.onForeground())
        phone.clock += 1_000
        assertTrue(phone.engine.onForeground())
    }

    @Test
    fun `a guest never syncs`() = runTest {
        val guest = Device(account, account = null)
        guest.phone.create("s1", "Biology")
        guest.sync()

        assertEquals(SyncStatus.Off, guest.engine.status.value)
        assertTrue(account.stacks.isEmpty())
        assertNull(guest.states.state)
    }

    @Test
    fun `the first sign-in asks once, and upload sends what the phone had`() = runTest {
        val ana = Device(account)
        ana.phone.create("s1", "Biology")
        ana.phone.finishReview("r1")

        ana.sync()
        assertEquals(Unlinked(stacks = 1, reviews = 1, results = 0), ana.engine.choice.value)
        assertTrue(account.stacks.isEmpty())

        ana.engine.choose(upload = true)
        ana.sync()
        assertNull(ana.engine.choice.value)
        assertEquals(listOf("Biology"), account.titles())
        assertEquals(listOf("r1"), account.reviewIds())
        assertIs<SyncStatus.UpToDate>(ana.engine.status.value)

        ana.phone.create("s2", "Chemistry")
        ana.sync()
        assertNull(ana.engine.choice.value)
        assertEquals(listOf("Biology", "Chemistry"), account.titles())
    }

    @Test
    fun `keep on this phone leaves the old stacks out, and new ones sync`() = runTest {
        val ana = Device(account)
        ana.phone.create("s1", "Old notes")
        ana.sync()
        ana.engine.choose(upload = false)
        ana.phone.create("s2", "Biology")
        ana.sync()

        assertEquals(listOf("Biology"), account.titles())
        assertEquals(listOf("Biology", "Old notes"), ana.phone.titles())
        // An edit to a stack kept on this phone stays there.
        ana.phone.edit("s1", "Old notes 2")
        ana.sync()
        assertEquals(listOf("Biology"), account.titles())
    }

    @Test
    fun `stacks, edits, and deletions reach the other phone`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.sync()
        second.sync()
        assertEquals(listOf("Biology"), second.phone.titles())

        first.phone.edit("s1", "Biology 2")
        first.sync()
        second.sync()
        assertEquals(listOf("Biology 2"), second.phone.titles())

        second.phone.delete("s1")
        second.sync()
        first.sync()
        assertTrue(first.phone.stacks.isEmpty())
        assertTrue(account.titles().isEmpty())
        assertTrue(second.phone.deletions.isEmpty())
    }

    @Test
    fun `many changes come in pages`() = runTest {
        val first = signedIn()
        repeat(7) { first.phone.create("s$it", "Stack $it") }
        first.sync()
        val second = signedIn()

        assertEquals((0..6).map { "Stack $it" }, second.phone.titles())
        assertEquals(account.stacks.values.maxOf { it.first }, second.states.state!!.cursor)
    }

    @Test
    fun `edits on two phones keep both, and the account copy wins the name`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.sync()
        second.sync()

        first.phone.edit("s1", "Biology from the first phone")
        second.phone.edit("s1", "Biology from the second phone")
        first.sync()
        second.sync()
        first.sync()

        val both = listOf("Biology from the first phone", "Biology from the second phone (from this phone)")
        assertEquals(both, second.phone.titles())
        assertEquals(both, account.titles())
        assertEquals(both, first.phone.titles())
    }

    @Test
    fun `a push that loses the race saves the phone's copy`() = runTest {
        val phone = signedIn()
        phone.phone.create("s1", "Biology")
        phone.sync()
        phone.phone.edit("s1", "Mine")
        // Another phone changes the stack between this phone's pull and its push.
        account.beforePush = { account.apply("s1", baseVersion = 1, title = "Theirs") }
        phone.sync()
        account.beforePush = {}

        assertEquals(listOf("Mine (from this phone)", "Theirs"), phone.phone.titles())
        phone.sync()
        assertEquals(listOf("Mine (from this phone)", "Theirs"), account.titles())
    }

    @Test
    fun `an edit on another phone wins over a deletion`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.sync()
        second.sync()

        first.phone.delete("s1")
        second.phone.edit("s1", "Biology 2")
        second.sync()
        first.sync()

        assertEquals(listOf("Biology 2"), first.phone.titles())
        assertTrue(first.phone.deletions.isEmpty())
        assertEquals(listOf("Biology 2"), account.titles())
    }

    @Test
    fun `a deletion on another phone keeps this phone's unsynced edit as a copy`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.sync()
        second.sync()

        second.phone.delete("s1")
        second.sync()
        first.phone.edit("s1", "Biology 2")
        first.sync()

        assertEquals(listOf("Biology 2 (from this phone)"), first.phone.titles())
        assertEquals(listOf("Biology 2 (from this phone)"), account.titles())
    }

    @Test
    fun `records are sent once and reach the other phone`() = runTest {
        val first = signedIn()
        first.phone.finishReview("r1")
        first.phone.recordResult("x1")
        first.sync()
        val calls = account.appendCalls
        first.sync()
        assertEquals(calls, account.appendCalls)

        val second = signedIn()
        assertEquals(setOf("r1"), second.phone.reviews.keys)
        assertEquals(setOf("x1"), second.phone.results.keys)
        second.sync()
        assertEquals(calls, account.appendCalls)
    }

    @Test
    fun `offline changes wait, and go when the server answers again`() = runTest {
        val phone = signedIn()
        val lastSynced = phone.states.state!!.lastSyncedAt
        phone.phone.create("s1", "Biology")

        phone.serverStatus = ServerStatus(ServerState.UNREACHABLE, sync = true)
        phone.sync()
        assertEquals(SyncStatus.Offline(lastSynced), phone.engine.status.value)

        // The monitor says online, but the request fails on the way.
        phone.serverStatus = ServerStatus(ServerState.ONLINE, sync = true)
        account.reachable = false
        phone.sync()
        assertEquals(SyncStatus.Offline(lastSynced), phone.engine.status.value)
        assertTrue(phone.phone.stacks.getValue("s1").pending)

        account.reachable = true
        phone.clock = 90_000
        phone.sync()
        assertEquals(listOf("Biology"), account.titles())
        assertEquals(SyncStatus.UpToDate(90_000), phone.engine.status.value)
        assertEquals(90_000L, phone.states.state!!.lastSyncedAt)
    }

    @Test
    fun `a server without sync says so`() = runTest {
        val phone = Device(account)
        phone.serverStatus = ServerStatus(ServerState.ONLINE, sync = false)
        phone.sync()
        assertEquals(SyncStatus.Unavailable, phone.engine.status.value)
    }

    @Test
    fun `sign-out can remove the synced stacks, and keeps unsynced changes`() = runTest {
        val phone = signedIn()
        phone.phone.create("s1", "Biology")
        phone.phone.create("s2", "Chemistry")
        phone.sync()
        phone.phone.edit("s2", "Chemistry 2")
        phone.phone.create("s3", "New")

        assertEquals(SignOutSummary(syncedStacks = 1, unsyncedStacks = 2), phone.engine.signOutSummary())
        phone.account = null
        phone.engine.forgetAccount(removeStacks = true)

        assertEquals(listOf("Chemistry 2", "New"), phone.phone.titles())
        assertTrue(phone.phone.stacks.values.all { it.accountVersion == null })
        assertNull(phone.states.state)
        assertEquals(listOf("Biology", "Chemistry"), account.titles())
    }

    @Test
    fun `signing in again after keeping the stacks links them without a question or copies`() = runTest {
        val phone = signedIn()
        phone.phone.create("s1", "Biology")
        phone.phone.create("s2", "Chemistry")
        phone.sync()
        phone.engine.forgetAccount(removeStacks = false)
        phone.phone.edit("s2", "Chemistry 2")

        phone.sync()
        // The unchanged stack is the account's own. The changed one is this phone's, and the question asks about it.
        assertEquals(Unlinked(stacks = 1, reviews = 0, results = 0), phone.engine.choice.value)
        phone.engine.choose(upload = true)
        phone.sync()

        assertEquals(listOf("Biology", "Chemistry", "Chemistry 2 (from this phone)"), phone.phone.titles())
        assertEquals(phone.phone.titles(), account.titles())
    }

    @Test
    fun `account data deleted on another phone makes this phone ask again`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.sync()
        second.sync()

        second.engine.deleteAccountData()
        assertTrue(account.stacks.isEmpty())
        assertEquals(listOf("Biology"), second.phone.titles())
        second.sync()
        assertNull(second.engine.choice.value)
        assertTrue(account.stacks.isEmpty())

        first.sync()
        assertEquals(Unlinked(stacks = 1, reviews = 0, results = 0), first.engine.choice.value)
        assertTrue(account.stacks.isEmpty())
        first.engine.choose(upload = true)
        first.sync()
        assertEquals(listOf("Biology"), account.titles())
    }

    @Test
    fun `another account on the same phone starts over`() = runTest {
        val phone = signedIn()
        phone.phone.create("s1", "Biology")
        phone.sync()

        // The fake server keeps one account, so Ben's is empty like this.
        account.stacks.clear()
        phone.account = "account-ben"
        phone.sync()
        assertEquals("account-ben", phone.states.state!!.accountId)
        assertEquals(Unlinked(stacks = 1, reviews = 0, results = 0), phone.engine.choice.value)
    }

    @Test
    fun `unsyncing one stack deletes it on the server, keeps it locally, and re-syncing restores it cleanly`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.phone.create("s2", "Chemistry")
        first.sync()
        second.sync()
        assertEquals(listOf("Biology", "Chemistry"), second.phone.titles())

        // Mark s1 as unsynced on first phone: stays local-only there, deleted on server and second phone.
        first.engine.unsyncStack("s1")
        first.sync()
        second.sync()
        // Pulling our own tombstone on the next sync leaves s1 untouched with its original title.
        first.sync()

        assertEquals(listOf("Biology", "Chemistry"), first.phone.titles())
        assertTrue(first.phone.stacks.getValue("s1").localOnly)
        assertEquals(listOf("Chemistry"), account.titles())
        assertEquals(listOf("Chemistry"), second.phone.titles())

        // Re-syncing s1 revives it on the server and reaches the second phone without spawning a copy.
        first.engine.syncStack("s1")
        first.sync()
        second.sync()

        assertFalse(first.phone.stacks.getValue("s1").localOnly)
        assertEquals(listOf("Biology", "Chemistry"), account.titles())
        assertEquals(listOf("Biology", "Chemistry"), second.phone.titles())
    }

    @Test
    fun `unsyncAll removes synced stacks from the server and syncAll brings everything back`() = runTest {
        val phone = signedIn()
        phone.phone.create("s1", "Biology")
        phone.phone.create("s2", "Chemistry")
        phone.sync()
        assertEquals(listOf("Biology", "Chemistry"), account.titles())

        phone.engine.unsyncAll()
        phone.sync()
        phone.sync()

        assertEquals(listOf("Biology", "Chemistry"), phone.phone.titles())
        assertTrue(phone.phone.stacks.values.all { it.localOnly })
        assertTrue(account.titles().isEmpty())

        phone.engine.syncAll()
        phone.sync()

        assertTrue(phone.phone.stacks.values.none { it.localOnly })
        assertEquals(listOf("Biology", "Chemistry"), account.titles())
    }

    @Test
    fun `restoring a deleted stack before sync cancels the deletion without forking`() = runTest {
        val first = signedIn()
        val second = signedIn()
        first.phone.create("s1", "Biology")
        first.sync()
        second.sync()

        // Delete and immediately restore before sync runs (e.g. offline or within the debounce window).
        first.phone.delete("s1")
        first.phone.restore("s1", "Biology (restored)")
        first.sync()
        // A follow-up pull must not see a tombstone or spawn a "(from this phone)" duplicate.
        first.sync()
        second.sync()

        assertEquals(listOf("Biology (restored)"), first.phone.titles())
        assertEquals(listOf("Biology (restored)"), account.titles())
        assertEquals(listOf("Biology (restored)"), second.phone.titles())
        assertTrue(first.phone.deletions.isEmpty())
        assertEquals(setOf("s1"), first.phone.stacks.keys)
    }
}
