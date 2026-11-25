package io.github.serogee.hive.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the HTTP and WebSocket contract served under [ApiPaths.PREFIX]. */
const val API_VERSION = 1

/** Which kind of server answered. */
@Serializable
enum class HealthMode {
    @SerialName("demo")
    DEMO,

    @SerialName("production")
    PRODUCTION,

    /** A room server on a Host's phone. */
    @SerialName("phone")
    PHONE,
}

/** Whether a server can check who a caller is. */
@Serializable
enum class HealthAuth {
    @SerialName("configured")
    CONFIGURED,

    @SerialName("not_configured")
    NOT_CONFIGURED,
}

@Serializable
data class HealthResponse(
    val status: String,
    val apiVersion: Int,
    /** Null from a server that does not say. */
    val mode: HealthMode? = null,
    val auth: HealthAuth = HealthAuth.NOT_CONFIGURED,
) {
    val isOk: Boolean get() = status == OK

    companion object {
        const val OK = "ok"
    }
}
