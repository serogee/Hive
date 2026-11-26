package io.github.serogee.hive.server.postgres

import java.sql.Connection

/** One numbered change to the tables. A change that has run anywhere is never edited. */
data class Migration(val version: Int, val sql: String)

/**
 * Runs the changes that a database does not have yet, each in its own transaction. Servers that start
 * together take turns.
 *
 * The tables are in their own schema. Supabase offers the public schema to anyone with the publishable
 * key, and the tables hold answer keys.
 */
object Migrations {
    const val SCHEMA = "hive"

    /** In order. Add new files at the end. */
    private val FILES = listOf("V1__rooms.sql", "V2__sync.sql")

    private const val LOCK = 4_715_001L

    val all: List<Migration> by lazy {
        FILES.map { file ->
            val version = file.removePrefix("V").substringBefore("__").toInt()
            val sql = checkNotNull(Migrations::class.java.getResourceAsStream("/migrations/$file")) { "Missing migration $file" }
            Migration(version, sql.bufferedReader().use { it.readText() })
        }
    }

    fun run(connection: Connection, migrations: List<Migration> = all) {
        connection.execute("SELECT pg_advisory_lock($LOCK)")
        try {
            connection.execute("CREATE SCHEMA IF NOT EXISTS $SCHEMA")
            connection.execute(
                "CREATE TABLE IF NOT EXISTS $SCHEMA.schema_migrations (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())",
            )
            val applied = connection.appliedVersions()
            val newest = migrations.maxOfOrNull { it.version } ?: 0
            check((applied.maxOrNull() ?: 0) <= newest) { "The database is newer than this server. Deploy a newer server." }

            migrations.sortedBy { it.version }.filter { it.version !in applied }.forEach { migration ->
                connection.transaction {
                    it.execute(migration.sql)
                    it.execute("INSERT INTO $SCHEMA.schema_migrations (version) VALUES (${migration.version})")
                }
            }
        } finally {
            connection.execute("SELECT pg_advisory_unlock($LOCK)")
        }
    }

    private fun Connection.appliedVersions(): Set<Int> = createStatement().use { statement ->
        statement.executeQuery("SELECT version FROM $SCHEMA.schema_migrations").use { rows ->
            buildSet { while (rows.next()) add(rows.getInt(1)) }
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
