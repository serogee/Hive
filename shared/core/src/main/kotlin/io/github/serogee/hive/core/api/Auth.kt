package io.github.serogee.hive.core.api

import io.github.serogee.hive.core.model.AccountId
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: AccountId,
    val displayName: String,
)

/** What an account shows to others. [displayName] is the name in sessions, on every phone. */
@Serializable
data class AccountProfile(val displayName: String)

@Serializable
data class DemoLoginRequest(
    val username: String,
    val password: String,
)

/** A signed-in session. Send [accessToken] as a bearer token. */
@Serializable
data class AuthSession(
    val accessToken: String,
    val account: Account,
)
