package io.github.serogee.hive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.serogee.hive.R
import io.github.serogee.hive.data.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-wide controller for Hive's bundled sound effects and looping background music.
 *
 * Uses [SoundPool] with load-completion tracking for low-latency effects, [MediaPlayer] for
 * gapless OGG Vorbis loops, [ProcessLifecycleOwner] so screen rotation does not interrupt music,
 * and [AccessibilityManager] so TalkBack defaults background music off.
 */
class HiveAudio(
    context: Context,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
) : SoundCuePlayer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val accessibilityManager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val guard = CueGuard()

    private val _musicState = MutableStateFlow(
        SessionMusicState(talkBackEnabled = accessibilityManager?.isTouchExplorationEnabled == true),
    )
    val musicState: StateFlow<SessionMusicState> = _musicState.asStateFlow()

    private val sfxAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_SFX_STREAMS)
        .setAudioAttributes(sfxAttributes)
        .build()

    private val soundIds = ConcurrentHashMap<Sfx, Int>()
    private val loadedIds = ConcurrentHashMap.newKeySet<Int>()

    private var started = false
    private var inForeground = true
    private var focusGranted = false
    private var ducked = false
    private var currentTrack: MusicTrack? = null
    private var mediaPlayer: MediaPlayer? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post { handleFocusChange(change) }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(musicAttributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            inForeground = true
            syncPlaybackOnMain()
        }

        override fun onStop(owner: LifecycleOwner) {
            inForeground = false
            stopPlayerAndAbandonFocus()
        }
    }

    private val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        _musicState.update { it.copy(talkBackEnabled = enabled) }
        postSyncPlayback()
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
        Sfx.entries.forEach { cue ->
            soundIds[cue] = soundPool.load(appContext, cue.rawRes(), 1)
        }
    }

    /** Begins observing saved preferences, process foreground state, and TalkBack state. */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Main.immediate) {
            preferences.soundPreferences.collect { prefs ->
                _musicState.update { it.copy(preferences = prefs) }
                syncPlaybackOnMain()
            }
        }
        accessibilityManager?.addTouchExplorationStateChangeListener(touchExplorationListener)
        mainHandler.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        }
    }

    override fun playSfx(cue: Sfx, scope: AudioScope) {
        if (!_musicState.value.preferences.sfxEnabled(scope)) return
        val soundId = soundIds[cue] ?: return
        if (soundId !in loadedIds) return
        soundPool.play(soundId, SFX_VOLUME, SFX_VOLUME, 1, 0, 1f)
    }

    override fun playSfxOnce(key: String, cue: Sfx, scope: AudioScope) {
        if (!_musicState.value.preferences.sfxEnabled(scope)) return
        if (!guard.tryClaimKey(key)) return
        playSfx(cue, scope)
    }

    override fun playSfxThrottled(bucket: String, minIntervalMillis: Long, cue: Sfx, scope: AudioScope) {
        if (!_musicState.value.preferences.sfxEnabled(scope)) return
        if (!guard.tryAcquireBucket(bucket, minIntervalMillis)) return
        playSfx(cue, scope)
    }

    /** Requests [track] (or silence if null) for the active screen [sessionKey]. */
    fun setSessionMusic(sessionKey: String, scope: AudioScope, track: MusicTrack?) {
        _musicState.update { it.withRequest(sessionKey, scope, track) }
        postSyncPlayback()
    }

    /** Releases the active music request when leaving [sessionKey], preserving its toggle override. */
    fun clearSessionMusic(sessionKey: String) {
        _musicState.update { it.clearedSession(sessionKey) }
        postSyncPlayback()
    }

    /** Toggles music on or off for [sessionKey] without changing saved Settings. */
    fun toggleSessionMusic(sessionKey: String, scope: AudioScope) {
        _musicState.update { it.toggled(sessionKey, scope) }
        postSyncPlayback()
    }

    private fun postSyncPlayback() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            syncPlaybackOnMain()
        } else {
            mainHandler.post { syncPlaybackOnMain() }
        }
    }

    private fun syncPlaybackOnMain() {
        val desired = if (inForeground) _musicState.value.effectiveTrack() else null
        if (desired == null) {
            stopPlayerAndAbandonFocus()
            return
        }
        val activePlayer = mediaPlayer
        val sameTrack = activePlayer != null && currentTrack == desired
        if (sameTrack && activePlayer?.isPlaying == true) return
        // Do not take audio from another app (such as a music player or podcast) that is playing,
        // also after that app took the audio focus from Hive.
        if (!focusGranted && audioManager.isMusicActive) return
        if (!ensureAudioFocus()) return
        if (sameTrack && activePlayer != null) {
            applyMusicVolume(activePlayer)
            runCatching { activePlayer.start() }
        } else {
            startTrack(desired)
        }
    }

    private fun ensureAudioFocus(): Boolean {
        if (focusGranted) return true
        val result = audioManager.requestAudioFocus(focusRequest)
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusGranted
    }

    private fun startTrack(track: MusicTrack) {
        releasePlayer()
        val player = runCatching {
            val afd = appContext.resources.openRawResourceFd(track.rawRes())
            MediaPlayer().apply {
                setAudioAttributes(musicAttributes)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                applyMusicVolume(this)
                start()
            }
        }.getOrNull()
        if (player == null) {
            abandonFocus()
            return
        }
        mediaPlayer = player
        currentTrack = track
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                focusGranted = false
                mediaPlayer?.let { if (it.isPlaying) runCatching { it.pause() } }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                mediaPlayer?.let(::applyMusicVolume)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                ducked = false
                syncPlaybackOnMain()
            }
        }
    }

    private fun applyMusicVolume(player: MediaPlayer) {
        val volume = if (ducked) MUSIC_VOLUME_DUCKED else MUSIC_VOLUME
        runCatching { player.setVolume(volume, volume) }
    }

    private fun stopPlayerAndAbandonFocus() {
        releasePlayer()
        abandonFocus()
    }

    private fun releasePlayer() {
        val existing = mediaPlayer ?: return
        mediaPlayer = null
        currentTrack = null
        runCatching {
            if (existing.isPlaying) existing.stop()
            existing.release()
        }
    }

    private fun abandonFocus() {
        if (!focusGranted) return
        focusGranted = false
        ducked = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun MusicTrack.rawRes(): Int = when (this) {
        MusicTrack.LOBBY -> R.raw.music_lobby
        MusicTrack.QUIZ -> R.raw.music_quiz
    }

    private fun Sfx.rawRes(): Int = when (this) {
        Sfx.TAP -> R.raw.sfx_tap
        Sfx.LOCK_IN -> R.raw.sfx_lock_in
        Sfx.CORRECT -> R.raw.sfx_correct
        Sfx.INCORRECT -> R.raw.sfx_incorrect
        Sfx.FLIP -> R.raw.sfx_flip
        Sfx.NEXT -> R.raw.sfx_next
        Sfx.TICK -> R.raw.sfx_tick
        Sfx.TIME_UP -> R.raw.sfx_time_up
        Sfx.PLAYER_JOIN -> R.raw.sfx_player_join
        Sfx.QUIZ_START -> R.raw.sfx_quiz_start
        Sfx.RESULTS_FANFARE -> R.raw.sfx_results_fanfare
    }

    private companion object {
        const val MAX_SFX_STREAMS = 4
        const val SFX_VOLUME = 0.85f
        const val MUSIC_VOLUME = 0.42f
        const val MUSIC_VOLUME_DUCKED = 0.14f
    }
}
