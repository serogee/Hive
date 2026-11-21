package io.github.serogee.hive.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.stacks.StackCard
import io.github.serogee.hive.ui.theme.DarkSlate
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.ElectricBlueDark
import io.github.serogee.hive.ui.theme.MidnightNavy
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer

@Composable
fun HomeScreen(
    onJoinCode: (code: String, hostAddress: String?) -> Unit,
    onOpenStacks: () -> Unit,
    onOpenStack: (StackId) -> Unit,
    onContinueReview: (attemptId: String) -> Unit,
    onReturnToRoom: (SavedMembership) -> Unit,
) {
    val container = LocalAppContainer.current
    val joined by container.rooms.joinedRoom.collectAsStateWithLifecycle(null)
    val stacks by container.stacks.stacks.collectAsStateWithLifecycle(emptyList())
    val review by container.reviews.latestUnfinished.collectAsStateWithLifecycle(null)

    val totalQuestions = stacks.sumOf { it.questionCount }
    val stacksCount = stacks.size

    Page(
        title = "Dashboard",
        action = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SoftBlueContainer)
                    .clickable { /* Profile / account action */ },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = "Profile",
                    tint = ElectricBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) {
        // Horizontal Stat Metrics Row (matching Dashboard's "25 workouts", "103k kg", "70 kg")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricStatChip(
                value = "$stacksCount",
                label = "stacks\ncreated",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "$totalQuestions",
                label = "questions\nin library",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = if (joined != null) "1" else "0",
                label = "active\nrooms",
                modifier = Modifier.weight(1f),
            )
        }

        // Return to active joined room (styled as Midnight Navy Hero Card)
        joined?.let { membership ->
            HeroNavyCard(
                badgeText = "ACTIVE QUIZ ROOM",
                title = membership.roomTitle,
                subtitle = "Joined as ${membership.displayName} · Code ${membership.roomCode}",
                buttonText = "RETURN TO ROOM",
                onButtonClick = { onReturnToRoom(membership) },
            )
        }

        // Continue Review (styled like "Previous workout" in the mockup)
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
                            text = "Continue Review",
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

        // Join a quiz section
        JoinCard(onJoinCode)

        // Stacks section (matching "MY WORKOUTS" in the reference design)
        SectionHeader(
            title = "My Stacks",
            actionText = if (stacks.isNotEmpty()) "Show All" else null,
            onActionClick = onOpenStacks,
        )

        if (stacks.isEmpty()) {
            SleekCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Build or import a stack",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Create question stacks once, then host multiplayer quizzes or practice them solo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                    Button(
                        onClick = onOpenStacks,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlue,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Go to My Stacks", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // First stack rendered as the prominent Midnight Navy hero card
            val featured = stacks.first()
            HeroNavyCard(
                badgeText = "FEATURED STACK",
                title = featured.title.ifBlank { "Untitled stack" },
                subtitle = "${featured.questionCount} questions · Ready for practice or hosting",
                buttonText = "VIEW STACK",
                onButtonClick = { onOpenStack(featured.id) },
            )

            // Other recent stacks rendered as sleek cards
            stacks.drop(1).take(2).forEach { stack ->
                StackCard(stack = stack, onClick = { onOpenStack(stack.id) })
            }
        }
    }
}

/**
 * Join a quiz by code. Modernized to match the reference app's sleek form style.
 */
@Composable
fun JoinCard(onJoinCode: (code: String, hostAddress: String?) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var nearby by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val normalized = RoomCodes.normalize(code)
    val hostAddress = address.trim().takeIf { nearby && it.isNotEmpty() }?.let(::normalizeAddress)
    val ready = normalized.length == RoomCodes.LENGTH && (!nearby || hostAddress != null)
    val submit = { if (ready) onJoinCode(normalized, hostAddress) }

    SleekCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "JOIN A QUIZ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MutedSlate,
                letterSpacing = 1.2.sp,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(RoomCodes.LENGTH + 2) },
                    placeholder = { Text("Room code (e.g. NETDSC)", color = MutedSlate) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
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
                        containerColor = ElectricBlue,
                        contentColor = Color.White,
                        disabledContainerColor = ElectricBlue.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier.height(54.dp),
                ) {
                    Text("Join", fontWeight = FontWeight.Bold)
                }
            }

            // Nearby toggle styled like the "Enable rest timer" switch from the picture
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { nearby = !nearby }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Room hosted on nearby phone",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = nearby,
                    onCheckedChange = { nearby = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ElectricBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD6DFEC),
                    ),
                )
            }

            if (nearby) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(60) },
                    label = { Text("Host phone address") },
                    placeholder = { Text("192.168.43.1:8090") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Connect to the Host's Wi-Fi or hotspot first. The Host's lobby shows the address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )
            }
        }
    }
}

/** Accepts "192.168.43.1", "192.168.43.1:8090", or a full URL. */
private fun normalizeAddress(raw: String): String {
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val hostPart = withScheme.substringAfter("://").substringBefore('/')
    return if (':' in hostPart) withScheme.trimEnd('/') else "${withScheme.trimEnd('/')}:${LocalHost.PORT}"
}
