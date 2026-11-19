package io.github.serogee.hive.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.remote.ApiException
import kotlin.coroutines.cancellation.CancellationException

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

/** Text for an error shown inline on a screen. */
fun Throwable.userMessage(): String = when (this) {
    is CancellationException -> throw this
    is ApiException -> error.message
    else -> "Can't reach the Hive server. Check your Wi-Fi and the server address in Accounts."
}

fun RoomMode.label() = when (this) {
    RoomMode.DISCUSSION -> "Discussion"
    RoomMode.ASYNCHRONOUS -> "Asynchronous"
}

fun AdmissionPolicy.label() = when (this) {
    AdmissionPolicy.EVERYONE -> "Everyone"
    AdmissionPolicy.SIGNED_IN_ONLY -> "Signed-in Players only"
    AdmissionPolicy.GUESTS_ONLY -> "Guests only"
}

fun RoomStatus.label() = when (this) {
    RoomStatus.LOBBY -> "In lobby"
    RoomStatus.LIVE -> "Live"
    RoomStatus.ENDED -> "Ended"
}

/** A scrolling page with standard padding and spacing. */
@Composable
fun Page(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}
