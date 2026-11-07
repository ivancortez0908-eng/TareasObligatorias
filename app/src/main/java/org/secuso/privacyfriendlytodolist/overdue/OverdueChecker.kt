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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.secuso.privacyfriendlytodolist.model.TodoTask
import org.secuso.privacyfriendlytodolist.util.LogTag
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr
import org.secuso.privacyfriendlytodolist.util.Timestamp
import org.secuso.privacyfriendlytodolist.viewmodel.CustomViewModel

/**
 * Decides which tasks are overdue and keeps the floating window / foreground service in sync.
 *
 * A task is overdue if it is not done, not in the recycle bin and
 *  - its deadline day has passed (non-recurring tasks only, because the deadline is a date), or
 *  - its reminder time has passed (if the setting "reminder time is the time limit" is enabled).
 */
object OverdueChecker {
    private val TAG = LogTag.create(this::class.java)
    private const val ALARM_REQUEST_CODE = 0x0D0E
    const val ACTION_CHECK = "org.secuso.privacyfriendlytodolist.OVERDUE_CHECK"
    const val ACTION_TEST = "org.secuso.privacyfriendlytodolist.OVERDUE_TEST"

    /** Lazily created model services that live as long as the process. */
    private var viewModel: CustomViewModel? = null

    fun getViewModel(context: Context): CustomViewModel {
        var vm = viewModel
        if (vm == null) {
            vm = CustomViewModel(context.applicationContext)
            viewModel = vm
        }
        return vm
    }

    fun isOverdue(task: TodoTask, now: Timestamp, reminderIsLimit: Boolean): Boolean {
        if (task.isDone() || task.isInRecycleBin()) {
            return false
        }
        val deadline = task.getDeadline()
        if (deadline != null && !task.isRecurring() && deadline.timeInDays < now.timeInDays) {
            return true
        }
        val reminder = task.getReminderTime()
        return reminderIsLimit && reminder != null && reminder <= now
    }

    /** Returns the next point in time at which some currently not overdue task becomes overdue. */
    private fun computeNextTransition(tasks: List<TodoTask>, now: Timestamp, reminderIsLimit: Boolean): Timestamp? {
        var next: Timestamp? = null
        for (task in tasks) {
            if (task.isDone() || task.isInRecycleBin() || isOverdue(task, now, reminderIsLimit)) {
                continue
            }
            val candidates = ArrayList<Timestamp>(2)
            val reminder = task.getReminderTime()
            if (reminderIsLimit && reminder != null && reminder > now) {
                candidates.add(reminder)
            }
            val deadline = task.getDeadline()
            if (deadline != null && !task.isRecurring()) {
                val dayAfterDeadline = deadline
                    .addInterval(TodoTask.RecurrencePattern.DAILY, 1)
                    .setTimePart(0, 0, 5)
                if (dayAfterDeadline > now) {
                    candidates.add(dayAfterDeadline)
                }
            }
            for (candidate in candidates) {
                if (next == null || candidate < next) {
                    next = candidate
                }
            }
        }
        return next
    }

    private fun filterOverdue(context: Context, tasks: List<TodoTask>, now: Timestamp): List<TodoTask> {
        val reminderIsLimit = PreferenceMgr.isOverdueReminderTheLimit(context)
        return tasks.filter { isOverdue(it, now, reminderIsLimit) }
            // Priority enum is declared HIGH, MEDIUM, LOW: lower ordinal = more important.
            .sortedWith(compareBy<TodoTask> { it.getPriority().ordinal }
                .thenBy { it.getDeadline()?.timeInSeconds ?: Long.MAX_VALUE })
    }

    /** Loads the overdue tasks, sorted by priority. The result is delivered to the main thread. */
    fun loadOverdueTasks(context: Context, consumer: (List<TodoTask>) -> Unit) {
        val appContext = context.applicationContext
        getViewModel(appContext).model.getAllToDoTasks { tasks ->
            consumer(filterOverdue(appContext, tasks, Timestamp.createCurrent()))
        }
    }

