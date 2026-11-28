package io.github.serogee.hive.audio

import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus

/** Separate sound preferences apply to solo self-review and multiplayer sessions. */
enum class AudioScope {
    SOLO,
    MULTIPLAYER,
}

/** Looping background music tracks bundled with the app. */
enum class MusicTrack {
    LOBBY,
    QUIZ,
}

/** Short one-shot sound effects bundled with the app. */
enum class Sfx {
    TAP,
    LOCK_IN,
    CORRECT,
    INCORRECT,
    FLIP,
    NEXT,
    TICK,
    TIME_UP,
    PLAYER_JOIN,
    QUIZ_START,
    RESULTS_FANFARE,
}

/** Saved on-device toggles for solo review and multiplayer audio. */
data class SoundPreferences(
    val soloSfx: Boolean = true,
    val soloMusic: Boolean = true,
    val multiSfx: Boolean = true,
    val multiMusic: Boolean = true,
) {
    fun sfxEnabled(scope: AudioScope): Boolean = when (scope) {
        AudioScope.SOLO -> soloSfx
        AudioScope.MULTIPLAYER -> multiSfx
    }

    fun musicEnabled(scope: AudioScope): Boolean = when (scope) {
        AudioScope.SOLO -> soloMusic
        AudioScope.MULTIPLAYER -> multiMusic
    }
}

/** Narrow interface for ViewModels and screens to fire sound effects cleanly in tests and production. */
interface SoundCuePlayer {
    fun playSfx(cue: Sfx, scope: AudioScope)

    /** Plays [cue] at most once for [key], preventing duplicate cues across rotations or reconnects. */
    fun playSfxOnce(key: String, cue: Sfx, scope: AudioScope)

    /** Plays [cue] at most once every [minIntervalMillis] within [bucket]. */
    fun playSfxThrottled(bucket: String, minIntervalMillis: Long, cue: Sfx, scope: AudioScope)
}

/** Default no-op player used by unit tests that do not inspect audio. */
object NoOpSoundCuePlayer : SoundCuePlayer {
    override fun playSfx(cue: Sfx, scope: AudioScope) = Unit

    override fun playSfxOnce(key: String, cue: Sfx, scope: AudioScope) = Unit

    override fun playSfxThrottled(bucket: String, minIntervalMillis: Long, cue: Sfx, scope: AudioScope) = Unit
}

