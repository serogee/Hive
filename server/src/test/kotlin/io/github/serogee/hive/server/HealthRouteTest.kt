package io.github.serogee.hive.server

import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.InMemoryRoomStore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    @Test
    fun healthReportsApiVersion() = testApplication {
        application { hiveModule(Backend(InMemoryRoomStore(), AccountVerifier.None)) }

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"apiVersion\":1"))
    }

    @Test
    fun configReadsOptionsBeforeEnvironment() {
        val config = ServerConfig.from(
            args = arrayOf("--port=9090", "--reset-demo"),
            env = mapOf("HIVE_PORT" to "7070", "HIVE_HOST" to "127.0.0.1", "HIVE_DEMO_DB" to "tmp/demo.db"),
        )

        assertEquals(9090, config.port)
        assertEquals("127.0.0.1", config.host)
        assertEquals(ServerMode.DEMO, config.mode)
        assertEquals(Path.of("tmp", "demo.db"), config.demoDatabase)
        assertTrue(config.resetDemo)
    }
}
