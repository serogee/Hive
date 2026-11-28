package io.github.serogee.hive.audio

import io.github.serogee.hive.core.api.AttemptView
import io.github.serogee.hive.core.api.ParticipantView
import io.github.serogee.hive.core.api.RoomState
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioPolicyTest {
    @Test
    fun `discussion rooms allow music only on the Host phone`() {
        assertEquals(
            RoomMusicPolicy(allowed = true, track = MusicTrack.LOBBY),
            roomMusicPolicy(roomState(RoomMode.DISCUSSION, RoomStatus.LOBBY, RoomRole.HOST)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = true, track = MusicTrack.QUIZ),
            roomMusicPolicy(roomState(RoomMode.DISCUSSION, RoomStatus.LIVE, RoomRole.HOST)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = false, track = null),
            roomMusicPolicy(roomState(RoomMode.DISCUSSION, RoomStatus.LOBBY, RoomRole.PLAYER)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = false, track = null),
            roomMusicPolicy(roomState(RoomMode.DISCUSSION, RoomStatus.LIVE, RoomRole.PLAYER)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = false, track = null),
            roomMusicPolicy(roomState(RoomMode.DISCUSSION, RoomStatus.ENDED, RoomRole.HOST)),
        )
    }

    @Test
    fun `async rooms play Host music only in lobby and Player quiz music only during an active attempt`() {
        assertEquals(
            RoomMusicPolicy(allowed = true, track = MusicTrack.LOBBY),
            roomMusicPolicy(roomState(RoomMode.ASYNCHRONOUS, RoomStatus.LOBBY, RoomRole.HOST)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = false, track = null),
            roomMusicPolicy(roomState(RoomMode.ASYNCHRONOUS, RoomStatus.LIVE, RoomRole.HOST)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = true, track = MusicTrack.LOBBY),
            roomMusicPolicy(roomState(RoomMode.ASYNCHRONOUS, RoomStatus.LOBBY, RoomRole.PLAYER)),
        )
        assertEquals(
            RoomMusicPolicy(allowed = true, track = null),
            roomMusicPolicy(
                roomState(
                    RoomMode.ASYNCHRONOUS,
                    RoomStatus.LIVE,
                    RoomRole.PLAYER,
                    attempt = AttemptView(started = false, finished = false, answeredCount = 0),
                ),
            ),
        )
        assertEquals(
            RoomMusicPolicy(allowed = true, track = MusicTrack.QUIZ),
            roomMusicPolicy(
                roomState(
                    RoomMode.ASYNCHRONOUS,
                    RoomStatus.LIVE,
                    RoomRole.PLAYER,
                    attempt = AttemptView(started = true, finished = false, answeredCount = 1),
                ),
            ),
        )
        assertEquals(
            RoomMusicPolicy(allowed = false, track = null),
            roomMusicPolicy(
                roomState(
                    RoomMode.ASYNCHRONOUS,
                    RoomStatus.LIVE,
                    RoomRole.PLAYER,
                    attempt = AttemptView(started = true, finished = true, answeredCount = 3),
                ),
            ),
        )
    }

    @Test
    fun `TalkBack defaults music off until the user explicitly turns it on for the session`() {
        val initial = SessionMusicState(
            preferences = SoundPreferences(soloMusic = true, multiMusic = true),
            talkBackEnabled = true,
        ).withRequest("review:1", AudioScope.SOLO, MusicTrack.QUIZ)

        assertFalse(initial.isMusicEnabled("review:1", AudioScope.SOLO))
        assertNull(initial.effectiveTrack())

        val optedIn = initial.toggled("review:1", AudioScope.SOLO)
        assertTrue(optedIn.isMusicEnabled("review:1", AudioScope.SOLO))
        assertEquals(MusicTrack.QUIZ, optedIn.effectiveTrack())
    }

    @Test
    fun `in-session music toggle overrides saved setting and survives session clears`() {
        val state = SessionMusicState(
            preferences = SoundPreferences(soloMusic = false, multiMusic = false),
        ).withRequest("room:r1", AudioScope.MULTIPLAYER, MusicTrack.LOBBY)

        assertFalse(state.isMusicEnabled("room:r1", AudioScope.MULTIPLAYER))
        assertNull(state.effectiveTrack())

        val enabled = state.toggled("room:r1", AudioScope.MULTIPLAYER)
        assertTrue(enabled.isMusicEnabled("room:r1", AudioScope.MULTIPLAYER))
        assertEquals(MusicTrack.LOBBY, enabled.effectiveTrack())

        // Leaving and re-entering the same session keeps the override; saved Settings stay false.
        val cleared = enabled.clearedSession("room:r1")
        assertNull(cleared.effectiveTrack())
        val resumed = cleared.withRequest("room:r1", AudioScope.MULTIPLAYER, MusicTrack.QUIZ)
        assertEquals(MusicTrack.QUIZ, resumed.effectiveTrack())
        assertFalse(resumed.preferences.multiMusic)
    }

    @Test
    fun `cue guard deduplicates claimed keys and throttles burst buckets`() {
        var now = 1_000L
        val guard = CueGuard(nowMillis = { now }, maxKeys = 2)

        assertTrue(guard.tryClaimKey("tick:r1:q1:5"))
        assertFalse(guard.tryClaimKey("tick:r1:q1:5"))
        assertTrue(guard.tryClaimKey("tick:r1:q1:4"))
        assertTrue(guard.tryClaimKey("tick:r1:q1:3"))
        // Oldest key was evicted once capacity (2) was exceeded.
        assertTrue(guard.tryClaimKey("tick:r1:q1:5"))

        assertTrue(guard.tryAcquireBucket("join:r1", 1_200L))
        now = 1_500L
        assertFalse(guard.tryAcquireBucket("join:r1", 1_200L))
        now = 2_200L
        assertTrue(guard.tryAcquireBucket("join:r1", 1_200L))
    }

    private fun roomState(
        mode: RoomMode,
        status: RoomStatus,
        role: RoomRole,
        attempt: AttemptView? = null,
    ): RoomState {
        val me = ParticipantView(ParticipantId("p1"), "Sam", role, isGuest = true)
        return RoomState(
            room = RoomSummary(
                id = RoomId("r1"),
                code = "HIVE42",
                title = "Quiz",
                hostName = "Host",
                mode = mode,
                admission = AdmissionPolicy.EVERYONE,
                status = status,
                questionCount = 5,
            ),
            you = me,
            participants = listOf(me),
            revision = 1,
            attempt = attempt,
        )
    }
}
