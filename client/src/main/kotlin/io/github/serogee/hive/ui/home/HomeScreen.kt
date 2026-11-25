package io.github.serogee.hive.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.localhost.LocalHost
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.CountBadge
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.ProgressCells
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.SolidCard
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.hiveColors
import kotlinx.coroutines.launch

/** The icons that stacks take turns using on Home. */
private val StackIcons = listOf(
    Icons.Outlined.Psychology,
    Icons.Outlined.Code,
    Icons.Outlined.Analytics,
    Icons.Outlined.Hub,
    Icons.Outlined.Devices,
    Icons.Outlined.School,
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

    Page(
        title = displayName,
        subtitle = "Ready to test your knowledge?",
        leading = {
            HexBadge(
                text = displayName.take(1).uppercase(),
                size = 48.dp,
                color = MaterialTheme.hiveColors.solid,
                contentColor = Honey,
            )
        },
    ) {
        // Active Joined Room Banner (if any)
        joined?.let { membership ->
            HeroCard(
                badgeText = "ACTIVE QUIZ ROOM",
                title = membership.roomTitle,
                subtitle = "Joined as ${membership.displayName} · Code ${membership.roomCode}",
                buttonText = "RETURN TO ROOM",
                onButtonClick = { onReturnToRoom(membership) },
                main = false,
            )
        }

        // Continue Review in Progress (if any)
        review?.let { attempt ->
            SleekCard(onClick = { onContinueReview(attempt.id) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CountBadge(
                        topText = "${attempt.position + 1}",
                        bottomText = "CARD",
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Caption("Continue review", color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = attempt.stack.title.ifBlank { "Untitled stack" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${attempt.position} of ${attempt.order.size} questions reviewed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        ProgressCells(done = attempt.position, total = attempt.order.size)
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(76.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOpenStack(stack.id) }
                            .padding(vertical = 4.dp),
                    ) {
                        HexBadge(size = 72.dp) {
                            Icon(
                                imageVector = StackIcons[index % StackIcons.size],
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(30.dp),
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = stack.title.ifBlank { "Untitled stack" },
                            style = MaterialTheme.typography.bodySmall,
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
        } else if (filteredStacks.isEmpty()) {
            Text(
                text = "No stacks match \"$searchQuery\"",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // Render recent stacks as cards:
            // [Icon]  Title
            //         X Questions
            filteredStacks.forEach { stack ->
                HomeCard(
                    icon = Icons.Outlined.Layers,
                    title = stack.title.ifBlank { "Untitled stack" },
                    subtitle = if (stack.questionCount == 1) "1 Question" else "${stack.questionCount} Questions",
                    onClick = { onOpenStack(stack.id) },
                )
            }
        }
    }
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
    var nearby by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val normalized = RoomCodes.normalize(code)
    val hostAddress = address.trim().takeIf { nearby && it.isNotEmpty() }?.let(::normalizeAddress)
    val ready = normalized.length == RoomCodes.LENGTH && (!nearby || hostAddress != null)
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
                placeholder = "Room Code",
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

        // Local nearby phone toggle
        ToggleRow("Nearby offline room", nearby, onSolid = true) { nearby = it }

        if (nearby) {
            HiveTextField(
                value = address,
                onValueChange = { address = it.take(60) },
                label = "Host address (e.g. 192.168.43.1:8090)",
                onSolid = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                text = "Connect to the Host's Wi-Fi or hotspot first. The Host's lobby shows the address.",
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
    onClick: () -> Unit,
) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HexBadge(size = 46.dp) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
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

            // Chevron (right)
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
