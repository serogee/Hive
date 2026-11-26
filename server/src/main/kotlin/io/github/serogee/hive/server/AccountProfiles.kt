package io.github.serogee.hive.server

import io.github.serogee.hive.core.model.AccountId

/** Where the server keeps the name that each account shows in sessions. */
interface AccountProfiles {
    /** The account's display name, or null for an account the server has not seen. */
    suspend fun displayName(accountId: AccountId): String?

    /** Saves a display name that [io.github.serogee.hive.core.engine.DisplayNames] already accepted. */
    suspend fun setDisplayName(accountId: AccountId, name: String)
}
