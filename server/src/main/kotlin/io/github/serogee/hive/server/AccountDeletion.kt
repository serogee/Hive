package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.model.AccountId
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

/** What the server keeps about an account: its sync data, and its place in rooms. */
fun interface AccountData {
    /**
     * Deletes the account and its synced data, and ends the rooms it hosts. In rooms that other people host,
     * the player keeps their name without a link to the account, like a guest, so that results still add up.
     */
    suspend fun delete(accountId: AccountId)
}

/** Where people sign in, such as Supabase Auth. */
fun interface SignInAccounts {
    suspend fun delete(accountId: AccountId)
}

/** Deletes an account: first what this server keeps, then the sign-in itself. */
class AccountDeleter(
    private val data: AccountData,
    private val signIn: SignInAccounts,
    /** Forgets cached details of the account, such as [io.github.serogee.hive.server.postgres.PostgresAccounts] keeps. */
    private val forget: (AccountId) -> Unit = {},
) {
    suspend fun delete(accountId: AccountId) {
        data.delete(accountId)
        forget(accountId)
        signIn.delete(accountId)
    }
}

/** [AccountData] in SQL that SQLite and Postgres both understand. [tables] goes before each table name. */
fun deleteAccountRows(connection: Connection, tables: String, accountId: AccountId, extraTables: List<String> = emptyList()) {
    fun update(sql: String) = connection.prepareStatement(sql).use {
        it.setString(1, accountId.value)
        it.executeUpdate()
    }
    update("UPDATE ${tables}rooms SET status = 'ENDED', host_account_id = NULL WHERE host_account_id = ?")
    // Like a guest: the name stays, and the link to the account goes.
    update("UPDATE ${tables}participants SET account_id = NULL WHERE account_id = ?")
    (listOf("sync_stacks", "sync_records", "sync_accounts") + extraTables).forEach { update("DELETE FROM $tables$it WHERE account_id = ?") }
    update("DELETE FROM ${tables}accounts WHERE id = ?")
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
