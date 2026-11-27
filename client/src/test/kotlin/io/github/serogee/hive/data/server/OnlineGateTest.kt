package io.github.serogee.hive.data.server

import io.github.serogee.hive.core.api.HealthAuth
import io.github.serogee.hive.core.api.HealthMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class OnlineGateTest {
    private val production = ServerStatus(ServerState.ONLINE, HealthMode.PRODUCTION, HealthAuth.CONFIGURED)
    private val demo = ServerStatus(ServerState.ONLINE, HealthMode.DEMO, HealthAuth.CONFIGURED)

    private fun facts(server: ServerStatus, signedIn: Boolean = false, google: Boolean = true, demo: Boolean = false) =
        OnlineFacts(google, demo, signedIn, server)

    private fun locked(reason: String) = Gate(GateLook.LOCKED, reason)

    private fun disconnected(reason: String) = Gate(GateLook.DISCONNECTED, reason)

    @Test
    fun `everything works when the server is online and sign-in is set up`() {
        listOf(false, true).forEach { signedIn ->
            assertNull(facts(production, signedIn).serverGate())
            assertNull(facts(production, signedIn).accountGate())
        }
    }

    @Test
    fun `signed out, a server that is down locks everything online`() {
        listOf(false, true).forEach { google ->
            val facts = facts(production.copy(state = ServerState.UNREACHABLE), google = google)
            assertEquals(locked("The server is down right now"), facts.serverGate())
            assertEquals(locked("The server is down right now"), facts.accountGate())
        }
    }

    @Test
    fun `signed out, a server that is not set up locks everything online`() {
        val facts = facts(ServerStatus(ServerState.NOT_CONFIGURED))
        assertEquals(locked("The server is not set up"), facts.serverGate())
        assertEquals(locked("The server is not set up"), facts.accountGate())
    }

    @Test
    fun `signed in, a lost connection looks different and says so`() {
        listOf(ServerState.UNREACHABLE, ServerState.NOT_CONFIGURED).forEach { state ->
            listOf(false, true).forEach { google ->
                val facts = facts(production.copy(state = state), signedIn = true, google = google)
                assertEquals(disconnected("Connection is currently unavailable"), facts.serverGate())
                assertEquals(disconnected("Connection is currently unavailable"), facts.accountGate())
            }
        }
        assertNotEquals(
            facts(production.copy(state = ServerState.UNREACHABLE), signedIn = false).serverGate()?.look,
            facts(production.copy(state = ServerState.UNREACHABLE), signedIn = true).serverGate()?.look,
        )
    }

    @Test
    fun `while checking, nothing online can be used`() {
        val checking = ServerStatus(ServerState.CHECKING)
        assertEquals(locked("Checking the connection"), facts(checking).serverGate())
        assertEquals(disconnected("Checking the connection"), facts(checking, signedIn = true).accountGate())
    }

    @Test
    fun `without sign-in values, only features that need an account are locked`() {
        val facts = facts(production, google = false)
        assertNull(facts.serverGate())
        assertEquals(locked("Sign-in is down right now"), facts.accountGate())
    }

    @Test
    fun `a server without sign-in locks features that need an account`() {
        val server = production.copy(auth = HealthAuth.NOT_CONFIGURED)
        listOf(false, true).forEach { signedIn ->
            assertNull(facts(server, signedIn).serverGate())
            assertEquals(locked("Sign-in is down right now"), facts(server, signedIn).accountGate())
        }
    }

    @Test
    fun `a demo server takes demo accounts and not Google accounts`() {
        assertNull(facts(demo, google = false, demo = true).accountGate())
        assertEquals(locked("Sign-in is down right now"), facts(demo, google = true, demo = false).accountGate())
        assertEquals(locked("Sign-in is down right now"), facts(production, google = false, demo = true).accountGate())
    }

    @Test
    fun `googleSignInGate requires a reachable production server with auth and Google keys`() {
        assertNull(facts(production, google = true, demo = true).googleSignInGate())
        assertEquals(locked("Sign-in is down right now"), facts(production, google = false).googleSignInGate())
        assertEquals(locked("Sign-in is down right now"), facts(production.copy(auth = HealthAuth.NOT_CONFIGURED), google = true).googleSignInGate())
        assertEquals(locked("Sign-in is down right now"), facts(demo, google = true, demo = true).googleSignInGate())
        assertEquals(locked("The server is down right now"), facts(production.copy(state = ServerState.UNREACHABLE), google = true).googleSignInGate())
        assertEquals(locked("The server is not set up"), facts(ServerStatus(ServerState.NOT_CONFIGURED), google = true).googleSignInGate())
        assertEquals(locked("Checking the connection"), facts(ServerStatus(ServerState.CHECKING), google = true).googleSignInGate())
    }
}
