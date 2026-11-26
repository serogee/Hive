package io.github.serogee.hive.server.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.server.AccountData
import io.github.serogee.hive.server.deleteAccountRows
import io.github.serogee.hive.server.sync.JdbcSyncStore
import io.github.serogee.hive.server.sync.SyncDatabase
import io.github.serogee.hive.server.sync.SyncStore
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A small pool of connections to Postgres. */
class PostgresDatabase private constructor(private val pool: HikariDataSource) : AutoCloseable {
    suspend fun <T> read(block: (Connection) -> T): T = withContext(Dispatchers.IO) { pool.connection.use(block) }

    /** Runs [block] in one transaction. */
    suspend fun <T> write(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        pool.connection.use { it.transaction(block) }
    }

    /** Account data that phones sync. */
    fun syncStore(): SyncStore = JdbcSyncStore(
        object : SyncDatabase {
            override val tables = "hive."

            override suspend fun <T> read(block: (Connection) -> T): T = this@PostgresDatabase.read(block)

            override suspend fun <T> writeForAccount(accountId: AccountId, block: (Connection) -> T): T = write { connection ->
                // Held until the transaction ends.
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                    it.setString(1, "sync:${accountId.value}")
                    it.executeQuery().close()
                }
                block(connection)
            }

            override fun nextChange(connection: Connection): Long = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT nextval('hive.sync_changes')").use { it.next(); it.getLong(1) }
            }
        },
    )

    /** Deletes an account and what it synced, in one transaction. */
    fun accountData(): AccountData = AccountData { accountId -> write { deleteAccountRows(it, "hive.", accountId) } }

    override fun close() = pool.close()

    companion object {
        /** The session pooler of a small Supabase project allows few connections. */
        private const val MAX_CONNECTIONS = 5

        /** Connects, and brings the tables up to date. */
        fun open(settings: DatabaseSettings): PostgresDatabase {
            val config = HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                username = settings.user
                password = settings.password
                maximumPoolSize = MAX_CONNECTIONS
                poolName = "hive"
            }
            val pool = HikariDataSource(config)
            try {
                pool.connection.use { Migrations.run(it) }
            } catch (e: Throwable) {
                pool.close()
                throw e
            }
            return PostgresDatabase(pool)
        }
    }
}

internal fun <T> Connection.transaction(block: (Connection) -> T): T {
    autoCommit = false
    try {
        return block(this).also { commit() }
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = true
    }
}
