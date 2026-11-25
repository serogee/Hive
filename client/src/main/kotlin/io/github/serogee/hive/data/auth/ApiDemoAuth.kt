package io.github.serogee.hive.data.auth

import io.github.serogee.hive.data.remote.HiveApi

/** Demo sign-in on the configured server. */
class ApiDemoAuth(private val api: HiveApi) : DemoAuth {
    override suspend fun signIn(username: String, password: String) = api.demoLogin(username, password)

    override suspend fun signOut(accessToken: String) = api.demoLogout(accessToken)

    override suspend fun restore(accessToken: String) = api.me(accessToken)
}
