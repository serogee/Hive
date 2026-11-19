package io.github.serogee.hive.server

import io.github.serogee.hive.server.demo.DemoAccounts
import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.DemoSeed
import io.github.serogee.hive.server.demo.SqliteRoomStore
import io.github.serogee.hive.server.demo.demoAuthRoutes
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.Inet4Address
import java.net.NetworkInterface

fun main(args: Array<String>) {
    val config = ServerConfig.from(args)
    check(config.mode == ServerMode.DEMO) {
        "Production mode needs Supabase settings, which are not available yet."
    }

    val db = DemoDatabase.open(config.demoDatabase)
    if (config.resetDemo) {
        DemoSeed.reset(db, System.currentTimeMillis())
        println("Reset the demo data in ${config.demoDatabase.toAbsolutePath()}")
        db.close()
        return
    }
    DemoSeed.seedIfEmpty(db, System.currentTimeMillis())

    val accounts = DemoAccounts(db)
    val backend = Backend(SqliteRoomStore(db), accounts) { demoAuthRoutes(accounts) }

    printAddresses(config.port)
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
