package dev.whiledos.threelauncher.data

import android.content.ComponentName
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

const val DOCK = -1

data class AppInfo(
    val component: ComponentName,
    val label: String,
    val icon: Drawable,
    val installTime: Long,
) {
    val key: String get() = component.flattenToString()
}

/** Элемент рабочего стола: ярлык, папка или виджет. page == DOCK — док. */
sealed class HomeItem {
    abstract val id: Long
    abstract val page: Int
    abstract val x: Int
    abstract val y: Int
    abstract val spanX: Int
    abstract val spanY: Int
    abstract fun moved(page: Int, x: Int, y: Int): HomeItem
    abstract fun toJson(): JSONObject

    data class App(
        override val id: Long,
        val component: ComponentName,
        override val page: Int,
        override val x: Int,
        override val y: Int,
    ) : HomeItem() {
        override val spanX: Int get() = 1
        override val spanY: Int get() = 1
        override fun moved(page: Int, x: Int, y: Int) = copy(page = page, x = x, y = y)
        override fun toJson() = JSONObject().put("t", "app").put("id", id)
            .put("c", component.flattenToString()).put("p", page).put("x", x).put("y", y)
    }

    data class Folder(
        override val id: Long,
        val title: String,
        val apps: List<ComponentName>,
        override val page: Int,
        override val x: Int,
        override val y: Int,
    ) : HomeItem() {
        override val spanX: Int get() = 1
        override val spanY: Int get() = 1
        override fun moved(page: Int, x: Int, y: Int) = copy(page = page, x = x, y = y)
        override fun toJson() = JSONObject().put("t", "folder").put("id", id).put("title", title)
            .put("apps", JSONArray(apps.map { it.flattenToString() }))
            .put("p", page).put("x", x).put("y", y)
    }

    data class Widget(
        override val id: Long,
        val appWidgetId: Int,
        val provider: ComponentName,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        override val spanX: Int,
        override val spanY: Int,
    ) : HomeItem() {
        override fun moved(page: Int, x: Int, y: Int) = copy(page = page, x = x, y = y)
        override fun toJson() = JSONObject().put("t", "widget").put("id", id)
            .put("wid", appWidgetId).put("c", provider.flattenToString())
            .put("p", page).put("x", x).put("y", y).put("sx", spanX).put("sy", spanY)
    }

    companion object {
        fun fromJson(o: JSONObject): HomeItem? = try {
            val page = o.getInt("p")
            val x = o.getInt("x")
            val y = o.getInt("y")
            val id = o.getLong("id")
            when (o.getString("t")) {
                "app" -> App(id, ComponentName.unflattenFromString(o.getString("c"))!!, page, x, y)
                "folder" -> {
                    val arr = o.getJSONArray("apps")
                    val apps = (0 until arr.length()).mapNotNull { ComponentName.unflattenFromString(arr.getString(it)) }
                    Folder(id, o.optString("title", ""), apps, page, x, y)
                }
                "widget" -> Widget(
                    id, o.getInt("wid"), ComponentName.unflattenFromString(o.getString("c"))!!,
                    page, x, y, o.getInt("sx"), o.getInt("sy"),
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
