package io.github.serogee.hive.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    @Test
    fun healthReportsApiVersion() = testApplication {
        application { hiveModule(ServerConfig(ServerMode.DEMO, "127.0.0.1", 0)) }

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"apiVersion\":1"))
    }

    @Test
    fun configReadsOptionsBeforeEnvironment() {
        val config = ServerConfig.from(
            args = arrayOf("--port=9090"),
            env = mapOf("HIVE_PORT" to "7070", "HIVE_HOST" to "127.0.0.1"),
        )

        assertEquals(9090, config.port)
        assertEquals("127.0.0.1", config.host)
        assertEquals(ServerMode.DEMO, config.mode)
    }
}
