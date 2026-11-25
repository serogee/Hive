package io.github.serogee.hive

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.ui.HiveApp
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.common.LocalAnswerInput
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.theme.AnswerColorChoice
import io.github.serogee.hive.ui.theme.HiveTheme
import io.github.serogee.hive.ui.theme.ThemePreference
import io.github.serogee.hive.ui.theme.isDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HiveApplication).container
        // Serve rooms that this phone was hosting before the app closed.
        lifecycleScope.launch {
            container.rooms.resumeLocalHosting()
        }
        setContent {
            val theme by container.preferences.theme.collectAsStateWithLifecycle(ThemePreference.SYSTEM)
            val answerColors by container.preferences.answerColors.collectAsStateWithLifecycle(AnswerColorChoice.APP_HONEY)
            val answerInput by container.preferences.answerInput.collectAsStateWithLifecycle(AnswerInput.CONFIRM)
            val dark = theme.isDark()
            // The status bar icons follow the app's theme, which can differ from the phone's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppContainer provides container, LocalAnswerInput provides answerInput) {
                HiveTheme(theme, answerColors) {
                    HiveApp()
                }
            }
        }
    }
}
