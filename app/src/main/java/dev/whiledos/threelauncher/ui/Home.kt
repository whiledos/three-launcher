package dev.whiledos.threelauncher.ui

import android.app.AlarmManager
import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.runtime.mutableStateOf
import dev.whiledos.threelauncher.NotificationDotsService
import dev.whiledos.threelauncher.R
import dev.whiledos.threelauncher.data.DOCK
import dev.whiledos.threelauncher.data.HomeItem
import dev.whiledos.threelauncher.data.LauncherModel
import dev.whiledos.threelauncher.data.Settings
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Геометрия рабочего стола в координатах корня — нужна для вычисления места сброса. */
class HomeGeometry {
    var workspace: Rect = Rect.Zero
    var dock: Rect = Rect.Zero
    var hPad = 0f
    var cols = 5
    var rows = 5
    var dockCount = 4
    val targets = HashMap<String, Rect>()

    val cellW: Float get() = if (cols > 0) (workspace.width - 2 * hPad) / cols else 0f
    val cellH: Float get() = if (rows > 0) workspace.height / rows else 0f

    class Target(val page: Int, val x: Int, val y: Int)

    fun targetFor(pos: Offset, sx: Int, sy: Int, currentPage: Int): Target? {
        if (dock.contains(pos) && dockCount > 0) {
            val fx = (pos.x - dock.left) / (dock.width / dockCount)
            return Target(DOCK, floor(fx).toInt().coerceIn(0, dockCount - 1), 0)
        }
        if (workspace.contains(pos) && cellW > 0f && cellH > 0f) {
            val fx = (pos.x - workspace.left - hPad) / cellW
            val fy = (pos.y - workspace.top) / cellH
            val x = floor(fx - sx / 2f + 0.5f).toInt().coerceIn(0, (cols - sx).coerceAtLeast(0))
            val y = floor(fy - sy / 2f + 0.5f).toInt().coerceIn(0, (rows - sy).coerceAtLeast(0))
            return Target(currentPage, x, y)
        }
        return null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeLayer(
    model: LauncherModel,
    settings: Settings,
    pager: PagerState,
    drag: DragController,
    geo: HomeGeometry,
    widgets: WidgetController,
    host: HostActions,
    onAppMenu: (MenuState.App) -> Unit,
    onHomeMenu: (Rect) -> Unit,
    onWidgetMenu: (MenuState.Widget) -> Unit,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    geo.hPad = with(density) { 8.dp.toPx() }
    geo.cols = settings.columns
    geo.rows = settings.rows
    geo.dockCount = settings.dockCount
    val labelColor = if (model.darkText) Color(0xFF1F1F1F) else Color.White

    BoxWithConstraints(modifier.fillMaxSize()) {
        val cellWdp = (maxWidth - 16.dp) / settings.columns
        val iconSize = cellWdp * 0.62f * (settings.iconScale / 100f)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { geo.workspace = it.boundsInRoot() },
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    WorkspacePage(
                        page, model, settings, drag, widgets, host, iconSize, labelColor,
                        onAppMenu, onHomeMenu, onWidgetMenu, onOpenFolder,
                    )
                }
                DropHint(drag, geo, pager)
            }
            PageDots(count = pager.pageCount, current = pager.currentPage, color = labelColor)
            Dock(model, settings, drag, geo, iconSize, onAppMenu, onOpenFolder)
            if (settings.showSearchBar) SearchPill(host)
            else Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WorkspacePage(
    page: Int,
    model: LauncherModel,
    settings: Settings,
    drag: DragController,
    widgets: WidgetController,
    host: HostActions,
    iconSize: androidx.compose.ui.unit.Dp,
    labelColor: Color,
    onAppMenu: (MenuState.App) -> Unit,
    onHomeMenu: (Rect) -> Unit,
    onWidgetMenu: (MenuState.Widget) -> Unit,
    onOpenFolder: (Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        val cellW = maxWidth / settings.columns
        val cellH = maxHeight / settings.rows
        var origin by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { off ->
                        onHomeMenu(Rect(origin + off, androidx.compose.ui.geometry.Size(1f, 1f)))
                    })
                },
        )

        if (page == 0 && settings.showGlance) {
            Glance(host, labelColor, Modifier.width(maxWidth).height(cellH))
        }

        model.items.filter { it.page == page }.forEach { item ->
            androidx.compose.runtime.key(item.id) {
                Box(
                    Modifier
                        .offset(cellW * item.x, cellH * item.y)
                        .size(cellW * item.spanX, cellH * item.spanY),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeItemView(
                        item, model, settings, drag, widgets, iconSize, labelColor,
                        showLabel = settings.showLabels, onAppMenu, onWidgetMenu, onOpenFolder,
                    )
                }
            }
        }
    }
}

