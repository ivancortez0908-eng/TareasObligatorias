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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the alarm that fires when the next task becomes overdue.
 */
class OverdueCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OverdueChecker.ACTION_CHECK) {
            return
        }
        val pendingResult = goAsync()
        OverdueChecker.check(context, "alarm") {
            pendingResult.finish()
        }
    }
}
