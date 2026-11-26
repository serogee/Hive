package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.PushStacksResponse
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.SyncChanges
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.model.AccountId

/** The kinds of append-only records. */
enum class RecordKind { REVIEW, RESULT }

/**
 * An account's synced data. Every change gets the next number of one sequence, and changes to one
 * account are written one at a time, so a cursor never skips a change.
 */
interface SyncStore {
    /**
     * Applies [changes] with [io.github.serogee.hive.core.sync.StackSync], in one transaction. Applies
     * nothing when [generation] is not the account's generation.
     */
    suspend fun pushStacks(accountId: AccountId, generation: Long, changes: List<StackChange>, now: Long): PushStacksResponse

    /** Stores the records that are new, and returns how many there were. */
    suspend fun append(accountId: AccountId, kind: RecordKind, records: List<SyncRecord>): Int

    /** Up to [limit] changes after [cursor], oldest first. */
    suspend fun changes(accountId: AccountId, cursor: Long, limit: Int): SyncChanges

    /** Deletes everything the account synced, and starts its next generation. */
    suspend fun deleteAll(accountId: AccountId)
}
