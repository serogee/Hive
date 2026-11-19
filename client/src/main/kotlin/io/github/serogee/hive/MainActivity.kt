package io.github.serogee.hive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.serogee.hive.ui.HiveApp
import io.github.serogee.hive.ui.theme.HiveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HiveTheme {
                HiveApp()
            }
        }
    }
}
