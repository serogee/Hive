package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.data.auth.UserSession
import io.github.serogee.hive.data.history.ActivityStats
import io.github.serogee.hive.data.history.QuizResult
import io.github.serogee.hive.data.review.FinishedReview
import io.github.serogee.hive.data.sync.SignOutSummary
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.GoogleSignIn
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveIconButton
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.common.watchOnline
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.HexagonShape
import io.github.serogee.hive.ui.theme.HEXAGON_RATIO
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.hiveColors
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many history entries show before Show all. */
private const val HISTORY_PREVIEW = 5

private enum class AccountTab { ACTIVITY, HISTORY }

private enum class HistoryFilter(val label: String) { ALL("All"), REVIEWS("Reviews"), QUIZZES("Quizzes") }

data class AccountsUiState(
    val busy: Boolean = false,
    val signInError: String? = null,
    /** Set while the sign-out question is open. */
    val signOut: SignOutSummary? = null,
    val unsyncing: Boolean = false,
    val unsyncError: String? = null,
    /** Set after the account was deleted, until the user closes the message. */
    val accountDeleted: Boolean = false,
    /** Says that a new display name waits on this phone. */
    val nameNote: String? = null,
)

class AccountsViewModel(private val container: AppContainer) : ViewModel() {
    val session: StateFlow<UserSession?> = container.session
    val preferredName = container.preferredName
    val accountName = container.preferences.accountName
    val demoAvailable = container.auth.demoAvailable
    val syncStatus = container.sync.status

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    fun signInWithDemo(username: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, signInError = null) }
        try {
            container.auth.signInWithDemo(username, password)
            _state.update { it.copy(busy = false) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, signInError = e.userMessage()) }
        }
    }

    /** Checks the server too, because the user may have just come back online. */
    fun syncNow() {
        container.server.checkNow()
        container.sync.requestSync()
    }

    /** Asks about the synced stacks first, when this phone has any. */
    fun startSignOut() = viewModelScope.launch {
        val summary = container.sync.signOutSummary()
        if (summary.syncedStacks == 0) container.signOut() else _state.update { it.copy(signOut = summary) }
    }

    fun signOut(removeSyncedStacks: Boolean) = viewModelScope.launch {
        _state.update { it.copy(signOut = null) }
        container.signOut(removeSyncedStacks)
    }

    fun cancelSignOut() = _state.update { it.copy(signOut = null) }

    fun setPreferredName(name: String) = viewModelScope.launch { container.preferences.setPreferredName(name) }

    /** Saves the account's display name here at once, and on the server now or with the next sync. */
    fun renameAccount(accountId: String, name: String) = viewModelScope.launch {
        val reached = container.profiles.rename(accountId, name)
        _state.update { it.copy(nameNote = if (reached) null else "Saved on this phone. Your account gets it when you are online.") }
    }

    fun closeNameNote() = _state.update { it.copy(nameNote = null) }

    fun closeDeletedMessage() = _state.update { it.copy(accountDeleted = false) }

    /**
     * Deletes the account and everything the server keeps about it, then signs out. This phone keeps its
     * stacks and history.
     */
    fun unsync(done: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(unsyncing = true, unsyncError = null) }
        try {
            container.api.deleteAccount()
            container.signOut(removeSyncedStacks = false)
            _state.update { it.copy(unsyncing = false, accountDeleted = true) }
            done()
        } catch (e: Exception) {
            _state.update { it.copy(unsyncing = false, unsyncError = "Nothing was deleted. ${e.userMessage()}") }
        }
    }
}

