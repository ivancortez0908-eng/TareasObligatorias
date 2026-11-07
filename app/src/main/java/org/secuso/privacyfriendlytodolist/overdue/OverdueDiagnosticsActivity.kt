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
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.secuso.privacyfriendlytodolist.R
import java.text.DateFormat
import java.util.Date

/**
 * "Alert diagnostics": shows every condition an overdue alert depends on, with a button to fix
 * each failing one, what the checker did last, and a button to fire a test alert.
 */
class OverdueDiagnosticsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.diag_title)
        val padding = (16 * resources.displayMetrics.density).toInt()
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(container)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        addHeader(getString(R.string.diag_title))
        addText(getString(R.string.diag_intro))

        // Device
        val lowRam = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        addText(getString(R.string.diag_device, Build.MANUFACTURER + " " + Build.MODEL,
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT) +
                if (lowRam) "\n" + getString(R.string.diag_android_go) else "")

        addSection(getString(R.string.diag_section_permissions))

        // 1. Notifications allowed at all
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED)
        addCheck(R.string.diag_notifications, notificationsEnabled, R.string.diag_fix) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppNotificationSettings()
            }
        }

        // 2. The "overdue tasks" channel is not muted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(OverdueNotifier.CHANNEL)
            // A channel that was not created yet will be created with high importance.
            val channelOk = channel == null || channel.importance >= NotificationManager.IMPORTANCE_HIGH
            addCheck(R.string.diag_channel, channelOk, R.string.diag_fix) {
                startSafely(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, OverdueNotifier.CHANNEL))
            }
        }

        // 3. Exact alarms
        addCheck(R.string.diag_exact_alarms, OverdueChecker.canScheduleExactAlarms(this), R.string.diag_fix) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            }
        }

        // 4. Full screen alerts
        addCheck(R.string.diag_full_screen, OverlayPermission.canUseFullScreenIntent(this), R.string.diag_fix) {
            OverlayPermission.openFullScreenIntentSettings(this)
        }

        // 5. Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            addCheck(R.string.diag_battery, power.isIgnoringBatteryOptimizations(packageName),
                R.string.diag_fix) { requestIgnoreBatteryOptimizations() }
        }

        // 6. Background restriction (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val restricted = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
            addCheck(R.string.diag_background, !restricted, R.string.diag_fix) { openAppDetails() }
        }

        // 7. Floating window (info only on Android Go)
        if (OverlayPermission.isOverlaySupported(this)) {
            addCheck(R.string.diag_overlay, OverlayPermission.canDrawOverlays(this), R.string.diag_fix) {
                OverlayPermission.openPermissionSettings(this)
            }
        } else {
            addText("ℹ " + getString(R.string.diag_overlay_go))
        }

        // Last activity
        addSection(getString(R.string.diag_section_activity))
        val prefs = OverdueDiagnostics.prefs(this)
        addText(getString(R.string.diag_last_check,
            formatTime(prefs.getLong(OverdueDiagnostics.LAST_CHECK_TIME, 0L)),
            prefs.getString(OverdueDiagnostics.LAST_CHECK_REASON, "-"),
            prefs.getInt(OverdueDiagnostics.LAST_CHECK_TASKS, 0),
            prefs.getInt(OverdueDiagnostics.LAST_CHECK_OVERDUE, 0)))
        addText(getString(R.string.diag_next_alarm,
            formatTime(prefs.getLong(OverdueDiagnostics.NEXT_ALARM_TIME, 0L)),
            prefs.getString(OverdueDiagnostics.NEXT_ALARM_METHOD, "-")))
        addText(getString(R.string.diag_last_alarm,
            formatTime(prefs.getLong(OverdueDiagnostics.LAST_ALARM_TIME, 0L)),
            prefs.getString(OverdueDiagnostics.LAST_ALARM_TYPE, "-")))
        addText(getString(R.string.diag_last_notification,
            formatTime(prefs.getLong(OverdueDiagnostics.LAST_NOTIFICATION_TIME, 0L)),
            prefs.getString(OverdueDiagnostics.LAST_NOTIFICATION_INFO, "-")))

        // Actions
        addSection(getString(R.string.diag_section_test))
        addText(getString(R.string.diag_test_explanation))
        addButton(R.string.diag_test_button) {
            OverdueChecker.scheduleTestAlert(this, 15)
            Toast.makeText(this, R.string.diag_test_scheduled, Toast.LENGTH_LONG).show()
        }
        addButton(R.string.diag_check_now) {
            OverdueChecker.check(this, "diagnostics") { refresh() }
        }
    }

    private fun formatTime(millis: Long): String {
        return if (millis <= 0L) "-" else
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))
    }

    private fun addHeader(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun addSection(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 4)
        })
    }

    private fun addText(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 6, 0, 6)
        })
    }

    private fun addCheck(labelId: Int, ok: Boolean, fixLabelId: Int, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
        }
        row.addView(TextView(this).apply {
            text = (if (ok) "✅ " else "❌ ") + getString(labelId)
            textSize = 15f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) {
            row.addView(Button(this).apply {
                setText(fixLabelId)
                isAllCaps = false
                setOnClickListener { fix() }
            })
        }
        container.addView(row)
    }

    private fun addButton(labelId: Int, action: () -> Unit) {
        container.addView(Button(this).apply {
            setText(labelId)
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun openAppNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            openAppDetails()
        }
    }

    @SuppressLint("BatteryLife") // Sideloaded personal reminder app; the user asks for it explicitly.
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
    }
}
