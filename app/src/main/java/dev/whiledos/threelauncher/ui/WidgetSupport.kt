package dev.whiledos.threelauncher.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import dev.whiledos.threelauncher.R
import dev.whiledos.threelauncher.data.HomeItem
import dev.whiledos.threelauncher.data.LauncherModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

interface WidgetTouchListener {
    fun onLongPress(rawX: Float, rawY: Float)
    fun onDragMove(rawX: Float, rawY: Float)
    fun onDragEnd(cancelled: Boolean)
}

/** Хост-view виджета: ловит долгое нажатие и пробрасывает перетаскивание в лаунчер. */
class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var touchListener: WidgetTouchListener? = null
    private var longPressed = false
    private var downX = 0f
    private var downY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastX = 0f
    private var lastY = 0f
    private val longRun = Runnable {
        longPressed = true
        touchListener?.onLongPress(lastX, lastY)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.rawX; downY = ev.rawY; lastX = ev.rawX; lastY = ev.rawY
                handler.postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = ev.rawX; lastY = ev.rawY
                if (!longPressed && (kotlin.math.abs(ev.rawX - downX) > slop || kotlin.math.abs(ev.rawY - downY) > slop)) {
                    handler.removeCallbacks(longRun)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRun)
        }
        return longPressed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> touchListener?.onDragMove(ev.rawX, ev.rawY)
                MotionEvent.ACTION_UP -> { longPressed = false; touchListener?.onDragEnd(false) }
                MotionEvent.ACTION_CANCEL -> { longPressed = false; touchListener?.onDragEnd(true) }
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longRun)
        super.onDetachedFromWindow()
    }
}

class WidgetEntry(val info: AppWidgetProviderInfo, val label: String, val spanX: Int, val spanY: Int)
class WidgetGroup(val packageName: String, val label: String, val widgets: List<WidgetEntry>)

/** Управляет хостом виджетов: выделение id, привязка, настройка, создание view. */
class WidgetController(private val activity: Activity, private val model: LauncherModel) {

    private val appContext = activity.applicationContext
    val manager: AppWidgetManager = AppWidgetManager.getInstance(appContext)
    private val views = HashMap<Int, LauncherWidgetHostView>()

    val host = object : AppWidgetHost(appContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            LauncherWidgetHostView(context)
    }

    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    fun start() = try { host.startListening() } catch (e: Exception) { }
    fun stop() = try { host.stopListening() } catch (e: Exception) { }

    fun viewFor(appWidgetId: Int): LauncherWidgetHostView? {
        views[appWidgetId]?.let { v -> (v.parent as? ViewGroup)?.removeView(v); return v }
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        val v = host.createView(activity, appWidgetId, info) as? LauncherWidgetHostView ?: return null
        views[appWidgetId] = v
        return v
    }

    fun release(appWidgetId: Int) {
        views.remove(appWidgetId)
        try { host.deleteAppWidgetId(appWidgetId) } catch (e: Exception) { }
    }

    fun loadGroups(): List<WidgetGroup> {
        val pm = appContext.packageManager
        val cols = model.settings.columns
        return manager.installedProviders
            .groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                val appLabel = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                WidgetGroup(
                    pkg, appLabel,
                    list.map { WidgetEntry(it, it.loadLabel(pm), spanFor(it).first.coerceAtMost(cols), spanFor(it).second) },
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Размер виджета в ячейках сетки. */
    fun spanFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val cols = model.settings.columns
        val rows = model.settings.rows
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, cols) to info.targetCellHeight.coerceIn(1, rows)
        }
        val dm = activity.resources.displayMetrics
        val cellWdp = dm.widthPixels / dm.density / cols
        val cellHdp = (dm.heightPixels / dm.density - 280f) / rows
        val wdp = info.minWidth / dm.density
        val hdp = info.minHeight / dm.density
        val sx = ceil(wdp / cellWdp).toInt().coerceIn(1, cols)
        val sy = ceil(hdp / cellHdp).toInt().coerceIn(1, rows)
        return sx to sy
    }

    // --- Добавление виджета ---

    fun startAdd(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingInfo = info
        val bound = try { manager.bindAppWidgetIdIfAllowed(id, info.provider) } catch (e: Exception) { false }
        if (bound) {
            configureOrFinish()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQ_BIND)
            } catch (e: Exception) {
                cancelPending()
            }
        }
    }

    private fun configureOrFinish() {
        val info = pendingInfo ?: return
        if (info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                // не получилось открыть настройку — добавляем как есть
            }
        }
        finishAdd()
    }

    private fun finishAdd() {
        val info = pendingInfo ?: return
        val (sx, sy) = spanFor(info)
        model.addWidget(pendingId, info.provider, sx, sy)
        pendingId = -1
        pendingInfo = null
    }

    private fun cancelPending() {
        if (pendingId != -1) release(pendingId)
        pendingId = -1
        pendingInfo = null
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQ_BIND -> {
                if (resultCode == Activity.RESULT_OK) configureOrFinish() else cancelPending()
                return true
            }
            REQ_CONFIGURE -> {
                if (resultCode == Activity.RESULT_OK) finishAdd() else cancelPending()
                return true
            }
        }
        return false
    }

    companion object {
        const val HOST_ID = 4242
        const val REQ_BIND = 7001
        const val REQ_CONFIGURE = 7002
    }
}

