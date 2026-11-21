package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.core.api.AuthSession
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.userMessage
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MidnightNavy
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
}

@Composable
fun AccountsScreen(onSignedIn: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountsViewModel(container) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }

    Page(
        title = "Accounts",
        subtitle = "Settings, profile & server connectivity",
    ) {
        val current = session
        if (current != null) {
            HeroNavyCard(
                badgeText = "AUTHENTICATED",
                title = current.account.displayName,
                subtitle = "User ID: ${current.account.id.value}",
                buttonText = "SIGN OUT",
                onButtonClick = { viewModel.signOut() },
            )
        } else {
            SectionHeader(title = "Sign In")
            SleekCard {
                SignInForm(
                    busy = state.busy,
                    showDemoHint = viewModel.showsDemoHint,
                    onSignIn = viewModel::signIn,
                )
                ErrorText(state.signInError)
            }
        }

        SectionHeader(title = "Server Configuration")
        SleekCard {
            var address by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Server address") },
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
            ) {
                Text("Test and Save", fontWeight = FontWeight.Bold)
            }
            state.serverStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MutedSlate)
            }
        }

        SectionHeader(title = "Appearance")
        SleekCard {
            ThemePreference.entries.forEach { option ->
                val isSelected = theme == option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .selectable(selected = isSelected) { viewModel.setTheme(option) },
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when (option) {
                            ThemePreference.SYSTEM -> "System default"
                            ThemePreference.SOFT_LIGHT -> "Clean light"
                            ThemePreference.SOFT_DARK -> "Midnight dark"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
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
