package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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

/** What the Account page says about sync, or null for a guest. */
internal fun syncStatusText(
    status: SyncStatus,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String? = when (status) {
    SyncStatus.Off -> null
    SyncStatus.Unavailable -> "This server does not sync accounts"
    SyncStatus.Syncing -> "Syncing"
    is SyncStatus.UpToDate -> "Up to date"
    is SyncStatus.Waiting -> status.lastSyncedAt?.let { "Last synced ${syncTime(it, zone, today)}" } ?: "Not synced yet"
    is SyncStatus.Offline -> "Offline" + (status.lastSyncedAt?.let { ". Last synced ${syncTime(it, zone, today)}" } ?: "")
}

private fun syncTime(epochMillis: Long, zone: ZoneId, today: LocalDate): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
    return "${historyDate(epochMillis, zone, today)} at ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
}

/** The account's sync status, with Sync now. */
@Composable
internal fun SyncStatusLine(status: SyncStatus, canSync: Boolean, onSyncNow: () -> Unit) {
    val text = syncStatusText(status) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = when (status) {
                is SyncStatus.Offline -> Icons.Outlined.CloudOff
                SyncStatus.Syncing -> Icons.Outlined.Sync
                else -> Icons.Outlined.CloudSync
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            "Sync: $text",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status != SyncStatus.Unavailable) {
            HiveTextButton("Sync now", onClick = onSyncNow, enabled = canSync && status != SyncStatus.Syncing)
        }
    }
}
