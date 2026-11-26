package io.github.serogee.hive.data.sync

import io.github.serogee.hive.core.api.ApiPaths
import io.github.serogee.hive.core.api.PushStacksRequest
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.data.remote.HiveApi

/** [SyncRemote] on the configured server, with the signed-in account's token. */
class ApiSyncRemote(private val api: HiveApi) : SyncRemote {
    override suspend fun changes(cursor: Long) = api.syncChanges(cursor)

    override suspend fun pushStacks(request: PushStacksRequest) = api.pushStacks(request)

    override suspend fun appendReviews(records: List<SyncRecord>) {
        api.appendRecords(ApiPaths.SYNC_REVIEWS, records)
    }

    override suspend fun appendResults(records: List<SyncRecord>) {
        api.appendRecords(ApiPaths.SYNC_RESULTS, records)
    }

    override suspend fun onlineRooms() = api.onlineRooms()

    override suspend fun deleteAll() = api.deleteSyncData()
}
