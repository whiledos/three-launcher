package dev.whiledos.threelauncher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whiledos.threelauncher.NotificationDotsService
import dev.whiledos.threelauncher.R
import dev.whiledos.threelauncher.data.AppInfo
import dev.whiledos.threelauncher.data.LauncherModel
import dev.whiledos.threelauncher.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Прогресс открытия списка приложений: 0 — закрыт, 1 — открыт. */
class DrawerState {
    var progress by mutableFloatStateOf(0f)
    var range = 1f
    private var job: Job? = null

    val isOpen: Boolean get() = progress > 0.001f

    fun dragBy(dy: Float) {
        job?.cancel()
        progress = (progress - dy / range).coerceIn(0f, 1f)
    }

    fun settle(scope: CoroutineScope, velocityY: Float) {
        val target = when {
            velocityY < -900f -> 1f
            velocityY > 900f -> 0f
            progress > 0.5f -> 1f
            else -> 0f
        }
        animateTo(scope, target, -velocityY / range)
    }

    fun open(scope: CoroutineScope) = animateTo(scope, 1f, 0f)
    fun close(scope: CoroutineScope) = animateTo(scope, 0f, 0f)

    private fun animateTo(scope: CoroutineScope, target: Float, velocity: Float) {
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress, targetValue = target, initialVelocity = velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f),
            ) { v, _ -> progress = v }
        }
    }
}

@Composable
fun AppDrawer(
    model: LauncherModel,
    settings: Settings,
    state: DrawerState,
    drag: DragController,
    host: HostActions,
    scope: CoroutineScope,
    focusRequest: Int,
    onAppMenu: (MenuState.App) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(state.isOpen) {
        if (!state.isOpen) {
            query = ""
            focus.clearFocus()
        }
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            kotlinx.coroutines.delay(200)
            try { searchFocus.requestFocus() } catch (e: Exception) { }
        }
    }

    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && state.progress < 1f) {
                    state.dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    state.dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.progress in 0.001f..0.999f) {
                    state.settle(scope, available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (state.progress in 0.001f..0.999f) state.settle(scope, available.y)
                else if (available.y > 1500f && state.progress >= 0.999f) state.close(scope)
                return available
            }
        }
    }

    val p = state.progress
    val corner = (28f * (1f - p)).dp
    val bg = colors.surface.copy(alpha = settings.drawerOpacity / 100f)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val apps: List<AppInfo> = remember(model.allApps, settings.hiddenApps, settings.drawerSort, query) {
        val base = model.drawerApps()
        if (query.isBlank()) base
        else base.filter { it.label.contains(query.trim(), ignoreCase = true) }
            .sortedBy { !it.label.startsWith(query.trim(), ignoreCase = true) }
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { translationY = (1f - state.progress) * size.height }
            .clip(RoundedCornerShape(topStart = corner, topEnd = corner))
            .background(bg)
            .nestedScroll(connection),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // Поле поиска
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp).fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(26.dp)).background(colors.surfaceContainerHigh),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(R.drawable.ic_search, Modifier.padding(start = 16.dp), tint = colors.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        apps.firstOrNull()?.let { model.launch(it.component) }
                    }),
                    decorationBox = { inner ->
                        Box(Modifier.padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(stringResource(R.string.search_apps), color = colors.onSurfaceVariant, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                )
                if (query.isNotEmpty()) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { query = "" }, contentAlignment = Alignment.Center) {
                        Glyph(R.drawable.ic_close, tint = colors.onSurfaceVariant)
                    }
                }
            }

            val cols = settings.drawerColumns
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = navBottom + 24.dp),
            ) {
                // Предложения
                if (query.isBlank() && settings.drawerSuggestions) {
                    val suggested = model.suggestedApps(cols)
                    if (suggested.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(cols) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(28.dp)).background(colors.surfaceContainer)
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                suggested.forEach { a ->
                                    Box(Modifier.weight(1f)) { DrawerCell(a, model, settings, drag, onAppMenu, showLabel = false) }
                                }
                                repeat(cols - suggested.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                items(apps, key = { it.key }) { a ->
                    DrawerCell(a, model, settings, drag, onAppMenu, showLabel = settings.drawerLabels)
                }
                if (query.isNotBlank()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(cols) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(20.dp))
                                .clickable { host.searchWeb(query.trim()) }
                                .background(colors.surfaceContainer).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(R.drawable.ic_search, tint = colors.onSurfaceVariant)
                            Text(
                                stringResource(R.string.search_web, query.trim()), color = colors.onSurface,
                                modifier = Modifier.padding(start = 16.dp), maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerCell(
    a: AppInfo,
    model: LauncherModel,
    settings: Settings,
    drag: DragController,
    onAppMenu: (MenuState.App) -> Unit,
    showLabel: Boolean,
) {
    val count = NotificationDotsService.counts[a.component.packageName] ?: 0
    AppIconView(
        icon = model.icons[a.key],
        label = a.label,
        iconSize = (56 * settings.iconScale / 100f).dp,
        showLabel = showLabel,
        labelColor = MaterialTheme.colorScheme.onSurface,
        shadowLabel = false,
        dot = settings.notificationDots && count > 0,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .iconGestures(
                drag, DragPayload.NewApp(a.component),
                onClick = { r -> model.launch(a.component, r.toAndroidRect()) },
                onLongPress = { r -> onAppMenu(MenuState.App(r, a.component, null)) },
            ),
    )
}
