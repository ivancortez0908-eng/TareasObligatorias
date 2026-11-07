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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.model.TodoTask
import org.secuso.privacyfriendlytodolist.util.LogTag
import org.secuso.privacyfriendlytodolist.view.MainActivity

/**
 * Foreground service that keeps the app alive while there are overdue tasks.
 * It shows a persistent notification and re-checks the overdue tasks every minute, so the
 * floating window comes back even if the system killed it.
 */
class OverdueService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            OverdueChecker.check(this@OverdueService, "service tick")
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, lastCount) ?: lastCount
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: lastText
        val notification = buildNotification(this, count, text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Restarted by the system after the process was killed: re-check right away.
            OverdueChecker.check(this, "service restart")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private val TAG = LogTag.create(this::class.java.declaringClass)
        private const val CHANNEL_ID = "overdue_tasks"
        private const val NOTIFICATION_ID = 0x0D0E
        private const val EXTRA_COUNT = "EXTRA_COUNT"
        private const val EXTRA_TEXT = "EXTRA_TEXT"
        private const val TICK_INTERVAL_MS = 60_000L

        @Volatile
        private var isRunning = false
        private var lastCount = 0
        private var lastText = ""

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.overdue_channel_name),
                    NotificationManager.IMPORTANCE_HIGH)
                channel.description = context.getString(R.string.overdue_channel_desc)
                channel.enableVibration(true)
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(context: Context, count: Int, text: String): Notification {
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
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
            if (!OverlayPermission.canDrawOverlays(context)) {
                builder.setSubText(context.getString(R.string.overdue_notification_no_permission))
            }
            return builder.build()
        }

        private fun describe(tasks: List<TodoTask>): String {
            return tasks.joinToString(separator = "\n") { "• " + it.getName() }
        }

        /**
         * Starts the service, or just updates its notification if it already runs.
         * If the system does not allow to start a foreground service right now,
         * a high priority notification gets posted instead.
         */
        fun startOrUpdate(context: Context, overdueTasks: List<TodoTask>) {
            val count = overdueTasks.size
            val text = describe(overdueTasks)
            val changed = count != lastCount || text != lastText
            val countIncreased = count > lastCount
            lastCount = count
            lastText = text
            if (isRunning) {
                if (changed) {
                    postNotification(context, buildNotification(context, count, text), countIncreased)
                }
                return
            }
            val intent = Intent(context, OverdueService::class.java)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_TEXT, text)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // E.g. ForegroundServiceStartNotAllowedException on Android 12+.
                Log.w(TAG, "Unable to start foreground service, posting notification instead: $e")
                if (changed) {
                    postNotification(context, buildNotification(context, count, text), true)
                }
            }
        }

        private fun postNotification(context: Context, notification: Notification, alert: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (alert) {
                // Remove "only alert once" effect by re-posting, so a newly overdue task makes noise.
                notification.flags = notification.flags and Notification.FLAG_ONLY_ALERT_ONCE.inv()
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        fun stop(context: Context) {
            lastCount = 0
            lastText = ""
            if (isRunning) {
                context.stopService(Intent(context, OverdueService::class.java))
            }
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