    /**
     * Loads all tasks, shows or hides the floating window and schedules the next check.
     * Can be called from any thread that has a looper (results are delivered to the main thread).
     */
    fun check(context: Context, reason: String, realert: Boolean = false, onFinished: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (!PreferenceMgr.isOverdueEnabled(appContext)) {
            Log.d(TAG, "Overdue check ($reason): feature disabled.")
            cancelNextCheck(appContext)
            OverdueOverlay.hide(appContext)
            OverdueNotifier.cancel(appContext)
            onFinished?.invoke()
            return
        }
        getViewModel(appContext).model.getAllToDoTasks { tasks ->
            try {
                val now = Timestamp.createCurrent()
                val reminderIsLimit = PreferenceMgr.isOverdueReminderTheLimit(appContext)
                val overdue = filterOverdue(appContext, tasks, now)
                Log.d(TAG, "Overdue check ($reason): ${overdue.size} of ${tasks.size} tasks are overdue.")
                var nextCheck = computeNextTransition(tasks, now, reminderIsLimit)
                var nextIsTransition = nextCheck != null
                if (overdue.isEmpty()) {
                    OverdueOverlay.hide(appContext)
                    OverdueNotifier.cancel(appContext)
                } else {
                    if (OverlayPermission.isOverlayUsed(appContext)) {
                        OverdueOverlay.show(appContext, overdue)
                    } else {
                        OverdueOverlay.hide(appContext)
                    }
                    OverdueNotifier.show(appContext, overdue, realert && !OverdueOverlay.isAppInForeground())
                    // No background service: an alarm wakes the app up again after the
                    // "minimize" time to re-alert and to bring the floating window back.
                    val realertTime = now.addSeconds(PreferenceMgr.getOverdueReshowMinutes(appContext) * 60L)
                    if (nextCheck == null || realertTime < nextCheck) {
                        nextCheck = realertTime
                        nextIsTransition = false
                    }
                }
                scheduleNextCheck(appContext, nextCheck, nextIsTransition)
                OverdueDiagnostics.recordCheck(appContext, reason, tasks.size, overdue.size)
            } catch (e: Exception) {
                Log.e(TAG, "Overdue check ($reason) failed: $e")
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private fun createAlarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, OverdueCheckReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    /**
     * @param isTransition True if at [time] a task becomes overdue. These alarms are set as
     * "alarm clock" alarms: the most reliable kind, they also fire in deep sleep (Doze) and on
     * devices with aggressive battery saving. Re-alerts of already overdue tasks use normal exact alarms.
     */
    private fun scheduleNextCheck(context: Context, time: Timestamp?, isTransition: Boolean = false) {
        if (time == null) {
            cancelNextCheck(context)
            OverdueDiagnostics.recordSchedule(context, null, "-")
            return
        }
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createAlarmIntent(context)
        var method: String
        try {
            if (!canScheduleExactAlarms(context)) {
                method = "inexact"
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time.timeInMillis, pendingIntent)
            } else if (isTransition) {
                method = "alarm clock"
                val showIntent = PendingIntent.getActivity(context, ALARM_REQUEST_CODE,
                    Intent(context, org.secuso.privacyfriendlytodolist.view.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(time.timeInMillis, showIntent), pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                method = "exact"
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time.timeInMillis, pendingIntent)
            } else {
                method = "exact"
                manager.setExact(AlarmManager.RTC_WAKEUP, time.timeInMillis, pendingIntent)
            }
            Log.d(TAG, "Next overdue check scheduled for $time ($method).")
        } catch (e: SecurityException) {
            method = "error: ${e.message}"
            Log.e(TAG, "Unable to schedule next overdue check: $e")
        }
        OverdueDiagnostics.recordSchedule(context, time, method)
    }

    /** Fires a test alert in [delaySeconds] seconds, to check that alerts work on this phone. */
    fun scheduleTestAlert(context: Context, delaySeconds: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, OverdueCheckReceiver::class.java).setAction(ACTION_TEST)
        val pendingIntent = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = System.currentTimeMillis() + delaySeconds * 1000L
        try {
            if (canScheduleExactAlarms(context)) {
                val showIntent = PendingIntent.getActivity(context, ALARM_REQUEST_CODE + 1,
                    Intent(context, OverdueDiagnosticsActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(time, showIntent), pendingIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to schedule test alert: $e")
        }
    }

    private fun cancelNextCheck(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(createAlarmIntent(context))
    }
}
