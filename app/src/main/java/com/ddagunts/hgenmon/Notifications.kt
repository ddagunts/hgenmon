package com.ddagunts.hgenmon

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.ddagunts.hgenmon.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/**
 * App-wide notification helper. Three channels:
 *  - STATUS (low importance, ongoing): persistent "monitoring" card with live power + last refresh.
 *  - ALARM (high importance, sound+vibrate): generator alarms (warnings, faults, EWI), stop failure.
 *  - SUCCESS (low importance, silent): one-shot confirmations (stop succeeded).
 *  - CONNECTIVITY (high importance, sound+vibrate): unexpected disconnects.
 */
object Notifications {

    const val CHAN_STATUS = "hgenmon.status"
    const val CHAN_ALARM = "hgenmon.alarm"
    const val CHAN_SUCCESS = "hgenmon.success"
    const val CHAN_CONNECTIVITY = "hgenmon.connectivity"

    const val ID_STATUS = 1
    const val ID_ALARM = 2
    const val ID_CONNECTIVITY = 3
    const val ID_STOP_RESULT = 4

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHAN_STATUS, "Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live generator readings while connected."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHAN_ALARM, "Generator alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Low oil, warnings, faults, and engine-stop failures."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHAN_SUCCESS, "Confirmations", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet confirmations (e.g. engine stopped)."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHAN_CONNECTIVITY, "Connection lost", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifies when the connection to the generator drops unexpectedly."
            }
        )
    }

    fun hasPostPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Post or update the ongoing status notification. Call repeatedly with fresh values. */
    fun showStatus(
        ctx: Context,
        powerWatts: Float?,
        lastRefreshAt: Long?,
        nowMs: Long,
        countdownLine: String? = null,
    ) {
        if (!hasPostPermission(ctx)) return
        val title = powerWatts?.let { "%.0f W".format(it) } ?: "—"
        val refresh = lastRefreshAt?.let {
            "Updated ${formatAgo(it, nowMs)} (${formatClock(it)})"
        } ?: "Waiting for data…"
        val bodyLines = buildList {
            add(refresh)
            countdownLine?.let { add(it) }
        }.joinToString("\n")
        val whenTs = lastRefreshAt ?: System.currentTimeMillis()
        val n = NotificationCompat.Builder(ctx, CHAN_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(bodyLines.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyLines))
            .setOngoing(true)
            .setWhen(whenTs)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setContentIntent(launchPI(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_STATUS, n)
    }

    private fun formatAgo(epochMs: Long, nowMs: Long): String {
        val ageSec = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
        return when {
            ageSec < 5 -> "just now"
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
    }

    fun clearStatus(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_STATUS)
    }

    fun showAlarm(ctx: Context, title: String, body: String) {
        if (!hasPostPermission(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHAN_ALARM)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(launchPI(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_ALARM, n)
    }

    fun showConnectivityLoss(ctx: Context, label: String?) {
        if (!hasPostPermission(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHAN_CONNECTIVITY)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Connection lost")
            .setContentText(label?.let { "Lost link to $it" } ?: "Lost link to generator")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(launchPI(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_CONNECTIVITY, n)
    }

    fun showStopSuccess(ctx: Context) {
        if (!hasPostPermission(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHAN_SUCCESS)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("Engine stopped")
            .setContentText("Stop command acknowledged.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(launchPI(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_STOP_RESULT, n)
    }

    fun showStopFailure(ctx: Context, reason: String) {
        if (!hasPostPermission(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHAN_ALARM)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Engine stop FAILED")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(launchPI(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_STOP_RESULT, n)
    }

    fun formatClock(epochMs: Long): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(epochMs))

    private fun launchPI(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
