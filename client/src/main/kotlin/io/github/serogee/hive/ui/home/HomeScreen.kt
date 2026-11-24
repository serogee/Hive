package io.github.serogee.hive.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.localhost.LocalHost
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.theme.Amber
import io.github.serogee.hive.ui.theme.AmberInk
import io.github.serogee.hive.ui.theme.DarkSlate
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.ElectricBlueLight
import io.github.serogee.hive.ui.theme.MidnightNavy
import io.github.serogee.hive.ui.theme.MidnightNavyLight
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.Orange
import io.github.serogee.hive.ui.theme.SoftBlueContainer
import io.github.serogee.hive.ui.theme.StackAccentColors
import kotlinx.coroutines.launch

/** The icon and color that a stack shows on Home, chosen by its position. */
private data class StackAccent(
    val icon: ImageVector,
    val color: Color,
)

private val StackAccents = listOf(
    StackAccent(Icons.Outlined.Psychology, StackAccentColors[0]),
    StackAccent(Icons.Outlined.Code, StackAccentColors[1]),
    StackAccent(Icons.Outlined.Analytics, StackAccentColors[2]),
    StackAccent(Icons.Outlined.Hub, StackAccentColors[3]),
    StackAccent(Icons.Outlined.Devices, StackAccentColors[4]),
    StackAccent(Icons.Outlined.School, StackAccentColors[5]),
)

enum class SortMode(val label: String) {
    RECENT("Recent"),
    TITLE("A-Z"),
    QUESTIONS("Questions"),
}

