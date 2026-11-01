package dev.whiledos.threelauncher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateMapOf

/** Считает активные уведомления по пакетам, чтобы рисовать точки на иконках. */
class NotificationDotsService : NotificationListenerService() {

    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onListenerDisconnected() {
        counts.clear()
    }

    private fun refresh() {
        val list = try { activeNotifications } catch (e: Exception) { null } ?: return
        val grouped = list
            .filter { !it.isOngoing && (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }
            .groupingBy { it.packageName }
            .eachCount()
        counts.keys.toList().filter { it !in grouped }.forEach { counts.remove(it) }
        grouped.forEach { (k, v) -> if (counts[k] != v) counts[k] = v }
    }

    companion object {
        val counts = mutableStateMapOf<String, Int>()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NotificationDotsService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
