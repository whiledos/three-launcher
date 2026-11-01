package dev.whiledos.threelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.whiledos.threelauncher.R
import dev.whiledos.threelauncher.data.DOCK
import dev.whiledos.threelauncher.data.HomeItem
import dev.whiledos.threelauncher.data.IconColors
import dev.whiledos.threelauncher.data.LauncherModel
import dev.whiledos.threelauncher.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Действия, которые выполняет Activity. */
interface HostActions {
    fun openSettings()
    fun expandNotifications()
    fun openWallpaperPicker()
    fun openGoogleSearch()
    fun openVoiceSearch()
    fun openLens()
    fun searchWeb(query: String)
    fun openCalendar()
    fun requestDefaultHome()
    fun setBlur(radius: Int)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherRoot(
    model: LauncherModel,
    settings: Settings,
    widgets: WidgetController,
    host: HostActions,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { DragController() }
    val geo = remember { HomeGeometry() }
    val drawer = remember { DrawerState() }

    var menu by remember { mutableStateOf<MenuState?>(null) }
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var resizeItem by remember { mutableStateOf<HomeItem.Widget?>(null) }
    var searchRequest by remember { mutableIntStateOf(0) }
    var bannerDismissed by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var downAccum by remember { mutableFloatStateOf(0f) }

    val pageCount = model.pageCount() + if (drag.moved) 1 else 0
    val pager = rememberPagerState { pageCount }

    drawer.range = (rootSize.height * 0.7f).coerceAtLeast(1f)
    val drawerVisible by remember { derivedStateOf { drawer.isOpen } }

    // Цвета «тематических» иконок берём из динамической палитры.
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(scheme.primaryContainer, scheme.onPrimaryContainer) {
        model.iconColors = IconColors(scheme.primaryContainer.toArgb(), scheme.onPrimaryContainer.toArgb())
    }

    // Размытие обоев при открытии списка приложений.
    LaunchedEffect(settings.drawerBlur) {
        snapshotFlow { if (settings.drawerBlur) (drawer.progress * 12).toInt() * 5 else 0 }.collect { host.setBlur(it) }
    }

    // Кнопка Home: закрыть всё и вернуться на первую страницу.
    LaunchedEffect(model.homeEvent) {
        if (model.homeEvent > 0) {
            menu = null; openFolderId = null; showPicker = false; resizeItem = null
            drawer.close(scope)
            pager.animateScrollToPage(0)
        }
    }

    // Начало реального перетаскивания: прячем меню, закрываем список приложений.
    LaunchedEffect(drag.moved) {
        if (drag.moved) {
            menu = null
            if (drag.payload is DragPayload.NewApp) drawer.close(scope)
        }
    }

    // Прокрутка страниц у края экрана во время перетаскивания.
    LaunchedEffect(drag.moved) {
        if (!drag.moved) return@LaunchedEffect
        var dwell = 0
        val edge = with(density) { 40.dp.toPx() }
        while (isActive && drag.active) {
            delay(50)
            val x = drag.pos.x
            val dir = when {
                x < edge -> -1
                x > rootSize.width - edge -> 1
                else -> 0
            }
            if (dir == 0) { dwell = 0; continue }
            dwell += 50
            if (dwell >= 500) {
                dwell = 0
                val target = pager.currentPage + dir
                if (target in 0 until pager.pageCount) pager.animateScrollToPage(target)
            }
        }
    }

    fun removeHomeItem(item: HomeItem) {
        if (item is HomeItem.Widget) widgets.release(item.appWidgetId)
        model.removeItem(item.id)
    }

    fun handleDrop(payload: DragPayload, pos: Offset) {
        val item = (payload as? DragPayload.Existing)?.item
        val comp = when {
            payload is DragPayload.NewApp -> payload.component
            item is HomeItem.App -> item.component
            else -> null
        }
        val zone = geo.targets.entries.firstOrNull { it.value.contains(pos) }?.key
        geo.targets.clear()
        when (zone) {
            "remove" -> { if (item != null) removeHomeItem(item); return }
            "info" -> { comp?.let { model.openAppInfo(it) }; return }
            "uninstall" -> { comp?.let { model.uninstall(it) }; return }
        }
        val sx = item?.spanX ?: 1
        val sy = item?.spanY ?: 1
        val t = geo.targetFor(pos, sx, sy, pager.currentPage) ?: return
        if (item is HomeItem.Widget && t.page == DOCK) return
        val excl = item?.id ?: -1L
        val occupant = model.itemAt(t.page, t.x, t.y, excl)
        if (sx == 1 && sy == 1 && item !is HomeItem.Widget && occupant != null &&
            (occupant is HomeItem.App || occupant is HomeItem.Folder)
        ) {
            if (model.mergeInto(occupant, comp, item?.id)) return
        }
        val spot = if (model.isAreaFree(t.page, t.x, t.y, sx, sy, excl)) t.x to t.y
        else model.nearestFree(t.page, t.x, t.y, sx, sy, excl)
        if (spot == null) return
        if (item != null) model.moveItem(item.id, t.page, spot.first, spot.second)
        else if (comp != null) model.addApp(comp, t.page, spot.first, spot.second)
    }
    SideEffect { drag.onDrop = { p, pos -> handleDrop(p, pos) } }

    val anyOverlay = menu != null || openFolderId != null || showPicker || resizeItem != null
    BackHandler(enabled = anyOverlay || drawerVisible) {
        when {
            menu != null -> menu = null
            resizeItem != null -> resizeItem = null
            showPicker -> showPicker = false
            openFolderId != null -> openFolderId = null
            drawerVisible -> drawer.close(scope)
        }
    }

    val draggableState = rememberDraggableState { dy ->
        if (settings.swipeUpOpensDrawer && (dy < 0f || drawer.progress > 0f)) drawer.dragBy(dy)
        else if (dy > 0f) downAccum += dy
    }
    val swipeDownThreshold = with(density) { 64.dp.toPx() }

    Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        // --- Рабочий стол ---
        HomeLayer(
            model = model, settings = settings, pager = pager, drag = drag, geo = geo, widgets = widgets, host = host,
            onAppMenu = { menu = it },
            onHomeMenu = { if (!drag.active) menu = MenuState.Home(it) },
            onWidgetMenu = { menu = it },
            onOpenFolder = { openFolderId = it },
            modifier = Modifier
                .graphicsLayer {
                    val p = drawer.progress
                    alpha = 1f - p
                    scaleX = 1f - 0.06f * p
                    scaleY = 1f - 0.06f * p
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    enabled = !drag.active && !anyOverlay,
                    onDragStopped = { v ->
                        if (drawer.progress > 0f) {
                            drawer.settle(scope, v)
                        } else if (downAccum > swipeDownThreshold) {
                            when (settings.swipeDownAction) {
                                0 -> host.expandNotifications()
                                1 -> { drawer.open(scope); searchRequest++ }
                            }
                        }
                        downAccum = 0f
                    },
                ),
        )

        // --- Список приложений ---
        if (drawerVisible || drag.active) {
            AppDrawer(
                model = model, settings = settings, state = drawer, drag = drag, host = host, scope = scope,
                focusRequest = searchRequest,
                onAppMenu = { menu = it },
            )
        }

        // --- Баннер «сделать основным» ---
        if (!model.isDefaultHome && !bannerDismissed && !drawerVisible) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.set_default_text), color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { host.requestDefaultHome() }) { Text(stringResource(R.string.set_default_button)) }
                    TextButton(onClick = { bannerDismissed = true }) { Text(stringResource(R.string.later)) }
                }
            }
        }

        // --- Папка ---
        val folder = openFolderId?.let { id -> model.items.firstOrNull { it.id == id } as? HomeItem.Folder }
        if (openFolderId != null && folder == null) openFolderId = null
        if (folder != null) {
            FolderPopup(folder, model, onClose = { openFolderId = null }, onAppMenu = { menu = it })
        }

        // --- Виджеты ---
        if (showPicker) {
            WidgetPicker(widgets, onPick = { showPicker = false; widgets.startAdd(it) }, onClose = { showPicker = false })
        }

        // --- Перетаскивание ---
        DragLayer(drag, model, geo)

        // --- Меню ---
        when (val m = menu) {
            is MenuState.App -> AppMenu(
                m, model, onDismiss = { menu = null },
                onRemove = when {
                    m.itemId != null -> ({ model.items.firstOrNull { it.id == m.itemId }?.let { removeHomeItem(it) } })
                    m.folderId != null -> ({ model.removeFromFolder(m.folderId, m.component) })
                    else -> null
                },
            )
            is MenuState.Home -> HomeMenu(
                m, onDismiss = { menu = null },
                onWallpaper = { host.openWallpaperPicker() },
                onWidgets = { showPicker = true },
                onSettings = { host.openSettings() },
            )
            is MenuState.Widget -> WidgetMenu(
                m, onDismiss = { menu = null },
                onResize = { resizeItem = m.item },
                onRemove = { removeHomeItem(m.item) },
            )
            null -> {}
        }

        resizeItem?.let { w ->
            ResizeDialog(
                w, settings.columns, settings.rows,
                onApply = { sx, sy -> model.resizeWidget(w.id, sx, sy) },
                onDismiss = { resizeItem = null },
            )
        }
    }
}
