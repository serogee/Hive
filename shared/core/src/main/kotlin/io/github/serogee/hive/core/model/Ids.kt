package io.github.serogee.hive.core.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class AccountId(val value: String)

@JvmInline
@Serializable
value class StackId(val value: String)

@JvmInline
@Serializable
value class QuestionId(val value: String)

@JvmInline
@Serializable
value class ChoiceId(val value: String)

@JvmInline
@Serializable
value class RoomId(val value: String)

@JvmInline
@Serializable
value class ParticipantId(val value: String)
