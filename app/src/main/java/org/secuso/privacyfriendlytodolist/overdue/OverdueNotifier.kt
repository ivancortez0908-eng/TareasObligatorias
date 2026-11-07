/*
Tareas Obligatorias - modification of Privacy Friendly To-Do List
Copyright (C) 2026

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package org.secuso.privacyfriendlytodolist.overdue

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.model.TodoTask
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr
import org.secuso.privacyfriendlytodolist.view.MainActivity

/**
 * Persistent, insistent notification while there are overdue tasks.
 *
 * Lightweight replacement of a foreground service: nothing keeps running in the background.
 * The notification is re-posted (with sound/vibration) by an alarm every few minutes
 * as long as there are overdue tasks, see [OverdueChecker].
 */
object OverdueNotifier {
    private const val CHANNEL_ID = "overdue_tasks"
    private const val NOTIFICATION_ID = 0x0D0E

    private var lastContent = ""

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID,
                context.getString(R.string.overdue_channel_name),
                NotificationManager.IMPORTANCE_HIGH)
            channel.description = context.getString(R.string.overdue_channel_desc)
            channel.enableVibration(true)
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun canPost(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * @param alert If true the notification makes sound / vibrates (and pops up) again,
     * even if its content did not change. Otherwise it only alerts when the content changed.
     */
    fun show(context: Context, overdueTasks: List<TodoTask>, alert: Boolean) {
        if (!canPost(context)) {
            return
        }
        val count = overdueTasks.size
        val text = overdueTasks.joinToString(separator = "\n") { "• " + it.getName() }
        val content = "$count|$text"
        val changed = content != lastContent
        lastContent = content
        if (!changed && !alert) {
            return
        }
        createChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = context.resources.getQuantityString(R.plurals.overdue_notification_title, count, count)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_checkbox)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(context, R.color.urgencyExceeded))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.overdue_open), pendingIntent)
        if (PreferenceMgr.isOverdueOverlayUsed(context) && !OverlayPermission.canDrawOverlays(context)) {
            builder.setSubText(context.getString(R.string.overdue_notification_no_permission))
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission was revoked meanwhile.
        }
    }

    fun cancel(context: Context) {
        lastContent = ""
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

