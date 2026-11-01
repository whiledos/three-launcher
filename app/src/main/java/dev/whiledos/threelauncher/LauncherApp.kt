package dev.whiledos.threelauncher

import android.app.Application
import dev.whiledos.threelauncher.data.LauncherModel
import dev.whiledos.threelauncher.data.Settings

class LauncherApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var model: LauncherModel
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        model = LauncherModel(this, settings)
    }
}