@Composable
fun HomeItemView(
    item: HomeItem,
    model: LauncherModel,
    settings: Settings,
    drag: DragController,
    widgets: WidgetController?,
    iconSize: androidx.compose.ui.unit.Dp,
    labelColor: Color,
    showLabel: Boolean,
    onAppMenu: (MenuState.App) -> Unit,
    onWidgetMenu: (MenuState.Widget) -> Unit,
    onOpenFolder: (Long) -> Unit,
) {
    val hidden = drag.moved && (drag.payload as? DragPayload.Existing)?.item?.id == item.id
    val payload = if (settings.lockLayout) null else DragPayload.Existing(item)
    when (item) {
        is HomeItem.App -> {
            val key = item.component.flattenToString()
            val info = model.appsByKey[key]
            val count = NotificationDotsService.counts[item.component.packageName] ?: 0
            AppIconView(
                icon = model.icons[key],
                label = info?.label ?: "",
                iconSize = iconSize,
                showLabel = showLabel,
                labelColor = labelColor,
                shadowLabel = !model.darkText,
                dot = settings.notificationDots && count > 0,
                alpha = if (hidden) 0f else 1f,
                modifier = Modifier.fillMaxSize().iconGestures(
                    drag, payload,
                    onClick = { r -> model.launch(item.component, r.toAndroidRect()) },
                    onLongPress = { r -> onAppMenu(MenuState.App(r, item.component, item.id)) },
                ),
            )
        }
        is HomeItem.Folder -> FolderIcon(
            item, model, iconSize, labelColor, showLabel, hidden,
            Modifier.fillMaxSize().iconGestures(
                drag, payload,
                onClick = { onOpenFolder(item.id) },
                onLongPress = { },
            ),
        )
        is HomeItem.Widget -> if (widgets != null) WidgetItemView(
            item, widgets, drag, canDrag = !settings.lockLayout,
            onLongPress = { r -> onWidgetMenu(MenuState.Widget(r, item)) },
            modifier = Modifier.fillMaxSize().padding(4.dp).alpha(if (hidden) 0f else 1f),
        )
    }
}

@Composable
private fun FolderIcon(
    folder: HomeItem.Folder,
    model: LauncherModel,
    iconSize: androidx.compose.ui.unit.Dp,
    labelColor: Color,
    showLabel: Boolean,
    hidden: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier.then(if (hidden) Modifier.background(Color.Transparent) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!hidden) {
            Box(
                Modifier.size(iconSize).clip(CircleShape).background(Color.White.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                val mini = iconSize * 0.38f
                Column(verticalArrangement = Arrangement.spacedBy(iconSize * 0.04f)) {
                    folder.apps.take(4).chunked(2).forEach { rowApps ->
                        Row(horizontalArrangement = Arrangement.spacedBy(iconSize * 0.04f)) {
                            rowApps.forEach { c ->
                                val bmp = model.icons[c.flattenToString()]
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        androidx.compose.ui.graphics.painter.BitmapPainter(bmp), null, Modifier.size(mini),
                                    )
                                } else Box(Modifier.size(mini))
                            }
                        }
                    }
                }
            }
            if (showLabel) {
                Text(
                    folder.title.ifBlank { stringResource(R.string.folder_default) },
                    color = labelColor, fontSize = 12.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        shadow = if (model.darkText) null else androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(0f, 1.5f), 4f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Dock(
    model: LauncherModel,
    settings: Settings,
    drag: DragController,
    geo: HomeGeometry,
    iconSize: androidx.compose.ui.unit.Dp,
    onAppMenu: (MenuState.App) -> Unit,
    onOpenFolder: (Long) -> Unit,
) {
    val target = if (drag.moved) geo.targetFor(drag.pos, 1, 1, 0) else null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(iconSize + 24.dp)
            .onGloballyPositioned { geo.dock = it.boundsInRoot() },
    ) {
        for (i in 0 until settings.dockCount) {
            val item = model.items.firstOrNull { it.page == DOCK && it.x == i }
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (target?.page == DOCK && target.x == i) Color.White.copy(alpha = 0.25f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (item != null && item !is HomeItem.Widget) {
                    HomeItemView(
                        item, model, settings, drag, null, iconSize, Color.White,
                        showLabel = false, onAppMenu, {}, onOpenFolder,
                    )
                }
            }
        }
    }
}

@Composable
private fun DropHint(drag: DragController, geo: HomeGeometry, pager: PagerState) {
    val payload = drag.payload
    if (!drag.moved || payload == null) return
    val item = (payload as? DragPayload.Existing)?.item
    val sx = item?.spanX ?: 1
    val sy = item?.spanY ?: 1
    val t = geo.targetFor(drag.pos, sx, sy, pager.currentPage) ?: return
    if (t.page == DOCK) return
    val density = LocalDensity.current
    val x = geo.hPad + t.x * geo.cellW
    val y = t.y * geo.cellH
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { (geo.cellW * sx).toDp() }, with(density) { (geo.cellH * sy).toDp() })
            .padding(5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.22f)),
    )
}

@Composable
private fun PageDots(count: Int, current: Int, color: Color) {
    Row(
        Modifier.fillMaxWidth().height(18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) {
            repeat(count) { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp)
                        .size(if (i == current) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = if (i == current) 1f else 0.45f)),
                )
            }
        }
    }
}

/** «Обзор»: дата и ближайший будильник, как в Pixel Launcher. */
@Composable
private fun Glance(host: HostActions, color: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }
    val locale = Locale.getDefault()
    val date = remember(now / 60_000, locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEMMMd")
        java.text.SimpleDateFormat(pattern, locale).format(Date(now)).replaceFirstChar { it.titlecase(locale) }
    }
    val alarm = remember(now / 60_000) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am?.nextAlarmClock?.triggerTime?.let { DateFormat.getTimeFormat(ctx).format(Date(it)) }
    }
    Column(
        modifier.padding(horizontal = 16.dp).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { host.openCalendar() },
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            date, color = color, fontSize = 30.sp, fontWeight = FontWeight.Normal,
            style = TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(Color(0x55000000), Offset(0f, 1.5f), 6f).takeIf { color == Color.White },
            ),
        )
        if (alarm != null) {
            Text(
                stringResource(R.string.next_alarm, alarm), color = color.copy(alpha = 0.85f), fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Строка поиска Google внизу, как в Pixel Launcher. */
@Composable
private fun SearchPill(host: HostActions) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.96f))
            .clickable { host.openGoogleSearch() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "G",
            modifier = Modifier.padding(start = 20.dp),
            style = TextStyle(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853)),
                ),
                fontSize = 26.sp, fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { host.openVoiceSearch() }, contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_mic, tint = colors.onSurfaceVariant)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { host.openLens() }, contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_lens, tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
    }
}
