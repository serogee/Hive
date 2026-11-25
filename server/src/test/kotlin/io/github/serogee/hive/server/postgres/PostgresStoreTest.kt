package io.github.serogee.hive.server.postgres

import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.PlayerCommand
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.HiveException
import io.github.serogee.hive.host.RoomService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.SQLException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

/** Runs against a real Postgres that the test starts on this computer. */
class PostgresStoreTest {
    private val alex = Account(AccountId("account-alex"), "Alex Rivera")
    private val sam = Account(AccountId("account-sam"), "Sam Cruz")
    private val q1 = QuestionId("nf-q1")
    private val rightQ1 = Answer.SelectedChoice(ChoiceId("nf-q1-c3"))
    private val settings get() = DatabaseSettings(postgres.getJdbcUrl("postgres", "postgres"), "postgres", null)
    private lateinit var db: PostgresDatabase

    /** Question timers of a test end with it, before its database closes. */
    private val timers = CoroutineScope(SupervisorJob())

    @BeforeTest
    fun openEmptyDatabase() {
        sql("DROP SCHEMA IF EXISTS hive CASCADE")
        db = PostgresDatabase.open(settings)
    }

    @AfterTest
    fun close() {
        timers.cancel()
        db.close()
    }

    private fun sql(statement: String) = postgres.postgresDatabase.connection.use { connection ->
        connection.createStatement().use { it.execute(statement) }
    }