@Composable
fun AccountsScreen(
    /** Shows the sign-in form on the page, for another screen that needs a sign-in. */
    signInRequested: Boolean = false,
    onSignedIn: () -> Unit = {},
    onOpenReview: (attemptId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountsViewModel(container) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferredName by viewModel.preferredName.collectAsStateWithLifecycle()
    val stacks by container.stackList.collectAsStateWithLifecycle()
    val hostedRooms by container.hostedRooms.collectAsStateWithLifecycle()
    val finishedReviews by container.finishedReviews.collectAsStateWithLifecycle()
    val quizResults by container.quizResults.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val online = watchOnline()

    val accountName by viewModel.accountName.collectAsStateWithLifecycle(initialValue = null)
    val signedInAs = session?.account
    // The name that the account chose wins over the name of the sign-in.
    val accountDisplayName = signedInAs?.let { account -> accountName?.takeIf { it.accountId == account.id.value }?.name ?: account.displayName }
    val displayName = accountDisplayName ?: preferredName?.takeIf { it.isNotBlank() } ?: "Guest"
    val editNameLabel = if (session == null) "Edit name" else "Edit display name"

    var tab by rememberSaveable { mutableStateOf(AccountTab.ACTIVITY) }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var showAllHistory by rememberSaveable { mutableStateOf(false) }
    var showEditName by rememberSaveable { mutableStateOf(false) }
    var showUnsync by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(session != null) { if (signInRequested && session != null) onSignedIn() }

    Page(
        title = displayName,
        subtitle = session?.provider?.label ?: "Guest",
        leading = {
            HexBadge(size = 48.dp, color = Honey) {
                Text(displayName.take(1).uppercase(), color = Graphite, style = MaterialTheme.typography.headlineSmall)
            }
        },
        action = { HiveIconButton(Icons.Outlined.Settings, "Settings", onOpenSettings) },
    ) {
        if (signInRequested && session == null) {
            SectionHeader(title = "Account sign in")
            SleekCard { SignInOptions(state, viewModel.demoAvailable, viewModel::signInWithDemo) }
        }

        if (session != null) {
            SyncBar(syncStatus, online.serverGate(), onSyncNow = viewModel::syncNow)
        } else {
            ServerStatusLine(online, onRetry = container.server::retry)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HiveSegment("Activity", selected = tab == AccountTab.ACTIVITY, onClick = { tab = AccountTab.ACTIVITY }, modifier = Modifier.weight(1f))
            HiveSegment("History", selected = tab == AccountTab.HISTORY, onClick = { tab = AccountTab.HISTORY }, modifier = Modifier.weight(1f))
        }

        val stats = ActivityStats.of(
            reviews = finishedReviews.orEmpty(),
            quizzes = quizResults.orEmpty(),
            hostedHereIds = hostedRooms.orEmpty().map { it.id.value }.toSet(),
            today = LocalDate.now(),
            zone = ZoneId.systemDefault(),
        )
        when (tab) {
            AccountTab.ACTIVITY -> {
                StreakCard(stats)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricStatChip("${stacks.orEmpty().size}", "stacks\nsaved", Modifier.weight(1f))
                    MetricStatChip("${finishedReviews.orEmpty().size}", "reviews\nfinished", Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricStatChip("${stats.quizzesPlayed}", "quizzes\nplayed", Modifier.weight(1f))
                    MetricStatChip("${stats.roomsHosted}", "sessions\nhosted", Modifier.weight(1f))
                }
            }
            AccountTab.HISTORY -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryFilter.entries.forEach { choice ->
                        HiveSegment(choice.label, selected = filter == choice, onClick = { filter = choice }, modifier = Modifier.weight(1f))
                    }
                }
                val entries = historyEntries(finishedReviews.orEmpty(), quizResults.orEmpty(), filter)
                if (finishedReviews != null && quizResults != null && entries.isEmpty()) {
                    SleekCard {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Finish a review, or host or play a quiz, to see it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                (if (showAllHistory) entries else entries.take(HISTORY_PREVIEW)).forEach { entry ->
                    when (entry) {
                        is HistoryEntry.Review -> ReviewCard(entry.review) { onOpenReview(entry.review.attempt.id) }
                        is HistoryEntry.Quiz -> QuizResultCard(entry.result)
                    }
                }
                if (entries.size > HISTORY_PREVIEW) {
                    HiveTextButton(
                        if (showAllHistory) "Show less" else "Show all ${entries.size}",
                        onClick = { showAllHistory = !showAllHistory },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        Caption("Your account")
        if (session == null) {
            SleekCard { AccountRow(Icons.Outlined.Edit, editNameLabel) { showEditName = true } }
            SleekCard {
                Caption("Sign in")
                SignInOptions(state, viewModel.demoAvailable, viewModel::signInWithDemo)
            }
        } else {
            SleekCard {
                Column {
                    AccountRow(Icons.Outlined.Edit, editNameLabel) { showEditName = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AccountRow(Icons.AutoMirrored.Outlined.Logout, "Sign out", onClick = viewModel::startSignOut)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AccountRow(Icons.Outlined.DeleteOutline, "Unsync and delete account", MaterialTheme.colorScheme.error) { showUnsync = true }
                }
            }
            Text(
                "Unsync and delete account deletes your account, and removes your synced stacks, history, and online sessions from " +
                    "the server. This phone keeps everything it has.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showEditName) {
        var nameInput by rememberSaveable { mutableStateOf(accountDisplayName ?: preferredName ?: "") }
        HiveDialog(
            title = editNameLabel,
            confirmText = "Save",
            onConfirm = {
                DisplayNames.normalize(nameInput)?.let { name ->
                    val account = signedInAs
                    if (account == null) viewModel.setPreferredName(name) else viewModel.renameAccount(account.id.value, name)
                }
                showEditName = false
            },
            dismissText = "Cancel",
            onDismiss = { showEditName = false },
        ) {
            Text(
                if (session == null) {
                    "Enter the display name you would like to use in Hive:"
                } else {
                    "Other players see this name in sessions, and every phone where you sign in uses it. Your Google account stays the same."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HiveTextField(
                value = nameInput,
                onValueChange = { nameInput = it.take(DisplayNames.MAX_LENGTH) },
                placeholder = "Your Name",
            )
        }
    }

    state.nameNote?.let { note ->
        HiveDialog(
            title = "Display name saved",
            message = note,
            confirmText = "OK",
            onConfirm = viewModel::closeNameNote,
            dismissText = "Close",
            onDismiss = viewModel::closeNameNote,
        )
    }

    if (showUnsync) {
        HiveDialog(
            title = "Unsync and delete account?",
            confirmText = if (state.unsyncing) "Deleting" else "Delete",
            onConfirm = { if (!state.unsyncing) viewModel.unsync { showUnsync = false } },
            dismissText = "Cancel",
            onDismiss = { if (!state.unsyncing) showUnsync = false },
            danger = true,
        ) {
            Text(
                "Your account is deleted, with the stacks, reviews, and quiz results that the server keeps for it. " +
                    "Any open sessions you host end, and past session results keep your display name. " +
                    "You are signed out on every phone, and you cannot undo this. This phone keeps its stacks and history.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ErrorText(state.unsyncError)
        }
    }

    if (state.accountDeleted) {
        HiveDialog(
            title = "Account deleted",
            message = "Your account and its data on the server are gone. Everything on this phone is still here.",
            confirmText = "OK",
            onConfirm = viewModel::closeDeletedMessage,
            dismissText = "Close",
            onDismiss = viewModel::closeDeletedMessage,
        )
    }

    state.signOut?.let { summary ->
        val kept = if (summary.unsyncedStacks == 0) {
            ""
        } else {
            " ${summary.unsyncedStacks} ${if (summary.unsyncedStacks == 1) "stack has" else "stacks have"} changes that your " +
                "account does not have yet, so they stay either way."
        }
        ChoiceDialog(
            title = "Sign out",
            message = "Keep the ${summary.syncedStacks} synced ${if (summary.syncedStacks == 1) "stack" else "stacks"} on this " +
                "phone, or remove them? Your account keeps them either way.$kept",
            choices = listOf(
                DialogChoice("Keep on this phone", HiveButtonStyle.SECONDARY) { viewModel.signOut(removeSyncedStacks = false) },
                DialogChoice("Remove from this phone", HiveButtonStyle.DANGER) { viewModel.signOut(removeSyncedStacks = true) },
            ),
            dismissText = "Cancel",
            onDismiss = viewModel::cancelSignOut,
        )
    }
}

/** The day streak, the days of this week with activity, and the average score. */
@Composable
private fun StreakCard(stats: ActivityStats) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, Honey),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${stats.streakDays}", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 56.sp, lineHeight = 56.sp))
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(if (stats.streakDays == 1) "DAY STREAK" else "DAYS STREAK", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Review or play today to keep it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, day ->
                    val active = stats.weekActive.getOrElse(index) { false }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(HEXAGON_RATIO)
                                .clip(HexagonShape)
                                .background(if (active) Honey else MaterialTheme.hiveColors.emptyCell),
                        )
                        Text(day, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Average score",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stats.averagePercent?.let { "$it%" } ?: "None yet", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** A finished review or a quiz, in one list. */
private sealed interface HistoryEntry {
    val at: Long

    data class Review(val review: FinishedReview) : HistoryEntry {
        override val at get() = review.finishedAt
    }

    data class Quiz(val result: QuizResult) : HistoryEntry {
        override val at get() = result.playedAt
    }
}

private fun historyEntries(reviews: List<FinishedReview>, quizzes: List<QuizResult>, filter: HistoryFilter): List<HistoryEntry> {
    val reviewEntries = if (filter == HistoryFilter.QUIZZES) emptyList() else reviews.map { HistoryEntry.Review(it) }
    val quizEntries = if (filter == HistoryFilter.REVIEWS) emptyList() else quizzes.map { HistoryEntry.Quiz(it) }
    return (reviewEntries + quizEntries).sortedByDescending { it.at }
}

@Composable
private fun ReviewCard(review: FinishedReview, onClick: () -> Unit) {
    val mode = if (review.attempt.settings.mode == ReviewMode.QUIZ) "Quiz review" else "Flashcard review"
    HistoryRow(
        title = review.attempt.stack.title.ifBlank { "Untitled stack" },
        meta = "${historyDate(review.finishedAt)}, $mode",
        score = review.score,
        onClick = onClick,
    )
}

/** A hosted or played quiz, with the Player's score once it ended. */
@Composable
private fun QuizResultCard(quiz: QuizResult) {
    val score = when {
        quiz.role == RoomRole.HOST -> if (quiz.playerCount == 1) "1 player" else "${quiz.playerCount} players"
        !quiz.ended -> "Live"
        quiz.correctCount != null -> "${quiz.correctCount}/${quiz.questionCount}"
        else -> "Ended"
    }
    val rank = quiz.rank?.takeIf { quiz.role == RoomRole.PLAYER && quiz.ended }?.let { ", rank $it" }.orEmpty()
    HistoryRow(title = quiz.title.ifBlank { "Untitled quiz" }, meta = "${historyDate(quiz.playedAt)}, ${quiz.label}$rank", score = score)
}

@Composable
private fun HistoryRow(title: String, meta: String, score: String, onClick: (() -> Unit)? = null) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TagPill(text = score, selected = false)
        }
    }
}

@Composable
private fun AccountRow(icon: ImageVector, title: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

/** Google, and the demo accounts in a debug app. */
@Composable
private fun SignInOptions(state: AccountsUiState, demoAvailable: Boolean, onDemoSignIn: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GoogleSignIn(onSignedIn = {})
        if (demoAvailable) {
            DemoSignInForm(state.busy, onDemoSignIn)
            ErrorText(state.signInError)
        }
    }
}

@Composable
private fun DemoSignInForm(busy: Boolean, onSignIn: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Caption("Demo server")
    Text(
        "Demo server accounts: alex or sam, password hive-demo",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HiveTextField(value = username, onValueChange = { username = it }, label = "Username")
    HiveTextField(
        value = password,
        onValueChange = { password = it },
        label = "Password",
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    HiveButton(
        text = "Sign in",
        onClick = { onSignIn(username, password) },
        enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        style = HiveButtonStyle.SECONDARY,
    )
}
