package io.github.serogee.hive

import android.app.Application
import io.github.serogee.hive.data.AppPreferences
import io.github.serogee.hive.data.auth.ApiDemoAuth
import io.github.serogee.hive.data.auth.AuthConfig
import io.github.serogee.hive.data.auth.AuthRepository
import io.github.serogee.hive.data.auth.GoogleIdTokens
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.data.auth.SupabaseAuth
import io.github.serogee.hive.data.history.QuizHistory
import io.github.serogee.hive.data.local.HiveDatabase
import io.github.serogee.hive.data.local.LocalRoomStore
import io.github.serogee.hive.data.remote.HiveApi
import io.github.serogee.hive.data.remote.jsonHttpClient
import io.github.serogee.hive.data.review.ReviewRepository
import io.github.serogee.hive.data.rooms.RoomRepository
import io.github.serogee.hive.data.rooms.StaleRoomChecker
import io.github.serogee.hive.data.server.OnlineFacts
import io.github.serogee.hive.data.server.ServerMonitor
import io.github.serogee.hive.data.server.ServerState
import io.github.serogee.hive.data.server.ServerStatus
import io.github.serogee.hive.data.server.ServerTriggers
import io.github.serogee.hive.data.stacks.StackRepository
import io.github.serogee.hive.data.sync.ApiSyncRemote
import io.github.serogee.hive.data.sync.RoomSyncLocal
import io.github.serogee.hive.core.api.AccountProfile
import io.github.serogee.hive.data.sync.ProfileRemote
import io.github.serogee.hive.data.sync.ProfileSync
import io.github.serogee.hive.data.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import io.github.serogee.hive.localhost.HostingService
import io.github.serogee.hive.localhost.LocalHost
import io.github.serogee.hive.localhost.NsdNearbyRooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Creates the app's long-lived objects once. */
class AppContainer(application: Application) {
    val appScope = CoroutineScope(SupervisorJob())
    val preferences = AppPreferences(application)

    val api: HiveApi = HiveApi(
        serverUrl = { preferences.currentServerUrl() },
        accessToken = { auth.accessToken() },
        onSessionExpired = { auth.sessionRefused() },
    )

    private val authConfig = AuthConfig.of(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, BuildConfig.GOOGLE_WEB_CLIENT_ID)

    /** Null when Google sign-in is not set up. */
    val googleIdTokens: GoogleIdTokens? = authConfig?.let { GoogleIdTokens(it.googleWebClientId) }

    val auth: AuthRepository = AuthRepository(
        sessions = preferences,
        supabase = authConfig?.let { SupabaseAuth(jsonHttpClient(), it) },
        demo = if (BuildConfig.DEBUG) ApiDemoAuth(api) else null,
    )
    val server = ServerMonitor(preferences.serverUrl, api::health, appScope)
    val database = HiveDatabase.create(application)
    private val localRoomStore = LocalRoomStore(database.localRooms())
    val localHost = LocalHost(localRoomStore)
    val nearbyRooms = NsdNearbyRooms(application)
    val quizHistory = QuizHistory(database.sync())
    val rooms = RoomRepository(api, preferences, localHost, localRoomStore, nearbyRooms) { results, joined ->
        joined.participantId?.let { quizHistory.recordPlayedNearby(results, it) }
    }
    val stacks = StackRepository(database.stacks())

    /** Asks the Host to close rooms that have been open for a day. */
    val staleRooms = StaleRoomChecker(rooms, preferences, signedIn = { auth.session.first() != null })
    val reviews = ReviewRepository(database.reviews())

    /** Signed-in accounts only. Guests keep everything on this phone. */
    /** The signed-in account's display name, on the server and every phone. */
    val profiles = ProfileSync(
        remote = object : ProfileRemote {
            override suspend fun displayName() = api.accountProfile().displayName

            override suspend fun setDisplayName(name: String) {
                api.setAccountProfile(AccountProfile(name))
            }
        },
        store = preferences,
    )

    val sync = SyncEngine(
        local = RoomSyncLocal(database),
        remote = ApiSyncRemote(api),
        states = preferences,
        accountId = { auth.session.first()?.account?.id?.value },
        server = { server.status.value },
        afterSync = { account ->
            try {
                profiles.sync(account)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An older server keeps no display names. The stacks synced all the same.
            }
        },
    )

    // What the tabs show. These are read and decoded off the main thread, and stay loaded for a while
    // after a tab closes, so that a tab opens with its content. Null means not loaded yet.
    val session = auth.session.kept()
    val preferredName = preferences.preferredName.kept()
    val joinedRoom = rooms.joinedRoom.kept()
    val hostedRooms = rooms.localHostedRooms.kept()
    val stackList = stacks.stacks.kept()
    val latestUnfinishedReview = reviews.latestUnfinished.kept()
    val finishedReviews = reviews.finished.kept()
    val quizResults = quizHistory.results.kept()

    val serverUrl = preferences.serverUrl.kept()

    /** Decides which online features work now. */
    val online: StateFlow<OnlineFacts> = combine(auth.session, server.status) { session, status -> facts(session != null, status) }
        .stateIn(appScope, SharingStarted.WhileSubscribed(KEEP_MILLIS), facts(signedIn = false, ServerStatus(ServerState.CHECKING)))

    private fun facts(signedIn: Boolean, status: ServerStatus) =
        OnlineFacts(auth.googleAvailable, auth.demoAvailable, signedIn, status)

    private fun <T> Flow<T>.kept(): StateFlow<T?> = stateIn(appScope, SharingStarted.WhileSubscribed(KEEP_MILLIS), null)

    /** Starts sync, and records each room that this phone hosted once it ends. */
    fun startSync() {
        sync.start(appScope, auth.session.map { it?.account?.id?.value }, server.status, database.sync().observePendingCount())
        appScope.launch {
            rooms.localHostedRooms.collect { hosted ->
                quizHistory.recordHostedHere(hosted) { room -> localRoomStore.participants(room.id).count { it.role == RoomRole.PLAYER } }
            }
        }
    }

    /** Signs out. [removeSyncedStacks] also deletes the stacks that the account keeps from this phone. */
    suspend fun signOut(removeSyncedStacks: Boolean = false) {
        auth.signOut()
        sync.forgetAccount(removeSyncedStacks)
        profiles.forget()
    }

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
        container.startSync()
        ServerTriggers(this, container.server) { container.sync.onForeground() }
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
