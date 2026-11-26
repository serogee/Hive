package io.github.serogee.hive.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** The display name of a signed-in account, as this phone knows it. [pending] until the server has it. */
@Serializable
data class SavedAccountName(val accountId: String, val name: String, val pending: Boolean = false)

interface AccountNameStore {
    suspend fun accountName(): SavedAccountName?

    suspend fun setAccountName(name: SavedAccountName?)
}

/** The account's display name on the server. */
interface ProfileRemote {
    suspend fun displayName(): String

    suspend fun setDisplayName(name: String)
}

/**
 * Keeps the account's display name the same on the server and every phone. A name changed offline waits on
 * this phone, and goes to the server with the next sync.
 */
class ProfileSync(private val remote: ProfileRemote, private val store: AccountNameStore) {
    /** Sends a waiting name, or else takes the name that the server keeps. */
    suspend fun sync(accountId: String) {
        val saved = store.accountName()?.takeIf { it.accountId == accountId }
        if (saved != null && saved.pending) {
            remote.setDisplayName(saved.name)
            store.setAccountName(saved.copy(pending = false))
        } else {
            store.setAccountName(SavedAccountName(accountId, remote.displayName()))
        }
    }

    /** Saves [name] on this phone at once, and on the server when it answers. Returns whether the server has it. */
    suspend fun rename(accountId: String, name: String): Boolean {
        val saved = SavedAccountName(accountId, name, pending = true)
        store.setAccountName(saved)
        return try {
            remote.setDisplayName(name)
            store.setAccountName(saved.copy(pending = false))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline. The next sync sends it.
            false
        }
    }

    /** After sign-out, so that the next account starts from its own name. */
    suspend fun forget() = store.setAccountName(null)
}
