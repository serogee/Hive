package io.github.serogee.hive.server.postgres

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.host.AccountVerifier
import java.util.concurrent.ConcurrentHashMap

/** Saves each account that [verifier] accepts, because rooms and participants refer to it. */
class PostgresAccounts(private val db: PostgresDatabase, private val verifier: AccountVerifier) : AccountVerifier {
    /** What is already saved, so that most requests write nothing. */
    private val saved = ConcurrentHashMap<AccountId, Account>()

    override val configured: Boolean get() = verifier.configured

    override suspend fun verify(accessToken: String): Account? {
        val account = verifier.verify(accessToken) ?: return null
        if (saved[account.id] != account) {
            save(account)
            saved[account.id] = account
        }
        return account
    }

    /** After the account was deleted, so that its next sign-in saves it again. */
    fun forget(accountId: AccountId) {
        saved.remove(accountId)
    }

    private suspend fun save(account: Account) = db.write { connection ->
        connection.prepareStatement(
            """
            INSERT INTO hive.accounts (id, display_name) VALUES (?, ?)
            ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name, updated_at = now()
            """.trimIndent(),
        ).use {
            it.setString(1, account.id.value)
            it.setString(2, account.displayName)
            it.executeUpdate()
        }
    }
}
