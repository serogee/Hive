package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.host.HiveException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A value that is never printed, such as a secret key. */
class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString() = "(hidden)"
}

/** A room that ended because its Host's account was deleted. */
data class EndedRoom(val roomId: RoomId, val revision: Long)

/** What the server keeps about an account: its sync data, and its place in rooms. */
fun interface AccountData {
    /**
     * Deletes the account and its synced data, ends the rooms it hosts, and revokes its participant
     * credentials. Past rooms keep the display name like a guest's, so that results still add up.
     */
    suspend fun delete(accountId: AccountId): List<EndedRoom>
}

/** Where people sign in, such as Supabase Auth. */
fun interface SignInAccounts {
    suspend fun delete(accountId: AccountId)
}

/**
 * Deletes an account: first the sign-in itself (so a failed sign-in delete never destroys server data),
 * then what this server keeps.
 */
class AccountDeleter(
    private val data: AccountData,
    private val signIn: SignInAccounts,
    /** Forgets and blocks cached details of the deleted account, such as [io.github.serogee.hive.server.postgres.PostgresAccounts] keeps. */
    private val forget: (AccountId) -> Unit = {},
) {
    suspend fun delete(accountId: AccountId): List<EndedRoom> {
        signIn.delete(accountId)
        // Block unexpired cached JWTs before mutating server rows so concurrent requests cannot race with deletion.
        forget(accountId)
        return data.delete(accountId)
    }
}

/** [AccountData] in SQL that SQLite and Postgres both understand. [tables] goes before each table name. */
fun deleteAccountRows(
    connection: Connection,
    tables: String,
    accountId: AccountId,
    extraTables: List<String> = emptyList(),
    recordDeleted: Boolean = false,
): List<EndedRoom> {
    fun update(sql: String) = connection.prepareStatement(sql).use {
        it.setString(1, accountId.value)
        it.executeUpdate()
    }
    val ended = connection.prepareStatement(
        "SELECT id, revision FROM ${tables}rooms WHERE host_account_id = ? AND status != 'ENDED'",
    ).use { statement ->
        statement.setString(1, accountId.value)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(EndedRoom(RoomId(rows.getString(1)), rows.getLong(2) + 1)) }
        }
    }
    connection.prepareStatement(
        "UPDATE ${tables}rooms SET status = 'ENDED', question_open = ?, revision = revision + 1, host_account_id = NULL " +
            "WHERE host_account_id = ? AND status != 'ENDED'",
    ).use {
        it.setBoolean(1, false)
        it.setString(2, accountId.value)
        it.executeUpdate()
    }
    update("UPDATE ${tables}rooms SET host_account_id = NULL WHERE host_account_id = ?")
    // Like a guest: the name stays, the link to the account goes, and participant tokens stop working.
    update("UPDATE ${tables}participants SET account_id = NULL, token_hash = '' WHERE account_id = ?")
    (listOf("sync_stacks", "sync_records", "sync_accounts") + extraTables).forEach { update("DELETE FROM $tables$it WHERE account_id = ?") }
    update("DELETE FROM ${tables}accounts WHERE id = ?")
    if (recordDeleted) {
        update("INSERT INTO ${tables}deleted_accounts (account_id) VALUES (?) ON CONFLICT (account_id) DO NOTHING")
    }
    return ended
}

/** Deletes Supabase Auth users with the Admin API. The secret key goes in the apikey header only. */
class SupabaseAdmin(
    private val supabaseUrl: String,
    private val secretKey: Secret,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : SignInAccounts {
    override suspend fun delete(accountId: AccountId) = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(accountId.value, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("$supabaseUrl/auth/v1/admin/users/$id"))
            .timeout(Duration.ofSeconds(15))
            .header("apikey", secretKey.reveal())
            .DELETE()
            .build()
        val status = try {
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: java.io.IOException) {
            throw HiveException(ErrorCode.AUTH_UNAVAILABLE, "Supabase could not be reached, so the sign-in was not deleted. Try again.")
        }
        // A user that is already gone is deleted too.
        if (status !in 200..299 && status != 404) {
            throw HiveException(ErrorCode.AUTH_UNAVAILABLE, "Supabase did not delete the sign-in (HTTP $status). Try again.")
        }
    }
}
