package dev.whiledos.threelauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Поле настройки, которое хранится в SharedPreferences и наблюдается Compose'ом. */
class Pref<T : Any>(
    private val sp: SharedPreferences,
    private val key: String,
    private val default: T,
) : ReadWriteProperty<Any?, T> {

    private val state = mutableStateOf(load())

    @Suppress("UNCHECKED_CAST")
    private fun load(): T = when (default) {
        is Boolean -> sp.getBoolean(key, default) as T
        is Int -> sp.getInt(key, default) as T
        is String -> (sp.getString(key, default) ?: default) as T
        is Set<*> -> (sp.getStringSet(key, default as Set<String>)?.toSet() ?: default) as T
        else -> error("Unsupported pref type")
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    @Suppress("UNCHECKED_CAST")
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        val e = sp.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is String -> e.putString(key, value)
            is Set<*> -> e.putStringSet(key, value as Set<String>)
        }
        e.apply()
    }
}

object IconShapes {
    const val SYSTEM = "system"
    const val CIRCLE = "circle"
    const val SQUIRCLE = "squircle"
    const val ROUNDED = "rounded"
    const val TEARDROP = "teardrop"
    const val SQUARE = "square"
    val all = listOf(SYSTEM, CIRCLE, SQUIRCLE, ROUNDED, TEARDROP, SQUARE)
}

/** Все настройки лаунчера. Читаются из UI напрямую, изменения сразу перерисовывают экран. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
    private fun <T : Any> pref(key: String, default: T) = Pref(sp, key, default)

    // --- Рабочий стол ---
    var columns by pref("columns", 5)
    var rows by pref("rows", 5)
    var dockCount by pref("dock_count", 4)
    var showGlance by pref("show_glance", true)
    var showSearchBar by pref("show_search_bar", true)
    var showLabels by pref("show_labels", true)
    var iconScale by pref("icon_scale", 100)
    var autoAddNewApps by pref("auto_add_new_apps", false)
    var lockLayout by pref("lock_layout", false)
    var allowRotation by pref("allow_rotation", false)

    // --- Иконки / оформление ---
    var iconShape by pref("icon_shape", IconShapes.SYSTEM)
    var themedIcons by pref("themed_icons", false)
    var notificationDots by pref("notification_dots", true)
    var dynamicColor by pref("dynamic_color", true)
    /** 0 — как в системе, 1 — светлая, 2 — тёмная */
    var darkMode by pref("dark_mode", 0)

    // --- Список приложений ---
    var drawerColumns by pref("drawer_columns", 4)
    var drawerSuggestions by pref("drawer_suggestions", true)
    var drawerLabels by pref("drawer_labels", true)
    /** 0 — А–Я, 1 — Я–А, 2 — сначала новые */
    var drawerSort by pref("drawer_sort", 0)
    var drawerOpacity by pref("drawer_opacity", 92)
    var drawerBlur by pref("drawer_blur", true)
    var hiddenApps by pref("hidden_apps", emptySet<String>())

    // --- Жесты ---
    var swipeUpOpensDrawer by pref("swipe_up_drawer", true)
    /** 0 — шторка уведомлений, 1 — поиск по приложениям, 2 — ничего */
    var swipeDownAction by pref("swipe_down_action", 0)
}
