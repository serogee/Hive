package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.stacks.StackSummary
import io.github.serogee.hive.ui.common.CountBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LazyPage
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.review.ReviewSetupSheet
import kotlinx.coroutines.launch

/** Why the stack list is open. A picker returns the chosen stack to the action that opened it. */
enum class StackListPurpose(val title: String) {
    BROWSE("My Stacks"),
    HOST("Choose a stack to host"),
    REVIEW("Choose a stack to review"),
}

@Composable
fun MyStacksScreen(
    purpose: StackListPurpose,
    onOpen: (StackId) -> Unit,
    onCreated: (StackId) -> Unit,
    onImport: () -> Unit,
    onHost: (StackId) -> Unit = onOpen,
    onReview: (attemptId: String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val stacks by container.stackList.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var practiceFor by remember { mutableStateOf<StackId?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    val open: (StackId) -> Unit = { if (purpose == StackListPurpose.REVIEW) practiceFor = it else onOpen(it) }

    LazyPage(title = purpose.title) {
        item {
            Text(
                "Create, review, and host questions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Quick Action Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HiveButton(
                    text = "Create Stack",
                    onClick = { scope.launch { onCreated(container.stacks.create()) } },
                    modifier = Modifier.weight(1f),
                )
                HiveButton(
                    text = "Import File",
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                    style = HiveButtonStyle.SECONDARY,
                )
            }
        }

        // Search bar
        item {
            HiveTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search stacks...",
                leadingIcon = Icons.Outlined.Search,
            )
        }

        // Filter tabs (LIBRARY / ALL style)
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                HiveSegment(
                    text = "ALL STACKS",
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                )
                HiveSegment(
                    text = "RECENT",
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                )
            }
        }

        val list = stacks ?: return@LazyPage

        // RECENT lists the newest change first. ALL STACKS is in title order.
        val filteredList = list
            .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
            .let { found ->
                if (activeTab == 1) {
                    found.sortedByDescending { it.updatedAt }
                } else {
                    found.sortedBy { it.title.ifBlank { "Untitled stack" }.lowercase() }
                }
            }

        if (list.isEmpty()) {
            item {
                SleekCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "No stacks yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Create your own stack or add the built-in sample stack to get started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HiveButton(
                            text = "Add Networking Fundamentals sample",
                            onClick = { scope.launch { open(container.stacks.addSample()) } },
                        )
                    }
                }
            }
        } else if (filteredList.isEmpty()) {
            item {
                Text(
                    text = "No stacks match \"$searchQuery\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            item { SectionHeader(title = "Library (${filteredList.size})") }

            items(filteredList, key = { it.id.value }) { stack ->
                StackCard(
                    stack = stack,
                    onClick = { open(stack.id) },
                    onHost = if (purpose == StackListPurpose.BROWSE) ({ onHost(stack.id) }) else null,
                    onPractice = if (purpose == StackListPurpose.BROWSE) ({ practiceFor = stack.id }) else null,
                )
            }
        }
    }

    practiceFor?.let { stackId ->
        ReviewSetupSheet(stackId, onDismiss = { practiceFor = null }, onStart = { attemptId ->
            practiceFor = null
            onReview(attemptId)
        })
    }
}

/** A saved stack. Quick actions appear when their callbacks are given. */
@Composable
fun StackCard(
    stack: StackSummary,
    onClick: () -> Unit,
    onHost: (() -> Unit)? = null,
    onPractice: (() -> Unit)? = null,
) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Blue Badge with question count
            CountBadge(
                topText = "${stack.questionCount}",
                bottomText = "QS",
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stack.title.ifBlank { "Untitled stack" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (stack.questionCount == 1) "1 question" else "${stack.questionCount} questions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (onHost != null || onPractice != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onHost?.let {
                            HiveButton(
                                text = "Host",
                                onClick = it,
                                style = HiveButtonStyle.SECONDARY,
                            )
                        }
                        onPractice?.let {
                            HiveButton(
                                text = "Practice",
                                onClick = it,
                            )
                        }
                    }
                }
            }
        }
    }
}
