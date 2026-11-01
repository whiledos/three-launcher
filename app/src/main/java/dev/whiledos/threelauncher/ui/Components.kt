package dev.whiledos.threelauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

@Composable
fun Glyph(res: Int, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Иконка приложения с подписью и (опционально) точкой уведомления. */
@Composable
fun AppIconView(
    icon: ImageBitmap?,
    label: String,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    labelColor: Color = Color.White,
    shadowLabel: Boolean = true,
    dot: Boolean = false,
    alpha: Float = 1f,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(Modifier.size(iconSize)) {
            if (icon != null) {
                Image(
                    painter = BitmapPainter(icon),
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    alpha = alpha,
                )
            }
            if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(iconSize * 0.24f)
                        .clip(CircleShape)
                        .background(Color.White),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(iconSize * 0.18f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        if (showLabel) {
            Text(
                text = label,
                color = labelColor.copy(alpha = alpha),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = if (shadowLabel) MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(0f, 1.5f), 4f),
                ) else MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Что именно тянется. */
sealed interface DragPayload {
    data class Existing(val item: dev.whiledos.threelauncher.data.HomeItem) : DragPayload
    data class NewApp(val component: android.content.ComponentName) : DragPayload
}

/** Состояние перетаскивания (в координатах корня окна). */
class DragController {
    var payload by mutableStateOf<DragPayload?>(null)
    var pos by mutableStateOf(Offset.Zero)
    var moved by mutableStateOf(false)
    var onDrop: ((DragPayload, Offset) -> Unit)? = null

    val active: Boolean get() = payload != null

    fun start(p: DragPayload, at: Offset) { payload = p; pos = at; moved = false }
    fun move(at: Offset) { pos = at; moved = true }
    fun end(drop: Boolean) {
        val p = payload
        val wasMoved = moved
        payload = null
        moved = false
        if (p != null && drop && wasMoved) onDrop?.invoke(p, pos)
    }
}

private class CoordsHolder { var coords: LayoutCoordinates? = null }

/**
 * Нажатие — [onClick]; долгое нажатие — [onLongPress] (с границами элемента) и, если [payload] != null, начало перетаскивания.
 */
@Composable
fun Modifier.iconGestures(
    drag: DragController,
    payload: DragPayload?,
    onClick: (Rect) -> Unit,
    onLongPress: (Rect) -> Unit,
): Modifier {
    val holder = remember { CoordsHolder() }
    val currentPayload by rememberUpdatedState(payload)
    val currentClick by rememberUpdatedState(onClick)
    val currentLong by rememberUpdatedState(onLongPress)
    val slop = LocalViewConfiguration.current.touchSlop * 2

    fun bounds(): Rect? {
        val c = holder.coords ?: return null
        if (!c.isAttached) return null
        return Rect(c.positionInRoot(), c.size.toSize())
    }

    return this
        .onGloballyPositioned { holder.coords = it }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { bounds()?.let(currentClick) })
        }
        .pointerInput(Unit) {
            var start = Offset.Zero
            var pressRoot = Offset.Zero
            detectDragGesturesAfterLongPress(
                onDragStart = { off ->
                    val b = bounds() ?: return@detectDragGesturesAfterLongPress
                    start = b.topLeft + off
                    pressRoot = start
                    currentLong(b)
                    currentPayload?.let { drag.start(it, start) }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val b = bounds() ?: return@detectDragGesturesAfterLongPress
                    val p = b.topLeft + change.position
                    if (drag.payload != null) {
                        if (drag.moved || (p - pressRoot).getDistance() > slop) drag.move(p)
                    }
                },
                onDragEnd = { drag.end(true) },
                onDragCancel = { drag.end(false) },
            )
        }
}

fun Modifier.shadowIfNeeded(enabled: Boolean, elevation: Dp, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (enabled) this.shadow(elevation, shape) else this
