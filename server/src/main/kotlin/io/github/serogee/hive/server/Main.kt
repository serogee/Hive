package io.github.serogee.hive.server

import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.SqliteRoomStore
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
    val backend = Backend(SqliteRoomStore(db), AccountVerifier.None)

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
    addresses.forEach { println("Phones on this network can use $it") }
}