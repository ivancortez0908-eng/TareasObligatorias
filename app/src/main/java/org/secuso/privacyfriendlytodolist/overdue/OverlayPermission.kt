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
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.secuso.privacyfriendlytodolist.R
import org.secuso.privacyfriendlytodolist.util.LogTag
import org.secuso.privacyfriendlytodolist.util.PreferenceMgr

/**
 * Helper for the "display over other apps" permission (SYSTEM_ALERT_WINDOW).
 */
object OverlayPermission {
    private val TAG = LogTag.create(this::class.java)

    /** Ask at most once per process start, so the user is not nagged on every resume. */
    private var askedThisSession = false

    fun canDrawOverlays(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun openPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName))
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Overlay permission settings not available: $e")
        }
    }

    /**
     * Shows an explanation dialog and opens the system settings if the permission is missing.
     * @param force If true the dialog is shown even if it was already shown in this session.
     */
    fun checkForPermission(activity: Activity, force: Boolean = false) {
        if (!PreferenceMgr.isOverdueEnabled(activity) || canDrawOverlays(activity)) {
            return
        }
        if (askedThisSession && !force) {
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
            show()
        }
    }
}
