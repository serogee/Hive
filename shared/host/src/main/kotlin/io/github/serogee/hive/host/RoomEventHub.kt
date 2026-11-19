package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.RoomEvent
import io.github.serogee.hive.core.model.RoomId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Broadcasts live events to the subscribers of one room only. */
class RoomEventHub {
    private val flows = ConcurrentHashMap<RoomId, MutableSharedFlow<RoomEvent>>()

    private fun flowFor(roomId: RoomId) =
        flows.computeIfAbsent(roomId) { MutableSharedFlow(extraBufferCapacity = 64) }

    fun events(roomId: RoomId): SharedFlow<RoomEvent> = flowFor(roomId).asSharedFlow()

    suspend fun publish(event: RoomEvent) {
        flowFor(event.roomId).emit(event)
    }
}
