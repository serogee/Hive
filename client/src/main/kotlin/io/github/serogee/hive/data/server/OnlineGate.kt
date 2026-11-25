package io.github.serogee.hive.data.server

import io.github.serogee.hive.core.api.HealthAuth
import io.github.serogee.hive.core.api.HealthMode

/** How a feature that cannot be used now looks. */
enum class GateLook {
    /** For people who are not signed in: a lock. */
    LOCKED,

    /** For people who are signed in and lost the connection: a crossed-out cloud and a dashed outline. */
    DISCONNECTED,
}

/** Why a feature cannot be used now. */
data class Gate(val look: GateLook, val reason: String)

/** Everything that decides which online features work. */
data class OnlineFacts(
    /** Whether this app has the Google sign-in values. */
    val googleAvailable: Boolean,
    /** Whether this app has the demo accounts. */
    val demoAvailable: Boolean,
    val signedIn: Boolean,
    val server: ServerStatus,
) {
    /** Whether a sign-in from this app means something to the server. */
    private val signInWorks: Boolean
        get() = server.auth == HealthAuth.CONFIGURED && if (server.mode == HealthMode.DEMO) demoAvailable else googleAvailable

    /** The gate of a feature that needs only the server, such as joining an online room as a guest. */
    fun serverGate(): Gate? {
        if (server.state == ServerState.ONLINE) return null
        if (signedIn) {
            val reason = if (server.state == ServerState.CHECKING) CHECKING else DISCONNECTED
            return Gate(GateLook.DISCONNECTED, reason)
        }
        return Gate(
            GateLook.LOCKED,
            when (server.state) {
                ServerState.CHECKING -> CHECKING
                ServerState.NOT_CONFIGURED -> NO_SERVER
                else -> SERVER_DOWN
            },
        )
    }

    /** The gate of a feature that also needs an account, such as hosting online. */
    fun accountGate(): Gate? = serverGate() ?: if (signInWorks) null else Gate(GateLook.LOCKED, SIGN_IN_DOWN)

    companion object {
        const val CHECKING = "Checking the connection"
        const val DISCONNECTED = "Connection is currently unavailable"
        const val NO_SERVER = "The server is not set up"
        const val SERVER_DOWN = "The server is down right now"
        const val SIGN_IN_DOWN = "Sign-in is down right now"
    }
}
