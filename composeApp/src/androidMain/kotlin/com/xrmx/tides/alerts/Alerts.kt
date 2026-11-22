package com.xrmx.tides.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xrmx.tides.MainActivity
import com.xrmx.tides.R
import com.xrmx.tides.data.Storage
import com.xrmx.tides.model.TideAlert
import com.xrmx.tides.widget.TideWidgetProvider
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Schedules tide alerts with AlarmManager and remembers them so they survive a reboot. */
object AlertScheduler {
    private const val PREFS = "tide_alerts"
    private const val KEY_ALERTS = "alerts"
    private const val KEY_COUNT = "count"
    const val MAX_ALERTS = 40
    const val HORIZON_DAYS = 7
    internal const val EXTRA_ID = "id"
    internal const val EXTRA_TITLE = "title"
    internal const val EXTRA_BODY = "body"
    internal const val ACTION_REFRESH = "com.xrmx.tides.action.REFRESH_ALERTS"
    private const val REFRESH_REQUEST_CODE = 9000

    private val serializer = ListSerializer(TideAlert.serializer())

    fun schedule(context: Context, alerts: List<TideAlert>) {
        cancel(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val future = alerts.filter { it.atEpochMillis > now }.take(MAX_ALERTS)
        future.forEachIndexed { i, alert ->
            // Inexact but Doze-friendly; a few minutes' drift is fine for a "tide in 1 hour" reminder.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.atEpochMillis, pendingIntent(context, i, alert))
        }
        prefs(context).edit()
            .putString(KEY_ALERTS, Json.encodeToString(serializer, future))
            .putInt(KEY_COUNT, future.size)
            .apply()
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val count = prefs(context).getInt(KEY_COUNT, 0)
        repeat(count) { i ->
            val existing = PendingIntent.getBroadcast(
                context, i, Intent(context, TideAlertReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (existing != null) {
                alarms.cancel(existing)
                existing.cancel()
            }
        }
        prefs(context).edit().remove(KEY_ALERTS).putInt(KEY_COUNT, 0).apply()
    }

    /**
     * Recomputes alerts from the saved station and model, without opening the app. Alerts are only
     * scheduled a week ahead, so this runs daily, after a reboot, after an app update (which
     * clears alarms) and when the time zone changes. Falls back to the stored list if there's no model.
     */
    fun refresh(context: Context) {
        val planned = runCatching {
            AlertPlanner.planFromStorage(
                Storage(), Clock.System.now().epochSeconds, TimeZone.currentSystemDefault(), HORIZON_DAYS, MAX_ALERTS,
            )
        }.getOrNull()
        when {
            planned != null -> schedule(context, planned)
            Storage().appSettings().alertsEnabled -> restore(context)
            else -> cancel(context)
        }
        TideWidgetProvider.updateAll(context)
    }

    /** A once-a-day, inexact wake-up that calls [refresh]. Re-registering is harmless. */
    fun ensureDailyRefresh(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlertRefreshReceiver::class.java).setAction(ACTION_REFRESH)
        val pending = PendingIntent.getBroadcast(
            context, REFRESH_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setInexactRepeating(
            AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY, AlarmManager.INTERVAL_DAY, pending,
        )
    }

    private fun restore(context: Context) {
        val stored = prefs(context).getString(KEY_ALERTS, null) ?: return
        val alerts = runCatching { Json.decodeFromString(serializer, stored) }.getOrNull() ?: return
        schedule(context, alerts)
    }

    private fun pendingIntent(context: Context, requestCode: Int, alert: TideAlert): PendingIntent {
        val intent = Intent(context, TideAlertReceiver::class.java)
            .putExtra(EXTRA_ID, alert.id)
            .putExtra(EXTRA_TITLE, alert.title)
            .putExtra(EXTRA_BODY, alert.body)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class TideAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tide alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = intent.getStringExtra(AlertScheduler.EXTRA_BODY).orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tide)
            .setContentTitle(intent.getStringExtra(AlertScheduler.EXTRA_TITLE) ?: "Tide alert")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_BASE + intent.getIntExtra(AlertScheduler.EXTRA_ID, 0), notification)
    }

    private companion object {
        const val CHANNEL_ID = "tide_alerts"
        const val NOTIFICATION_BASE = 1000
    }
}

/** Reschedules alerts after a reboot, an app update, a clock or time zone change, and once a day. */
class AlertRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlertScheduler.ACTION_REFRESH,
            -> Unit
            else -> return
        }
        val pending = goAsync()
        Thread {
            try {
                AlertScheduler.ensureDailyRefresh(context)
                AlertScheduler.refresh(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
