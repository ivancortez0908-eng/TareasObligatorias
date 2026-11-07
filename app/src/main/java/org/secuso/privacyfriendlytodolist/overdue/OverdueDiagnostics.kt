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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.secuso.privacyfriendlytodolist.util.Timestamp

/**
 * Remembers what the overdue checker did last (stored locally, never sent anywhere),
 * so the diagnostics screen can show why an alert did or did not appear.
 */
object OverdueDiagnostics {
    private const val PREFS = "overdue_diagnostics"
    const val LAST_CHECK_TIME = "last_check_time"
    const val LAST_CHECK_REASON = "last_check_reason"
    const val LAST_CHECK_TASKS = "last_check_tasks"
    const val LAST_CHECK_OVERDUE = "last_check_overdue"
    const val NEXT_ALARM_TIME = "next_alarm_time"
    const val NEXT_ALARM_METHOD = "next_alarm_method"
    const val LAST_ALARM_TIME = "last_alarm_time"
    const val LAST_ALARM_TYPE = "last_alarm_type"
    const val LAST_NOTIFICATION_TIME = "last_notification_time"
    const val LAST_NOTIFICATION_INFO = "last_notification_info"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val ALERTED_TASK_IDS = "alerted_task_ids"

    fun getAlertedTaskIds(context: Context): Set<Int> =
        prefs(context).getStringSet(ALERTED_TASK_IDS, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    fun setAlertedTaskIds(context: Context, ids: Set<Int>) {
        prefs(context).edit { putStringSet(ALERTED_TASK_IDS, ids.map { it.toString() }.toSet()) }
    }

    fun recordCheck(context: Context, reason: String, tasks: Int, overdue: Int) {
        prefs(context).edit {
            putLong(LAST_CHECK_TIME, System.currentTimeMillis())
            putString(LAST_CHECK_REASON, reason)
            putInt(LAST_CHECK_TASKS, tasks)
            putInt(LAST_CHECK_OVERDUE, overdue)
        }
    }

    fun recordSchedule(context: Context, time: Timestamp?, method: String) {
        prefs(context).edit {
            putLong(NEXT_ALARM_TIME, time?.timeInMillis ?: 0L)
            putString(NEXT_ALARM_METHOD, method)
        }
    }

    fun recordAlarmReceived(context: Context, type: String) {
        prefs(context).edit {
            putLong(LAST_ALARM_TIME, System.currentTimeMillis())
            putString(LAST_ALARM_TYPE, type)
        }
    }

    fun recordNotification(context: Context, info: String) {
        prefs(context).edit {
            putLong(LAST_NOTIFICATION_TIME, System.currentTimeMillis())
            putString(LAST_NOTIFICATION_INFO, info)
        }
    }
}
