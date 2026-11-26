package io.github.serogee.hive.server.postgres

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.server.AccountProfiles
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves each account that [verifier] accepts, because rooms and participants refer to it. The account goes
 * on with the display name that its person chose, or else the name from the sign-in.
 */
class PostgresAccounts(private val db: PostgresDatabase, private val verifier: AccountVerifier) : AccountVerifier, AccountProfiles {
    /** The account from the sign-in, and the account with its display name, so that most requests read nothing. */
    private val saved = ConcurrentHashMap<AccountId, Pair<Account, Account>>()
    private val deleted = ConcurrentHashMap.newKeySet<AccountId>()

    override val configured: Boolean get() = verifier.configured

    override suspend fun verify(accessToken: String): Account? {
        val account = verifier.verify(accessToken) ?: return null
        if (account.id in deleted) return null
        saved[account.id]?.let { (signedIn, named) -> if (signedIn == account) return named }
        val displayName = save(account) ?: run {
            deleted += account.id
            saved.remove(account.id)
            return null
        }
        val named = account.copy(displayName = displayName)
        if (account.id in deleted) return null
        saved[account.id] = account to named
        return named
    }

    /** After the account changed, so that its next request reads it again. */
    fun forget(accountId: AccountId) {
        saved.remove(accountId)
    }

    /** After the account was deleted, so unexpired access tokens cannot save it again. */
    fun markDeleted(accountId: AccountId) {
        deleted += accountId
        saved.remove(accountId)
    }

    override suspend fun displayName(accountId: AccountId): String? = db.read { connection ->
        connection.prepareStatement("SELECT COALESCE(chosen_name, display_name) FROM hive.accounts WHERE id = ?").use {
            it.setString(1, accountId.value)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    override suspend fun setDisplayName(accountId: AccountId, name: String) {
        db.write { connection ->
            connection.prepareStatement("UPDATE hive.accounts SET chosen_name = ?, updated_at = now() WHERE id = ?").use {
                it.setString(1, name)
                it.setString(2, accountId.value)
                it.executeUpdate()
            }
        }
        forget(accountId)
    }

    /** Saves the account, and returns its display name, or null when the account was deleted. */
    private suspend fun save(account: Account): String? = db.write { connection ->
        val wasDeleted = connection.prepareStatement("SELECT 1 FROM hive.deleted_accounts WHERE account_id = ?").use {
            it.setString(1, account.id.value)
            it.executeQuery().use { rows -> rows.next() }
        }
        if (wasDeleted) return@write null
        connection.prepareStatement(
            """
            INSERT INTO hive.accounts (id, display_name) VALUES (?, ?)
            ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name, updated_at = now()
            RETURNING COALESCE(chosen_name, display_name)
            """.trimIndent(),
        ).use {
            it.setString(1, account.id.value)
            it.setString(2, account.displayName)
            it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
    }
}
