package io.github.serogee.hive.server.supabase

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.host.HiveException
import java.net.URI
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SupabaseAccountsTest {
    private val url = "https://hive-test.supabase.co"
    private val now = 1_800_000_000_000L
    private val ecKey = ECKeyGenerator(Curve.P_256).keyID("ec").generate()
    private val rsaKey = RSAKeyGenerator(2048).keyID("rsa").generate()

    private fun keys(vararg keys: JWK) = JWKSource<SecurityContext> { selector, _ -> selector.select(JWKSet(keys.toList())) }

    private fun accounts(keys: JWKSource<SecurityContext> = keys(ecKey.toPublicJWK(), rsaKey.toPublicJWK())) =
        SupabaseAccounts(url, keys) { now }

    private fun claims(change: JWTClaimsSet.Builder.() -> Unit = {}): JWTClaimsSet = JWTClaimsSet.Builder()
        .subject("user-1")
        .issuer("$url/auth/v1")
        .audience("authenticated")
        .expirationTime(Date(now + 3_600_000))
        .claim("user_metadata", mapOf("full_name" to "Ana Reyes", "name" to "Ana"))
        .apply(change)
        .build()

    private fun sign(claims: JWTClaimsSet, key: JWK = ecKey, keyId: String = key.keyID): String {
        val (algorithm, signer) = when (key) {
            is ECKey -> JWSAlgorithm.ES256 to ECDSASigner(key) as JWSSigner
            is RSAKey -> JWSAlgorithm.RS256 to RSASSASigner(key) as JWSSigner
            else -> error("unexpected key")
        }
        return SignedJWT(JWSHeader.Builder(algorithm).keyID(keyId).build(), claims).apply { sign(signer) }.serialize()
    }

    @Test
    fun validTokensOfBothAlgorithmsNameTheAccount() = runTest {
        val account = Account(AccountId("user-1"), "Ana Reyes")
        assertEquals(account, accounts().verify(sign(claims(), ecKey)))
        assertEquals(account, accounts().verify(sign(claims(), rsaKey)))
    }

    @Test
    fun theNameComesFromTheMetadata() = runTest {
        suspend fun name(metadata: Map<String, Any?>?) = accounts().verify(sign(claims { claim("user_metadata", metadata) }))?.displayName

        assertEquals("Ana", name(mapOf("name" to "Ana")))
        assertEquals("Ana", name(mapOf("full_name" to "  ", "name" to "Ana")))
        assertEquals("Player", name(emptyMap()))
        assertEquals("Player", name(null))
        assertEquals(30, name(mapOf("full_name" to "x".repeat(80)))?.length)
    }

    @Test
    fun expiredTokensAreRejected() = runTest {
        assertNull(accounts().verify(sign(claims { expirationTime(Date(now - 120_000)) })))
        assertNull(accounts().verify(sign(claims { expirationTime(null) })))
    }

    @Test
    fun tokensOfAnotherIssuerAreRejected() = runTest {
        assertNull(accounts().verify(sign(claims { issuer("https://other.supabase.co/auth/v1") })))
    }

    @Test
    fun tokensForAnotherAudienceAreRejected() = runTest {
        assertNull(accounts().verify(sign(claims { audience("anon") })))
    }

    @Test
    fun tokensWithoutASubjectAreRejected() = runTest {
        assertNull(accounts().verify(sign(claims { subject(null) })))
    }

    @Test
    fun tokensWithAnUnknownKeyAreRejected() = runTest {
        val other = ECKeyGenerator(Curve.P_256).keyID("other").generate()
        assertNull(accounts().verify(sign(claims(), other)))
        assertNull(accounts().verify(sign(claims(), other, keyId = "ec")), "a known key ID with another key")
    }

    @Test
    fun tokensThatArePlainSymmetricOrBrokenAreRejected() = runTest {
        val symmetric = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims()).apply { sign(MACSigner(ByteArray(32) { 7 })) }
        assertNull(accounts().verify(PlainJWT(claims()).serialize()))
        assertNull(accounts().verify(symmetric.serialize()))
        assertNull(accounts().verify("not a token"))
        assertNull(accounts().verify(sign(claims()).dropLast(6) + "AAAAAA"))
    }

    @Test
    fun keysAreCachedAndFetchedAgainForAnUnknownKey() = runTest {
        var fetches = 0
        var published = JWKSet(listOf<JWK>(ecKey.toPublicJWK()))
        val retriever = ResourceRetriever {
            fetches++
            Resource(published.toString(), "application/json")
        }
        val source = JWKSourceBuilder.create<SecurityContext>(URI("https://hive-test.supabase.co/keys").toURL(), retriever)
            .rateLimited(false)
            .build()
        val accounts = accounts(source)

        repeat(3) { assertEquals("user-1", accounts.verify(sign(claims(), ecKey))?.id?.value) }
        assertEquals(1, fetches)

        published = JWKSet(listOf<JWK>(ecKey.toPublicJWK(), rsaKey.toPublicJWK()))
        assertEquals("user-1", accounts.verify(sign(claims(), rsaKey))?.id?.value)
        assertEquals(2, fetches)
    }

    @Test
    fun aTokenIsNotRejectedWhenTheKeysCannotBeFetched() = runTest {
        val failing = JWKSource<SecurityContext> { _, _ -> throw KeySourceException("offline") }
        val failure = assertFailsWith<HiveException> { accounts(failing).verify(sign(claims())) }
        assertEquals(ErrorCode.AUTH_UNAVAILABLE, failure.code)
    }
}
