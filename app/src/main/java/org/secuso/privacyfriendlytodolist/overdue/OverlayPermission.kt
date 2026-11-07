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

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.util.LogTag
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr

/**
 * Helper for the "display over other apps" permission (SYSTEM_ALERT_WINDOW) and, where the
 * floating window is not possible, for the full screen alert (USE_FULL_SCREEN_INTENT).
 */
object OverlayPermission {
    private val TAG = LogTag.create(this::class.java)

    /** Ask at most once per process start, so the user is not nagged on every resume. */
    private var askedThisSession = false

    /**
     * Android Go (low RAM) devices do not allow "display over other apps" for any app
     * ("Feature not available - it slows down the phone").
     */
    fun isOverlaySupported(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return !manager.isLowRamDevice
    }

    /** True if the floating window shall be used: supported on this device and enabled by the user. */
    fun isOverlayUsed(context: Context): Boolean {
        return isOverlaySupported(context) && PreferenceMgr.isOverdueOverlayUsed(context)
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    /** True if the floating window can be shown right now (used, supported and permitted). */
    fun isOverlayActive(context: Context): Boolean {
        return isOverlayUsed(context) && canDrawOverlays(context)
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.canUseFullScreenIntent()
    }

    private fun startSettings(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Settings not available: $e")
            // Fallback: the app details page.
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun openPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        startSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)))
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        startSettings(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:" + context.packageName)))
    }

    /**
     * Shows an explanation dialog and opens the system settings if a needed permission is missing:
     * "display over other apps" for the floating window, otherwise "full screen alerts".
     * @param force If true the dialog is shown even if it was already shown in this session.
     */
    fun checkForPermission(activity: Activity, force: Boolean = false) {
        if (!PreferenceMgr.isOverdueEnabled(activity) || (askedThisSession && !force)) {
            return
        }
        if (isOverlayUsed(activity)) {
            if (canDrawOverlays(activity)) {
                return
            }
            askedThisSession = true
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(R.string.overdue_permission_dialog_title)
                setMessage(R.string.overdue_permission_dialog_message)
                setPositiveButton(R.string.overdue_permission_dialog_ok) { _, _ ->
                    openPermissionSettings(activity)
                }
                setNegativeButton(R.string.overdue_permission_dialog_later, null)
                setNeutralButton(R.string.overdue_permission_dialog_notification_only) { _, _ ->
                    PreferenceManager.getDefaultSharedPreferences(activity).edit {
                        putBoolean(PreferenceMgr.P_OVERDUE_USE_OVERLAY.name, false)
                    }
                    OverdueChecker.check(activity, "overlay disabled by user")
                }
                show()
            }
        } else if (!canUseFullScreenIntent(activity)) {
            askedThisSession = true
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(R.string.overdue_fullscreen_dialog_title)
                setMessage(R.string.overdue_fullscreen_dialog_message)
                setPositiveButton(R.string.overdue_permission_dialog_ok) { _, _ ->
                    openFullScreenIntentSettings(activity)
                }
                setNegativeButton(R.string.overdue_permission_dialog_later, null)
                show()
            }
        }
    }
}
