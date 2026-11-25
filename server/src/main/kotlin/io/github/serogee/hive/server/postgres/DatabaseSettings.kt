package io.github.serogee.hive.server.postgres

import java.net.URI
import java.net.URLDecoder

/** How to reach Postgres. It never shows its password, for example in a log. */
class DatabaseSettings(val jdbcUrl: String, val user: String?, val password: String?) {
    override fun toString() = "DatabaseSettings($jdbcUrl)"

    companion object {
        private val LOCAL = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /**
         * Reads an address such as "postgresql://user:password@host:5432/postgres". Fails without repeating
         * the address, because it holds the password.
         */
        fun parse(url: String): DatabaseSettings {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val host = uri?.host
            require(uri != null && host != null && (uri.scheme == "postgresql" || uri.scheme == "postgres")) {
                "DATABASE_URL must look like postgresql://user:password@host:5432/database."
            }
            val (user, password) = uri.rawUserInfo.orEmpty().split(':', limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
            val port = if (uri.port > 0) uri.port else 5432
            val database = uri.path.trim('/').ifEmpty { "postgres" }
            val options = buildList {
                uri.rawQuery?.takeIf { it.isNotEmpty() }?.let(::add)
                // Anything beyond this computer is encrypted.
                if (uri.rawQuery?.contains("sslmode=") != true && host !in LOCAL) add("sslmode=require")
                // Connection poolers may not keep prepared statements between requests.
                add("prepareThreshold=0")
            }
            return DatabaseSettings("jdbc:postgresql://$host:$port/$database?${options.joinToString("&")}", user?.decode(), password?.decode())
        }

        private fun String.decode() = ifEmpty { null }?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
    }
}
