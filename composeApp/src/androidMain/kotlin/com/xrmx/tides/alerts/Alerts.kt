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
import com.xrmx.tides.model.TideAlert
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Schedules tide alerts with AlarmManager and remembers them so they survive a reboot. */
object AlertScheduler {
    private const val PREFS = "tide_alerts"
    private const val KEY_ALERTS = "alerts"
    private const val KEY_COUNT = "count"
    private const val MAX_ALERTS = 40
    internal const val EXTRA_ID = "id"
    internal const val EXTRA_TITLE = "title"
    internal const val EXTRA_BODY = "body"

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

    fun restore(context: Context) {
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlertScheduler.restore(context)
    }
}
