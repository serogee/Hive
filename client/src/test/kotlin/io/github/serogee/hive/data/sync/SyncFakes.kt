package io.github.serogee.hive.data.sync

import io.github.serogee.hive.core.api.OnlineRoomEntry
import io.github.serogee.hive.core.api.PushOutcome
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.PushStacksResponse
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.StackPushResult
import io.github.serogee.hive.core.api.SyncChanges
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.api.SyncedStack
import io.github.serogee.hive.core.sync.StackDecision
import io.github.serogee.hive.core.sync.StackSync
import io.github.serogee.hive.data.local.RecordSync
import io.github.serogee.hive.data.server.ServerState
import io.github.serogee.hive.data.server.ServerStatus
import java.io.IOException
import kotlinx.serialization.json.buildJsonObject

/** An account on a server, with the same rules as the real one. */
class FakeAccount(private val pageSize: Int = 2) : SyncRemote {
    var generation = 0L
    private var seq = 0L
    private var time = 1_000L
    val stacks = mutableMapOf<String, Pair<Long, SyncedStack>>()
    val records = mutableMapOf<Pair<String, String>, Pair<Long, SyncRecord>>()
    var rooms = emptyList<OnlineRoomEntry>()
    var reachable = true
    var appendCalls = 0

    /** Runs before each push is applied, as another phone that pushes at the same moment. */
    var beforePush: () -> Unit = {}

    fun titles() = stacks.values.map { it.second }.filter { !it.deleted }.map { it.document!!.title }.sorted()

    fun reviewIds() = records.keys.filter { it.first == "review" }.map { it.second }.sorted()

    /** Applies a change as another phone would. */
    fun apply(stackId: String, baseVersion: Long?, title: String?) {
        val change = io.github.serogee.hive.core.api.StackChange(stackId, baseVersion, deleted = title == null, document = title?.let { StackDocument(it) })
        val decision = StackSync.decide(stacks[stackId]?.second, change, ++time)
        if (decision is StackDecision.Apply) stacks[stackId] = ++seq to decision.stack
    }

    private fun check() {
        if (!reachable) throw IOException("No network")
    }

    override suspend fun changes(cursor: Long): SyncChanges {
        check()
        val all = (stacks.values.map { it.first to it.second } + records.map { (key, value) -> value.first to (key.first to value.second) })
            .filter { it.first > cursor }
            .sortedBy { it.first }
        val page = all.take(pageSize)
        return SyncChanges(
            stacks = page.mapNotNull { it.second as? SyncedStack },
            reviews = page.mapNotNull { (it.second as? Pair<*, *>)?.takeIf { p -> p.first == "review" }?.second as? SyncRecord },
            results = page.mapNotNull { (it.second as? Pair<*, *>)?.takeIf { p -> p.first == "result" }?.second as? SyncRecord },
            cursor = page.lastOrNull()?.first ?: cursor,
            more = all.size > pageSize,
            generation = generation,
        )
    }

    override suspend fun pushStacks(request: PushStacksRequest): PushStacksResponse {
        check()
        beforePush()
        if (request.generation != generation) return PushStacksResponse(emptyList(), generation)
        val results = request.changes.map { change ->
            when (val decision = StackSync.decide(stacks[change.stackId]?.second, change, ++time)) {
                is StackDecision.Apply -> {
                    stacks[change.stackId] = ++seq to decision.stack
                    StackPushResult(change.stackId, PushOutcome.APPLIED, decision.stack)
                }
                is StackDecision.Keep -> StackPushResult(change.stackId, decision.outcome, decision.stack)
            }
        }
        return PushStacksResponse(results, generation)
    }

    private fun append(kind: String, list: List<SyncRecord>) {
        check()
        appendCalls++
        list.forEach { record -> if ((kind to record.id) !in records) records[kind to record.id] = ++seq to record }
    }

    override suspend fun appendReviews(records: List<SyncRecord>) = append("review", records)

    override suspend fun appendResults(records: List<SyncRecord>) = append("result", records)

    override suspend fun onlineRooms(): List<OnlineRoomEntry> {
        check()
        return rooms
    }

    override suspend fun deleteAll() {
        check()
        stacks.clear()
        records.clear()
        generation++
    }
}

/** A phone's stacks and history, with the same rules as the Room database. */
class FakePhone : SyncLocal {
    data class Row(val document: StackDocument, val accountVersion: Long?, val pending: Boolean, val localOnly: Boolean, val updatedAt: Long)

    private var time = 0L
    private var copies = 0
    val stacks = linkedMapOf<String, Row>()
    val deletions = mutableMapOf<String, Long>()
    val reviews = linkedMapOf<String, Pair<SyncRecord, Int>>()
    val results = linkedMapOf<String, Pair<SyncRecord, Int>>()
    var onlineRooms = emptyList<OnlineRoomEntry>()

    fun titles() = stacks.values.map { it.document.title }.sorted()

    fun idOf(title: String) = stacks.entries.single { it.value.document.title == title }.key

    fun create(id: String, title: String) {
        stacks[id] = Row(StackDocument(title), null, pending = true, localOnly = false, updatedAt = ++time)
    }

    fun edit(id: String, title: String) {
        val row = stacks.getValue(id)
        stacks[id] = row.copy(document = row.document.copy(title = title), pending = !row.localOnly, updatedAt = ++time)
    }

    fun delete(id: String) {
        val row = stacks.remove(id) ?: return
        if (row.accountVersion != null && !row.localOnly) deletions[id] = row.accountVersion
    }

