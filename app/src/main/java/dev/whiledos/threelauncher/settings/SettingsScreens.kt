package dev.whiledos.threelauncher.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whiledos.threelauncher.NotificationDotsService
import dev.whiledos.threelauncher.R
import dev.whiledos.threelauncher.data.IconShapes
import dev.whiledos.threelauncher.data.LauncherModel
import dev.whiledos.threelauncher.data.Settings
import dev.whiledos.threelauncher.ui.Glyph
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsApp(model: LauncherModel, settings: Settings, resumeTick: Int, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = if (page == "hidden") "drawer" else "main" }

    val title = when (page) {
        "home" -> R.string.settings_home
        "style" -> R.string.settings_style
        "drawer" -> R.string.settings_drawer
        "gestures" -> R.string.settings_gestures
        "hidden" -> R.string.hidden_apps
        "about" -> R.string.settings_about
        else -> R.string.settings_title
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    Box(
                        Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).clickable {
                            if (page == "main") onClose() else page = if (page == "hidden") "drawer" else "main"
                        },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(R.drawable.ic_back) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (page == "hidden") {
            HiddenAppsPage(model, settings, Modifier.padding(padding))
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    "home" -> HomePage(model, settings)
                    "style" -> StylePage(settings, resumeTick)
                    "drawer" -> DrawerPage(settings) { page = "hidden" }
                    "gestures" -> GesturesPage(settings)
                    "about" -> AboutPage()
                    else -> MainPage(settings, resumeTick) { page = it }
                }
                Box(Modifier.size(24.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------- страницы

@Composable
private fun MainPage(settings: Settings, resumeTick: Int, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val isDefault = remember(resumeTick) {
        try { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) } catch (e: Exception) { true }
    }
    if (!isDefault) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp),
        ) {
            Text(stringResource(R.string.set_default_title), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 18.sp)
            Text(
                stringResource(R.string.set_default_text), color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = {
                try {
                    val rm = ctx.getSystemService(RoleManager::class.java)
                    ctx.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } catch (e: Exception) {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
                }
            }) { Text(stringResource(R.string.set_default_button)) }
        }
    }
    Group {
        NavRow(R.drawable.ic_home, R.string.settings_home, R.string.settings_home_desc) { go("home") }
        NavRow(R.drawable.ic_palette, R.string.settings_style, R.string.settings_style_desc) { go("style") }
        NavRow(R.drawable.ic_apps, R.string.settings_drawer, R.string.settings_drawer_desc) { go("drawer") }
        NavRow(R.drawable.ic_gesture, R.string.settings_gestures, R.string.settings_gestures_desc) { go("gestures") }
    }
    Group {
        NavRow(R.drawable.ic_about, R.string.settings_about, null) { go("about") }
    }
}

