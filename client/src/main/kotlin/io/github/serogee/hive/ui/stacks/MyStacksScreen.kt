package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.stacks.StackSummary
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.review.ReviewSetupSheet
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MidnightNavy
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer
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
    val stacks by container.stacks.stacks.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var practiceFor by remember { mutableStateOf<StackId?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    val open: (StackId) -> Unit = { if (purpose == StackListPurpose.REVIEW) practiceFor = it else onOpen(it) }

    Page(
        title = purpose.title,
        subtitle = "Create, review, and host questions",
    ) {
        // Quick Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { scope.launch { onCreated(container.stacks.create()) } },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Create Stack", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onImport,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.5.dp, ElectricBlue),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ElectricBlue,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Import File", fontWeight = FontWeight.Bold)
            }
        }

        // Search bar (styled like the "Add Exercises" search bar from the picture)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search stacks...", color = MutedSlate) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MutedSlate,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricBlue,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Filter tabs (LIBRARY / ALL style)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            TagPill(
                text = "ALL STACKS",
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
            )
            TagPill(
                text = "RECENT",
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
            )
        }

        val list = stacks ?: return@Page

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
            SleekCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "No stacks yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Create your own stack or add the built-in sample stack to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                    Button(
                        onClick = { scope.launch { open(container.stacks.addSample()) } },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlue,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Add Networking Fundamentals sample", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (filteredList.isEmpty()) {
            Text(
                text = "No stacks match \"$searchQuery\"",
                color = MutedSlate,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            SectionHeader(
                title = "Library (${filteredList.size})",
            )

            filteredList.forEach { stack ->
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

/** A saved stack styled like the workout list items in the reference mockup. */
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
            // Blue Badge with question count (matching 22 MAY style)
            BlueBadge(
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
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (stack.questionCount == 1) "1 question" else "${stack.questionCount} questions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )

                if (onHost != null || onPractice != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onHost?.let {
                            Button(
                                onClick = it,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MidnightNavy,
                                    contentColor = Color.White,
                                ),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Host", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        onPractice?.let {
                            Button(
                                onClick = it,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SoftBlueContainer,
                                    contentColor = ElectricBlue,
                                ),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Practice", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
