package io.github.serogee.hive.server.sync

import io.github.serogee.hive.server.demo.DemoDatabase
import io.github.serogee.hive.server.demo.DemoSeed
import java.nio.file.Files
import kotlin.test.AfterTest

class SqliteSyncStoreTest : SyncStoreContract() {
    private val directory = Files.createTempDirectory("hive-sync-test")
    private val db = DemoDatabase.open(directory.resolve("demo.db")).also { DemoSeed.reset(it, now = 1_000) }
    override val store = db.syncStore()
    override val alex = DemoSeed.alex.id
    override val sam = DemoSeed.sam.id

    @AfterTest
    fun cleanUp() {
        db.close()
        directory.toFile().deleteRecursively()
    }
}
