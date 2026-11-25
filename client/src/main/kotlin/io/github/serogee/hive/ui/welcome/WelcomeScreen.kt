package io.github.serogee.hive.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.ui.common.BeeLogo
import io.github.serogee.hive.ui.common.GoogleSignIn
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.theme.hiveColors
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    // Leaves only after the choice is saved, because leaving cancels this screen's work.
    val finish: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                container.preferences.completeOnboarding()
                onContinue()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BeeLogo(size = 200.dp, onGraphite = MaterialTheme.hiveColors.isDark)
            Spacer(Modifier.height(56.dp))
            GoogleSignIn(enabled = !saving, onSignedIn = finish)
            Spacer(Modifier.height(16.dp))
            HiveButton(
                text = "Use Hive without an account",
                onClick = finish,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                style = HiveButtonStyle.SECONDARY,
            )
        }
    }
}
