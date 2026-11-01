package dev.whiledos.threelauncher.data

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Цвета для «тематических» иконок (Material You). */
data class IconColors(val bg: Int, val fg: Int)

/**
 * Центральная модель лаунчера: список приложений, раскладка рабочего стола, иконки, запуск.
 * Все мутации — с главного потока.
 */
class LauncherModel(private val context: Context, val settings: Settings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val user = Process.myUserHandle()
    private val layoutFile = File(context.filesDir, "layout.json")
    private val launchPrefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)

    /** Все приложения (с учётом сортировки, без скрытых — см. [drawerApps]). */
    var allApps by mutableStateOf<List<AppInfo>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    val items = mutableStateListOf<HomeItem>()

    /** Отрисованные иконки по ключу компонента. */
    var icons by mutableStateOf<Map<String, ImageBitmap>>(emptyMap()); private set
    var iconColors by mutableStateOf(IconColors(0xFFD3E3FD.toInt(), 0xFF041E49.toInt()))
    private var appsVersion by mutableIntStateOf(0)
    private var launchVersion by mutableIntStateOf(0)

    /** Увеличивается при нажатии кнопки Home. */
    var homeEvent by mutableIntStateOf(0)

    private var nextId = 1L

    var appsByKey by mutableStateOf<Map<String, AppInfo>>(emptyMap()); private set

    /** Подходит ли тёмный текст для обоев (светлые обои). */
    var darkText by mutableStateOf(false)
    var isDefaultHome by mutableStateOf(true)

    init {
        loadLayoutFile()
        reloadApps()
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) = reloadApps()
            override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) = reloadApps()
            override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) = reloadApps()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) = reloadApps()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) = reloadApps()
        }, Handler(Looper.getMainLooper()))

        // Перерисовка иконок при смене формы / темы / списка приложений.
        scope.launch {
            snapshotFlow { listOf(appsVersion, settings.iconShape, settings.themedIcons, iconColors) }
                .collectLatest {
                    val apps = allApps
                    val shape = settings.iconShape
                    val themed = settings.themedIcons
                    val colors = iconColors
                    val map = withContext(Dispatchers.Default) {
                        apps.associate { it.key to IconRenderer.render(it.icon, shape, themed, colors.bg, colors.fg) }
                    }
                    icons = map
                }
        }
        // Перераскладка при смене размера сетки.
        scope.launch {
            snapshotFlow { listOf(settings.columns, settings.rows, settings.dockCount, settings.showGlance) }
                .collectLatest { if (loaded) { reflow(); save() } }
        }
    }

    // ------------------------------------------------------------------ приложения

    fun reloadApps() {
        scope.launch {
            val list = withContext(Dispatchers.Default) {
                try {
                    launcherApps.getActivityList(null, user)
                        .filter { it.componentName.packageName != context.packageName }
                        .map { AppInfo(it.componentName, it.label.toString(), it.getIcon(0), it.firstInstallTime) }
                        .sortedBy { it.label.lowercase() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val previous = allApps.map { it.key }.toSet()
            allApps = list
            appsByKey = list.associateBy { it.key }
            appsVersion++
            if (list.isEmpty()) return@launch
            if (!loaded) {
                loaded = true
                if (items.isEmpty() && !layoutFile.exists()) buildDefaultLayout()
            } else if (settings.autoAddNewApps) {
                val onHome = homeComponents()
                list.filter { it.key !in previous && it.component !in onHome }.forEach { placeNewApp(it.component) }
            }
            pruneMissing()
            reflow()
            save()
        }
    }

    private fun homeComponents(): Set<ComponentName> = items.flatMap {
        when (it) {
            is HomeItem.App -> listOf(it.component)
            is HomeItem.Folder -> it.apps
            else -> emptyList()
        }
    }.toSet()

    private fun pruneMissing() {
        val keys = allApps.map { it.component }.toSet()
        val toRemove = ArrayList<HomeItem>()
        val replace = ArrayList<Pair<HomeItem, HomeItem?>>()
        items.forEach {
            when (it) {
                is HomeItem.App -> if (it.component !in keys) toRemove += it
                is HomeItem.Folder -> {
                    val left = it.apps.filter { c -> c in keys }
                    if (left.size != it.apps.size) {
                        when (left.size) {
                            0 -> toRemove += it
                            1 -> replace += it to HomeItem.App(it.id, left[0], it.page, it.x, it.y)
                            else -> replace += it to it.copy(apps = left)
                        }
                    }
                }
                else -> {}
            }
        }
        toRemove.forEach { items.remove(it) }
        replace.forEach { (old, new) -> val i = items.indexOf(old); if (i >= 0 && new != null) items[i] = new }
    }

    /** Приложения для списка (без скрытых), с учётом настроек сортировки. */
    fun drawerApps(): List<AppInfo> {
        val hidden = settings.hiddenApps
        val base = allApps.filter { it.key !in hidden }
        return when (settings.drawerSort) {
            1 -> base.sortedByDescending { it.label.lowercase() }
            2 -> base.sortedByDescending { it.installTime }
            else -> base
        }
    }

    /** Самые часто запускаемые приложения. */
    fun suggestedApps(count: Int): List<AppInfo> {
        launchVersion // подписка
        val hidden = settings.hiddenApps
        return allApps.asSequence()
            .filter { it.key !in hidden }
            .map { it to launchPrefs.getInt(it.key, 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }
            .toList()
    }

    fun launch(component: ComponentName, sourceBounds: Rect? = null) {
        try {
            launcherApps.startMainActivity(component, user, sourceBounds, ActivityOptions.makeBasic().toBundle())
            launchPrefs.edit().putInt(component.flattenToString(), launchPrefs.getInt(component.flattenToString(), 0) + 1).apply()
            launchVersion++
        } catch (e: Exception) {
            reloadApps()
        }
    }

    fun openAppInfo(component: ComponentName) {
        try {
            launcherApps.startAppDetailsActivity(component, user, null, null)
        } catch (e: Exception) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${component.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun uninstall(component: ComponentName) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${component.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
        }
    }

    fun shortcutsFor(component: ComponentName): List<ShortcutInfo> {
        return try {
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(component.packageName)
                .setActivity(component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            (launcherApps.getShortcuts(q, user) ?: emptyList()).filter { it.isEnabled }.take(4)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun shortcutIcon(info: ShortcutInfo) = try { launcherApps.getShortcutIconDrawable(info, 0) } catch (e: Exception) { null }

    fun startShortcut(info: ShortcutInfo) {
        try {
            launcherApps.startShortcut(info, null, null)
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ раскладка

    private fun newId() = nextId++

    fun reservedRows(page: Int) = if (page == 0 && settings.showGlance) 1 else 0

    private fun inBounds(page: Int, x: Int, y: Int, sx: Int, sy: Int): Boolean {
        if (x < 0 || y < 0 || sx < 1 || sy < 1) return false
        return if (page == DOCK) {
            y == 0 && sy == 1 && x + sx <= settings.dockCount
        } else {
            x + sx <= settings.columns && y + sy <= settings.rows && y >= reservedRows(page)
        }
    }

    fun isAreaFree(page: Int, x: Int, y: Int, sx: Int, sy: Int, excludeId: Long = -1, pool: List<HomeItem> = items): Boolean {
        if (!inBounds(page, x, y, sx, sy)) return false
        return pool.none {
            it.id != excludeId && it.page == page &&
                x < it.x + it.spanX && x + sx > it.x && y < it.y + it.spanY && y + sy > it.y
        }
    }

    fun itemAt(page: Int, x: Int, y: Int, excludeId: Long = -1): HomeItem? =
        items.firstOrNull {
            it.id != excludeId && it.page == page &&
                x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY
        }

    fun pageCount(): Int = ((items.maxOfOrNull { it.page } ?: 0).coerceAtLeast(0)) + 1

    private fun findFree(page: Int, sx: Int, sy: Int, excludeId: Long = -1, pool: List<HomeItem> = items): Pair<Int, Int>? {
        val rowsLimit = if (page == DOCK) 1 else settings.rows
        val colsLimit = if (page == DOCK) settings.dockCount else settings.columns
        for (y in 0 until rowsLimit) for (x in 0 until colsLimit) {
            if (isAreaFree(page, x, y, sx, sy, excludeId, pool)) return x to y
        }
        return null
    }

    private fun findSpotAnyPage(sx: Int, sy: Int, startPage: Int = 0, excludeId: Long = -1, pool: List<HomeItem> = items): Triple<Int, Int, Int> {
        var page = startPage
        while (true) {
            findFree(page, sx, sy, excludeId, pool)?.let { return Triple(page, it.first, it.second) }
            page++
            if (page > 50) return Triple(page, 0, reservedRows(page))
        }
    }

    fun placeNewApp(component: ComponentName) {
        val (p, x, y) = findSpotAnyPage(1, 1)
        items += HomeItem.App(newId(), component, p, x, y)
        save()
    }

    /** Ближайшая свободная ячейка к (x, y) на странице; null — нет места. */
    fun nearestFree(page: Int, x: Int, y: Int, sx: Int, sy: Int, excludeId: Long): Pair<Int, Int>? {
        val rowsLimit = if (page == DOCK) 1 else settings.rows
        val colsLimit = if (page == DOCK) settings.dockCount else settings.columns
        var best: Pair<Int, Int>? = null
        var bestD = Int.MAX_VALUE
        for (yy in 0 until rowsLimit) for (xx in 0 until colsLimit) {
            if (isAreaFree(page, xx, yy, sx, sy, excludeId)) {
                val d = (xx - x) * (xx - x) + (yy - y) * (yy - y)
                if (d < bestD) { bestD = d; best = xx to yy }
            }
        }
        return best
    }

    fun moveItem(id: Long, page: Int, x: Int, y: Int) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = items[i].moved(page, x, y)
        compactPages()
        save()
    }

    fun removeItem(id: Long) {
        items.removeAll { it.id == id }
        compactPages()
        save()
    }

    fun addApp(component: ComponentName, page: Int, x: Int, y: Int) {
        items += HomeItem.App(newId(), component, page, x, y)
        compactPages()
        save()
    }

    /** Складывает [dragged] в папку/приложение, находящееся в ячейке. Возвращает true, если удалось. */
    fun mergeInto(target: HomeItem, component: ComponentName?, draggedId: Long?): Boolean {
        val dragged = draggedId?.let { id -> items.firstOrNull { it.id == id } }
        val draggedApps: List<ComponentName> = when {
            dragged is HomeItem.App -> listOf(dragged.component)
            dragged is HomeItem.Folder -> dragged.apps
            component != null -> listOf(component)
            else -> return false
        }
        if (dragged is HomeItem.Widget) return false
        val ti = items.indexOfFirst { it.id == target.id }
        if (ti < 0) return false
        when (target) {
            is HomeItem.Folder -> items[ti] = target.copy(apps = (target.apps + draggedApps).distinct())
            is HomeItem.App -> items[ti] = HomeItem.Folder(newId(), "", (listOf(target.component) + draggedApps).distinct(), target.page, target.x, target.y)
            else -> return false
        }
        if (dragged != null) items.removeAll { it.id == dragged.id }
        compactPages()
        save()
        return true
    }

    fun renameFolder(id: Long, title: String) {
        val i = items.indexOfFirst { it.id == id }
        val f = items.getOrNull(i) as? HomeItem.Folder ?: return
        items[i] = f.copy(title = title)
        save()
    }

    fun removeFromFolder(folderId: Long, component: ComponentName) {
        val i = items.indexOfFirst { it.id == folderId }
        val f = items.getOrNull(i) as? HomeItem.Folder ?: return
        val left = f.apps - component
        when (left.size) {
            0 -> items.removeAt(i)
            1 -> items[i] = HomeItem.App(f.id, left[0], f.page, f.x, f.y)
            else -> items[i] = f.copy(apps = left)
        }
        // Вынести приложение на рабочий стол.
        val (p, x, y) = findSpotAnyPage(1, 1, startPage = f.page.coerceAtLeast(0))
        items += HomeItem.App(newId(), component, p, x, y)
        compactPages()
        save()
    }

    fun addWidget(appWidgetId: Int, provider: ComponentName, sx: Int, sy: Int): Boolean {
        val w = sx.coerceIn(1, settings.columns)
        val h = sy.coerceIn(1, settings.rows - 1)
        val (p, x, y) = findSpotAnyPage(w, h)
        items += HomeItem.Widget(newId(), appWidgetId, provider, p, x, y, w, h)
        save()
        return true
    }

    fun resizeWidget(id: Long, sx: Int, sy: Int) {
        val i = items.indexOfFirst { it.id == id }
        val w = items.getOrNull(i) as? HomeItem.Widget ?: return
        val nw = sx.coerceIn(1, settings.columns)
        val nh = sy.coerceIn(1, settings.rows)
        if (isAreaFree(w.page, w.x, w.y, nw, nh, w.id)) {
            items[i] = w.copy(spanX = nw, spanY = nh)
            save()
        }
    }

    /** Убирает пустые страницы (кроме первой), сохраняя порядок. */
    private fun compactPages() {
        val used = (items.filter { it.page >= 0 }.map { it.page } + 0).distinct().sorted()
        if (used.withIndex().all { (i, p) -> i == p }) return
        val map = used.withIndex().associate { (i, p) -> p to i }
        for (i in items.indices) {
            val it = items[i]
            if (it.page >= 0) items[i] = it.moved(map[it.page] ?: 0, it.x, it.y)
        }
    }

    /** Приводит раскладку в соответствие с текущим размером сетки. */
    fun reflow() {
        val sorted = items.sortedWith(compareBy({ if (it.page == DOCK) -1 else it.page }, { it.y }, { it.x }))
        val accepted = ArrayList<HomeItem>()
        for (item in sorted) {
            if (isAreaFree(item.page, item.x, item.y, item.spanX, item.spanY, item.id, accepted)) {
                accepted += item
                continue
            }
            var fixed = item
            val sx = item.spanX.coerceAtMost(settings.columns)
            val sy = item.spanY.coerceAtMost(settings.rows - 1)
            if (item is HomeItem.Widget && (sx != item.spanX || sy != item.spanY)) fixed = item.copy(spanX = sx, spanY = sy)
            val start = item.page.coerceAtLeast(0)
            val (p, x, y) = findSpotAnyPage(fixed.spanX, fixed.spanY, start, fixed.id, accepted)
            accepted += fixed.moved(p, x, y)
        }
        // Сохраняем исходный порядок элементов.
        val byId = accepted.associateBy { it.id }
        val result = items.mapNotNull { byId[it.id] }
        if (result != items.toList()) {
            items.clear()
            items.addAll(result)
        }
        compactPages()
    }

    // ------------------------------------------------------------------ раскладка по умолчанию

    private fun appForIntent(intent: Intent): AppInfo? {
        val pm = context.packageManager
        val ri = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        if (pkg == "android") return null
        return allApps.firstOrNull { it.component.packageName == pkg }
    }

    private fun buildDefaultLayout() {
        val used = HashSet<ComponentName>()
        fun pick(app: AppInfo?): ComponentName? = app?.component?.takeIf { used.add(it) }

        // Док: телефон, сообщения, браузер, камера
        val dockApps = listOfNotNull(
            pick(appForIntent(Intent(Intent.ACTION_DIAL))),
            pick(appForIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))),
            pick(appForIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))),
            pick(appForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE))),
        )
        dockApps.take(settings.dockCount).forEachIndexed { i, c -> items += HomeItem.App(newId(), c, DOCK, i, 0) }

        // Главная страница: популярные приложения
        val byPackage = listOf(
            "com.android.vending", "com.google.android.gm", "com.google.android.apps.photos",
            "com.google.android.apps.maps", "com.google.android.youtube", "com.google.android.calendar",
            "com.google.android.deskclock", "com.google.android.apps.nbu.files", "com.google.android.contacts",
        )
        val home = ArrayList<ComponentName>()
        byPackage.forEach { pkg -> pick(allApps.firstOrNull { it.component.packageName == pkg })?.let { home += it } }
        listOf(
            Intent(AndroidSettings.ACTION_SETTINGS),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL),
        ).forEach { i -> pick(appForIntent(i))?.let { home += it } }

        val startRow = reservedRows(0)
        home.take((settings.rows - startRow - 1).coerceAtLeast(1) * settings.columns).forEachIndexed { i, c ->
            items += HomeItem.App(newId(), c, 0, i % settings.columns, startRow + i / settings.columns)
        }
    }

    // ------------------------------------------------------------------ хранение

    private fun loadLayoutFile() {
        try {
            if (!layoutFile.exists()) return
            val arr = JSONArray(layoutFile.readText())
            for (i in 0 until arr.length()) {
                HomeItem.fromJson(arr.getJSONObject(i))?.let { items += it }
            }
            nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
        } catch (e: Exception) {
            items.clear()
        }
    }

    fun save() {
        val snapshot = items.toList()
        scope.launch(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                layoutFile.writeText(arr.toString())
            } catch (e: Exception) {
            }
        }
    }

    fun resetLayout() {
        items.clear()
        buildDefaultLayout()
        save()
    }
}
