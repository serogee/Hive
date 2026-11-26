package io.github.serogee.hive.data.sync

import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.PushStacksResponse
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.SyncChanges
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.api.SyncedStack
import kotlinx.serialization.Serializable

/** The account's side of sync. */
interface SyncRemote {
    suspend fun changes(cursor: Long): SyncChanges

    suspend fun pushStacks(request: PushStacksRequest): PushStacksResponse

    suspend fun appendReviews(records: List<SyncRecord>)

    suspend fun appendResults(records: List<SyncRecord>)

    suspend fun onlineRooms(): List<OnlineRoomEntry>

    suspend fun deleteAll()
}

/** A stack on this phone, as sync sees it. */
data class LocalStack(
    val id: String,
    val document: StackDocument,
    /** The account's version that this copy is based on. Null when the account does not have it. */
    val accountVersion: Long?,
    /** Changed since the account last got it. */
    val pending: Boolean,
    val localOnly: Boolean,
    val updatedAt: Long,
)

data class StackDeletion(val stackId: String, val baseVersion: Long)

/** What this phone has that the account does not. */
data class Unlinked(val stacks: Int, val reviews: Int, val results: Int) {
    val isEmpty: Boolean get() = stacks == 0 && reviews == 0 && results == 0
}

/** What sign-out would remove, and what it would keep because the account does not have it yet. */
data class SignOutSummary(val syncedStacks: Int, val unsyncedStacks: Int)

/** This phone's side of sync. */
interface SyncLocal {
    suspend fun pendingStacks(): List<LocalStack>

    suspend fun stack(id: String): LocalStack?

    suspend fun deletions(): List<StackDeletion>

    suspend fun deletion(id: String): StackDeletion?

    suspend fun clearDeletion(id: String)

    /** Saves the account's copy, or deletes the stack for a tombstone. The stack is then up to date. */
    suspend fun takeAccountCopy(stack: SyncedStack)

    suspend fun markPushed(id: String, version: Long, sentAt: Long)

    /** Saves [stack] again with new IDs as "<title> (from this phone)", so that the account copy can replace it. */
    suspend fun saveAsCopy(stack: LocalStack)

    suspend fun unlinked(): Unlinked

    suspend fun keepUnlinkedOnPhone()

    suspend fun unlinkAll()

    suspend fun signOutSummary(): SignOutSummary

    /** Deletes the stacks that the account has without changes from this phone. */
    suspend fun deleteSyncedStacks()

    suspend fun unsentReviews(limit: Int): List<SyncRecord>

    suspend fun markReviewsSent(ids: List<String>)

    suspend fun saveReviews(records: List<SyncRecord>)

    suspend fun unsentResults(limit: Int): List<SyncRecord>

    suspend fun markResultsSent(ids: List<String>)

    suspend fun saveResults(records: List<SyncRecord>)

    suspend fun saveOnlineRooms(rooms: List<OnlineRoomEntry>)

    /** Whether changes wait to go to the account. */
    suspend fun hasPendingWork(): Boolean
}

/** How far this phone is with one account. */
@Serializable
data class SyncState(
    val accountId: String,
    val cursor: Long = 0,
    /** The account's generation at the last pull. Null before the first pull. */
    val generation: Long? = null,
    /** Whether the user chose what happens to what this phone had before. */
    val decided: Boolean = false,
    val lastSyncedAt: Long? = null,
)

interface SyncStateStore {
    suspend fun syncState(): SyncState?

    suspend fun setSyncState(state: SyncState?)
}

/** What a stack from the account does to the copy on this phone. */
enum class PullDecision {
    /** This phone already has that version or a newer one. */
    SKIP,

    /** Replace the phone's copy. */
    TAKE,

    /** Keep the phone's changes as a new stack, then take the account copy. */
    KEEP_BOTH,
}

object StackMerge {
    /** The title of the phone's own changes, when the account keeps another copy. */
    fun copyTitle(title: String) = "${title.ifBlank { "Untitled stack" }} (from this phone)"

    fun onPulled(local: LocalStack, remote: SyncedStack): PullDecision {
        val base = local.accountVersion
        val changedHere = local.pending || local.localOnly
        return when {
            base != null && base >= remote.version -> PullDecision.SKIP
            !changedHere -> PullDecision.TAKE
            !remote.deleted && remote.document == local.document -> PullDecision.TAKE
            else -> PullDecision.KEEP_BOTH
        }
    }
}
