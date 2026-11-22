package com.xrmx.tides.alerts

import com.xrmx.tides.data.Storage
import com.xrmx.tides.engine.TideCalculations
import com.xrmx.tides.engine.TidePredictor
import com.xrmx.tides.engine.forDisplay
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.Station
import com.xrmx.tides.model.TideAlert
import com.xrmx.tides.model.TideType
import com.xrmx.tides.ui.components.formatHeight
import com.xrmx.tides.ui.components.formatShortDate
import com.xrmx.tides.ui.components.formatTime
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Works out which tide alerts to schedule. Shared by the app and the background refresh. */
object AlertPlanner {
    /** [model] must already be the display model (see [forDisplay]). */
    fun plan(
        station: Station,
        model: HarmonicModel,
        settings: AppSettings,
        nowSec: Long,
        timeZone: TimeZone,
        horizonDays: Int,
        maxAlerts: Int,
    ): List<TideAlert> {
        if (!settings.alertsEnabled) return emptyList()
        val lead = settings.alertLeadMinutes * 60L
        val predictor = TidePredictor(model)
        return TideCalculations.events(model, predictor, nowSec, nowSec + horizonDays * 86400L)
            .filter { (it.type == TideType.HIGH && settings.alertHigh) || (it.type == TideType.LOW && settings.alertLow) }
            .filter { it.epochSeconds - lead > nowSec }
            .take(maxAlerts)
            .mapIndexed { i, e ->
                val kind = if (e.type == TideType.HIGH) "High" else "Low"
                val day = formatShortDate(Instant.fromEpochSeconds(e.epochSeconds).toLocalDateTime(timeZone).date)
                TideAlert(
                    id = i,
                    atEpochMillis = (e.epochSeconds - lead) * 1000,
                    title = "$kind tide at ${station.name} in ${leadLabel(settings.alertLeadMinutes)}",
                    body = "$kind water $day ${formatTime(e.epochSeconds, timeZone)}, ${formatHeight(e.height, settings.units)} (predicted). Use as a guide only.",
                )
            }
    }

    /** Plans from what's saved on the device, for background refreshes. Null if alerts are off or there's no model. */
    fun planFromStorage(storage: Storage, nowSec: Long, timeZone: TimeZone, horizonDays: Int, maxAlerts: Int): List<TideAlert>? {
        val settings = storage.appSettings()
        if (!settings.alertsEnabled) return null
        val station = storage.selectedStation() ?: return null
        val model = storage.model(station.id)?.forDisplay(settings) ?: return null
        return plan(station, model, settings, nowSec, timeZone, horizonDays, maxAlerts)
    }

    private fun leadLabel(minutes: Int) = when {
        minutes % 60 == 0 && minutes >= 120 -> "${minutes / 60} hours"
        minutes == 60 -> "1 hour"
        else -> "$minutes min"
    }
}
