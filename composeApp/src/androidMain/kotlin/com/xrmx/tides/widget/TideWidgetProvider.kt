package com.xrmx.tides.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.xrmx.tides.MainActivity
import com.xrmx.tides.R
import com.xrmx.tides.data.Storage
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** Home-screen widget: level now and the next two tides at the selected station. Refreshes every 30 minutes. */
class TideWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** Redraws every placed widget, e.g. after the station or its model changes. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TideWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val summary = runCatching {
                WidgetSummaries.fromStorage(Storage(), Clock.System.now(), TimeZone.currentSystemDefault())
            }.getOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_tide)
            if (summary == null) {
                views.setTextViewText(R.id.widget_station, "Tides")
                views.setTextViewText(R.id.widget_now, "Open Tides and choose a station")
                views.setViewVisibility(R.id.widget_next1, View.GONE)
                views.setViewVisibility(R.id.widget_next2, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_station, summary.stationName)
                views.setTextViewText(R.id.widget_now, summary.now)
                listOf(R.id.widget_next1, R.id.widget_next2).forEachIndexed { i, id ->
                    val text = summary.upcoming.getOrNull(i)
                    views.setViewVisibility(id, if (text == null) View.GONE else View.VISIBLE)
                    views.setTextViewText(id, text.orEmpty())
                }
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
