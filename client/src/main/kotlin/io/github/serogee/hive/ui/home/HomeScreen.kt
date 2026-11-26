package io.github.serogee.hive.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.data.rooms.HostAddress
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.CountBadge
import io.github.serogee.hive.ui.common.GateNote
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LazyPage
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.ProgressCells
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.SolidCard
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.common.watchOnline
import io.github.serogee.hive.ui.rooms.NEARBY_HELP
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.Honey
import kotlinx.coroutines.launch

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
    val joined by container.joinedRoom.collectAsStateWithLifecycle()
    val loadedStacks by container.stackList.collectAsStateWithLifecycle()
    val ongoingReviews by container.ongoingReviews.collectAsStateWithLifecycle()
    val preferredName by container.preferredName.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val accountName by container.preferences.accountName.collectAsStateWithLifecycle(initialValue = null)
    val stacks = loadedStacks.orEmpty()
    val existingStackIds = stacks.map { it.id }.toSet()
    val activeReviews = ongoingReviews.orEmpty()
    var abandonId by rememberSaveable { mutableStateOf<String?>(null) }

    val displayName = session?.account?.let { account ->
        accountName?.takeIf { it.accountId == account.id.value }?.name ?: account.displayName
    } ?: preferredName?.takeIf { it.isNotBlank() } ?: "Guest"

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.RECENT) }

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

    LazyPage(
        title = displayName,
        leading = {
            HexBadge(
                text = displayName.take(1).uppercase(),
                size = 48.dp,
                color = Honey,
                contentColor = Graphite,
            )
        },
    ) {
        item {
            Text(
                text = "Ready to test your knowledge?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Active Joined Room Banner (if any)
        joined?.let { membership ->
            item { JoinedRoomCard(membership, "Active session", "Return to session") { onReturnToRoom(membership) } }
        }

        // Continue Review in Progress (swipeable up to 5 ongoing sessions, with gallery dots)
        if (activeReviews.isNotEmpty()) {
            item {
                val pagerState = rememberPagerState(pageCount = { activeReviews.size })
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        pageSpacing = 12.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { page ->
                        val attempt = activeReviews[page]
                        val deleted = loadedStacks != null && attempt.stack.id !in existingStackIds
                        SleekCard(onClick = { onContinueReview(attempt.id) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CountBadge(
                                    topText = "${attempt.position + 1}",
                                    bottomText = "CARD",
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Caption(
                                        if (deleted) "Continue review · stack deleted" else "Continue review",
                                        color = if (deleted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = attempt.stack.title.ifBlank { "Untitled stack" },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${attempt.answeredCount} of ${attempt.order.size} questions answered",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    ProgressCells(done = attempt.answeredCount, total = attempt.order.size)
                                }
                                IconButton(onClick = { abandonId = attempt.id }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Abandon review",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (activeReviews.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(activeReviews.size) { index ->
                                val selected = pagerState.currentPage == index
                                Box(
                                    modifier = Modifier
                                        .size(if (selected) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) Honey else MaterialTheme.colorScheme.outline),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. HERO DARK CARD: Room Code Input + Join, and [Create Stack] + [Host Quiz] buttons
        item {
            JoinCard(
                onJoinCode = onJoinCode,
                onCreateStack = { scope.launch { onStackCreated(container.stacks.create()) } },
                onHostQuiz = onHostQuiz,
            )
        }

        // Review entry
        item {
            HomeCard(
                icon = Icons.Outlined.School,
                title = "Review",
                subtitle = "Practice a stack on your own",
                standaloneIcon = true,
                onClick = onReview,
            )
        }

        // 3. SEARCH & SORT ROW: [Search input] + [[sort]] button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HiveTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search",
                    leadingIcon = Icons.Outlined.Search,
                    modifier = Modifier.weight(1f),
                )

                // Sort button
                HiveButton(
                    text = sortMode.label,
                    onClick = {
                        sortMode = when (sortMode) {
                            SortMode.RECENT -> SortMode.TITLE
                            SortMode.TITLE -> SortMode.QUESTIONS
                            SortMode.QUESTIONS -> SortMode.RECENT
                        }
                    },
                    style = HiveButtonStyle.SECONDARY,
                    icon = Icons.AutoMirrored.Outlined.Sort,
                    iconDescription = "Sort",
                )
            }
        }

        // 4. RECENT STACKS SECTION: List of Stacks
        item {
            SectionHeader(
                title = "Recent stacks",
                actionText = if (stacks.isNotEmpty()) "View All" else null,
                onActionClick = onOpenStacks,
            )
        }

        if (loadedStacks == null) return@LazyPage
        if (stacks.isEmpty()) {
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
                            text = "Create a stack, or add the built-in sample to host it or review it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HiveButton(
                            text = "Add Sample Stack",
                            onClick = { scope.launch { onOpenStack(container.stacks.addSample()) } },
                        )
                    }
                }
            }
        } else if (filteredStacks.isEmpty()) {
            item {
                Text(
                    text = "No stacks match \"$searchQuery\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            // Render recent stacks as cards:
            // [Icon]  Title
            //         X Questions
            items(filteredStacks, key = { it.id.value }) { stack ->
                HomeCard(
                    icon = Icons.Outlined.Layers,
                    title = stack.title.ifBlank { "Untitled stack" },
                    subtitle = if (stack.questionCount == 1) "1 Question" else "${stack.questionCount} Questions",
                    unsynced = session != null && stack.localOnly,
                    onClick = { onOpenStack(stack.id) },
                )
            }
        }
    }

    abandonId?.let { id ->
        HiveDialog(
            title = "Abandon this review?",
            message = "Your progress in this review session will be deleted.",
            confirmText = "Abandon",
            onConfirm = {
                abandonId = null
                scope.launch { container.reviews.abandon(id) }
            },
            dismissText = "Keep",
            onDismiss = { abandonId = null },
            danger = true,
        )
    }
}

/** The room that the user is in. An online room cannot be opened while the server cannot be reached. */
@Composable
fun JoinedRoomCard(membership: SavedMembership, badge: String, buttonText: String, onOpen: () -> Unit) {
    val container = LocalAppContainer.current
    val online = watchOnline()
    val serverUrl by container.serverUrl.collectAsStateWithLifecycle()
    val gate = if (membership.serverUrl == serverUrl) online.serverGate() else null
    HeroCard(
        badgeText = badge,
        title = membership.roomTitle,
        subtitle = "Joined as ${membership.displayName}\nCode ${membership.roomCode}",
        buttonText = buttonText.takeIf { gate == null },
        onButtonClick = onOpen,
        main = false,
        content = gate?.let { { GateNote(it) } },
    )
}

/**
 * Hero Card:
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
    var typeAddress by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val normalized = RoomCodes.normalize(code)
    val hostAddress = if (typeAddress) HostAddress.normalize(address) else null
    val ready = normalized.length == RoomCodes.LENGTH && (!typeAddress || hostAddress != null)
    val submit = { if (ready) onJoinCode(normalized, hostAddress) }

    SolidCard {
        // Top row: Room Code Field + Join Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            HiveTextField(
                value = code,
                onValueChange = { code = it.take(RoomCodes.LENGTH + 2) },
                placeholder = "Session Code",
                onSolid = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.weight(1f),
            )
            HiveButton(
                text = "Join",
                onClick = submit,
                enabled = ready,
                onSolid = true,
            )
        }

        // Bottom row: [Create Stack], until QR scanning exists, and [Host Quiz]
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            HiveButton(
                text = "Create Stack",
                onClick = onCreateStack,
                style = HiveButtonStyle.SECONDARY,
                onSolid = true,
                icon = Icons.Outlined.Add,
                modifier = Modifier.weight(1.3f),
            )
            HiveButton(
                text = "Host Quiz",
                onClick = onHostQuiz,
                onSolid = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Rooms nearby are found by their code. The address is for networks that block the search.
        ToggleRow("Enter the Host's address", typeAddress, onSolid = true) { typeAddress = it }

        if (typeAddress) {
            HiveTextField(
                value = address,
                onValueChange = { address = it.take(60) },
                label = "Host address (e.g. 192.168.43.1:8090)",
                onSolid = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                text = NEARBY_HELP,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A card on Home, used for stacks and for Review:
 * [Icon]  Title
 *         Subtitle
 */
@Composable
private fun HomeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    /** Shows the icon by itself, without a hexagon behind it. */
    standaloneIcon: Boolean = false,
    unsynced: Boolean = false,
    onClick: () -> Unit,
) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (standaloneIcon) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                HexBadge(size = 46.dp) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Title and subtitle (center)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (unsynced) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = "Not synced",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Chevron (right)
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
