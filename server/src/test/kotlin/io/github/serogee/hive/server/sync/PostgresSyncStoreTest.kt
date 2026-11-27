package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.server.postgres.DatabaseSettings
import io.github.serogee.hive.server.postgres.PostgresAccounts
import io.github.serogee.hive.server.postgres.PostgresDatabase
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

/** Runs against a real Postgres that the test starts on this computer. */
class PostgresSyncStoreTest : SyncStoreContract() {
    override val alex = AccountId("account-alex")
    override val sam = AccountId("account-sam")
    private lateinit var db: PostgresDatabase
    override lateinit var store: SyncStore

    @BeforeTest
    fun openEmptyDatabase() {
        postgres.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS hive CASCADE") }
        }
        db = PostgresDatabase.open(DatabaseSettings(postgres.getJdbcUrl("postgres", "postgres"), "postgres", null))
        store = db.syncStore()
        runBlocking {
            listOf(Account(alex, "Alex Rivera"), Account(sam, "Sam Cruz")).forEach { account ->
                PostgresAccounts(db) { account }.verify("token")
            }
        }
    }

    @AfterTest
    fun close() = db.close()

    @Test
    fun readSnapshotSeesAConsistentRepeatableReadSnapshotAcrossQueries() = runBlocking {
        store.pushStacks(alex, generation = 0, listOf(StackChange("s1", document = StackDocument("Initial"))), now = 1_000)

        val (isolation, readOnly, beforeCount, afterCount) = db.readSnapshot { connection ->
            val iso = connection.createStatement().use { st ->
                st.executeQuery("SHOW transaction_isolation").use { rs -> rs.next(); rs.getString(1) }
            }
            val ro = connection.createStatement().use { st ->
                st.executeQuery("SHOW transaction_read_only").use { rs -> rs.next(); rs.getString(1) }
            }
            val first = connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM hive.sync_stacks").use { rs -> rs.next(); rs.getInt(1) }
            }
            // Commit a concurrent write on a separate connection mid-read.
            runBlocking {
                store.pushStacks(alex, generation = 0, listOf(StackChange("s2", document = StackDocument("Concurrent"))), now = 2_000)
            }
            val second = connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM hive.sync_stacks").use { rs -> rs.next(); rs.getInt(1) }
            }
            SnapshotCheck(iso, ro, first, second)
        }

        assertEquals("repeatable read", isolation)
        assertEquals("on", readOnly)
        assertEquals(1, beforeCount)
        assertEquals(1, afterCount)
        // After the snapshot transaction ends, the committed write is visible.
        assertEquals(2, store.changes(alex, cursor = 0, limit = 10).stacks.size)
    }

    private data class SnapshotCheck(val isolation: String, val readOnly: String, val before: Int, val after: Int)

    companion object {
        private lateinit var postgres: EmbeddedPostgres

        @JvmStatic
        @BeforeAll
        fun start() {
            postgres = EmbeddedPostgres.start()
        }

        @JvmStatic
        @AfterAll
        fun stop() = postgres.close()
    }
}
