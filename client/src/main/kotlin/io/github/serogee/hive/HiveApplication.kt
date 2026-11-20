package io.github.serogee.hive

import android.app.Application
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.auth.AuthProvider
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.auth.DemoAuthProvider
import io.github.serogee.hive.data.local.HiveDatabase
import io.github.serogee.hive.data.remote.HiveApi
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.data.stacks.StackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates the app's long-lived objects once. */
class AppContainer(application: Application) {
    val appScope = CoroutineScope(SupervisorJob())
    val preferences = AppPreferences(application)

    val api = HiveApi(
        serverUrl = { preferences.currentServerUrl() },
        accessToken = { preferences.currentSession()?.accessToken },
        onSessionExpired = { preferences.setSession(null) },
    )

    private val authProvider: AuthProvider = when (BuildConfig.AUTH_PROVIDER) {
        "demo" -> DemoAuthProvider(api)
        else -> error("Unknown auth provider ${BuildConfig.AUTH_PROVIDER}")
    }

    val auth = AuthRepository(preferences, authProvider)
    val rooms = RoomRepository(api, preferences)
    val database = HiveDatabase.create(application)
    val stacks = StackRepository(database.stacks())
}

class HiveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.auth.restore() }
    }
}
