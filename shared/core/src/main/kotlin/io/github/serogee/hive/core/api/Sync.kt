package io.github.serogee.hive.core.api

import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.model.RoomId
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.model.RoomStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A stack as an account stores it: its title and its questions in order, with every field. */
@Serializable
data class StackDocument(
    val title: String,
    val description: String = "",
    val questions: List<QuestionDraft> = emptyList(),
)

/** The account's copy of a stack. A deleted stack stays as a tombstone, so that other phones learn of it. */
@Serializable
data class SyncedStack(
    val stackId: String,
    /** Goes up with every change, starting at 1. */
    val version: Long,
    /** Epoch milliseconds of the server's clock. */
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Null for a tombstone. */
    val document: StackDocument? = null,
)

/** A phone's change to a stack. [baseVersion] is the account's version that the phone changed, or null for a new stack. */
@Serializable
data class StackChange(
    val stackId: String,
    val baseVersion: Long? = null,
    val deleted: Boolean = false,
    val document: StackDocument? = null,
)

/**
 * [generation] is the one that the phone last pulled. When the account's sync data was deleted since,
 * nothing is applied, and the phone must pull again from the start.
 */
@Serializable
data class PushStacksRequest(val changes: List<StackChange>, val generation: Long = 0)

@Serializable
enum class PushOutcome {
    @SerialName("applied")
    APPLIED,

    /** Another phone changed the stack first. The account kept its copy, which comes back. */
    @SerialName("conflict")
    CONFLICT,
}

@Serializable
data class StackPushResult(val stackId: String, val outcome: PushOutcome, val stack: SyncedStack)

@Serializable
data class PushStacksResponse(val results: List<StackPushResult>, val generation: Long = 0)

/**
 * Something that happened once and never changes, such as a finished review. The phone chooses [id],
 * so that sending it again stores it once. [data] is the phone's own record.
 */
@Serializable
data class SyncRecord(
    val id: String,
    /** Epoch milliseconds when it happened. */
    val at: Long,
    val data: JsonObject,
)

@Serializable
data class AppendRecordsRequest(val records: List<SyncRecord>)

@Serializable
data class AppendRecordsResponse(
    /** How many of the records were new. */
    val stored: Int,
)

/** What changed for the account after a cursor. Pass [cursor] back for the next page. */
@Serializable
data class SyncChanges(
    val stacks: List<SyncedStack> = emptyList(),
    val reviews: List<SyncRecord> = emptyList(),
    val results: List<SyncRecord> = emptyList(),
    val cursor: Long,
    val more: Boolean = false,
    /** Goes up each time the account's sync data is deleted. A phone that saw another one starts over. */
    val generation: Long = 0,
)

/** An online room that the account hosted or played, with its result when it has one. */
@Serializable
data class OnlineRoomEntry(
    val roomId: RoomId,
    val code: String,
    val title: String,
    val role: RoomRole,
    val mode: RoomMode,
    val status: RoomStatus,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val questionCount: Int,
    val playerCount: Int,
    /** For a Player of a room that ended: base points plus speed bonus, in tenths of a point. */
    val totalTenths: Int? = null,
    val correctCount: Int? = null,
    val rank: Int? = null,
)