@Composable
fun HomeScreen(
    onJoinCode: (code: String, hostAddress: String?) -> Unit,
    onOpenStacks: () -> Unit,
    onOpenStack: (StackId) -> Unit,
    onContinueReview: (attemptId: String) -> Unit,
    onReturnToRoom: (SavedMembership) -> Unit,
    onHostQuiz: () -> Unit,
    onReview: () -> Unit,
    onStackCreated: (StackId) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val joined by container.rooms.joinedRoom.collectAsStateWithLifecycle(null)
    val stacks by container.stacks.stacks.collectAsStateWithLifecycle(emptyList())
    val review by container.reviews.latestUnfinished.collectAsStateWithLifecycle(null)
    val preferredName by container.preferences.preferredName.collectAsStateWithLifecycle(null)
    val session by container.auth.session.collectAsStateWithLifecycle(null)

    val displayName = session?.account?.displayName
        ?: preferredName?.takeIf { it.isNotBlank() }
        ?: "Guest"

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.RECENT) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 1. TOP HEADER: Avatar Circle + User's Name (Matching Figma Placement)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(ElectricBlue, MidnightNavyLight)
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Ready to test your knowledge?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )
            }
        }

        // Active Joined Room Banner (if any)
        joined?.let { membership ->
            HeroNavyCard(
                badgeText = "ACTIVE QUIZ ROOM",
                title = membership.roomTitle,
                subtitle = "Joined as ${membership.displayName} · Code ${membership.roomCode}",
                buttonText = "RETURN TO ROOM",
                onButtonClick = { onReturnToRoom(membership) },
            )
        }

        // Continue Review in Progress (if any)
        review?.let { attempt ->
            val progress = if (attempt.order.isNotEmpty()) {
                attempt.position.toFloat() / attempt.order.size.toFloat()
            } else 0f

            SleekCard(onClick = { onContinueReview(attempt.id) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BlueBadge(
                        topText = "${attempt.position + 1}",
                        bottomText = "CARD",
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CONTINUE REVIEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricBlue,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = attempt.stack.title.ifBlank { "Untitled stack" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${attempt.position} of ${attempt.order.size} questions reviewed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSlate,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ElectricBlue,
                            trackColor = SoftBlueContainer,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MutedSlate,
                    )
                }
            }
        }

        // 2. HERO DARK CARD: Room Code Input + Join, and [Create Stack] + [Host Quiz] buttons
        JoinCard(
            onJoinCode = onJoinCode,
            onCreateStack = { scope.launch { onStackCreated(container.stacks.create()) } },
            onHostQuiz = onHostQuiz,
        )

        // Review entry
        HomeCard(
            icon = Icons.Outlined.School,
            tint = ElectricBlue,
            title = "Review",
            subtitle = "Practice a stack on your own",
            onClick = onReview,
        )

        // 3. SEARCH & SORT ROW: [Search input] + [[sort]] button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search", color = MutedSlate) },
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
                modifier = Modifier.weight(1f),
            )

            // Sort button
            OutlinedButton(
                onClick = {
                    sortMode = when (sortMode) {
                        SortMode.RECENT -> SortMode.TITLE
                        SortMode.TITLE -> SortMode.QUESTIONS
                        SortMode.QUESTIONS -> SortMode.RECENT
                    }
                },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = DarkSlate,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = "Sort",
                    tint = ElectricBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = sortMode.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElectricBlue,
                )
            }
        }

        // 4. MY STACKS ROW: [see all] action + Horizontal row of icon boxes with label underneath
        if (stacks.isNotEmpty()) {
            SectionHeader(
                title = "My Stacks",
                actionText = "[see all]",
                onActionClick = onOpenStacks,
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                itemsIndexed(stacks.take(6)) { index, stack ->
                    val accent = StackAccents[index % StackAccents.size]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(72.dp)
                            .clickable { onOpenStack(stack.id) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .background(accent.color.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = accent.icon,
                                contentDescription = null,
                                tint = accent.color,
                                modifier = Modifier.size(30.dp),
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = stack.title.ifBlank { "Untitled stack" },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 5. RECENT STACKS SECTION: List of Stacks
        SectionHeader(
            title = "Recent stacks",
            actionText = if (stacks.isNotEmpty()) "View All" else null,
            onActionClick = onOpenStacks,
        )

        // Filter and sort stacks
        val filteredStacks = stacks
            .filter { stack -> searchQuery.isBlank() || stack.title.contains(searchQuery, ignoreCase = true) }
            .let { list ->
                when (sortMode) {
                    SortMode.RECENT -> list.sortedByDescending { it.updatedAt }
                    SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                    SortMode.QUESTIONS -> list.sortedByDescending { it.questionCount }
                }
            }

        if (stacks.isEmpty()) {
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
                        text = "Create a stack, or add the built-in sample to host it or review it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { scope.launch { onOpenStack(container.stacks.addSample()) } },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricBlue,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Add Sample Stack", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (filteredStacks.isEmpty()) {
            Text(
                text = "No stacks match \"$searchQuery\"",
                color = MutedSlate,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // Render recent stacks in the Figma card style:
            // [Square icon box]  Title
            //                    X Questions
            filteredStacks.forEachIndexed { index, stack ->
                val fallbackColor = StackAccents[index % StackAccents.size].color
                HomeCard(
                    icon = Icons.Outlined.Layers,
                    tint = fallbackColor,
                    title = stack.title.ifBlank { "Untitled stack" },
                    subtitle = if (stack.questionCount == 1) "1 Question" else "${stack.questionCount} Questions",
                    onClick = { onOpenStack(stack.id) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Figma Hero Card:
 * - Room Code Input + [Join]
 * - [Create Stack]  [Host Quiz]
 */
@Composable
fun JoinCard(
    onJoinCode: (code: String, hostAddress: String?) -> Unit,
    onCreateStack: () -> Unit,
    onHostQuiz: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var nearby by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val normalized = RoomCodes.normalize(code)
    val hostAddress = address.trim().takeIf { nearby && it.isNotEmpty() }?.let(::normalizeAddress)
    val ready = normalized.length == RoomCodes.LENGTH && (!nearby || hostAddress != null)
    val submit = { if (ready) onJoinCode(normalized, hostAddress) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(MidnightNavy, MidnightNavyLight)
                    )
                )
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Top row: Room Code Field + Join Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(RoomCodes.LENGTH + 2) },
                        placeholder = {
                            Text(
                                "Room Code",
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlueLight,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedContainerColor = Color.White.copy(alpha = 0.10f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.weight(1f),
                    )

                    Button(
                        onClick = submit,
                        enabled = ready,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = MidnightNavy,
                            disabledContainerColor = Color.White.copy(alpha = 0.3f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.height(54.dp),
                    ) {
                        Text(
                            "Join",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }

                // Bottom row: [Create Stack] (warm accent / pill) + [Host Quiz] (primary)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Create Stack button (warm gold/amber accent like in Figma), until QR scanning exists
                    Button(
                        onClick = onCreateStack,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = AmberInk,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Create Stack",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }

                    // Host Quiz button (orange / vibrant accent button like in Figma)
                    Button(
                        onClick = onHostQuiz,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Orange,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                    ) {
                        Text(
                            text = "Host Quiz",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }

                // Local nearby phone toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { nearby = !nearby }
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Nearby offline room",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Switch(
                        checked = nearby,
                        onCheckedChange = { nearby = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ElectricBlueLight,
                            uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                        ),
                    )
                }

                if (nearby) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.take(60) },
                        label = { Text("Host address (e.g. 192.168.43.1:8090)", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlueLight,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Connect to the Host's Wi-Fi or hotspot first. The Host's lobby shows the address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * A card on Home, used for stacks and for Review:
 * [Square icon]  Title
 *                Subtitle
 */
@Composable
private fun HomeCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Rounded square icon box (left)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Title and subtitle (center)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )
            }

            // Chevron (right)
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MutedSlate,
            )
        }
    }
}

/** Accepts "192.168.43.1", "192.168.43.1:8090", or a full URL. */
private fun normalizeAddress(raw: String): String {
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val hostPart = withScheme.substringAfter("://").substringBefore('/')
    return if (':' in hostPart) withScheme.trimEnd('/') else "${withScheme.trimEnd('/')}:${LocalHost.PORT}"
}
