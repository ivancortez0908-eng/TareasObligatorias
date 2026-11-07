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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.model.TodoTask
import org.secuso.privacyfriendlytodolist.util.LogTag
import org.secuso.privacyfriendlytodolist.util.NotificationMgr
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr
import org.secuso.privacyfriendlytodolist.util.Timestamp
import org.secuso.privacyfriendlytodolist.view.MainActivity
import kotlin.math.abs

/**
 * The floating window that shows overdue tasks on top of all other apps.
 *
 * It has two states:
 *  - expanded: a panel with all overdue tasks, their key points (subtasks) and a "done" button.
 *    It has no close button. The only ways to get rid of it are finishing the tasks or
 *    minimizing it for a limited time.
 *  - minimized: a small draggable bubble. It expands again by itself after the configured
 *    time, or right away when tapped.
 *
 * While the app itself is in the foreground the window is minimized, so the user can work
 * on the task. When the user leaves the app it expands again.
 */
@SuppressLint("StaticFieldLeak") // Only the application context is stored.
object OverdueOverlay {
    private val TAG = LogTag.create(this::class.java)

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelIsFullscreen = false
    /** Content signature of the panel, to avoid rebuilding it (and losing the scroll position) every minute. */
    private var panelSignature: String? = null

    private var tasks: List<TodoTask> = emptyList()
    private var minimizedUntilMs = 0L
    private var appInForeground = false

    private val expandRunnable = Runnable {
        minimizedUntilMs = 0L
        render()
    }

