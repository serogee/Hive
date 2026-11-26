package io.github.serogee.hive.server

import io.github.serogee.hive.core.api.HealthMode
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.InMemoryRoomStore
import io.github.serogee.hive.server.demo.DemoAccounts
import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.DemoSeed
import io.github.serogee.hive.server.demo.SqliteRoomStore
import io.github.serogee.hive.server.demo.demoAuthRoutes
import io.github.serogee.hive.server.postgres.PostgresAccounts
import io.github.serogee.hive.server.postgres.PostgresDatabase
import io.github.serogee.hive.server.postgres.PostgresRoomStore
import io.github.serogee.hive.server.supabase.SupabaseAccounts
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.Inet4Address
import java.net.NetworkInterface

fun main(args: Array<String>) {
    val config = ServerConfig.from(args)
    when (config.mode) {
        ServerMode.DEMO -> runDemo(config)
        ServerMode.PRODUCTION -> runProduction(config)
    }
}

private fun runDemo(config: ServerConfig) {
    val db = DemoDatabase.open(config.demoDatabase)
    if (config.resetDemo) {
        DemoSeed.reset(db, System.currentTimeMillis())
        println("Reset the demo data in ${config.demoDatabase.toAbsolutePath()}")
        db.close()
        return
    }
    DemoSeed.seedIfEmpty(db, System.currentTimeMillis())

    val accounts = DemoAccounts(db)
    printAddresses(config.port)
    // A demo account has no sign-in elsewhere, so its rows are all there is.
    val deleter = AccountDeleter(db.accountData(), signIn = {})
    serve(config, Backend(SqliteRoomStore(db), accounts, HealthMode.DEMO, db.syncStore(), deleter, accounts) { demoAuthRoutes(accounts) })
}

private fun runProduction(config: ServerConfig) {
    val accounts = productionAccounts(config.supabaseUrl)
    if (!accounts.configured) {
        println("SUPABASE_URL is missing or is not a web address, so nobody can sign in. Guests can still join rooms.")
    }
    if (config.database == null) {
        println("DATABASE_URL is missing, so rooms are kept in memory and end when the server stops.")
        serve(config, Backend(InMemoryRoomStore(), accounts, HealthMode.PRODUCTION))
        return
    }
    val db = PostgresDatabase.open(config.database)
    val saved = PostgresAccounts(db, accounts)
    val secret = config.supabaseSecretKey
    val deleter = if (config.supabaseUrl != null && secret != null) {
        AccountDeleter(db.accountData(), SupabaseAdmin(config.supabaseUrl, secret), saved::forget)
    } else {
        println("SUPABASE_SECRET_KEY is missing, so accounts cannot be deleted.")
        null
    }
    serve(config, Backend(PostgresRoomStore(db), saved, HealthMode.PRODUCTION, db.syncStore(), deleter, saved))
}

/** Checks Supabase tokens, or accepts nobody without a usable [supabaseUrl], so that the server always starts. */
internal fun productionAccounts(supabaseUrl: String?): AccountVerifier {
    val url = supabaseUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return AccountVerifier.NotConfigured
    return runCatching { SupabaseAccounts(url) }.getOrDefault(AccountVerifier.NotConfigured)
}

private fun serve(config: ServerConfig, backend: Backend) {
    embeddedServer(CIO, host = config.host, port = config.port) {
        hiveModule(backend)
    }.start(wait = true)
}

/** Prints the addresses that phones on the same network can enter in Accounts. */
private fun printAddresses(port: Int) {
    val addresses = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .map { "http://${it.hostAddress}:$port" }
        .toList()
    println("Hive demo server. Demo accounts: ${DemoSeed.alex.username}, ${DemoSeed.sam.username} (password ${DemoSeed.PASSWORD})")
    addresses.forEach { println("Phones on this network can use $it") }
}
