package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.server.postgres.DatabaseSettings
import io.github.serogee.hive.server.postgres.PostgresAccounts
import io.github.serogee.hive.server.postgres.PostgresDatabase
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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