    private fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
    }

    private fun themedContext(): Context = ContextThemeWrapper(appContext!!, R.style.AppTheme)

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /** Shows (or refreshes) the floating window with the given overdue tasks. */
    fun show(context: Context, overdueTasks: List<TodoTask>) {
        init(context)
        tasks = overdueTasks
        render()
    }

    /** Removes the floating window completely. */
    fun hide(context: Context) {
        init(context)
        tasks = emptyList()
        minimizedUntilMs = 0L
        handler.removeCallbacks(expandRunnable)
        removePanel()
        removeBubble()
    }

    /**
     * Gets called by the application when the first activity of this app starts or the last one stops.
     */
    fun setAppInForeground(context: Context, inForeground: Boolean) {
        init(context)
        if (appInForeground == inForeground) {
            return
        }
        appInForeground = inForeground
        if (!inForeground) {
            // Leaving the app ends any "minimized" period: the user has to face the tasks again.
            minimizedUntilMs = 0L
            handler.removeCallbacks(expandRunnable)
        }
        if (tasks.isNotEmpty()) {
            render()
        }
    }

    fun isAppInForeground(): Boolean = appInForeground

    private fun isMinimized(): Boolean {
        return appInForeground || System.currentTimeMillis() < minimizedUntilMs
    }

    private fun render() {
        val context = appContext ?: return
        if (tasks.isEmpty() || !OverlayPermission.canDrawOverlays(context)) {
            removePanel()
            removeBubble()
            return
        }
        try {
            if (isMinimized()) {
                removePanel()
                showBubble()
            } else {
                removeBubble()
                showPanel()
            }
        } catch (e: Exception) {
            // E.g. BadTokenException / SecurityException if the permission was revoked meanwhile.
            Log.e(TAG, "Unable to show floating window: $e")
        }
    }

    private fun minimize() {
        val context = appContext ?: return
        val minutes = PreferenceMgr.getOverdueReshowMinutes(context)
        minimizedUntilMs = System.currentTimeMillis() + minutes * 60_000L
        handler.removeCallbacks(expandRunnable)
        handler.postDelayed(expandRunnable, minutes * 60_000L)
        render()
    }

    private fun expandNow() {
        minimizedUntilMs = 0L
        handler.removeCallbacks(expandRunnable)
        render()
    }

    // ---------------------------------------------------------------- panel

    private fun showPanel() {
        val context = appContext!!
        val fullscreen = PreferenceMgr.isOverdueFullscreen(context)
        if (panelView != null && panelIsFullscreen != fullscreen) {
            removePanel()
        }
        var view = panelView
        if (view == null) {
            val layout = if (fullscreen) R.layout.overlay_panel_fullscreen else R.layout.overlay_panel
            view = LayoutInflater.from(themedContext()).inflate(layout, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                if (fullscreen) {
                    // Covers the whole screen and consumes all touches.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                } else {
                    // Touches outside of the panel go to the app below.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                },
                PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            windowManager!!.addView(view, params)
            panelView = view
            panelIsFullscreen = fullscreen
        }
        bindPanel(view!!)
    }

    private fun bindPanel(view: View) {
        val context = appContext!!
        val now = Timestamp.createCurrent()
        val signature = buildString {
            append(now.timeInDays).append('|')
            append(PreferenceMgr.getOverdueReshowMinutes(context)).append('|')
            append(PreferenceMgr.isOverdueKeyPointsRequired(context)).append('|')
            for (task in tasks) {
                append(task.getId()).append(':').append(task.getName()).append(':')
                    .append(task.getDescription()).append(':').append(task.getPriority())
                for (subtask in task.getSubtasks()) {
                    append(',').append(subtask.getId()).append(subtask.getName()).append(subtask.isDone())
                }
                append(';')
            }
        }
        if (signature == panelSignature) {
            return
        }
        panelSignature = signature
        view.findViewById<TextView>(R.id.overlay_title).text =
            context.resources.getQuantityString(R.plurals.overdue_overlay_title, tasks.size, tasks.size)
        val minutes = PreferenceMgr.getOverdueReshowMinutes(context)
        view.findViewById<Button>(R.id.overlay_minimize).apply {
            text = context.getString(R.string.overdue_minimize, minutes)
            setOnClickListener { minimize() }
        }
        val container = view.findViewById<LinearLayout>(R.id.overlay_task_container)
        OverdueTaskViews.bindTaskList(themedContext(), container, tasks, taskListener)
    }

    private val taskListener = object : OverdueTaskViews.Listener {
        override fun onKeyPointChanged() {
            // Re-render to enable / disable the done button.
            render()
        }

        override fun onTaskDone(task: TodoTask) {
            // Remove the task from the window immediately; the following check confirms the state.
            tasks = tasks.filter { it.getId() != task.getId() }
            render()
        }

        override fun onOpenTask(task: TodoTask) {
            openTask(task)
        }
    }

    private fun openTask(task: TodoTask) {
        val context = appContext ?: return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(NotificationMgr.EXTRA_NOTIFICATION_TASK_ID, task.getId())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Minimize first, so the window does not cover the app. It comes back when the app is left.
        appInForeground = true
        render()
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open task: $e")
            appInForeground = false
            render()
        }
    }

    private fun removePanel() {
        val view = panelView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Removing panel failed: $e")
        }
        panelView = null
        panelSignature = null
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val context = appContext!!
        var view = bubbleView
        if (view == null) {
            view = LayoutInflater.from(themedContext()).inflate(R.layout.overlay_bubble, null)
            val params = bubbleParams ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = (context.resources.displayMetrics.heightPixels * 0.3).toInt()
            }
            bubbleParams = params
            view!!.setOnTouchListener(BubbleTouchListener(params))
            windowManager!!.addView(view, params)
            bubbleView = view
        }
        view!!.findViewById<TextView>(R.id.overlay_bubble_count).text = tasks.size.toString()
    }

    private fun removeBubble() {
        val view = bubbleView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Removing bubble failed: $e")
        }
        bubbleView = null
    }

    /** Drags the bubble around; a tap (without movement) expands the window. */
    private class BubbleTouchListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                    }
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (appInForeground) {
                            // Inside the app: the bubble just reminds. Expanding would cover the app.
                            view.performClick()
                        } else {
                            expandNow()
                        }
                    }
                    return true
                }
            }
            return false
        }
    }
}
