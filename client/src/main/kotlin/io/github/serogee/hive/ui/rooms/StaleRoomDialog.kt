package io.github.serogee.hive.ui.rooms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.serogee.hive.core.api.RoomSummary
import io.github.serogee.hive.data.rooms.StaleRooms
import io.github.serogee.hive.ui.accounts.ChoiceDialog
import io.github.serogee.hive.ui.accounts.DialogChoice
import io.github.serogee.hive.ui.accounts.openedAt
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.LocalAppContainer
import kotlinx.coroutines.launch

/** Each time the app opens, asks the Host once whether to close the rooms that have been open for a day. */
@Composable
fun StaleRoomDialog() {
    val container = LocalAppContainer.current
    val checker = container.staleRooms
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(lifecycle, checker) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { checker.check() }
    }
    val due by checker.due.collectAsStateWithLifecycle()
    val failed by checker.failed.collectAsStateWithLifecycle()
    val closedCount by checker.closedCount.collectAsStateWithLifecycle()

    if (failed.isNotEmpty()) {
        val othersNote = if (closedCount > 0) " The others are closed." else ""
        val title = when {
            failed.size == 1 && closedCount == 0 -> "Session did not close"
            failed.size == 1 -> "A session did not close"
            closedCount == 0 -> "Sessions did not close"
            else -> "Some sessions did not close"
        }
        HiveDialog(
            title = title,
            message = "${failed.joinToString(", ") { name(it) }} could not be closed.$othersNote " +
                "Check the connection, and Hive asks again the next time it opens.",
            confirmText = "OK",
            onConfirm = checker::clearFailed,
            dismissText = "Close",
            onDismiss = checker::clearFailed,
        )
        return
    }
    if (due.isEmpty()) return
    val list = due
    val now = checker.now()
    ChoiceDialog(
        title = if (list.size == 1) "Close ${name(list.single())}?" else "Close ${list.size} open sessions?",
        message = if (list.size == 1) {
            val room = list.single()
            "It has been open for ${StaleRooms.openFor(room.createdAt ?: now, now)}, since ${openedAt(room.createdAt ?: now)}. " +
                "Close it for everyone, or keep it open for another day?"
        } else {
            "These sessions have been open for more than a day:\n" +
                list.joinToString("\n") { "${name(it)}, open for ${StaleRooms.openFor(it.createdAt ?: now, now)}" } +
                "\n\nClose them all for everyone, or keep them open for another day?"
        },
        choices = listOf(
            DialogChoice(if (list.size == 1) "Close it" else "Close all", HiveButtonStyle.DANGER) { scope.launch { checker.closeAll(list) } },
            DialogChoice("Keep open", HiveButtonStyle.SECONDARY) { scope.launch { checker.keepOpenAll(list) } },
        ),
    )
}

private fun name(room: RoomSummary) = room.title.ifBlank { "Untitled session" }
