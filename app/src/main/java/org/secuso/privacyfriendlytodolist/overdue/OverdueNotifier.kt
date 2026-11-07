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
    private const val TEST_NOTIFICATION_ID = 0x0D0F
    const val CHANNEL = CHANNEL_ID

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
    fun show(context: Context, overdueTasks: List<TodoTask>, realert: Boolean) {
        if (!canPost(context)) {
            OverdueDiagnostics.recordNotification(context, "blocked: notification permission missing")
            return
        }
        val count = overdueTasks.size
        val text = overdueTasks.joinToString(separator = "\n") { "• " + it.getName() }
        val content = "$count|$text"
        val changed = content != lastContent
        val ids = overdueTasks.map { it.getId() }.toSet()
        // A task that just became overdue always alerts with the full screen alert.
        // The already alerted tasks are stored, so a restart of the app does not alert again.
        val alerted = OverdueDiagnostics.getAlertedTaskIds(context)
        val newOverdue = !alerted.containsAll(ids)
        lastContent = content
        OverdueDiagnostics.setAlertedTaskIds(context, ids)
        if (!changed && !realert) {
            return
        }
        val alert = realert || newOverdue
        createChannel(context)
        // Tapping the notification shows the overdue tasks screen.
        val alertIntent = Intent(context, OverdueActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val appIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val appPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID + 1, appIntent,
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
            .addAction(0, context.getString(R.string.overdue_open_app), appPendingIntent)
        if (OverlayPermission.isOverlayUsed(context) && !OverlayPermission.canDrawOverlays(context)) {
            builder.setSubText(context.getString(R.string.overdue_notification_no_permission))
        }
        if (alert && !OverlayPermission.isOverlayActive(context)) {
            // No floating window (e.g. Android Go): alert like an alarm clock. With the screen off or
            // locked the tasks screen opens directly, otherwise Android shows a pop-up notification.
            builder.setFullScreenIntent(pendingIntent, true)
        }
        if (!alert) {
            // Only update the content (e.g. after an app restart), without sound or pop-up.
            builder.setSilent(true)
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            OverdueDiagnostics.recordNotification(context,
                "$count overdue, alert=$alert, fullscreen=${alert && !OverlayPermission.isOverlayActive(context)}")
        } catch (e: SecurityException) {
            OverdueDiagnostics.recordNotification(context, "error: ${e.message}")
        }
    }

    /** Test alert from the diagnostics screen: same channel and full screen alert as a real one. */
    fun showTest(context: Context) {
        if (!canPost(context)) {
            OverdueDiagnostics.recordNotification(context, "test blocked: notification permission missing")
            return
        }
        createChannel(context)
        val intent = Intent(context, OverdueActivity::class.java)
            .putExtra(OverdueActivity.EXTRA_TEST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(context, TEST_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_checkbox)
            .setContentTitle(context.getString(R.string.overdue_test_title))
            .setContentText(context.getString(R.string.overdue_test_text))
            .setColor(ContextCompat.getColor(context, R.color.urgencyExceeded))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
        try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, builder.build())
            OverdueDiagnostics.recordNotification(context, "test notification posted")
        } catch (e: SecurityException) {
            OverdueDiagnostics.recordNotification(context, "test error: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        lastContent = ""
        OverdueDiagnostics.setAlertedTaskIds(context, emptySet())
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