/** Deduplicates one-shot cue keys and throttles bursty buckets such as lobby joins. */
class CueGuard(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxKeys: Int = 256,
) {
    private val claimedKeys = LinkedHashSet<String>()
    private val lastBucketPlayedAt = mutableMapOf<String, Long>()

    @Synchronized
    fun tryClaimKey(key: String): Boolean {
        if (key in claimedKeys) return false
        claimedKeys.add(key)
        if (claimedKeys.size > maxKeys) {
            val iterator = claimedKeys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    @Synchronized
    fun tryAcquireBucket(bucket: String, minIntervalMillis: Long): Boolean {
        val now = nowMillis()
        val last = lastBucketPlayedAt[bucket]
        if (last != null && now - last < minIntervalMillis) return false
        lastBucketPlayedAt[bucket] = now
        return true
    }
}

/** Whether music controls are shown for a room and which track should loop right now. */
data class RoomMusicPolicy(
    val allowed: Boolean,
    val track: MusicTrack?,
)

/**
 * Decides room music behavior from the room mode and role without a server-side setting.
 *
 * - Discussion: music plays on the Host phone during Lobby and Live; Player phones stay music-free.
 * - Asynchronous: Host hears Lobby music only (rooms can stay open for days); Players hear Lobby
 *   music before start and Quiz music only while their own attempt is active.
 */
fun roomMusicPolicy(room: RoomState): RoomMusicPolicy {
    val isHost = room.you.role == RoomRole.HOST
    return when (room.room.status) {
        RoomStatus.LOBBY -> when {
            isHost -> RoomMusicPolicy(allowed = true, track = MusicTrack.LOBBY)
            room.room.mode == RoomMode.ASYNCHRONOUS -> RoomMusicPolicy(allowed = true, track = MusicTrack.LOBBY)
            else -> RoomMusicPolicy(allowed = false, track = null)
        }
        RoomStatus.LIVE -> when (room.room.mode) {
            RoomMode.DISCUSSION -> if (isHost) {
                RoomMusicPolicy(allowed = true, track = MusicTrack.QUIZ)
            } else {
                RoomMusicPolicy(allowed = false, track = null)
            }
            RoomMode.ASYNCHRONOUS -> if (isHost) {
                RoomMusicPolicy(allowed = false, track = null)
            } else {
                val attempt = room.attempt
                when {
                    attempt?.finished == true -> RoomMusicPolicy(allowed = false, track = null)
                    attempt?.started == true -> RoomMusicPolicy(allowed = true, track = MusicTrack.QUIZ)
                    else -> RoomMusicPolicy(allowed = true, track = null)
                }
            }
        }
        RoomStatus.ENDED -> RoomMusicPolicy(allowed = false, track = null)
    }
}

data class ActiveMusicRequest(
    val sessionKey: String,
    val scope: AudioScope,
    val track: MusicTrack,
)

/**
 * Pure state machine for background music selection and per-session overrides.
 *
 * Overrides are keyed by session ID so the top-bar toggle survives screen rotation and state changes
 * inside a session without modifying saved Settings.
 */
data class SessionMusicState(
    val preferences: SoundPreferences = SoundPreferences(),
    val talkBackEnabled: Boolean = false,
    val sessionOverrides: Map<String, Boolean> = emptyMap(),
    val activeRequest: ActiveMusicRequest? = null,
) {
    /** Whether the saved Settings allow music in [scope], so that the top-bar button is shown. */
    fun isMusicAvailable(scope: AudioScope): Boolean = preferences.musicEnabled(scope)

    /**
     * Effective music toggle state for [sessionKey] in [scope]. Music plays only when Settings allow
     * it; the in-session override can then mute it, or turn it on after the TalkBack default.
     */
    fun isMusicEnabled(sessionKey: String, scope: AudioScope): Boolean {
        if (!isMusicAvailable(scope)) return false
        sessionOverrides[sessionKey]?.let { return it }
        return !talkBackEnabled
    }

    /** Flips the in-session music override for [sessionKey]. Does nothing when Settings turn music off. */
    fun toggled(sessionKey: String, scope: AudioScope): SessionMusicState {
        if (!isMusicAvailable(scope)) return this
        val next = !isMusicEnabled(sessionKey, scope)
        val updated = LinkedHashMap(sessionOverrides)
        updated.remove(sessionKey)
        updated[sessionKey] = next
        while (updated.size > MAX_SESSION_OVERRIDES) {
            val oldest = updated.keys.firstOrNull() ?: break
            updated.remove(oldest)
        }
        return copy(sessionOverrides = updated)
    }

    /** Sets or clears the currently active music track for [sessionKey]. */
    fun withRequest(sessionKey: String, scope: AudioScope, track: MusicTrack?): SessionMusicState =
        if (track != null) {
            copy(activeRequest = ActiveMusicRequest(sessionKey, scope, track))
        } else if (activeRequest?.sessionKey == sessionKey) {
            copy(activeRequest = null)
        } else {
            this
        }

    /** Clears the active music request if it belongs to [sessionKey], keeping the session override. */
    fun clearedSession(sessionKey: String): SessionMusicState =
        if (activeRequest?.sessionKey == sessionKey) copy(activeRequest = null) else this

    /** The track that should be playing right now, or null if music should be stopped. */
    fun effectiveTrack(): MusicTrack? {
        val request = activeRequest ?: return null
        return if (isMusicEnabled(request.sessionKey, request.scope)) request.track else null
    }

    private companion object {
        const val MAX_SESSION_OVERRIDES = 32
    }
}
