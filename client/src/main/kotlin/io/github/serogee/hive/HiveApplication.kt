package io.github.serogee.hive

import android.app.Application
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.auth.AuthProvider
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.auth.DemoAuthProvider
import io.github.serogee.hive.data.local.HiveDatabase
import io.github.serogee.hive.data.local.LocalRoomStore
import io.github.serogee.hive.data.remote.HiveApi
import io.github.serogee.hive.data.review.ReviewRepository
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.data.stacks.StackRepository
import io.github.serogee.hive.localhost.LocalHost
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
    val database = HiveDatabase.create(application)
    private val localRoomStore = LocalRoomStore(database.localRooms())
    val localHost = LocalHost(localRoomStore)
    val rooms = RoomRepository(api, preferences, localHost, localRoomStore)
    val stacks = StackRepository(database.stacks())
    val reviews = ReviewRepository(database.reviews())
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
