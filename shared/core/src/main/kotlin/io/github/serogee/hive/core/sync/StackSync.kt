package io.github.serogee.hive.core.sync

import io.github.serogee.hive.core.api.PushOutcome
import io.github.serogee.hive.core.api.StackChange
import io.github.serogee.hive.core.api.SyncedStack

/** What the account does with a phone's change to a stack. */
sealed interface StackDecision {
    /** Store [stack] as the account's new copy. */
    data class Apply(val stack: SyncedStack) : StackDecision

    /** Keep the account's copy, which the phone already has or must take. */
    data class Keep(val stack: SyncedStack, val outcome: PushOutcome) : StackDecision
}

/** The rules of stack sync. Nothing a phone sends is ever lost without that phone being told. */
object StackSync {
    fun decide(current: SyncedStack?, change: StackChange, now: Long): StackDecision {
        val next = { version: Long -> SyncedStack(change.stackId, version, now, change.deleted, change.document.takeUnless { change.deleted }) }
        return when {
            // A new stack, or a stack that the account no longer has at all.
            current == null -> StackDecision.Apply(next(1))
            // A stack previously unsynced/deleted on the server, now explicitly re-synced from a phone.
            current.deleted && !change.deleted && change.baseVersion == null -> StackDecision.Apply(next(current.version + 1))
            // A change that already arrived, sent again because its answer was lost.
            current.deleted == change.deleted && current.document == change.document.takeUnless { change.deleted } ->
                StackDecision.Keep(current, PushOutcome.APPLIED)
            change.baseVersion == current.version -> StackDecision.Apply(next(current.version + 1))
            else -> StackDecision.Keep(current, PushOutcome.CONFLICT)
        }
    }
}
