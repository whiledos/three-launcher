package dev.whiledos.threelauncher.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.whiledos.threelauncher.data.Settings

private val PixelLight = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondaryContainer = Color(0xFFDDE3EA),
    onSecondaryContainer = Color(0xFF151C22),
    surface = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFEDF0F4),
    surfaceContainerHigh = Color(0xFFE6E9EE),
    background = Color(0xFFF8F9FA),
)

private val PixelDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondaryContainer = Color(0xFF3C4043),
    onSecondaryContainer = Color(0xFFE3E3E3),
    surface = Color(0xFF131314),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    background = Color(0xFF131314),
)

@Composable
fun isDarkTheme(settings: Settings): Boolean = when (settings.darkMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun LauncherTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> PixelDark
        else -> PixelLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
