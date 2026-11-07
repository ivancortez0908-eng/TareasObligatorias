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

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.model.TodoTask
import org.secuso.privacyfriendlytodolist.util.NotificationMgr
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr
import org.secuso.privacyfriendlytodolist.view.MainActivity

/**
 * Full screen alert with the overdue tasks, like an alarm clock.
 *
 * Used where the floating window is not possible, e.g. on Android Go devices, which do not
 * allow "display over other apps" at all. It gets launched by the full screen intent of the
 * overdue notification: if the phone is locked / the screen is off it opens directly,
 * if the phone is in use Android shows a pop-up notification that opens this screen.
 */
class OverdueActivity : AppCompatActivity(), OverdueTaskViews.Listener {
    private var tasks: List<TodoTask> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show above the lock screen, but only if the app is not protected by a PIN.
        if (!PreferenceMgr.isPinEnabled(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
        setContentView(R.layout.overlay_panel_fullscreen)
        findViewById<MaxHeightScrollView>(R.id.overlay_scroll).maxHeightFraction = 0.75f
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra(EXTRA_TEST, false) == true) {
            bindTest()
            return
        }
        OverdueChecker.loadOverdueTasks(this) { overdue ->
            if (isFinishing || isDestroyed) {
                return@loadOverdueTasks
            }
            tasks = overdue
            if (tasks.isEmpty()) {
                finish()
            } else {
                bind()
            }
        }
    }

    /** Test alert from the diagnostics screen: proves that the full screen alert works. */
    private fun bindTest() {
        findViewById<TextView>(R.id.overlay_title).setText(R.string.overdue_test_title)
        findViewById<Button>(R.id.overlay_minimize).apply {
            setText(R.string.overdue_test_close)
            setOnClickListener { finish() }
        }
        val container = findViewById<LinearLayout>(R.id.overlay_task_container)
        container.removeAllViews()
        container.addView(TextView(this).apply {
            setText(R.string.overdue_test_success)
            textSize = 16f
            setPadding(0, 24, 0, 24)
        })
    }

    private fun bind() {
        findViewById<TextView>(R.id.overlay_title).text =
            resources.getQuantityString(R.plurals.overdue_overlay_title, tasks.size, tasks.size)
        val minutes = PreferenceMgr.getOverdueReshowMinutes(this)
        findViewById<Button>(R.id.overlay_minimize).apply {
            text = getString(R.string.overdue_minimize, minutes)
            setOnClickListener { finish() }
        }
        val container = findViewById<LinearLayout>(R.id.overlay_task_container)
        OverdueTaskViews.bindTaskList(this, container, tasks, this)
    }

    override fun onKeyPointChanged() {
        if (!isFinishing) {
            bind()
        }
    }

    override fun onTaskDone(task: TodoTask) {
        tasks = tasks.filter { it.getId() != task.getId() }
        if (tasks.isEmpty()) {
            finish()
        } else {
            bind()
        }
    }

    override fun onOpenTask(task: TodoTask) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(NotificationMgr.EXTRA_NOTIFICATION_TASK_ID, task.getId())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_TEST = "EXTRA_OVERDUE_TEST"
    }
}
