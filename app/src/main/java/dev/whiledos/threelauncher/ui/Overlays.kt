package dev.whiledos.threelauncher.ui

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.whiledos.threelauncher.R
import dev.whiledos.threelauncher.data.HomeItem
import dev.whiledos.threelauncher.data.LauncherModel
import kotlin.math.roundToInt

sealed interface MenuState {
    /** [itemId] != null — иконка на рабочем столе; [folderId] != null — внутри папки. */
    data class App(val anchor: Rect, val component: ComponentName, val itemId: Long?, val folderId: Long? = null) : MenuState
    data class Home(val anchor: Rect) : MenuState
    data class Widget(val anchor: Rect, val item: HomeItem.Widget) : MenuState
}

/** Окно рядом с якорем (над ним, если есть место, иначе под ним). */
@Composable
fun AnchoredPopup(anchor: Rect, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    Layout(
        content = {
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { content() }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, c ->
        val m = 12.dp.roundToPx()
        val child = measurables.first().measure(Constraints(maxWidth = (c.maxWidth - 2 * m).coerceAtLeast(0), maxHeight = c.maxHeight))
        val x = (anchor.center.x - child.width / 2f).roundToInt().coerceIn(m, (c.maxWidth - child.width - m).coerceAtLeast(m))
        val gap = 6.dp.roundToPx()
        val above = anchor.top - child.height - gap >= 48.dp.roundToPx()
        val y = (if (above) anchor.top - child.height - gap else anchor.bottom + gap)
            .roundToInt().coerceIn(m, (c.maxHeight - child.height - m).coerceAtLeast(m))
        layout(c.maxWidth, c.maxHeight) { child.place(x, y) }
    }
}

@Composable
private fun MenuContainer(content: @Composable () -> Unit) {
    Column(
        Modifier
            .widthIn(min = 220.dp, max = 300.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 8.dp),
    ) { content() }
}

@Composable
fun MenuRow(icon: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
        Text(
            text, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
fun AppMenu(
    state: MenuState.App,
    model: LauncherModel,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val shortcuts = remember(state.component) { model.shortcutsFor(state.component) }
    AnchoredPopup(state.anchor, onDismiss) {
        MenuContainer {
            shortcuts.forEach { s ->
                val icon = remember(s.id) {
                    model.shortcutIcon(s)?.let { d -> try { d.toBitmap(72, 72).asImageBitmap() } catch (e: Exception) { null } }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { model.startShortcut(s); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) Image(BitmapPainter(icon), null, Modifier.size(24.dp).clip(CircleShape))
                    else Box(Modifier.size(24.dp))
                    Text(
                        (s.shortLabel ?: s.longLabel ?: "").toString(),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        maxLines = 1, modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            MenuRow(R.drawable.ic_info, stringResource(R.string.app_info)) {
                model.openAppInfo(state.component); onDismiss()
            }
            if (onRemove != null) {
                MenuRow(R.drawable.ic_close, stringResource(R.string.remove)) { onRemove(); onDismiss() }
            }
            MenuRow(R.drawable.ic_delete, stringResource(R.string.uninstall)) {
                model.uninstall(state.component); onDismiss()
            }
        }
    }
}

@Composable
fun HomeMenu(
    state: MenuState.Home,
    onDismiss: () -> Unit,
    onWallpaper: () -> Unit,
    onWidgets: () -> Unit,
    onSettings: () -> Unit,
) {
    AnchoredPopup(state.anchor, onDismiss) {
        MenuContainer {
            MenuRow(R.drawable.ic_wallpaper, stringResource(R.string.wallpaper_style)) { onDismiss(); onWallpaper() }
            MenuRow(R.drawable.ic_widgets, stringResource(R.string.widgets)) { onDismiss(); onWidgets() }
            MenuRow(R.drawable.ic_settings, stringResource(R.string.home_settings)) { onDismiss(); onSettings() }
        }
    }
}

@Composable
fun WidgetMenu(
    state: MenuState.Widget,
    onDismiss: () -> Unit,
    onResize: () -> Unit,
    onRemove: () -> Unit,
) {
    AnchoredPopup(state.anchor, onDismiss) {
        MenuContainer {
            MenuRow(R.drawable.ic_edit, stringResource(R.string.resize)) { onDismiss(); onResize() }
            MenuRow(R.drawable.ic_close, stringResource(R.string.remove)) { onDismiss(); onRemove() }
        }
    }
}

/** Диалог изменения размера виджета в ячейках. */
@Composable
fun ResizeDialog(
    item: HomeItem.Widget,
    maxCols: Int,
    maxRows: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var w by remember { mutableStateOf(item.spanX) }
    var h by remember { mutableStateOf(item.spanY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resize)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper(stringResource(R.string.width), w, 1, maxCols) { w = it }
                Stepper(stringResource(R.string.height), h, 1, maxRows) { h = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(w, h); onDismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(enabled = value > min) { onChange(value - 1) },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_remove, tint = MaterialTheme.colorScheme.onSecondaryContainer, size = 20.dp) }
        Text(
            value.toString(), Modifier.widthIn(min = 40.dp), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp,
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(enabled = value < max) { onChange(value + 1) },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_add, tint = MaterialTheme.colorScheme.onSecondaryContainer, size = 20.dp) }
    }
}

/** Открытая папка. */
@Composable
fun FolderPopup(
    folder: HomeItem.Folder,
    model: LauncherModel,
    onClose: () -> Unit,
    onAppMenu: (MenuState.App) -> Unit,
) {
    var title by remember(folder.id) { mutableStateOf(folder.title) }
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) {
            detectTapGestures { model.renameFolder(folder.id, title); onClose() }
        },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.padding(24.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.renameFolder(folder.id, title) }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (title.isEmpty()) {
                                Text(
                                    stringResource(R.string.folder_default),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                val apps = folder.apps.mapNotNull { model.appsByKey[it.flattenToString()] }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(300.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(apps, key = { it.key }) { app ->
                        var rect by remember { mutableStateOf(Rect.Zero) }
                        AppIconView(
                            icon = model.icons[app.key], label = app.label, iconSize = 56.dp,
                            labelColor = MaterialTheme.colorScheme.onSurface, shadowLabel = false,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .onGloballyPositioned { rect = it.boundsInRoot() }
                                .pointerInput(app.key) {
                                    detectTapGestures(
                                        onTap = { model.launch(app.component); onClose() },
                                        onLongPress = { onAppMenu(MenuState.App(rect, app.component, null, folder.id)) },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Зоны «Убрать / О приложении / Удалить» при перетаскивании + «призрак» иконки. */
@Composable
fun DragLayer(
    drag: DragController,
    model: LauncherModel,
    geo: HomeGeometry,
) {
    val payload = drag.payload ?: return
    if (!drag.moved) return
    val density = LocalDensity.current
    val item = (payload as? DragPayload.Existing)?.item
    val component: ComponentName? = when {
        payload is DragPayload.NewApp -> payload.component
        item is HomeItem.App -> item.component
        else -> null
    }

    // Зоны сверху
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item != null) DropZone("remove", R.drawable.ic_close, R.string.remove, drag, geo, Modifier.weight(1f))
        if (component != null) {
            DropZone("info", R.drawable.ic_info, R.string.app_info, drag, geo, Modifier.weight(1f))
            DropZone("uninstall", R.drawable.ic_delete, R.string.uninstall, drag, geo, Modifier.weight(1f))
        }
    }

    // «Призрак»
    val sizeDp = 64.dp
    val spanX = item?.spanX ?: 1
    val spanY = item?.spanY ?: 1
    val ghostW = if (item is HomeItem.Widget) with(density) { (geo.cellW * spanX) } else with(density) { sizeDp.toPx() }
    val ghostH = if (item is HomeItem.Widget) with(density) { (geo.cellH * spanY) } else with(density) { sizeDp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((drag.pos.x - ghostW / 2f).roundToInt(), (drag.pos.y - ghostH / 2f).roundToInt()) }
            .size(with(density) { ghostW.toDp() }, with(density) { ghostH.toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        when {
            item is HomeItem.Widget -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
            item is HomeItem.Folder -> Box(
                Modifier.size(sizeDp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)),
            )
            component != null -> {
                val bmp = model.icons[component.flattenToString()]
                if (bmp != null) {
                    Image(BitmapPainter(bmp), null, Modifier.size(sizeDp).shadow(10.dp, CircleShape, clip = false))
                }
            }
        }
    }
}

@Composable
private fun DropZone(
    id: String, icon: Int, label: Int, drag: DragController, geo: HomeGeometry, modifier: Modifier,
) {
    val hovering = geo.targets[id]?.contains(drag.pos) == true
    Row(
        modifier
            .height(48.dp)
            .onGloballyPositioned { geo.targets[id] = it.boundsInRoot() }
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (hovering) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, size = 20.dp)
        Text(stringResource(label), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, maxLines = 1)
    }
}
