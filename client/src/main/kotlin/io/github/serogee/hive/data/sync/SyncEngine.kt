package io.github.serogee.hive.data.sync

import io.github.serogee.hive.core.api.PushOutcome
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.SyncedStack
import io.github.serogee.hive.data.remote.ApiException
import io.github.serogee.hive.data.server.ServerState
import io.github.serogee.hive.data.server.ServerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Account page says about sync. */
sealed interface SyncStatus {
    /** Nobody is signed in, so everything stays on this phone. */
    data object Off : SyncStatus

    /** The server keeps no account data. */
    data object Unavailable : SyncStatus

    data object Syncing : SyncStatus

    data class UpToDate(val at: Long) : SyncStatus

    /** Changes wait for the next sync. */
    data class Waiting(val lastSyncedAt: Long?) : SyncStatus

    data class Offline(val lastSyncedAt: Long?) : SyncStatus
}

/**
 * Keeps a signed-in account's stacks, finished reviews, and quiz results the same on every phone.
 * Guests are never synced. It never runs on the main thread, and a failed sync changes nothing that a
 * later sync cannot finish.
 */
class SyncEngine(
    private val local: SyncLocal,
    private val remote: SyncRemote,
    private val states: SyncStateStore,
    /** The signed-in account, or null for a guest. */
    private val accountId: suspend () -> String?,
    private val server: () -> ServerStatus,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Set when the user must choose what happens to this phone's own stacks and history. */
    private val _choice = MutableStateFlow<Unlinked?>(null)
    val choice: StateFlow<Unlinked?> = _choice.asStateFlow()

    /** Syncs soon. Requests that arrive during a sync make one more sync. */
    fun requestSync() {
        requests.trySend(Unit)
    }

    /**
     * Syncs after sign-in, at start, a few seconds after a local change, when the server can be reached
     * again, and on [requestSync].
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, accounts: Flow<String?>, serverStatus: Flow<ServerStatus>, pendingCount: Flow<Int>) {
        scope.launch { for (request in requests) syncOnce() }
        scope.launch { accounts.distinctUntilChanged().collect { requestSync() } }
        scope.launch {
            serverStatus.map { it.state == ServerState.ONLINE }.distinctUntilChanged().filter { it }.collect { requestSync() }
        }
        scope.launch {
            serverStatus.map { it.state }.distinctUntilChanged().collect { offlineChanged() }
        }
        scope.launch { pendingCount.distinctUntilChanged().drop(1).filter { it > 0 }.debounce(DEBOUNCE_MILLIS).collect { requestSync() } }
    }

    /** One full sync: pull, the first sign-in choice, push, and the list of online rooms. */
    suspend fun syncOnce() = mutex.withLock {
        val account = accountId()
        if (account == null) {
            _status.value = SyncStatus.Off
            _choice.value = null
            return@withLock
        }
        var state = states.syncState()?.takeIf { it.accountId == account } ?: run {
            // Another account, or the first sign-in on this phone. Nothing here belongs to this account yet.
            local.unlinkAll()
            SyncState(account).also { states.setSyncState(it) }
        }
        val status = server()
        if (status.state != ServerState.ONLINE) {
            _status.value = SyncStatus.Offline(state.lastSyncedAt)
            return@withLock
        }
        if (!status.sync) {
            _status.value = SyncStatus.Unavailable
            return@withLock
        }

        _status.value = SyncStatus.Syncing
        try {
            for (round in 1..MAX_ROUNDS) {
                state = pull(state)
                if (!state.decided) {
                    val unlinked = local.unlinked()
                    if (unlinked.isEmpty) {
                        state = state.copy(decided = true)
                        states.setSyncState(state)
                        _choice.value = null
                    } else {
                        _choice.value = unlinked
                    }
                }
                if (!state.decided) break
                val generation = pushStacks(state)
                if (generation != null) {
                    state = startOver(state, generation)
                    continue
                }
                pushRecords()
                break
            }
            local.saveOnlineRooms(remote.onlineRooms())
            state = state.copy(lastSyncedAt = now())
            states.setSyncState(state)
            _status.value = if (state.decided && local.hasPendingWork()) SyncStatus.Waiting(state.lastSyncedAt) else SyncStatus.UpToDate(now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _status.value = SyncStatus.Waiting(state.lastSyncedAt)
        } catch (e: Exception) {
            _status.value = SyncStatus.Offline(state.lastSyncedAt)
        }
    }

    /** Answers the first sign-in question: upload this phone's stacks and history, or keep them here only. */
    suspend fun choose(upload: Boolean) {
        mutex.withLock {
            val state = states.syncState() ?: return@withLock
            if (!upload) local.keepUnlinkedOnPhone()
            states.setSyncState(state.copy(decided = true))
            _choice.value = null
        }
        requestSync()
    }

    suspend fun signOutSummary(): SignOutSummary = local.signOutSummary()

    /** Before sign-out. [removeStacks] deletes the stacks that the account keeps. Unsynced changes stay. */
    suspend fun forgetAccount(removeStacks: Boolean) = mutex.withLock {
        if (removeStacks) local.deleteSyncedStacks()
        local.unlinkAll()
        states.setSyncState(null)
        _choice.value = null
        _status.value = SyncStatus.Off
    }

    /**
     * Deletes everything the account synced, on the server. This phone keeps its stacks and history, on
     * this phone only. Other phones keep theirs, and ask again what to do with them.
     */
    suspend fun deleteAccountData() = mutex.withLock {
        val account = accountId() ?: return@withLock
        remote.deleteAll()
        local.unlinkAll()
        local.keepUnlinkedOnPhone()
        states.setSyncState(SyncState(account, decided = true))
        _choice.value = null
    }

    private fun offlineChanged() {
        val current = _status.value
        if (server().state != ServerState.ONLINE && current !is SyncStatus.Off && current !is SyncStatus.Syncing) {
            val last = when (current) {
                is SyncStatus.UpToDate -> current.at
                is SyncStatus.Waiting -> current.lastSyncedAt
                is SyncStatus.Offline -> current.lastSyncedAt
                else -> null
            }
            _status.value = SyncStatus.Offline(last)
        }
    }

    /** Pulls every change after the cursor. A new generation means the account's data was deleted. */
    private suspend fun pull(start: SyncState): SyncState {
        var state = start
        while (true) {
            val page = remote.changes(state.cursor)
            if (state.generation != null && page.generation != state.generation) {
                state = startOver(state, page.generation)
                continue
            }
            page.stacks.forEach { applyPulled(it) }
            local.saveReviews(page.reviews)
            local.saveResults(page.results)
            state = state.copy(cursor = page.cursor, generation = page.generation)
            states.setSyncState(state)
            if (!page.more) return state
        }
    }

    private suspend fun startOver(state: SyncState, generation: Long): SyncState {
        local.unlinkAll()
        return SyncState(state.accountId, cursor = 0, generation = generation, decided = false, lastSyncedAt = state.lastSyncedAt)
            .also { states.setSyncState(it) }
    }

    private suspend fun applyPulled(remoteStack: SyncedStack) {
        val here = local.stack(remoteStack.stackId)
        if (here == null) {
            val deletion = local.deletion(remoteStack.stackId)
            when {
                deletion == null -> if (!remoteStack.deleted) local.takeAccountCopy(remoteStack)
                remoteStack.deleted -> local.clearDeletion(remoteStack.stackId)
                // Another phone changed the stack after this phone deleted it. The account keeps its copy.
                remoteStack.version > deletion.baseVersion -> {
                    local.clearDeletion(remoteStack.stackId)
                    local.takeAccountCopy(remoteStack)
                }
            }
            return
        }
        when (StackMerge.onPulled(here, remoteStack)) {
            PullDecision.SKIP -> Unit
            PullDecision.TAKE -> local.takeAccountCopy(remoteStack)
            PullDecision.KEEP_BOTH -> {
                local.saveAsCopy(here)
                local.takeAccountCopy(remoteStack)
            }
        }
    }

    /** Returns the account's generation when it differs from [state]'s, and nothing was applied. */
    private suspend fun pushStacks(state: SyncState): Long? {
        val generation = state.generation ?: 0
        val deletions = local.deletions().associateBy { it.stackId }
        val pending = local.pendingStacks().filter { it.id !in deletions }.associateBy { it.id }
        val changes = deletions.values.map { StackChange(it.stackId, it.baseVersion, deleted = true) } +
            pending.values.map { StackChange(it.id, it.accountVersion, document = it.document) }
        for (batch in changes.chunked(BATCH)) {
            val response = remote.pushStacks(PushStacksRequest(batch, generation))
            if (response.generation != generation) return response.generation
            response.results.forEach { result ->
                val accountCopy = result.stack
                if (result.stackId in deletions) {
                    local.clearDeletion(result.stackId)
                    // A newer copy on the account wins over the deletion, so nothing is lost.
                    if (result.outcome == PushOutcome.CONFLICT && !accountCopy.deleted) local.takeAccountCopy(accountCopy)
                } else if (result.outcome == PushOutcome.APPLIED) {
                    pending[result.stackId]?.let { local.markPushed(it.id, accountCopy.version, it.updatedAt) }
                } else {
                    val here = local.stack(result.stackId)
                    if (here != null && (accountCopy.deleted || here.document != accountCopy.document)) local.saveAsCopy(here)
                    local.takeAccountCopy(accountCopy)
                }
            }
        }
        return null
    }

    private suspend fun pushRecords() {
        while (true) {
            val reviews = local.unsentReviews(BATCH).takeIf { it.isNotEmpty() } ?: break
            remote.appendReviews(reviews)
            local.markReviewsSent(reviews.map { it.id })
        }
        while (true) {
            val results = local.unsentResults(BATCH).takeIf { it.isNotEmpty() } ?: break
            remote.appendResults(results)
            local.markResultsSent(results.map { it.id })
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 3_000L
        const val BATCH = 100

        /** A sync that finds the account's data deleted starts over once. */
        private const val MAX_ROUNDS = 2
    }
}
