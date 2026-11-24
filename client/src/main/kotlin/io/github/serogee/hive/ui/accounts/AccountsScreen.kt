package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.theme.DarkSlate
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.ElectricBlueLight
import io.github.serogee.hive.ui.theme.MidnightNavy
import io.github.serogee.hive.ui.theme.MidnightNavyLight
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer
import io.github.serogee.hive.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountsUiState(
    val busy: Boolean = false,
    val signInError: String? = null,
    val serverStatus: String? = null,
    /** Set after a sign-in requested by another screen succeeds. */
    val signedIn: Boolean = false,
)

class AccountsViewModel(private val container: AppContainer) : ViewModel() {
    val session: StateFlow<AuthSession?> = container.auth.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val serverUrl = container.preferences.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val theme = container.preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemePreference.SYSTEM)
    val preferredName = container.preferences.preferredName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val showsDemoHint = container.auth.showsDemoHint

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    fun signIn(username: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, signInError = null) }
        try {
            container.auth.signIn(username, password)
            _state.update { it.copy(busy = false, signedIn = true) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, signInError = e.userMessage()) }
        }
    }

    fun signOut() = viewModelScope.launch { container.auth.signOut() }

    fun setTheme(theme: ThemePreference) = viewModelScope.launch { container.preferences.setTheme(theme) }

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
                container.auth.signOut()
                container.preferences.setServerUrl(url)
            }
            _state.update { it.copy(serverStatus = "Connected to $url (API v${health.apiVersion})") }
        } catch (e: Exception) {
            _state.update { it.copy(serverStatus = "No Hive server at $url. ${e.userMessage()}") }
        }
    }

    fun resetData() = viewModelScope.launch {
        container.preferences.setJoinedRoom(null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onSignedIn: () -> Unit = {},
    onOpenStack: (StackId) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountsViewModel(container) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferredName by viewModel.preferredName.collectAsStateWithLifecycle()
    val stacks by container.stacks.stacks.collectAsStateWithLifecycle(emptyList())

    val displayName = session?.account?.displayName
        ?: preferredName?.takeIf { it.isNotBlank() }
        ?: "Guest"

    var showEditNameDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 1. PROFILE HERO SECTION (Matching Figma Placement)
        // [Avatar Circle]
        // Name
        // Edit Name
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
                    .padding(22.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Large Circle Avatar
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(ElectricBlue, ElectricBlueLight)
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = displayName.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Spacer(Modifier.height(4.dp))

                        // Edit Name Clickable Link
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showEditNameDialog = true }
                                .padding(vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit Name",
                                tint = ElectricBlueLight,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Edit Name",
                                color = ElectricBlueLight,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // 2. STAT CHIPS ROW: [day streak] [quiz taken] [Average score]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricStatChip(
                value = "1",
                label = "day\nstreak",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "${stacks.size}",
                label = "quiz\ntaken",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = if (stacks.isNotEmpty()) "85%" else "--%",
                label = "Average\nscore",
                modifier = Modifier.weight(1f),
            )
        }

        // 3. QUIZ HISTORY SECTION (Matching Figma Cards with Date + Score)
        SectionHeader(
            title = "Quiz History",
        )

        if (stacks.isEmpty()) {
            SleekCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No quiz history yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Take a quiz or practice question stacks to record your scores and progress.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                }
            }
        } else {
            // Render quiz history cards in warm gold/amber or sleek cards like in Figma:
            // "Contemporary"  20/20
            // today
            val sampleScores = listOf("20/20", "05/20", "18/20", "12/15")
            stacks.forEachIndexed { index, stack ->
                val score = sampleScores.getOrElse(index) {
                    "${stack.questionCount}/${stack.questionCount}"
                }
                QuizHistoryCard(
                    title = stack.title.ifBlank { "Quiz ${index + 1}" },
                    date = if (index == 0) "today" else "yesterday",
                    score = score,
                    isHighlight = index == 0,
                    onClick = { onOpenStack(stack.id) },
                )
            }
        }

        // 4. SETTINGS SECTION (Opens the popup / bottom sheet as designed in Figma)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showSettingsSheet = true },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = DarkSlate,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = ElectricBlue,
                    )
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MutedSlate,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // Edit Name Dialog
    if (showEditNameDialog) {
        var nameInput by rememberSaveable { mutableStateOf(preferredName ?: "") }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Edit Your Name", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the display name you would like to use in Hive:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(35) },
                        placeholder = { Text("Your Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nameInput.isNotBlank()) {
                            viewModel.setPreferredName(nameInput.trim())
                        }
                        showEditNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel", color = MutedSlate)
                }
            },
        )
    }

    // Reset Data Confirmation Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Data", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to reset your local sessions and cache? Your question stacks will remain safe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlate,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetData()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Reset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = MutedSlate)
                }
            },
        )
    }

    // 5. SETTINGS BOTTOM SHEET POPUP ("pop up shit only" from Figma note)
    if (showSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
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
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Theme Toggle Section: [Theme] -> [Auto] [Light] [Dark] (matching Figma)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "THEME",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate,
                        letterSpacing = 1.2.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemePill(
                            label = "Auto",
                            selected = theme == ThemePreference.SYSTEM,
                            onClick = { viewModel.setTheme(ThemePreference.SYSTEM) },
                            modifier = Modifier.weight(1f),
                        )
                        ThemePill(
                            label = "Light",
                            selected = theme == ThemePreference.SOFT_LIGHT,
                            onClick = { viewModel.setTheme(ThemePreference.SOFT_LIGHT) },
                            modifier = Modifier.weight(1f),
                        )
                        ThemePill(
                            label = "Dark",
                            selected = theme == ThemePreference.SOFT_DARK,
                            onClick = { viewModel.setTheme(ThemePreference.SOFT_DARK) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Server Configuration Section
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "SERVER ADDRESS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate,
                        letterSpacing = 1.2.sp,
                    )
                    var address by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = { Text("http://10.0.2.2:8080") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.saveServer(address) },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlue,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Test and Save", fontWeight = FontWeight.Bold)
                    }
                    state.serverStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MutedSlate)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Reset Data Row (Figma: "Reset Data")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
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
                            tint = MutedSlate,
                        )
                        Text(
                            text = "Reset Data",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MutedSlate,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Log Out / Sign In Section (Figma: "Log Out")
                val current = session
                if (current != null) {
                    Button(
                        onClick = {
                            viewModel.signOut()
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                showSettingsSheet = false
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text("Log Out", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "ACCOUNT SIGN IN",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MutedSlate,
                            letterSpacing = 1.2.sp,
                        )
                        SignInForm(
                            busy = state.busy,
                            showDemoHint = viewModel.showsDemoHint,
                            onSignIn = viewModel::signIn,
                        )
                        ErrorText(state.signInError)
                    }
                }

                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

/**
 * Quiz History Card matching Figma:
 * Title (e.g. Contemporary)     Score (e.g. 20/20)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlight) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (isHighlight) Color(0xFFFCD34D) else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isHighlight) Color(0xFF78350F) else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isHighlight) Color(0xFF92400E) else MutedSlate,
                )
            }

            // Score on right (e.g. "20/20")
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isHighlight) Color(0xFFF59E0B) else SoftBlueContainer,
            ) {
                Text(
                    text = score,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = if (isHighlight) Color.White else ElectricBlue,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Segmented Pill for Theme (Auto / Light / Dark) matching Figma
 */
@Composable
private fun ThemePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) ElectricBlue else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SignInForm(busy: Boolean, showDemoHint: Boolean, onSignIn: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    if (showDemoHint) {
        Text("Demo server accounts: alex or sam, password hive-demo", style = MaterialTheme.typography.bodySmall, color = MutedSlate)
    }
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("Username") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSignIn(username, password) },
        enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = ElectricBlue,
            contentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
    ) {
        Text("Sign in", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
