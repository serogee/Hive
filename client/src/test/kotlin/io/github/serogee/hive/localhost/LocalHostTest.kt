package io.github.serogee.hive.localhost

import io.github.serogee.hive.core.api.CreateRoomRequest
import io.github.serogee.hive.core.api.HostCommand
import io.github.serogee.hive.core.api.JoinRoomRequest
import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.api.SubmitAnswerRequest
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.sample.SampleStacks
import io.github.serogee.hive.data.remote.HiveApi
import io.github.serogee.hive.host.InMemoryRoomStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the embedded room server on this computer and talks to it with the app's own client, the
 * way two phones would over a hotspot. No online server or account is involved.
 */
class LocalHostTest {
    private val host = LocalHost(InMemoryRoomStore())
    private var sessionCleared = false

    // The configured server is unreachable on purpose. Rooms on a phone must not need it.
    private val api = HiveApi(
        serverUrl = { "http://127.0.0.1:1" },
        accessToken = { "online-account-token" },
        onSessionExpired = { sessionCleared = true },
    )

    @AfterTest
    fun stop() = runBlocking { host.stop() }

    @Test
    fun aGuestPlaysARoomHostedOnThePhoneWithoutTheOnlineServer() = runBlocking {
        withTimeout(60_000) { playRoom() }
    }

    private suspend fun playRoom() {
        host.start()
        val server = host.selfUrl

        val hostMembership = api.createRoom(
            CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION, hostDisplayName = "Ms. Reyes"),
            server,
        )
        val roomId = hostMembership.room.id
        val summary = api.lookupRoom(hostMembership.room.code, server)
        val guest = api.join(roomId, JoinRoomRequest("Jamie", asGuest = true), null, server)
        val rejoined = api.join(roomId, JoinRoomRequest("Jamie", asGuest = true), guest.participantToken, server)

        val started = api.command(roomId, HostCommand.Start, hostMembership.participantToken, server)
        val receipt = api.submitAnswer(
            roomId,
            SubmitAnswerRequest(started.live!!.question.id, Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "k1"),
            guest.participantToken,
            server,
        )
        val retry = api.submitAnswer(
            roomId,
            SubmitAnswerRequest(started.live!!.question.id, Answer.SelectedChoice(ChoiceId("nf-q1-c3")), "k1"),
            rejoined.participantToken,
            server,
        )
        val subscribed = withTimeout(10_000) {
            api.roomEvents(roomId, guest.participantToken, server).filterIsInstance<RoomEvent.Subscribed>().first()
        }
        api.command(roomId, HostCommand.EndRoom, hostMembership.participantToken, server)
        val results = api.results(roomId, guest.participantToken, server)

        assertFalse(summary.acceptsAccounts)
        assertEquals("Ms. Reyes", summary.hostName)
        assertEquals(guest.participant.id, rejoined.participant.id)
        assertEquals(receipt, retry)
        assertEquals(roomId, subscribed.roomId)
        assertEquals(listOf(2), results.leaderboard.map { it.score })
        assertFalse(sessionCleared, "A phone-hosted room must not sign the user out of the online server.")
    }

    @Test
    fun theServerListsAnAddressForPlayers() {
        val addresses = host.playerAddresses()

        addresses.forEach { assertEquals(true, it.startsWith("http://") && it.endsWith(":${LocalHost.PORT}")) }
    }

    @Test
    fun aRoomOpensAgainAfterTheServerStopped() = runBlocking {
        withTimeout(60_000) {
            host.start()
            val membership = api.createRoom(
                CreateRoomRequest(SampleStacks.networkingFundamentals, RoomMode.DISCUSSION, hostDisplayName = "Ms. Reyes"),
                host.selfUrl,
            )
            host.stop()
            assertFalse(host.running.value)

            host.start()
            val state = api.roomState(membership.room.id, membership.participantToken, host.selfUrl)
            assertEquals(membership.room.id, state.room.id)
        }
    }
}
