package dev.whiledos.threelauncher.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.whiledos.threelauncher.LauncherApp
import dev.whiledos.threelauncher.ui.LauncherTheme

class SettingsActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LauncherApp
        setContent {
            LauncherTheme(app.settings) {
                SettingsApp(app.model, app.settings, resumeTick, onClose = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