    private fun count(table: String) = postgres.postgresDatabase.connection.use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM hive.$table").use { rows -> rows.next(); rows.getInt(1) } }
    }

    private fun service() = RoomService(PostgresRoomStore(db), clock = { 5_000L }, requireAccountToHost = true, timers = timers)

    /** Signs an account in, which saves it. */
    private suspend fun signIn(account: Account) = PostgresAccounts(db) { account }.verify("token")

    /** As after a deploy. */
    private fun reopen() {
        db.close()
        db = PostgresDatabase.open(settings)
    }

    private suspend fun room(mode: RoomMode = RoomMode.DISCUSSION) = signIn(alex).let {
        service().createRoom(alex, CreateRoomRequest(SampleStacks.networkingFundamentals, mode))
    }

    @Test
    fun aDiscussionQuizSurvivesARestart() = runBlocking {
        val host = room()
        val guest = service().join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service().command(host.room.id, alex, null, HostCommand.Start)
        val receipt = service().submitAnswer(host.room.id, null, guest.participantToken, SubmitAnswerRequest(q1, rightQ1, "key-1"))
        val before = service().state(host.room.id, null, guest.participantToken)

        reopen()

        assertEquals(before, service().state(host.room.id, null, guest.participantToken))
        assertEquals(receipt, service().submitAnswer(host.room.id, null, guest.participantToken, SubmitAnswerRequest(q1, rightQ1, "key-1")))
        val second = assertFailsWith<HiveException> {
            service().submitAnswer(host.room.id, null, guest.participantToken, SubmitAnswerRequest(q1, rightQ1, "key-2"))
        }
        assertEquals(ErrorCode.ANSWER_ALREADY_RECORDED, second.code)

        service().command(host.room.id, alex, null, HostCommand.EndRoom)
        val results = service().results(host.room.id, null, guest.participantToken)
        assertEquals("Jamie", results.leaderboard.single().displayName)
        assertTrue(results.yourAnswers.first { it.questionId == q1 }.correct)
        assertEquals(RoomStatus.ENDED, service().lookup(host.room.code).status)
    }

    @Test
    fun asynchronousAttemptsSurviveARestart() = runBlocking {
        val host = room(RoomMode.ASYNCHRONOUS)
        service().command(host.room.id, alex, null, HostCommand.Start)
        val guest = service().join(host.room.id, null, JoinRoomRequest("Jamie", asGuest = true), null)
        service().attempt(host.room.id, null, guest.participantToken, PlayerCommand.StartAttempt)
        val before = service().attempt(host.room.id, null, guest.participantToken, PlayerCommand.NextQuestion)

        reopen()

        val after = service().state(host.room.id, null, guest.participantToken)
        assertEquals(before, after)
        assertEquals(2, after.live?.question?.number)
        assertEquals(1, count("attempts"))
    }

    @Test
    fun settingsAndHostedRoomsAreKept() = runBlocking {
        val host = room()
        service().command(
            host.room.id,
            alex,
            null,
            HostCommand.UpdateSettings("Friday quiz", AdmissionPolicy.GUESTS_ONLY, speedScoring = true, timeTiebreak = false, deadlineAt = 90_000, questionSeconds = 20),
        )
        reopen()

        val stored = assertNotNull(PostgresRoomStore(db).findRoom(host.room.id))
        assertEquals("Friday quiz", stored.title)
        assertEquals(AdmissionPolicy.GUESTS_ONLY, stored.admission)
        assertTrue(stored.speedScoring)
        assertEquals(false, stored.timeTiebreak)
        assertEquals(90_000L, stored.deadlineAt)
        assertEquals(setOf<Int?>(20), stored.stack.questions.map { it.durationSeconds }.toSet())
        assertEquals(listOf(host.room.id), service().hostedRooms(alex).map { it.id })
        assertNull(PostgresRoomStore(db).findRoomByCode("NOPE22"))
    }

    @Test
    fun anAccountIsInOneRoomAtATime() = runBlocking {
        signIn(sam)
        val first = room()
        val second = room()
        service().join(first.room.id, sam, JoinRoomRequest(asGuest = false), null)
        val denied = assertFailsWith<HiveException> { service().join(second.room.id, sam, JoinRoomRequest(asGuest = false), null) }
        assertEquals(ErrorCode.ALREADY_IN_ROOM, denied.code)

        service().join(second.room.id, sam, JoinRoomRequest(asGuest = false, leaveOtherRoom = true), null)
        reopen()
        assertEquals(listOf(second.room.id), PostgresRoomStore(db).activeMembershipsOf(sam.id).map { it.roomId })
        val left = PostgresRoomStore(db).participants(first.room.id).single { it.accountId == sam.id }
        assertEquals(5_000L, left.leftAt)
    }

    @Test
    fun participantsKeepTheirOrderAndCredentials() = runBlocking {
        val host = room()
        val guests = listOf("Zoe", "Ana", "Mia").map { service().join(host.room.id, null, JoinRoomRequest(it, asGuest = true), null) }
        reopen()
        assertEquals(listOf("Alex Rivera", "Zoe", "Ana", "Mia"), PostgresRoomStore(db).participants(host.room.id).map { it.displayName })
        guests.forEach { guest ->
            assertEquals(guest.participant.id, service().authorize(host.room.id, null, guest.participantToken).id)
        }
    }

    @Test
    fun accountsAreSavedOnceAndFollowTheirName() = runBlocking {
        var account: Account? = alex
        val accounts = PostgresAccounts(db) { account }
        repeat(2) { accounts.verify("token") }
        account = alex.copy(displayName = "Alex R.")
        accounts.verify("token")
        account = null
        assertNull(accounts.verify("expired"))

        assertEquals(1, count("accounts"))
        val name = db.read { c -> c.createStatement().use { it.executeQuery("SELECT display_name FROM hive.accounts").use { rows -> rows.next(); rows.getString(1) } } }
        assertEquals("Alex R.", name)
        assertEquals(false, PostgresAccounts(db, AccountVerifier.NotConfigured).configured)
    }

    @Test
    fun aFailedWriteLeavesNothingBehind() = runBlocking {
        val host = room()
        val store = PostgresRoomStore(db)
        val record = assertNotNull(store.findRoom(host.room.id))
        val duplicate = store.participants(host.room.id).first()
        // The room is new, but its host's ID is taken, so neither may be stored.
        assertFailsWith<SQLException> { store.insertRoom(record.copy(id = RoomId("other"), code = "OTHER2"), duplicate) }
        assertNull(store.findRoomByCode("OTHER2"))
        // The connection goes back to the pool in working order.
        repeat(10) { assertNotNull(store.findRoom(host.room.id)) }
    }

    @Test
    fun migrationsRunOnceAlsoWhenServersStartTogether() {
        sql("DROP SCHEMA hive CASCADE")
        val failures = mutableListOf<Throwable>()
        (1..4).map { thread { runCatching { PostgresDatabase.open(settings).close() }.onFailure { synchronized(failures) { failures += it } } } }.forEach { it.join() }
        assertEquals(emptyList(), failures)
        assertEquals(Migrations.all.size, count("schema_migrations"))
    }

    @Test
    fun aFailedMigrationChangesNothing() {
        val broken = Migrations.all + Migration(1_000, "CREATE TABLE hive.half (id INTEGER); SELECT * FROM hive.missing")
        postgres.postgresDatabase.connection.use { connection ->
            assertFailsWith<SQLException> { Migrations.run(connection, broken) }
            assertFailsWith<SQLException> { connection.createStatement().use { it.execute("SELECT * FROM hive.half") } }
        }
        assertEquals(Migrations.all.size, count("schema_migrations"))
    }

    @Test
    fun anOlderServerDoesNotTouchANewerDatabase() {
        sql("INSERT INTO hive.schema_migrations (version) VALUES (1000)")
        assertFailsWith<IllegalStateException> { PostgresDatabase.open(settings) }
    }

    @Test
    fun thePublicRolesOfSupabaseCannotReadTheTables() {
        sql("DROP ROLE IF EXISTS anon")
        sql("CREATE ROLE anon")
        postgres.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET ROLE anon")
                listOf("accounts", "rooms", "participants", "submissions", "attempts", "schema_migrations").forEach { table ->
                    val failure = assertFailsWith<SQLException> { statement.execute("SELECT * FROM hive.$table") }
                    assertEquals("42501", failure.sqlState, table)
                }
            }
        }
    }

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
