package io.github.serogee.hive.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.R
import io.github.serogee.hive.data.server.Gate
import io.github.serogee.hive.data.server.GateLook
import io.github.serogee.hive.data.server.OnlineFacts
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.hiveColors
import kotlinx.coroutines.launch

/** Continue with Google. Greyed out while sign-in is not set up. */
@Composable
fun GoogleSignIn(modifier: Modifier = Modifier, enabled: Boolean = true, onSignedIn: () -> Unit) {
    val container = LocalAppContainer.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val idTokens = container.googleIdTokens

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Gated(if (idTokens == null) Gate(GateLook.LOCKED, OnlineFacts.SIGN_IN_DOWN) else null) { available ->
            GoogleButton(
                enabled = available && enabled && !busy && activity != null,
                onClick = {
                    if (idTokens != null && activity != null) {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                if (container.auth.signInWithGoogle { nonce -> idTokens.request(activity, nonce) }) onSignedIn()
                            } catch (e: Exception) {
                                error = e.userMessage()
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            )
        }
        ErrorText(error)
    }
}

/** White with Google's G, in light and dark. */
@Composable
private fun GoogleButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Graphite,
            disabledContainerColor = Color.White,
            disabledContentColor = Graphite,
        ),
        border = BorderStroke(2.dp, MaterialTheme.hiveColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_google_g),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("CONTINUE WITH GOOGLE")
    }
}
