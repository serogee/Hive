package io.github.serogee.hive.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.serogee.hive.BuildConfig
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.data.auth.SessionStore
import io.github.serogee.hive.data.auth.UserSession
import io.github.serogee.hive.data.sync.AccountNameStore
import io.github.serogee.hive.data.sync.SavedAccountName
import io.github.serogee.hive.data.sync.SyncState
import io.github.serogee.hive.data.sync.SyncStateStore
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.theme.AnswerColorChoice
import io.github.serogee.hive.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import androidx.datastore.preferences.core.booleanPreferencesKey

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hive")

/** The room this device last joined as a Player, kept so the Player can reconnect. */
@Serializable
data class SavedMembership(
    val serverUrl: String,
    val roomId: RoomId,
    val roomCode: String,
    val roomTitle: String,
    val displayName: String,
    val participantToken: String,
    /** Set when the room ended. Its results stay open, but the Player is free to join another room. */
    val ended: Boolean = false,
    /** Null for a membership saved before it was kept. */
    val participantId: ParticipantId? = null,
    val isGuest: Boolean = true,
    val isOnline: Boolean = false,
)

/** Small settings and credentials stored on this device. */
class AppPreferences(context: Context) : SessionStore, SyncStateStore, AccountNameStore {
    private val store = context.applicationContext.dataStore

    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL }
    val theme: Flow<ThemePreference> = store.data.map { prefs ->
        prefs[THEME]?.let { name -> ThemePreference.entries.firstOrNull { it.name == name } } ?: ThemePreference.SYSTEM
    }
    val answerColors: Flow<AnswerColorChoice> = store.data.map { AnswerColorChoice.of(it[ANSWER_COLORS]) }
    val answerInput: Flow<AnswerInput> = store.data.map { AnswerInput.of(it[ANSWER_INPUT]) }
    override val session: Flow<UserSession?> = store.data.map { it.decode(SESSION, UserSession.serializer()) }
    val joinedRoom: Flow<SavedMembership?> = store.data.map { it.decode(JOINED_ROOM, SavedMembership.serializer()) }
    val preferredName: Flow<String?> = store.data.map { it[PREFERRED_NAME] }

    /** Rooms whose "close it?" question waits until a time, by room ID. */
    val roomSnoozes: Flow<Map<String, Long>> = store.data.map { prefs ->
        prefs.decode(ROOM_SNOOZES, MapSerializer(String.serializer(), Long.serializer())).orEmpty()
    }

    /** The signed-in account's display name, which can differ from the name of the sign-in. */
    val accountName: Flow<SavedAccountName?> = store.data.map { it.decode(ACCOUNT_NAME, SavedAccountName.serializer()) }

    override suspend fun accountName(): SavedAccountName? = accountName.first()

    override suspend fun setAccountName(name: SavedAccountName?) {
        store.edit { prefs ->
            if (name == null) prefs.remove(ACCOUNT_NAME) else prefs[ACCOUNT_NAME] = HiveJson.encodeToString(SavedAccountName.serializer(), name)
        }
    }

    /** Whether Check for updates also offers test versions. */
    val includePrereleases: Flow<Boolean> = store.data.map { it[INCLUDE_PRERELEASES] ?: false }

    /** Whether the app opens to Home instead of Welcome. */
    val onboardingCompleted: Flow<Boolean> = store.data.map { it.onboardingCompleted() }

    /** Host credentials for rooms this phone hosts, by room ID. They never leave this phone. */
    val hostTokens: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs.decode(HOST_TOKENS, MapSerializer(String.serializer(), String.serializer())).orEmpty()
    }

    suspend fun currentServerUrl(): String = serverUrl.first()

    override suspend fun syncState(): SyncState? = store.data.first().decode(SYNC_STATE, SyncState.serializer())

    override suspend fun setSyncState(state: SyncState?) {
        store.edit { prefs ->
            if (state == null) prefs.remove(SYNC_STATE) else prefs[SYNC_STATE] = HiveJson.encodeToString(SyncState.serializer(), state)
        }
    }

    override suspend fun currentSession(): UserSession? = session.first()

    suspend fun setServerUrl(url: String) = store.edit { it[SERVER_URL] = url }

    suspend fun setPreferredName(name: String) = store.edit { it[PREFERRED_NAME] = name }

    /** Finishes Welcome, so that the app opens to Home from now on. */
    suspend fun completeOnboarding() = store.edit { it[ONBOARDING_COMPLETED] = true }

    suspend fun addHostToken(roomId: RoomId, token: String) = store.edit { prefs ->
        val serializer = MapSerializer(String.serializer(), String.serializer())
        val tokens = prefs.decode(HOST_TOKENS, serializer).orEmpty() + (roomId.value to token)
        prefs[HOST_TOKENS] = HiveJson.encodeToString(serializer, tokens)
    }

    suspend fun setTheme(theme: ThemePreference) = store.edit { it[THEME] = theme.name }

    suspend fun setIncludePrereleases(include: Boolean) = store.edit { it[INCLUDE_PRERELEASES] = include }

    /** Waits with the question about [roomId] until [until]. Times that passed are forgotten. */
    suspend fun snoozeRoom(roomId: RoomId, until: Long) = store.edit { prefs ->
        val serializer = MapSerializer(String.serializer(), Long.serializer())
        val now = System.currentTimeMillis()
        val snoozes = prefs.decode(ROOM_SNOOZES, serializer).orEmpty().filterValues { it > now } + (roomId.value to until)
        prefs[ROOM_SNOOZES] = HiveJson.encodeToString(serializer, snoozes)
    }

    suspend fun setAnswerColors(choice: AnswerColorChoice) = store.edit { it[ANSWER_COLORS] = choice.name }

    suspend fun setAnswerInput(input: AnswerInput) = store.edit { it[ANSWER_INPUT] = input.name }

    override suspend fun setSession(session: UserSession?) {
        store.edit { prefs ->
            if (session == null) prefs.remove(SESSION) else prefs[SESSION] = HiveJson.encodeToString(UserSession.serializer(), session)
        }
    }

    suspend fun setJoinedRoom(membership: SavedMembership?) = store.edit { prefs ->
        if (membership == null) {
            prefs.remove(JOINED_ROOM)
        } else {
            prefs[JOINED_ROOM] = HiveJson.encodeToString(SavedMembership.serializer(), membership)
            prefs[PREFERRED_NAME] = membership.displayName
        }
    }

    private fun <T> Preferences.decode(key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
        this[key]?.let { runCatching { HiveJson.decodeFromString(serializer, it) }.getOrNull() }

    internal companion object {
        /** True after Welcome, and for people who had a name or a session before Welcome existed. */
        fun Preferences.onboardingCompleted(): Boolean =
            this[ONBOARDING_COMPLETED] ?: (contains(PREFERRED_NAME) || contains(SESSION))

        val SERVER_URL = stringPreferencesKey("server_url")
        val THEME = stringPreferencesKey("theme")
        val ANSWER_COLORS = stringPreferencesKey("answer_colors")
        val ANSWER_INPUT = stringPreferencesKey("answer_input")
        val SESSION = stringPreferencesKey("session")
        val JOINED_ROOM = stringPreferencesKey("joined_room")
        val PREFERRED_NAME = stringPreferencesKey("preferred_name")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val HOST_TOKENS = stringPreferencesKey("host_tokens")
        val SYNC_STATE = stringPreferencesKey("sync_state")
        val INCLUDE_PRERELEASES = booleanPreferencesKey("include_prereleases")
        val ROOM_SNOOZES = stringPreferencesKey("room_snoozes")
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
    }
}
