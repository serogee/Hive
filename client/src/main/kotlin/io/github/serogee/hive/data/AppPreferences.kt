package io.github.serogee.hive.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.serogee.hive.BuildConfig
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.model.RoomId
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
)

/** Small settings and credentials stored on this device. */
class AppPreferences(context: Context) {
    private val store = context.applicationContext.dataStore

    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL }
    val theme: Flow<ThemePreference> = store.data.map { prefs ->
        prefs[THEME]?.let { name -> ThemePreference.entries.firstOrNull { it.name == name } } ?: ThemePreference.SYSTEM
    }
    val answerColors: Flow<AnswerColorChoice> = store.data.map { AnswerColorChoice.of(it[ANSWER_COLORS]) }
    val session: Flow<AuthSession?> = store.data.map { it.decode(SESSION, AuthSession.serializer()) }
    val joinedRoom: Flow<SavedMembership?> = store.data.map { it.decode(JOINED_ROOM, SavedMembership.serializer()) }
    val preferredName: Flow<String?> = store.data.map { it[PREFERRED_NAME] }

    /** Whether the app opens to Home instead of Welcome. */
    val onboardingCompleted: Flow<Boolean> = store.data.map { it.onboardingCompleted() }

    /** Host credentials for rooms this phone hosts, by room ID. They never leave this phone. */
    val hostTokens: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs.decode(HOST_TOKENS, MapSerializer(String.serializer(), String.serializer())).orEmpty()
    }

    suspend fun currentServerUrl(): String = serverUrl.first()

    suspend fun currentSession(): AuthSession? = session.first()

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

    suspend fun setAnswerColors(choice: AnswerColorChoice) = store.edit { it[ANSWER_COLORS] = choice.name }

    suspend fun setSession(session: AuthSession?) = store.edit { prefs ->
        if (session == null) prefs.remove(SESSION) else prefs[SESSION] = HiveJson.encodeToString(AuthSession.serializer(), session)
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
        val SESSION = stringPreferencesKey("session")
        val JOINED_ROOM = stringPreferencesKey("joined_room")
        val PREFERRED_NAME = stringPreferencesKey("preferred_name")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val HOST_TOKENS = stringPreferencesKey("host_tokens")
    }
}
