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
import io.github.serogee.hive.localhost.HostingService
import io.github.serogee.hive.localhost.LocalHost
import io.github.serogee.hive.localhost.NsdNearbyRooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
    val nearbyRooms = NsdNearbyRooms(application)
    val rooms = RoomRepository(api, preferences, localHost, localRoomStore, nearbyRooms)
    val stacks = StackRepository(database.stacks())
    val reviews = ReviewRepository(database.reviews())

    // What the tabs show. These are read and decoded off the main thread, and stay loaded for a while
    // after a tab closes, so that a tab opens with its content. Null means not loaded yet.
    val session = auth.session.kept()
    val preferredName = preferences.preferredName.kept()
    val joinedRoom = rooms.joinedRoom.kept()
    val hostedRooms = rooms.localHostedRooms.kept()
    val stackList = stacks.stacks.kept()
    val latestUnfinishedReview = reviews.latestUnfinished.kept()
    val finishedReviews = reviews.finished.kept()

    private fun <T> Flow<T>.kept(): StateFlow<T?> = stateIn(appScope, SharingStarted.WhileSubscribed(KEEP_MILLIS), null)

    /** Stops hosting after every room has ended, once Players have had time to get the results. */
    fun stopHostingSoon() = appScope.launch {
        delay(RESULTS_MILLIS)
        rooms.stopHostingWhenIdle()
    }

    private companion object {
        const val RESULTS_MILLIS = 60_000L
        const val KEEP_MILLIS = 60_000L
    }
}

class HiveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.auth.restore() }
        // Keeps the app running while the embedded server runs, and lets it close afterwards.
        container.appScope.launch {
            container.rooms.localHostRunning.collect { running ->
                // Android does not allow the start while the app is in the background.
                runCatching { if (running) HostingService.start(this@HiveApplication) else HostingService.stop(this@HiveApplication) }
            }
        }
        container.appScope.launch { container.rooms.joinableCodes.collect(container.nearbyRooms::announce) }
    }
}
