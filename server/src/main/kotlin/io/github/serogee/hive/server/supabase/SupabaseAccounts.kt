package io.github.serogee.hive.server.supabase

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.JOSEException
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.github.serogee.hive.core.api.Account
import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.engine.Clock
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.host.AccountVerifier
import io.github.serogee.hive.host.HiveException
import java.net.URI
import java.text.ParseException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks Supabase access tokens with the project's public keys.
 *
 * The keys are cached, and fetched again when a token names a key that is not among them.
 */
class SupabaseAccounts(
    supabaseUrl: String,
    keys: JWKSource<SecurityContext> = remoteKeys(supabaseUrl),
    private val clock: Clock = Clock.System,
) : AccountVerifier {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(setOf(JWSAlgorithm.ES256, JWSAlgorithm.RS256), keys)
        val expected = JWTClaimsSet.Builder().issuer(issuer(supabaseUrl)).build()
        jwtClaimsSetVerifier = object : DefaultJWTClaimsVerifier<SecurityContext>(AUDIENCE, expected, setOf("sub", "exp")) {
            override fun currentTime() = Date(clock.nowMillis())
        }
    }

    override suspend fun verify(accessToken: String): Account? = withContext(Dispatchers.IO) {
        try {
            processor.process(accessToken, null).toAccount()
        } catch (_: KeySourceException) {
            // The keys cannot be fetched. The token may be good, so the user must not be signed out.
            throw HiveException(ErrorCode.AUTH_UNAVAILABLE, "Sign-in cannot be checked right now.")
        } catch (_: ParseException) {
            null
        } catch (_: BadJOSEException) {
            null
        } catch (_: JOSEException) {
            null
        }
    }

    private fun JWTClaimsSet.toAccount(): Account {
        val metadata = getClaim("user_metadata") as? Map<*, *>
        val name = listOf("full_name", "name").firstNotNullOfOrNull { key ->
            (metadata?.get(key) as? String)?.let { DisplayNames.normalize(it.take(DisplayNames.MAX_LENGTH)) }
        }
        return Account(AccountId(subject), name ?: DEFAULT_NAME)
    }

    companion object {
        private const val AUDIENCE = "authenticated"
        private const val DEFAULT_NAME = "Player"

        fun issuer(supabaseUrl: String) = "$supabaseUrl/auth/v1"

        private fun remoteKeys(supabaseUrl: String): JWKSource<SecurityContext> =
            JWKSourceBuilder.create<SecurityContext>(URI("${issuer(supabaseUrl)}/.well-known/jwks.json").toURL()).retrying(true).build()
    }
}
