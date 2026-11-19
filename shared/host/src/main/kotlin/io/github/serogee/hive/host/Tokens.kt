package io.github.serogee.hive.host

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Creates and hashes opaque credentials. */
object Tokens {
    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
