package io.github.serogee.hive.core.api

import io.github.serogee.hive.core.model.ParticipantId
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.StackSnapshot
import io.github.serogee.hive.core.sample.SampleStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer

class ContractsTest {
    @Test
    fun stackSnapshotRoundTripsWithTypeTags() {
        val stack = SampleStacks.networkingFundamentals

        val json = HiveJson.encodeToString(StackSnapshot.serializer(), stack)

        assertTrue(json.contains("\"type\":\"multiple_choice\""))
        assertTrue(json.contains("\"type\":\"true_false\""))
        assertTrue(json.contains("\"type\":\"identification\""))
        assertEquals(stack, HiveJson.decodeFromString(StackSnapshot.serializer(), json))
    }

    @Test
    fun playerQuestionsCarryNoAnswerKey() {
        val questions = SampleStacks.networkingFundamentals.questions.mapIndexed { index, question ->
            question.toPlayerQuestion(index + 1)
        }

        val json = HiveJson.encodeToString(ListSerializer(PlayerQuestion.serializer()), questions)

        assertFalse(json.contains("correctChoiceId"))
        assertFalse(json.contains("\"answer\""))
        assertFalse(json.contains("explanation"))
        assertFalse(json.contains("DNS"))
        assertTrue(json.contains("\"choices\""))
    }

    @Test
    fun hostCommandsAreTaggedObjects() {
        val json = HiveJson.encodeToString(HostCommand.serializer(), HostCommand.NextQuestion)

        assertEquals("{\"type\":\"next_question\"}", json)
        assertEquals(HostCommand.Start, HiveJson.decodeFromString(HostCommand.serializer(), "{\"type\":\"start\"}"))
    }

    @Test
    fun eventsUseSnakeCaseNames() {
        val event: RoomEvent = RoomEvent.ParticipantJoined(
            roomId = RoomId("room-1"),
            revision = 3,
            participant = ParticipantView(ParticipantId("p-1"), "Alex", RoomRole.PLAYER, isGuest = true),
        )

        val json = HiveJson.encodeToString(RoomEvent.serializer(), event)

        assertTrue(json.contains("\"type\":\"participant_joined\""))
        assertTrue(json.contains("\"role\":\"player\""))
        assertEquals(event, HiveJson.decodeFromString(RoomEvent.serializer(), json))
    }
}
