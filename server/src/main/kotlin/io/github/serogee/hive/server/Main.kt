package io.github.serogee.hive.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main(args: Array<String>) {
    val config = ServerConfig.from(args)
    embeddedServer(CIO, host = config.host, port = config.port) {
        hiveModule(config)
    }.start(wait = true)
}
