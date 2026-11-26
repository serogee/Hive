package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.data.sync.Unlinked
import io.github.serogee.hive.data.sync.SyncStatus
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.GateNote
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.data.server.Gate
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.theme.hiveColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** One choice of a [ChoiceDialog]. */
data class DialogChoice(val text: String, val style: HiveButtonStyle = HiveButtonStyle.PRIMARY, val onClick: () -> Unit)

/**
 * A dialog with a button for each choice. Without [dismissText], a tap outside does nothing, so that
 * the user makes the choice.
 */
@Composable
fun ChoiceDialog(
    title: String,
    message: String,
    choices: List<DialogChoice>,
    dismissText: String? = null,
    onDismiss: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = { if (dismissText != null) onDismiss() },
        modifier = Modifier.border(2.dp, MaterialTheme.hiveColors.border, MaterialTheme.shapes.extraLarge),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                choices.forEach { choice ->
                    HiveButton(text = choice.text, onClick = choice.onClick, style = choice.style, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = dismissText?.let { { HiveTextButton(it, onDismiss) } },
    )
}

/** Asks once, after sign-in, what happens to what this phone had before. Shown over any screen. */
@Composable
fun SyncChoiceDialog() {
    val container = LocalAppContainer.current
    val choice by container.sync.choice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val unlinked = choice ?: return
    ChoiceDialog(
        title = "Sync this phone?",
        message = choiceMessage(unlinked),
        choices = listOf(
            DialogChoice("Upload to my account") { scope.launch { container.sync.choose(upload = true) } },
            DialogChoice("Keep on this phone only", HiveButtonStyle.SECONDARY) { scope.launch { container.sync.choose(upload = false) } },
        ),
    )
}

internal fun choiceMessage(unlinked: Unlinked): String {
    val parts = listOfNotNull(
        count(unlinked.stacks, "stack"),
        count(unlinked.reviews, "finished review"),
        count(unlinked.results, "quiz result"),
    )
    val list = when (parts.size) {
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> "${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}"
    }
    val them = if (unlinked.stacks + unlinked.reviews + unlinked.results == 1) "it" else "them"
    return "This phone has $list that your account does not have. Upload $them to sync with your other phones, " +
        "or keep $them on this phone only. Stacks you make from now on sync."
}

private fun count(n: Int, noun: String) = if (n <= 0) null else if (n == 1) "1 $noun" else "$n ${noun}s"

/** The first line of the sync bar, or null for a guest, who has no sync. */
internal fun syncHeadline(status: SyncStatus): String? = when (status) {
    SyncStatus.Off -> null
    SyncStatus.Unavailable -> "Sync is off on this server"
    SyncStatus.Syncing -> "Syncing"
    is SyncStatus.UpToDate -> "Up to date"
    is SyncStatus.Waiting -> "Changes wait to sync"
    is SyncStatus.Offline -> "Offline, will sync later"
}

/** The second line: that sync runs by itself, and when it last worked. */
internal fun syncDetail(
    status: SyncStatus,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    val last = when (status) {
        is SyncStatus.UpToDate -> status.at
        is SyncStatus.Waiting -> status.lastSyncedAt
        is SyncStatus.Offline -> status.lastSyncedAt
        else -> null
    }
    return when {
        status == SyncStatus.Unavailable -> "This server does not keep accounts"
        last == null -> "Syncs automatically"
        else -> "Syncs automatically. Last at ${syncTime(last, zone, today)}"
    }
}

/** The time alone today, and the day too before that. */
private fun syncTime(epochMillis: Long, zone: ZoneId, today: LocalDate): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val time = moment.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return if (moment.toLocalDate() == today) time else "${historyDate(epochMillis, zone, today)}, $time"
}

/** The account's sync across the page: what it is doing, and a button to sync now. */
@Composable
internal fun SyncBar(status: SyncStatus, gate: Gate?, onSyncNow: () -> Unit) {
    val headline = syncHeadline(status) ?: return
    SleekCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = when (status) {
                    is SyncStatus.Offline -> Icons.Outlined.CloudOff
                    SyncStatus.Syncing -> Icons.Outlined.Sync
                    is SyncStatus.Waiting -> Icons.Outlined.CloudSync
                    else -> Icons.Outlined.CloudDone
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(syncDetail(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status != SyncStatus.Unavailable) {
                val enabled = gate == null && status != SyncStatus.Syncing
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.5f)) {
                    IconButton(onClick = onSyncNow, enabled = enabled) {
                        Icon(Icons.Outlined.Sync, contentDescription = "Sync now", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        gate?.let { GateNote(it) }
    }
}
