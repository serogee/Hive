package io.github.serogee.hive.server.sync

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.api.PushOutcome
import io.github.serogee.hive.core.api.PushStacksResponse
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.StackDocument
import io.github.serogee.hive.core.api.StackPushResult
import io.github.serogee.hive.core.api.SyncChanges
import io.github.serogee.hive.core.api.SyncRecord
import io.github.serogee.hive.core.api.SyncedStack
import io.github.serogee.hive.core.model.AccountId
import io.github.serogee.hive.core.sync.StackDecision
import io.github.serogee.hive.core.sync.StackSync
import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.JsonObject

/** What [JdbcSyncStore] needs from a database. */
interface SyncDatabase {
    /** Put before each table name, such as "hive." for the Postgres schema. */
    val tables: String

    suspend fun <T> read(block: (Connection) -> T): T

    /** Runs [block] in one transaction, after other transactions that write to the account end. */
    suspend fun <T> writeForAccount(accountId: AccountId, block: (Connection) -> T): T

    /** The next number of the change sequence, in the transaction of [connection]. */
    fun nextChange(connection: Connection): Long
}

/** The sync tables, in SQL that SQLite and Postgres both understand. */
class JdbcSyncStore(private val db: SyncDatabase) : SyncStore {
    private val t = db.tables

    override suspend fun pushStacks(accountId: AccountId, generation: Long, changes: List<StackChange>, now: Long) =
        db.writeForAccount(accountId) { connection ->
            val current = connection.generation(accountId)
            if (generation != current) return@writeForAccount PushStacksResponse(emptyList(), current)
            val results = changes.map { change ->
                when (val decision = StackSync.decide(connection.stack(accountId, change.stackId), change, now)) {
                    is StackDecision.Apply -> {
                        connection.saveStack(accountId, decision.stack, db.nextChange(connection))
                        StackPushResult(change.stackId, PushOutcome.APPLIED, decision.stack)
                    }
                    is StackDecision.Keep -> StackPushResult(change.stackId, decision.outcome, decision.stack)
                }
            }
            PushStacksResponse(results, current)
        }

    override suspend fun append(accountId: AccountId, kind: RecordKind, records: List<SyncRecord>) =
        db.writeForAccount(accountId) { connection ->
            records.distinctBy { it.id }.count { record ->
                val exists = connection.exists(
                    "SELECT 1 FROM ${t}sync_records WHERE account_id = ? AND kind = ? AND id = ?",
                    accountId.value,
                    kind.name,
                    record.id,
                )
                if (!exists) {
                    connection.update(
                        "INSERT INTO ${t}sync_records (account_id, kind, id, happened_at, data, change_seq) VALUES (?, ?, ?, ?, ?, ?)",
                        accountId.value,
                        kind.name,
                        record.id,
                        record.at,
                        HiveJson.encodeToString(JsonObject.serializer(), record.data),
                        db.nextChange(connection),
                    )
                }
                !exists
            }
        }

    override suspend fun changes(accountId: AccountId, cursor: Long, limit: Int) = db.read { connection ->
        // One more than asked tells whether there are more.
        val stacks = connection.query(
            "SELECT * FROM ${t}sync_stacks WHERE account_id = ? AND change_seq > ? ORDER BY change_seq LIMIT ?",
            accountId.value,
            cursor,
            limit + 1,
        ) { Change(it.getLong("change_seq"), stack = it.toStack()) }
        val records = connection.query(
            "SELECT * FROM ${t}sync_records WHERE account_id = ? AND change_seq > ? ORDER BY change_seq LIMIT ?",
            accountId.value,
            cursor,
            limit + 1,
        ) { Change(it.getLong("change_seq"), kind = RecordKind.valueOf(it.getString("kind")), record = it.toRecord()) }

        val all = (stacks + records).sortedBy { it.seq }
        val page = all.take(limit)
        SyncChanges(
            stacks = page.mapNotNull { it.stack },
            reviews = page.filter { it.kind == RecordKind.REVIEW }.mapNotNull { it.record },
            results = page.filter { it.kind == RecordKind.RESULT }.mapNotNull { it.record },
            cursor = page.lastOrNull()?.seq ?: cursor,
            more = all.size > limit,
            generation = connection.generation(accountId),
        )
    }

    override suspend fun deleteAll(accountId: AccountId) {
        db.writeForAccount(accountId) { connection ->
            connection.update("DELETE FROM ${t}sync_stacks WHERE account_id = ?", accountId.value)
            connection.update("DELETE FROM ${t}sync_records WHERE account_id = ?", accountId.value)
            val next = connection.generation(accountId) + 1
            connection.update("DELETE FROM ${t}sync_accounts WHERE account_id = ?", accountId.value)
            connection.update("INSERT INTO ${t}sync_accounts (account_id, generation) VALUES (?, ?)", accountId.value, next)
        }
    }

    private class Change(val seq: Long, val stack: SyncedStack? = null, val kind: RecordKind? = null, val record: SyncRecord? = null)

    private fun Connection.generation(accountId: AccountId): Long =
        query("SELECT generation FROM ${t}sync_accounts WHERE account_id = ?", accountId.value) { it.getLong(1) }.firstOrNull() ?: 0

    private fun Connection.stack(accountId: AccountId, stackId: String): SyncedStack? =
        query("SELECT * FROM ${t}sync_stacks WHERE account_id = ? AND stack_id = ?", accountId.value, stackId) { it.toStack() }.firstOrNull()

    private fun Connection.saveStack(accountId: AccountId, stack: SyncedStack, change: Long) {
        update("DELETE FROM ${t}sync_stacks WHERE account_id = ? AND stack_id = ?", accountId.value, stack.stackId)
        update(
            "INSERT INTO ${t}sync_stacks (account_id, stack_id, version, updated_at, deleted, document, change_seq) VALUES (?, ?, ?, ?, ?, ?, ?)",
            accountId.value,
            stack.stackId,
            stack.version,
            stack.updatedAt,
            stack.deleted,
            stack.document?.let { HiveJson.encodeToString(StackDocument.serializer(), it) },
            change,
        )
    }

    private fun Connection.exists(sql: String, vararg values: Any?) = query(sql, *values) { true }.isNotEmpty()

    private fun Connection.update(sql: String, vararg values: Any?) = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.bind(index + 1, value) }
        statement.executeUpdate()
    }

    private fun <T> Connection.query(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.bind(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }

    private fun java.sql.PreparedStatement.bind(index: Int, value: Any?) = when (value) {
        null -> setNull(index, java.sql.Types.VARCHAR)
        is String -> setString(index, value)
        is Long -> setLong(index, value)
        is Int -> setInt(index, value)
        is Boolean -> setBoolean(index, value)
        else -> error("No binding for ${value::class}")
    }

    private fun ResultSet.toStack() = SyncedStack(
        stackId = getString("stack_id"),
        version = getLong("version"),
        updatedAt = getLong("updated_at"),
        deleted = getBoolean("deleted"),
        document = getString("document")?.let { HiveJson.decodeFromString(StackDocument.serializer(), it) },
    )

    private fun ResultSet.toRecord() = SyncRecord(
        id = getString("id"),
        at = getLong("happened_at"),
        data = HiveJson.decodeFromString(JsonObject.serializer(), getString("data")),
    )
}
