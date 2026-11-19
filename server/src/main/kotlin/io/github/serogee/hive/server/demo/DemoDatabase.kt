package io.github.serogee.hive.server.demo

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The demo server's SQLite file. Data survives restarts until [DemoSeed.reset] runs.
 *
 * One connection serves all requests, one at a time. That is enough for a classroom demo.
 */
class DemoDatabase private constructor(private val connection: Connection) : AutoCloseable {
    private val mutex = Mutex()

    suspend fun <T> read(block: (Connection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block(connection) }
    }

    /** Runs [block] in one transaction. */
    suspend fun <T> write(block: (Connection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { transaction(block) }
    }

    fun <T> transaction(block: (Connection) -> T): T {
        connection.autoCommit = false
        try {
            return block(connection).also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun isEmpty(): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM accounts").use { it.next() && it.getInt(1) == 0 }
    }

    fun dropAll() = connection.createStatement().use { statement ->
        listOf("participants", "rooms", "sessions", "accounts").forEach {
            statement.executeUpdate("DROP TABLE IF EXISTS $it")
        }
    }

    fun createSchema() = connection.createStatement().use { statement ->
        SCHEMA.forEach(statement::executeUpdate)
    }

    override fun close() = connection.close()

    companion object {
        fun open(path: Path): DemoDatabase {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            val connection = DriverManager.getConnection("jdbc:sqlite:$path")
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            return DemoDatabase(connection).also { it.createSchema() }
        }

        private val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                display_name TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                account_id TEXT NOT NULL REFERENCES accounts(id),
                created_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS rooms (
                id TEXT PRIMARY KEY,
                code TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                host_name TEXT NOT NULL,
                host_account_id TEXT REFERENCES accounts(id),
                mode TEXT NOT NULL,
                admission TEXT NOT NULL,
                status TEXT NOT NULL,
                stack_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                deadline_at INTEGER,
                revision INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS participants (
                id TEXT PRIMARY KEY,
                room_id TEXT NOT NULL REFERENCES rooms(id),
                display_name TEXT NOT NULL,
                role TEXT NOT NULL,
                account_id TEXT REFERENCES accounts(id),
                token_hash TEXT NOT NULL,
                joined_at INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS participants_by_room ON participants(room_id)",
        ).map { it.trimIndent() }
    }
}
