package io.github.serogee.hive.data.sync

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProfileSyncTest {
    private class Server(var name: String = "Alex Rivera") : ProfileRemote {
        var reachable = true
        val sent = mutableListOf<String>()

        override suspend fun displayName(): String {
            if (!reachable) throw IOException("offline")
            return name
        }

        override suspend fun setDisplayName(name: String) {
            if (!reachable) throw IOException("offline")
            sent += name
            this.name = name
        }
    }

    private class Phone : AccountNameStore {
        var saved: SavedAccountName? = null

        override suspend fun accountName() = saved

        override suspend fun setAccountName(name: SavedAccountName?) {
            saved = name
        }
    }

    private val server = Server()
    private val phone = Phone()
    private val profiles = ProfileSync(server, phone)

    @Test
    fun `a sync takes the name that the account keeps`() = runTest {
        server.name = "Ms. Rivera"
        profiles.sync("ana")
        assertEquals(SavedAccountName("ana", "Ms. Rivera"), phone.saved)
    }

    @Test
    fun `a new name reaches the server at once when it is online`() = runTest {
        assertTrue(profiles.rename("ana", "Ms. Rivera"))
        assertEquals(listOf("Ms. Rivera"), server.sent)
        assertEquals(SavedAccountName("ana", "Ms. Rivera"), phone.saved)
    }

    @Test
    fun `a name changed offline waits, and the next sync sends it instead of taking the old one`() = runTest {
        server.reachable = false
        assertFalse(profiles.rename("ana", "Ms. Rivera"))
        assertEquals(SavedAccountName("ana", "Ms. Rivera", pending = true), phone.saved)

        server.reachable = true
        profiles.sync("ana")
        assertEquals(listOf("Ms. Rivera"), server.sent)
        assertEquals("Ms. Rivera", server.name)
        assertEquals(SavedAccountName("ana", "Ms. Rivera"), phone.saved)
    }

    @Test
    fun `another account never sends the name of the last one`() = runTest {
        phone.saved = SavedAccountName("ben", "Ben", pending = true)
        profiles.sync("ana")
        assertTrue(server.sent.isEmpty())
        assertEquals(SavedAccountName("ana", "Alex Rivera"), phone.saved)
    }

    @Test
    fun `sign-out forgets the name`() = runTest {
        profiles.sync("ana")
        profiles.forget()
        assertNull(phone.saved)
    }
}
