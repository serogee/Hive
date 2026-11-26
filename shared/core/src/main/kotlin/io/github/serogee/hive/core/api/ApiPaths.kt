package io.github.serogee.hive.core.api

/** Paths and headers shared by every Hive server and client. */
object ApiPaths {
    const val PREFIX = "/v1"
    const val HEALTH = "$PREFIX/health"

    const val DEMO_LOGIN = "$PREFIX/demo/auth/login"
    const val DEMO_LOGOUT = "$PREFIX/demo/auth/logout"
    const val ME = "$PREFIX/me"

    const val ROOMS = "$PREFIX/rooms"
    const val HOSTED_ROOMS = "$PREFIX/rooms/hosted"

    fun roomByCode(code: String) = "$ROOMS/by-code/$code"

    fun participants(roomId: String) = "$ROOMS/$roomId/participants"

    fun state(roomId: String) = "$ROOMS/$roomId/state"

    fun events(roomId: String) = "$ROOMS/$roomId/events"

    fun commands(roomId: String) = "$ROOMS/$roomId/commands"

    fun answers(roomId: String) = "$ROOMS/$roomId/answers"

    fun attempt(roomId: String) = "$ROOMS/$roomId/attempt"

    fun leave(roomId: String) = "$ROOMS/$roomId/leave"

    fun results(roomId: String) = "$ROOMS/$roomId/results"

    /** DELETE deletes the signed-in account and everything the server keeps about it. */
    const val ACCOUNT = "$PREFIX/account"
    const val SYNC = "$PREFIX/sync"
    const val SYNC_CHANGES = "$SYNC/changes"
    const val SYNC_STACKS = "$SYNC/stacks"
    const val SYNC_REVIEWS = "$SYNC/reviews"
    const val SYNC_RESULTS = "$SYNC/results"
    const val SYNC_ROOMS = "$SYNC/rooms"

    /** Carries the reconnect credential that a join or room creation returned. */
    const val PARTICIPANT_TOKEN_HEADER = "X-Hive-Participant"
}
