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
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.model.TodoSubtask
import org.secuso.privacyfriendlytodolist.model.TodoTask
import org.secuso.privacyfriendlytodolist.util.Helper
import org.secuso.privacyfriendlytodolist.util.NotificationMgr
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr
import org.secuso.privacyfriendlytodolist.util.Timestamp

/**
 * Builds the views of overdue tasks (name, how long overdue, priority, description,
 * key points with check boxes, "open" and "done" buttons) and performs their actions.
 * Shared by the floating window ([OverdueOverlay]) and the full screen alert ([OverdueActivity]).
 */
object OverdueTaskViews {

    interface Listener {
        /** A key point was checked / unchecked and saved. The views should be rebuilt. */
        fun onKeyPointChanged()
        /** The task was marked as done (saving runs in background). */
        fun onTaskDone(task: TodoTask)
        /** The user wants to open the task in the app. */
        fun onOpenTask(task: TodoTask)
    }

    fun bindTaskList(themedContext: Context, container: LinearLayout, tasks: List<TodoTask>, listener: Listener) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(themedContext)
        val now = Timestamp.createCurrent()
        for (task in tasks) {
            val item = inflater.inflate(R.layout.overlay_task_item, container, false)
            bindTask(themedContext, item, task, now, listener)
            container.addView(item)
        }
    }

    private fun bindTask(themedContext: Context, item: View, task: TodoTask, now: Timestamp, listener: Listener) {
        val context = themedContext.applicationContext
        item.findViewById<TextView>(R.id.overlay_task_name).text = task.getName()

        val info = StringBuilder()
        val deadline = task.getDeadline()
        if (deadline != null && !task.isRecurring() && deadline.timeInDays < now.timeInDays) {
            val days = (now.timeInDays - deadline.timeInDays).toInt()
            info.append(context.resources.getQuantityString(R.plurals.overdue_since_days, days, days,
                deadline.createLocalizedDateString()))
        } else {
            val reminder = task.getReminderTime()
            if (reminder != null) {
                info.append(context.getString(R.string.overdue_since_time, reminder.createLocalizedDateTimeString()))
            }
        }
        info.append(" · ").append(context.getString(R.string.overdue_priority,
            Helper.priorityToString(context, task.getPriority())))
        item.findViewById<TextView>(R.id.overlay_task_info).text = info

        val description = item.findViewById<TextView>(R.id.overlay_task_description)
        if (task.getDescription().isBlank()) {
            description.visibility = View.GONE
        } else {
            description.visibility = View.VISIBLE
            description.text = task.getDescription()
        }

        val subtasks = task.getSubtasks().filter { !it.isInRecycleBin() }
        val keyPointsTitle = item.findViewById<TextView>(R.id.overlay_key_points_title)
        val keyPoints = item.findViewById<LinearLayout>(R.id.overlay_key_points)
        keyPoints.removeAllViews()
        keyPointsTitle.visibility = if (subtasks.isEmpty()) View.GONE else View.VISIBLE
        for (subtask in subtasks) {
            val checkBox = CheckBox(themedContext)
            checkBox.text = subtask.getName()
            checkBox.isChecked = subtask.isDone()
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                setSubtaskDone(context, task, subtask, isChecked) { listener.onKeyPointChanged() }
            }
            keyPoints.addView(checkBox)
        }

        val allKeyPointsDone = subtasks.all { it.isDone() }
        val keyPointsRequired = PreferenceMgr.isOverdueKeyPointsRequired(context)
        val doneButton = item.findViewById<Button>(R.id.overlay_task_done)
        val hint = item.findViewById<TextView>(R.id.overlay_task_done_hint)
        val canFinish = allKeyPointsDone || !keyPointsRequired
        doneButton.isEnabled = canFinish
        hint.visibility = if (canFinish) View.GONE else View.VISIBLE
        doneButton.setOnClickListener {
            setTaskDone(context, task)
            listener.onTaskDone(task)
        }

        item.findViewById<Button>(R.id.overlay_task_open).setOnClickListener { listener.onOpenTask(task) }
    }

    private fun setSubtaskDone(context: Context, task: TodoTask, subtask: TodoSubtask, isDone: Boolean,
                               onSaved: () -> Unit) {
        if (subtask.isDone() == isDone) {
            return
        }
        subtask.setDone(isDone)
        subtask.setChanged()
        val model = OverdueChecker.getViewModel(context).model
        model.saveTodoSubtaskInDb(subtask) {
            if (PreferenceMgr.isAutoProgressEnabled(context) && task.computeProgress()) {
                task.setChanged()
                model.saveTodoTaskInDb(task)
            }
            model.notifyDataChangedOutsideAppUI(0, 0, 1)
            onSaved()
        }
    }

    private fun setTaskDone(context: Context, task: TodoTask) {
        task.setDone(true)
        task.setChanged()
        NotificationMgr.cancelNotification(context, task.getId())
        val model = OverdueChecker.getViewModel(context).model
        model.saveTodoTaskInDb(task) { counter ->
            if (counter > 0) {
                model.notifyDataChangedOutsideAppUI(0, counter, 0)
            }
            OverdueChecker.check(context, "task done")
        }
    }
}
