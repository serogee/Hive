package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.core.engine.DisplayNames
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.data.auth.UserSession
import io.github.serogee.hive.core.model.RoomRole
import io.github.serogee.hive.data.history.ActivityStats
import io.github.serogee.hive.data.history.QuizResult
import io.github.serogee.hive.data.server.OnlineFacts
import io.github.serogee.hive.data.sync.SignOutSummary
import io.github.serogee.hive.data.server.ServerState
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.GateNote
import io.github.serogee.hive.ui.common.GoogleSignIn
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.SolidCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.common.watchOnline
import io.github.serogee.hive.ui.theme.AnswerColorChoice
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.SheetShape
import io.github.serogee.hive.ui.theme.ThemePreference
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many finished reviews show before Show All. */
private const val HISTORY_PREVIEW = 5

data class AccountsUiState(
    val busy: Boolean = false,
    val signInError: String? = null,
    val serverStatus: String? = null,
    /** Set while the sign-out question is open. */
    val signOut: SignOutSummary? = null,
    val resetError: String? = null,
)

class AccountsViewModel(private val container: AppContainer) : ViewModel() {
    val session: StateFlow<UserSession?> = container.session
    val serverUrl = container.preferences.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val theme = container.preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemePreference.SYSTEM)
    val answerColors = container.preferences.answerColors
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnswerColorChoice.APP_HONEY)
    val answerInput = container.preferences.answerInput.stateIn(viewModelScope, SharingStarted.Eagerly, AnswerInput.CONFIRM)
    val preferredName = container.preferredName
    val demoAvailable = container.auth.demoAvailable

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

    val syncStatus = container.sync.status

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

    fun setTheme(theme: ThemePreference) = viewModelScope.launch { container.preferences.setTheme(theme) }

    fun setAnswerColors(choice: AnswerColorChoice) = viewModelScope.launch { container.preferences.setAnswerColors(choice) }

    fun setAnswerInput(input: AnswerInput) = viewModelScope.launch { container.preferences.setAnswerInput(input) }

    fun setPreferredName(name: String) = viewModelScope.launch {
        container.preferences.setPreferredName(name)
    }

    /** Saves the address only when a Hive server answers there. */
    fun saveServer(rawUrl: String) = viewModelScope.launch {
        val url = rawUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        _state.update { it.copy(serverStatus = "Checking $url…") }
        try {
            val health = container.api.health(url)
            if (url != serverUrl.value) {
                // Sessions belong to one server, so end the old one there first.
                container.signOut()
                container.preferences.setServerUrl(url)
            }
            _state.update { it.copy(serverStatus = "Connected to $url (API v${health.apiVersion})") }
        } catch (e: Exception) {
            _state.update { it.copy(serverStatus = "No Hive server at $url. ${e.userMessage()}") }
        }
    }

    /**
     * Leaves the joined room on its server too, so that the Host sees the Player leave. With [account], it
     * first deletes what the account synced. Returns whether it worked.
     */
    fun resetData(account: Boolean, done: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(resetError = null) }
        if (account) {
            try {
                container.sync.deleteAccountData()
            } catch (e: Exception) {
                _state.update { it.copy(resetError = "The account data was not deleted. ${e.userMessage()}") }
                return@launch
            }
        }
        container.rooms.leaveJoinedRoom()
        done()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    /** Shows the sign-in form on the page, for another screen that needs a sign-in. */
    signInRequested: Boolean = false,
    onSignedIn: () -> Unit = {},
    onOpenReview: (attemptId: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountsViewModel(container) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val answerColors by viewModel.answerColors.collectAsStateWithLifecycle()
    val answerInput by viewModel.answerInput.collectAsStateWithLifecycle()
    val preferredName by viewModel.preferredName.collectAsStateWithLifecycle()
    val stacks by container.stackList.collectAsStateWithLifecycle()
    val hostedRooms by container.hostedRooms.collectAsStateWithLifecycle()
    val finishedReviews by container.finishedReviews.collectAsStateWithLifecycle()
    val quizResults by container.quizResults.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    val displayName = session?.account?.displayName
        ?: preferredName?.takeIf { it.isNotBlank() }
        ?: "Guest"

    val editNameLabel = if (session == null) "Edit Name" else "Edit Guest Name"

    var showEditNameDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showAnswerColors by rememberSaveable { mutableStateOf(false) }
    var showAnswerInput by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showAllHistory by rememberSaveable { mutableStateOf(false) }
    var showAllQuizzes by rememberSaveable { mutableStateOf(false) }
    var confirmAccountReset by rememberSaveable { mutableStateOf(false) }

    val online = watchOnline()

    LaunchedEffect(session != null) { if (signInRequested && session != null) onSignedIn() }

    Page(title = "Account") {
        // 1. PROFILE HERO SECTION: Initial, name, how the user signed in, and Edit Name
        SolidCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                HexBadge(size = 72.dp, color = Honey) {
                    Text(
                        text = displayName.take(1).uppercase(),
                        color = Graphite,
                        style = MaterialTheme.typography.headlineLarge,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName.uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    session?.let { Text(it.provider.label, style = MaterialTheme.typography.bodyMedium) }

                    // Edit Name Clickable Link
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { showEditNameDialog = true }
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = Honey,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Caption(editNameLabel, color = Honey)
                    }
                }
            }
        }

        if (signInRequested && session == null) {
            SectionHeader(title = "Account sign in")
            SleekCard { SignInOptions(state, viewModel.demoAvailable, viewModel::signInWithDemo) }
        }

        ServerStatusLine(online, onRetry = container.server::retry)
        if (session != null) SyncStatusLine(syncStatus, canSync = online.serverGate() == null, onSyncNow = viewModel::syncNow)

        // 2. STAT CHIPS: what this phone and the account have
        val stats = ActivityStats.of(
            reviews = finishedReviews.orEmpty(),
            quizzes = quizResults.orEmpty(),
            hostedHereIds = hostedRooms.orEmpty().map { it.id.value }.toSet(),
            today = LocalDate.now(),
            zone = ZoneId.systemDefault(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricStatChip(
                value = "${stacks.orEmpty().size}",
                label = "stacks\nsaved",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "${finishedReviews.orEmpty().size}",
                label = "reviews\nfinished",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "${stats.quizzesPlayed}",
                label = "quizzes\nplayed",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricStatChip(
                value = "${stats.roomsHosted}",
                label = "rooms\nhosted",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "${stats.streakDays}",
                label = if (stats.streakDays == 1) "day\nstreak" else "days\nstreak",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = stats.averagePercent?.let { "$it%" } ?: "None",
                label = "average\nscore",
                modifier = Modifier.weight(1f),
            )
        }

        // 3. SETTINGS ROWS: Each opens its bottom sheet
        SettingRow(
            icon = Icons.Outlined.Palette,
            title = "Answer colours",
            value = answerColors.label,
            onClick = { showAnswerColors = true },
        )
        SettingRow(
            icon = Icons.Outlined.TouchApp,
            title = "Answering",
            value = answerInput.label,
            onClick = { showAnswerInput = true },
        )
        SettingRow(
            icon = Icons.Outlined.Settings,
            title = "Settings",
            value = null,
            onClick = { showSettingsSheet = true },
        )

        // 4. REVIEW HISTORY SECTION: Cards with date and score
        val finished = finishedReviews.orEmpty()
        val hasMore = finished.size > HISTORY_PREVIEW
        SectionHeader(
            title = "Review History",
            actionText = if (!hasMore) null else if (showAllHistory) "Show Less" else "Show All",
            onActionClick = { showAllHistory = !showAllHistory },
        )

        if (finishedReviews?.isEmpty() == true) {
            SleekCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No finished reviews yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Finish a review of a stack to see its score and date here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        (if (showAllHistory) finished else finished.take(HISTORY_PREVIEW)).forEachIndexed { index, review ->
            val mode = if (review.attempt.settings.mode == ReviewMode.QUIZ) "Quiz Review" else "Flashcard Review"
            QuizHistoryCard(
                title = review.attempt.stack.title.ifBlank { "Untitled stack" },
                date = "${historyDate(review.finishedAt)}, $mode",
                score = review.score,
                isHighlight = index == 0,
                onClick = { onOpenReview(review.attempt.id) },
            )
        }

        // 5. QUIZ HISTORY: hosted and played quizzes, from this phone and the account
        val quizzes = quizResults.orEmpty()
        SectionHeader(
            title = "Quiz History",
            actionText = if (quizzes.size <= HISTORY_PREVIEW) null else if (showAllQuizzes) "Show Less" else "Show All",
            onActionClick = { showAllQuizzes = !showAllQuizzes },
        )
        if (quizResults?.isEmpty() == true) {
            SleekCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No quizzes yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Host or play a quiz to see it here. Online quizzes show when you are signed in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        (if (showAllQuizzes) quizzes else quizzes.take(HISTORY_PREVIEW)).forEach { quiz ->
            QuizResultCard(quiz)
        }
    }

    if (showAnswerColors) {
        AnswerColorsSheet(
            selected = answerColors,
            onSelect = viewModel::setAnswerColors,
            onDismiss = { showAnswerColors = false },
        )
    }

    if (showAnswerInput) {
        AnswerInputSheet(
            selected = answerInput,
            onSelect = viewModel::setAnswerInput,
            onDismiss = { showAnswerInput = false },
        )
    }

    // Edit Name Dialog
    if (showEditNameDialog) {
        var nameInput by rememberSaveable { mutableStateOf(preferredName ?: "") }
        HiveDialog(
            title = editNameLabel,
            confirmText = "Save",
            onConfirm = {
                DisplayNames.normalize(nameInput)?.let { viewModel.setPreferredName(it) }
                showEditNameDialog = false
            },
            dismissText = "Cancel",
            onDismiss = { showEditNameDialog = false },
        ) {
            Text(
                if (session == null) {
                    "Enter the display name you would like to use in Hive:"
                } else {
                    "This changes only the name you use as a guest. Your account name stays the same."
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

    // Reset Data: this phone only, or this phone and the account
    if (showResetDialog) {
        val phoneOnly = "This leaves the room you joined and forgets it on this phone. Your stacks, reviews, hosted rooms, " +
            "name, sign-in, and settings stay."
        if (session == null) {
            HiveDialog(
                title = "Reset Data",
                message = phoneOnly,
                confirmText = "Reset",
                onConfirm = { viewModel.resetData(account = false) { showResetDialog = false } },
                dismissText = "Cancel",
                onDismiss = { showResetDialog = false },
                danger = true,
            )
        } else {
            ChoiceDialog(
                title = "Reset Data",
                message = "$phoneOnly\n\nWith the account, it also deletes the stacks, reviews, and quiz results that your account " +
                    "keeps for your other phones.",
                choices = listOf(
                    DialogChoice("This phone only", HiveButtonStyle.SECONDARY) {
                        viewModel.resetData(account = false) { showResetDialog = false }
                    },
                    DialogChoice("This phone and the account", HiveButtonStyle.DANGER) {
                        showResetDialog = false
                        confirmAccountReset = true
                    },
                ),
                dismissText = "Cancel",
                onDismiss = { showResetDialog = false },
            )
        }
    }

    if (confirmAccountReset) {
        HiveDialog(
            title = "Delete the account data?",
            confirmText = "Delete",
            onConfirm = { viewModel.resetData(account = true) { confirmAccountReset = false } },
            dismissText = "Cancel",
            onDismiss = { confirmAccountReset = false },
            danger = true,
        ) {
            Text(
                "Your account loses its stacks, reviews, and quiz results, and you cannot undo this. This phone keeps its " +
                    "own, on this phone only. Your other phones keep theirs, and ask again whether to upload them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ErrorText(state.resetError)
        }
    }

    state.signOut?.let { summary ->
        val kept = if (summary.unsyncedStacks == 0) {
            ""
        } else {
            " ${summary.unsyncedStacks} ${if (summary.unsyncedStacks == 1) "stack has" else "stacks have"} changes that your " +
                "account does not have yet, so they stay either way."
        }
        ChoiceDialog(
            title = "Log out",
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

    // 5. SETTINGS BOTTOM SHEET
    if (showSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = SheetShape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "SETTINGS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Theme Toggle Section: [Auto] [Light] [Dark]
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Theme")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HiveSegment(
                            text = "Auto",
                            selected = theme == ThemePreference.SYSTEM,
                            onClick = { viewModel.setTheme(ThemePreference.SYSTEM) },
                            modifier = Modifier.weight(1f),
                        )
                        HiveSegment(
                            text = "Light",
                            selected = theme == ThemePreference.SOFT_LIGHT,
                            onClick = { viewModel.setTheme(ThemePreference.SOFT_LIGHT) },
                            modifier = Modifier.weight(1f),
                        )
                        HiveSegment(
                            text = "Dark",
                            selected = theme == ThemePreference.SOFT_DARK,
                            onClick = { viewModel.setTheme(ThemePreference.SOFT_DARK) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Server Configuration Section
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Server address")
                    var address by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
                    HiveTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = "http://10.0.2.2:8080",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    HiveButton(
                        text = "Test and Save",
                        onClick = { viewModel.saveServer(address) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.serverStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                UpdatesSection()

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Reset Data Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { showResetDialog = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Reset Data",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Reset Data",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Log Out / Sign In Section
                val current = session
                if (current != null) {
                    HiveButton(
                        text = "Log Out",
                        onClick = {
                            viewModel.startSignOut()
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                showSettingsSheet = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = HiveButtonStyle.DANGER,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Caption("Account sign in")
                        SignInOptions(state, viewModel.demoAvailable, viewModel::signInWithDemo)
                    }
                }

                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

/**
 * Quiz History Card:
 * Title     Score (e.g. 20/20)
 * Date (e.g. today)
 */
@Composable
private fun QuizHistoryCard(
    title: String,
    date: String,
    score: String,
    isHighlight: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (isHighlight) scheme.primaryContainer else scheme.surface,
        contentColor = if (isHighlight) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(1.dp, if (isHighlight) Honey else scheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isHighlight) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }

            // Score on right (e.g. "20/20")
            TagPill(text = score, selected = isHighlight)
        }
    }
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
    SleekCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(quiz.title.ifBlank { "Untitled quiz" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${historyDate(quiz.playedAt)}, ${quiz.label}$rank",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TagPill(text = score, selected = false)
        }
    }
}

/** A row that opens a sheet, with its current [value] when it has one. */
@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    SleekCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (value != null) {
                    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Says whether the server can be used. Signed-in users see a lost connection in its own look. */
@Composable
private fun ServerStatusLine(online: OnlineFacts, onRetry: () -> Unit) {
    val state = online.server.state
    val label = when (state) {
        ServerState.ONLINE -> "Server: online"
        ServerState.UNREACHABLE -> "Server: down"
        ServerState.NOT_CONFIGURED -> "Server: not set up"
        ServerState.CHECKING -> "Server: checking"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (state == ServerState.ONLINE) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == ServerState.UNREACHABLE) HiveTextButton("Retry", onClick = onRetry)
        }
        if (online.signedIn) online.serverGate()?.let { GateNote(it) }
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
    HiveTextField(
        value = username,
        onValueChange = { username = it },
        label = "Username",
    )
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