    fun finishReview(id: String) {
        reviews[id] = SyncRecord(id, ++time, buildJsonObject {}) to RecordSync.UNSENT
    }

    fun recordResult(id: String) {
        results[id] = SyncRecord(id, ++time, buildJsonObject {}) to RecordSync.UNSENT
    }

    private fun Map.Entry<String, Row>.toLocal() =
        LocalStack(key, value.document, value.accountVersion, value.pending, value.localOnly, value.updatedAt)

    override suspend fun pendingStacks() = stacks.entries.filter { it.value.pending && !it.value.localOnly }.map { it.toLocal() }

    override suspend fun stack(id: String) = stacks.entries.firstOrNull { it.key == id }?.toLocal()

    override suspend fun deletions() = deletions.map { StackDeletion(it.key, it.value) }

    override suspend fun deletion(id: String) = deletions[id]?.let { StackDeletion(id, it) }

    override suspend fun clearDeletion(id: String) {
        deletions.remove(id)
    }

    override suspend fun takeAccountCopy(stack: SyncedStack) {
        if (stack.deleted) {
            stacks.remove(stack.stackId)
        } else {
            stacks[stack.stackId] = Row(stack.document!!, stack.version, pending = false, localOnly = false, updatedAt = stack.updatedAt)
        }
    }

    override suspend fun markPushed(id: String, version: Long, sentAt: Long) {
        val row = stacks[id] ?: return
        stacks[id] = row.copy(accountVersion = version, pending = row.updatedAt != sentAt)
    }

    override suspend fun saveAsCopy(stack: LocalStack) {
        stacks["copy-${++copies}"] = Row(
            stack.document.copy(title = StackMerge.copyTitle(stack.document.title)),
            null,
            pending = !stack.localOnly,
            localOnly = stack.localOnly,
            updatedAt = ++time,
        )
    }

    private fun count(map: Map<String, Pair<SyncRecord, Int>>) = map.values.count { it.second == RecordSync.UNSENT }

    override suspend fun unlinked() = Unlinked(stacks.values.count { it.accountVersion == null && !it.localOnly }, count(reviews), count(results))

    override suspend fun keepUnlinkedOnPhone() {
        stacks.replaceAll { _, row -> if (row.accountVersion == null && !row.localOnly) row.copy(localOnly = true, pending = false) else row }
        listOf(reviews, results).forEach { map ->
            map.replaceAll { _, value -> if (value.second == RecordSync.UNSENT) value.first to RecordSync.LOCAL_ONLY else value }
        }
    }

    override suspend fun unlinkAll() {
        stacks.replaceAll { _, row -> if (row.localOnly) row else row.copy(accountVersion = null, pending = true) }
        deletions.clear()
        listOf(reviews, results).forEach { map ->
            map.replaceAll { _, value -> if (value.second == RecordSync.SENT) value.first to RecordSync.UNSENT else value }
        }
        onlineRooms = emptyList()
    }

    override suspend fun signOutSummary() = SignOutSummary(
        syncedStacks = stacks.values.count { it.accountVersion != null && !it.localOnly && !it.pending },
        unsyncedStacks = stacks.values.count { !it.localOnly && (it.accountVersion == null || it.pending) },
    )

    override suspend fun deleteSyncedStacks() {
        stacks.entries.removeAll { it.value.accountVersion != null && !it.value.localOnly && !it.value.pending }
    }

    private fun unsent(map: Map<String, Pair<SyncRecord, Int>>, limit: Int) =
        map.values.filter { it.second == RecordSync.UNSENT }.map { it.first }.take(limit)

    private fun markSent(map: MutableMap<String, Pair<SyncRecord, Int>>, ids: List<String>) =
        ids.forEach { id -> map[id]?.let { map[id] = it.first to RecordSync.SENT } }

    private fun save(map: MutableMap<String, Pair<SyncRecord, Int>>, records: List<SyncRecord>) =
        records.forEach { if (it.id !in map) map[it.id] = it to RecordSync.SENT }

    override suspend fun unsentReviews(limit: Int) = unsent(reviews, limit)

    override suspend fun markReviewsSent(ids: List<String>) = markSent(reviews, ids)

    override suspend fun saveReviews(records: List<SyncRecord>) = save(reviews, records)

    override suspend fun unsentResults(limit: Int) = unsent(results, limit)

    override suspend fun markResultsSent(ids: List<String>) = markSent(results, ids)

    override suspend fun saveResults(records: List<SyncRecord>) = save(results, records)

    override suspend fun saveOnlineRooms(rooms: List<OnlineRoomEntry>) {
        onlineRooms = rooms
    }

    override suspend fun hasPendingWork() =
        pendingStacks().isNotEmpty() || deletions.isNotEmpty() || count(reviews) > 0 || count(results) > 0
}

class FakeStates : SyncStateStore {
    var state: SyncState? = null

    override suspend fun syncState() = state

    override suspend fun setSyncState(state: SyncState?) {
        this.state = state
    }
}

/** A phone with its sync engine, signed in as [account] until that is set to null. */
class Device(val server: FakeAccount, var account: String? = "account-ana") {
    val phone = FakePhone()
    val states = FakeStates()
    var serverStatus = ServerStatus(ServerState.ONLINE, sync = true)
    var clock = 50_000L
    val engine = SyncEngine(phone, server, states, accountId = { account }, server = { serverStatus }, now = { clock })

    suspend fun sync() = engine.syncOnce()
}