/** Виджет на рабочем столе. */
@Composable
fun WidgetItemView(
    item: HomeItem.Widget,
    controller: WidgetController,
    drag: DragController,
    canDrag: Boolean,
    onLongPress: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootView = LocalView.current
    val holder = remember { Array<androidx.compose.ui.layout.LayoutCoordinates?>(1) { null } }
    val slop = LocalViewConfiguration.current.touchSlop * 2
    val currentItem by rememberUpdatedState(item)
    val currentCanDrag by rememberUpdatedState(canDrag)
    val currentOnLong by rememberUpdatedState(onLongPress)

    fun toRoot(rawX: Float, rawY: Float): Offset {
        val loc = IntArray(2)
        rootView.getLocationOnScreen(loc)
        return Offset(rawX - loc[0], rawY - loc[1])
    }

    val density = LocalContext.current.resources.displayMetrics.density
    val view = remember(item.appWidgetId) { controller.viewFor(item.appWidgetId) }
    Box(
        modifier
            .onGloballyPositioned { holder[0] = it }
            .onSizeChanged { sz ->
                val w = (sz.width / density).toInt().coerceAtLeast(1)
                val h = (sz.height / density).toInt().coerceAtLeast(1)
                view?.updateAppWidgetSize(Bundle(), w, h, w, h)
            },
    ) {
        if (view != null) {
            AndroidView(
                factory = {
                    (view.parent as? ViewGroup)?.removeView(view)
                    var start = Offset.Zero
                    view.touchListener = object : WidgetTouchListener {
                        override fun onLongPress(rawX: Float, rawY: Float) {
                            val c = holder[0] ?: return
                            start = toRoot(rawX, rawY)
                            currentOnLong(Rect(c.positionInRoot(), c.size.toSize()))
                            if (currentCanDrag) drag.start(DragPayload.Existing(currentItem), start)
                        }

                        override fun onDragMove(rawX: Float, rawY: Float) {
                            val p = toRoot(rawX, rawY)
                            if (drag.payload != null && (drag.moved || (p - start).getDistance() > slop)) drag.move(p)
                        }

                        override fun onDragEnd(cancelled: Boolean) {
                            drag.end(!cancelled)
                        }
                    }
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Выбор виджета (как «Виджеты» в Pixel Launcher). */
@Composable
fun WidgetPicker(controller: WidgetController, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val groups by produceState<List<WidgetGroup>>(emptyList()) {
        value = withContext(Dispatchers.Default) { controller.loadGroups() }
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.widgets), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Glyph(R.drawable.ic_close) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.packageName }) { g ->
                    val open = expanded == g.packageName
                    val appIcon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, g.packageName) {
                        value = withContext(Dispatchers.Default) {
                            try { ctx.packageManager.getApplicationIcon(g.packageName).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = if (open) null else g.packageName }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            appIcon?.let { Image(BitmapPainter(it), null, Modifier.size(40.dp)) } ?: Box(Modifier.size(40.dp))
                            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                Text(g.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.widgets_count, g.widgets.size),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (open) {
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(g.widgets, key = { it.info.provider.flattenToString() }) { w ->
                                    WidgetCard(w, onClick = { onPick(w.info) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(w: WidgetEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, w.info.provider) {
        value = withContext(Dispatchers.Default) {
            try {
                val d = w.info.loadPreviewImage(ctx, 0) ?: w.info.loadIcon(ctx, 0)
                d?.toBitmap(
                    d.intrinsicWidth.coerceIn(48, 600), d.intrinsicHeight.coerceIn(48, 600),
                )?.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }
    Column(
        Modifier.width(160.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(BitmapPainter(it), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Text(
            w.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Text("${w.spanX} × ${w.spanY}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}
