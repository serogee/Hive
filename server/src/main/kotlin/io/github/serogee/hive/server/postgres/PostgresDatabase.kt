package io.github.serogee.hive.server.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