@Composable
private fun HomePage(model: LauncherModel, s: Settings) {
    var confirmReset by remember { mutableStateOf(false) }
    Group {
        SwitchRow(R.string.glance, R.string.glance_desc, s.showGlance) { s.showGlance = it }
        SwitchRow(R.string.search_bar, R.string.search_bar_desc, s.showSearchBar) { s.showSearchBar = it }
        SwitchRow(R.string.show_labels, null, s.showLabels) { s.showLabels = it }
        SwitchRow(R.string.auto_add, R.string.auto_add_desc, s.autoAddNewApps) { s.autoAddNewApps = it }
        SwitchRow(R.string.lock_layout, R.string.lock_layout_desc, s.lockLayout) { s.lockLayout = it }
        SwitchRow(R.string.allow_rotation, null, s.allowRotation) { s.allowRotation = it }
    }
    Group {
        SliderRow(R.string.grid_columns, s.columns, 4..6) { s.columns = it }
        SliderRow(R.string.grid_rows, s.rows, 4..7) { s.rows = it }
        SliderRow(R.string.dock_icons, s.dockCount, 3..5) { s.dockCount = it }
        SliderRow(R.string.icon_size, s.iconScale, 70..130, step = 5, suffix = "%") { s.iconScale = it }
    }
    Group {
        ActionRow(R.string.reset_layout, R.string.reset_layout_desc) { confirmReset = true }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_layout)) },
            text = { Text(stringResource(R.string.reset_layout_confirm)) },
            confirmButton = {
                TextButton(onClick = { model.resetLayout(); confirmReset = false }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StylePage(s: Settings, resumeTick: Int) {
    val ctx = LocalContext.current
    val listenerOn = remember(resumeTick) { NotificationDotsService.isEnabled(ctx) }
    val shapeNames = IconShapes.all.map { it to shapeName(it) }

    Group {
        ChoiceRow(R.string.icon_shape, shapeNames, s.iconShape) { s.iconShape = it }
        SwitchRow(
            R.string.themed_icons,
            if (Build.VERSION.SDK_INT >= 33) R.string.themed_icons_desc else R.string.themed_icons_unsupported,
            s.themedIcons,
        ) { s.themedIcons = it }
        SwitchRow(
            R.string.notification_dots,
            if (listenerOn) R.string.notification_dots_desc else R.string.notification_dots_permission,
            s.notificationDots && listenerOn,
        ) { on ->
            s.notificationDots = on
            if (on && !listenerOn) {
                try { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) { }
            }
        }
    }
    Group {
        SwitchRow(R.string.dynamic_color, R.string.dynamic_color_desc, s.dynamicColor) { s.dynamicColor = it }
        ChoiceRow(
            R.string.theme_mode,
            listOf(
                0 to stringResource(R.string.theme_system),
                1 to stringResource(R.string.theme_light),
                2 to stringResource(R.string.theme_dark),
            ),
            s.darkMode,
        ) { s.darkMode = it }
    }
}

@Composable
private fun DrawerPage(s: Settings, openHidden: () -> Unit) {
    Group {
        SliderRow(R.string.drawer_columns, s.drawerColumns, 3..6) { s.drawerColumns = it }
        SwitchRow(R.string.drawer_suggestions, R.string.drawer_suggestions_desc, s.drawerSuggestions) { s.drawerSuggestions = it }
        SwitchRow(R.string.show_labels, null, s.drawerLabels) { s.drawerLabels = it }
        ChoiceRow(
            R.string.drawer_sort,
            listOf(
                0 to stringResource(R.string.sort_az),
                1 to stringResource(R.string.sort_za),
                2 to stringResource(R.string.sort_new),
            ),
            s.drawerSort,
        ) { s.drawerSort = it }
    }
    Group {
        SliderRow(R.string.drawer_opacity, s.drawerOpacity, 50..100, step = 5, suffix = "%") { s.drawerOpacity = it }
        SwitchRow(R.string.drawer_blur, R.string.drawer_blur_desc, s.drawerBlur) { s.drawerBlur = it }
    }
    Group {
        NavRow(
            R.drawable.ic_folder_open, R.string.hidden_apps, null,
            summary = if (s.hiddenApps.isEmpty()) null else stringResource(R.string.hidden_count, s.hiddenApps.size),
            onClick = openHidden,
        )
    }
}

@Composable
private fun GesturesPage(s: Settings) {
    Group {
        SwitchRow(R.string.swipe_up, R.string.swipe_up_desc, s.swipeUpOpensDrawer) { s.swipeUpOpensDrawer = it }
        ChoiceRow(
            R.string.swipe_down,
            listOf(
                0 to stringResource(R.string.swipe_down_notifications),
                1 to stringResource(R.string.swipe_down_search),
                2 to stringResource(R.string.swipe_down_none),
            ),
            s.swipeDownAction,
        ) { s.swipeDownAction = it }
    }
}

@Composable
private fun AboutPage() {
    Group {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.app_name), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.about_version, "1.0"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.about_text), color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun HiddenAppsPage(model: LauncherModel, s: Settings, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(model.allApps, key = { it.key }) { app ->
            val hidden = app.key in s.hiddenApps
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable { s.hiddenApps = if (hidden) s.hiddenApps - app.key else s.hiddenApps + app.key }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                model.icons[app.key]?.let { Image(BitmapPainter(it), null, Modifier.size(40.dp)) } ?: Box(Modifier.size(40.dp))
                Text(
                    app.label, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp).weight(1f), maxLines = 1,
                )
                Checkbox(checked = hidden, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun shapeName(shape: String): String = stringResource(
    when (shape) {
        IconShapes.CIRCLE -> R.string.shape_circle
        IconShapes.SQUIRCLE -> R.string.shape_squircle
        IconShapes.ROUNDED -> R.string.shape_rounded
        IconShapes.TEARDROP -> R.string.shape_teardrop
        IconShapes.SQUARE -> R.string.shape_square
        else -> R.string.shape_system
    },
)

// ----------------------------------------------------------------------- компоненты

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

@Composable
private fun RowScaffold(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                if (subtitle != null) {
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            if (trailing != null) {
                Box(Modifier.size(12.dp))
                trailing()
            }
        }
        below?.invoke()
    }
}

@Composable
private fun NavRow(icon: Int, title: Int, desc: Int?, summary: String? = null, onClick: () -> Unit) {
    RowScaffold(
        title = stringResource(title),
        subtitle = summary ?: desc?.let { stringResource(it) },
        onClick = onClick,
        leading = { Glyph(icon, tint = MaterialTheme.colorScheme.primary) },
        trailing = { Glyph(R.drawable.ic_chevron, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun ActionRow(title: Int, desc: Int?, onClick: () -> Unit) {
    RowScaffold(stringResource(title), desc?.let { stringResource(it) }, onClick)
}

@Composable
private fun SwitchRow(title: Int, desc: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowScaffold(
        title = stringResource(title),
        subtitle = desc?.let { stringResource(it) },
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun SliderRow(title: Int, value: Int, range: IntRange, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    RowScaffold(
        title = stringResource(title),
        subtitle = "$value$suffix",
        onClick = null,
        below = {
            val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
            Slider(
                value = value.toFloat(),
                onValueChange = { v ->
                    val snapped = ((v - range.first) / step).roundToInt() * step + range.first
                    if (snapped != value) onChange(snapped.coerceIn(range.first, range.last))
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = steps,
            )
        },
    )
}

@Composable
private fun <T> ChoiceRow(title: Int, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second
    RowScaffold(title = stringResource(title), subtitle = current, onClick = { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(value); open = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
