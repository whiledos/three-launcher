package dev.whiledos.threelauncher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import dev.whiledos.threelauncher.data.LauncherModel
import dev.whiledos.threelauncher.settings.SettingsActivity
import dev.whiledos.threelauncher.ui.HostActions
import dev.whiledos.threelauncher.ui.LauncherRoot
import dev.whiledos.threelauncher.ui.LauncherTheme
import dev.whiledos.threelauncher.ui.WidgetController

class MainActivity : ComponentActivity(), HostActions {

    private lateinit var model: LauncherModel
    private lateinit var settings: dev.whiledos.threelauncher.data.Settings
    private lateinit var widgets: WidgetController
    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, _ -> updateWallpaperColors() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LauncherApp
        model = app.model
        settings = app.settings
        widgets = WidgetController(this, model)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        setContent {
            LauncherTheme(settings) {
                LaunchedEffect(settings.allowRotation) {
                    requestedOrientation = if (settings.allowRotation) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                LaunchedEffect(Unit) {
                    snapshotFlow { model.darkText }.collect { dark ->
                        val c = WindowCompat.getInsetsController(window, window.decorView)
                        c.isAppearanceLightStatusBars = dark
                        c.isAppearanceLightNavigationBars = dark
                    }
                }
                LauncherRoot(model, settings, widgets, this@MainActivity)
            }
        }

        try {
            val wm = WallpaperManager.getInstance(this)
            wm.addOnColorsChangedListener(colorsListener, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
        }
        updateWallpaperColors()
    }

    override fun onStart() {
        super.onStart()
        widgets.start()
    }

    override fun onStop() {
        widgets.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateWallpaperColors()
        model.isDefaultHome = try {
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        } catch (e: Exception) {
            true
        }
    }

    override fun onDestroy() {
        try {
            WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            model.homeEvent++
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgets.onActivityResult(requestCode, resultCode, data)
    }

    private fun updateWallpaperColors() {
        try {
            val colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            model.darkText = colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ HostActions

    private fun tryStart(vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
        return false
    }

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun expandNotifications() {
        try {
            val sbs = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbs)
        } catch (e: Exception) {
        }
    }

    override fun openWallpaperPicker() {
        val ok = tryStart(
            Intent().setComponent(
                ComponentName("com.google.android.apps.wallpaper", "com.google.android.apps.wallpaper.picker.CategoryPickerActivity"),
            ),
            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper_style)),
        )
        if (!ok) Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }

    override fun openGoogleSearch() {
        val google = packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
        val ok = tryStart(*listOfNotNull(google, Intent(Intent.ACTION_WEB_SEARCH)).toTypedArray())
        if (!ok) Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }

    override fun openVoiceSearch() {
        val ok = tryStart(Intent(Intent.ACTION_VOICE_COMMAND), Intent("android.speech.action.WEB_SEARCH"))
        if (!ok) openGoogleSearch()
    }

    override fun openLens() {
        val lens = Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).setPackage("com.google.android.googlequicksearchbox")
        if (!tryStart(lens)) openGoogleSearch()
    }

    override fun searchWeb(query: String) {
        val ok = tryStart(
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))),
        )
        if (!ok) Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }

    override fun openCalendar() {
        tryStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
    }

    override fun requestDefaultHome() {
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7100)
                return
            }
        } catch (e: Exception) {
        }
        tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    override fun setBlur(radius: Int) {
        try {
            window.setBackgroundBlurRadius(radius)
        } catch (e: Exception) {
        }
    }
}
