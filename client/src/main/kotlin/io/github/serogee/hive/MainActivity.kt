package io.github.serogee.hive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.ui.HiveApp
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.theme.HiveTheme
import io.github.serogee.hive.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HiveApplication).container
        setContent {
            val theme by container.preferences.theme.collectAsStateWithLifecycle(ThemePreference.SYSTEM)
            CompositionLocalProvider(LocalAppContainer provides container) {
                HiveTheme(theme) {
                    HiveApp()
                }
            }
        }
    }
}
