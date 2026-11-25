package io.github.serogee.hive.server.postgres

import io.github.serogee.hive.server.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DatabaseSettingsTest {
    @Test
    fun readsAPoolerAddress() {
        val settings = DatabaseSettings.parse("postgresql://postgres.abc:p%40ss%2Fw%3Ard+1@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres")
        assertEquals("jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require&prepareThreshold=0", settings.jdbcUrl)
        assertEquals("postgres.abc", settings.user)
        assertEquals("p@ss/w:rd+1", settings.password)
    }

    @Test
    fun keepsItsOptionsAndDefaults() {
        assertEquals("jdbc:postgresql://db.example:5432/postgres?sslmode=verify-full&prepareThreshold=0", DatabaseSettings.parse("postgres://u:p@db.example?sslmode=verify-full").jdbcUrl)
        assertEquals("jdbc:postgresql://localhost:5433/hive?prepareThreshold=0", DatabaseSettings.parse("postgresql://localhost:5433/hive").jdbcUrl)
    }

    @Test
    fun neverShowsThePassword() {
        val settings = DatabaseSettings.parse("postgresql://user:secret-word@db.example:5432/postgres")
        assertFalse("secret-word" in settings.toString())
        val config = ServerConfig.from(emptyArray(), mapOf("DATABASE_URL" to "postgresql://user:secret-word@db.example:5432/postgres"))
        assertNotNull(config.database)
        assertFalse("secret-word" in config.toString())

        listOf("mysql://user:secret-word@db.example/x", "user:secret-word@db.example", "postgresql://user:secret word@db.example/x").forEach { url ->
            val failure = assertFailsWith<IllegalArgumentException> { DatabaseSettings.parse(url) }
            assertFalse("secret" in failure.message.orEmpty())
        }
    }

    @Test
    fun thePortOfTheHostingServiceComesFirst() {
        fun port(vararg env: Pair<String, String>, args: Array<String> = emptyArray()) = ServerConfig.from(args, env.toMap()).port

        assertEquals(10_000, port("PORT" to "10000", "HIVE_PORT" to "7070"))
        assertEquals(7070, port("HIVE_PORT" to "7070"))
        assertEquals(8080, port())
        assertEquals(9090, port("PORT" to "10000", args = arrayOf("--port=9090")))
    }

    @Test
    fun withoutAnAddressThereIsNoDatabase() {
        assertNull(ServerConfig.from(emptyArray(), emptyMap()).database)
        assertNull(ServerConfig.from(emptyArray(), mapOf("DATABASE_URL" to " ")).database)
    }
}
