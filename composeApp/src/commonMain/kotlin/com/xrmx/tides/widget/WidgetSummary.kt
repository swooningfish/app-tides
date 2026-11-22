package com.xrmx.tides.widget

import com.xrmx.tides.data.Storage
import com.xrmx.tides.engine.TideCalculations
import com.xrmx.tides.engine.TidePredictor
import com.xrmx.tides.engine.forDisplay
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.Station
import com.xrmx.tides.model.TideType
import com.xrmx.tides.ui.components.formatHeight
import com.xrmx.tides.ui.components.formatTime
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** What a home-screen widget shows: the selected station, the level now and the next two tides. */
data class WidgetSummary(
    val stationName: String,
    val now: String,
    val upcoming: List<String>,
)

object WidgetSummaries {
    fun build(station: Station, model: HarmonicModel, settings: AppSettings, now: Instant, timeZone: TimeZone): WidgetSummary {
        val predictor = TidePredictor(model)
        val status = TideCalculations.now(model, predictor, now)
        val t = now.epochSeconds
        val upcoming = TideCalculations.events(model, predictor, t, t + 26 * 3600).take(2).map { e ->
            val kind = if (e.type == TideType.HIGH) "High" else "Low"
            "$kind ${formatTime(e.epochSeconds, timeZone)} · ${formatHeight(e.height, settings.units)}"
        }
        return WidgetSummary(
            stationName = station.name,
            now = "Now ${formatHeight(status.height, settings.units)} · ${if (status.rising) "rising" else "falling"}",
            upcoming = upcoming,
        )
    }

    /** From saved data; null if no station has been chosen and synced yet. */
    fun fromStorage(storage: Storage, now: Instant, timeZone: TimeZone): WidgetSummary? {
        val settings = storage.appSettings()
        val station = storage.selectedStation() ?: return null
        val model = storage.model(station.id)?.forDisplay(settings) ?: return null
        return build(station, model, settings, now, timeZone)
    }
}
