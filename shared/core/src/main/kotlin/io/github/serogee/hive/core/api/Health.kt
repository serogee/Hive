package io.github.serogee.hive.core.api

import kotlinx.serialization.Serializable

/** Version of the HTTP and WebSocket contract served under [ApiPaths.PREFIX]. */
const val API_VERSION = 1

@Serializable
data class HealthResponse(
    val status: String,
    val apiVersion: Int,
)
