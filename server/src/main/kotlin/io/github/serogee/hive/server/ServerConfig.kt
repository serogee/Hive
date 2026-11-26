package io.github.serogee.hive.server

import io.github.serogee.hive.server.postgres.DatabaseSettings
import java.nio.file.Path

enum class ServerMode {
    DEMO,
    PRODUCTION,
}

/**
 * Startup settings for the standalone server.
 *
 * Each value comes from a command-line option, then an environment variable, then a default.
 */
data class ServerConfig(
    val mode: ServerMode,
    val host: String,
    val port: Int,
    val demoDatabase: Path = Path.of("data", "hive-demo.db"),
    /** The Supabase project that signs users in, or null when it is not set up. Used in production. */
    val supabaseUrl: String? = null,
    /** Where production keeps rooms, or null to keep them in memory. */
    val database: DatabaseSettings? = null,
    /** The Supabase secret key, which deletes accounts. Null when it is not set up. */
    val supabaseSecretKey: Secret? = null,
    /** Replace the demo data with fresh seed data, then exit. */
    val resetDemo: Boolean = false,
) {
    companion object {
        fun from(args: Array<String>, env: Map<String, String> = System.getenv()): ServerConfig {
            val options = args
                .filter { it.startsWith("--") && it.contains('=') }
                .associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }

            fun value(option: String, variable: String): String? = options[option] ?: env[variable]

            val mode = when (val raw = value("mode", "HIVE_MODE")?.lowercase() ?: "demo") {
                "demo" -> ServerMode.DEMO
                "production" -> ServerMode.PRODUCTION
                else -> error("Unknown server mode '$raw'. Use demo or production.")
            }
            return ServerConfig(
                mode = mode,
                host = value("host", "HIVE_HOST") ?: "0.0.0.0",
                // Hosting services choose the port and pass it in PORT.
                port = (options["port"] ?: env["PORT"] ?: env["HIVE_PORT"])?.toInt() ?: 8080,
                demoDatabase = value("demo-db", "HIVE_DEMO_DB")?.let(Path::of) ?: Path.of("data", "hive-demo.db"),
                supabaseUrl = value("supabase-url", "SUPABASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
                // Only from the environment, because other users of a computer can see command lines.
                database = env["DATABASE_URL"]?.takeIf { it.isNotBlank() }?.let(DatabaseSettings::parse),
                supabaseSecretKey = env["SUPABASE_SECRET_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::Secret),
                resetDemo = "--reset-demo" in args,
            )
        }
    }
}
