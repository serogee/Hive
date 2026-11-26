package io.github.serogee.hive.server.demo

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.engine.Clock
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.host.Tokens
import io.github.serogee.hive.server.AccountProfiles

/**
 * Simulated sign-in for the fictional demo accounts.
 *
 * Sessions are opaque tokens stored in the demo database, so they work only on this server.
 */
class DemoAccounts(private val db: DemoDatabase, private val clock: Clock = Clock.System) : AccountVerifier, AccountProfiles {
    suspend fun login(username: String, password: String): AuthSession {
        val account = db.read { connection ->
            connection.prepareStatement(
                "SELECT id, display_name, password_hash FROM accounts WHERE username = ?",
            ).use { statement ->
                statement.setString(1, username.trim().lowercase())
                statement.executeQuery().use { row ->
                    if (row.next() && row.getString("password_hash") == Tokens.hash(password)) {
                        Account(AccountId(row.getString("id")), row.getString("display_name"))
                    } else {
                        null
                    }
                }
            }
        } ?: throw HiveException(ErrorCode.INVALID_CREDENTIALS, "The username or password is incorrect.")

        val token = Tokens.newToken()
        db.write { connection ->
            connection.prepareStatement(
                "INSERT INTO sessions (token_hash, account_id, created_at) VALUES (?, ?, ?)",
            ).use {
                it.setString(1, Tokens.hash(token))
                it.setString(2, account.id.value)
                it.setLong(3, clock.nowMillis())
                it.executeUpdate()
            }
        }
        return AuthSession(token, account)
    }

    suspend fun logout(accessToken: String) {
        db.write { connection ->
            connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use {
                it.setString(1, Tokens.hash(accessToken))
                it.executeUpdate()
            }
        }
    }

    override suspend fun verify(accessToken: String): Account? = db.read { connection ->
        connection.prepareStatement(
            """
            SELECT accounts.id, accounts.display_name FROM sessions
            JOIN accounts ON accounts.id = sessions.account_id
            WHERE sessions.token_hash = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, Tokens.hash(accessToken))
            statement.executeQuery().use { row ->
                if (row.next()) Account(AccountId(row.getString(1)), row.getString(2)) else null
            }
        }
    }

    override suspend fun displayName(accountId: AccountId): String? = db.read { connection ->
        connection.prepareStatement("SELECT display_name FROM accounts WHERE id = ?").use {
            it.setString(1, accountId.value)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    /** A demo account has no other name, so the chosen name replaces it. */
    override suspend fun setDisplayName(accountId: AccountId, name: String) {
        db.write { connection ->
            connection.prepareStatement("UPDATE accounts SET display_name = ? WHERE id = ?").use {
                it.setString(1, name)
                it.setString(2, accountId.value)
                it.executeUpdate()
            }
        }
    }
}
