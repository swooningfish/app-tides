package com.xrmx.tides.engine

import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.Station
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TidePoint
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

data class DayData(
    val date: LocalDate,
    val startEpoch: Long,
    val endEpoch: Long,
    val points: List<TidePoint>,
    val events: List<TideEvent>,
    val sun: SunTimes,
    val moon: MoonInfo,
    val nights: List<Pair<Long, Long>>,
)

data class WeekData(
    val days: List<DayData>,
    val startEpoch: Long,
    val endEpoch: Long,
    val points: List<TidePoint>,
    val nights: List<Pair<Long, Long>>,
)

data class NowStatus(val height: Double, val rising: Boolean, val next: TideEvent?)

object TideCalculations {
    fun day(model: HarmonicModel, predictor: TidePredictor, station: Station, date: LocalDate, tz: TimeZone, stepSeconds: Long = 300): DayData {
        val start = date.atStartOfDayIn(tz).epochSeconds
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz).epochSeconds
        return DayData(
            date = date,
            startEpoch = start,
            endEpoch = end,
            points = predictor.series(start, end, stepSeconds),
            events = events(model, predictor, start, end),
            sun = SunMoon.sunTimes(date, station.latitude, station.longitude),
            moon = SunMoon.moon(date.atTime(LocalTime(12, 0)).toInstant(tz)),
            nights = nights(station, date, date.plus(1, DateTimeUnit.DAY), start, end),
        )
    }

    fun week(model: HarmonicModel, predictor: TidePredictor, station: Station, startDate: LocalDate, tz: TimeZone): WeekData {
        val days = (0 until 7).map { day(model, predictor, station, startDate.plus(it, DateTimeUnit.DAY), tz, stepSeconds = 1200) }
        val start = days.first().startEpoch
        val end = days.last().endEpoch
        return WeekData(
            days = days,
            startEpoch = start,
            endEpoch = end,
            points = predictor.series(start, end, 900),
            nights = nights(station, startDate, startDate.plus(7, DateTimeUnit.DAY), start, end),
        )
    }

    /**
     * High and low waters in a window. Where the upstream source supplied reference events
     * (e.g. UK Admiralty) they are used for the period they cover; the model fills the rest.
     */
    fun events(model: HarmonicModel, predictor: TidePredictor, from: Long, to: Long): List<TideEvent> {
        val refs = model.referenceEvents.sortedBy { it.epochSeconds }
        val predicted = predictor.extremes(from, to)
        if (refs.size < 2) return predicted
        val refStart = refs.first().epochSeconds - 3 * 3600
        val refEnd = refs.last().epochSeconds + 3 * 3600
        val fromRefs = refs.filter { it.epochSeconds in from until to }
        val fromModel = predicted.filter { it.epochSeconds < refStart || it.epochSeconds > refEnd }
        return (fromRefs + fromModel).sortedBy { it.epochSeconds }
    }

    fun now(model: HarmonicModel, predictor: TidePredictor, now: Instant): NowStatus {
        val t = now.epochSeconds
        val next = events(model, predictor, t, t + 26 * 3600).firstOrNull()
        return NowStatus(predictor.height(t.toDouble()), predictor.rate(t.toDouble()) > 0, next)
    }

    /** Night spans (sunset to next sunrise) overlapping [from, to]. */
    private fun nights(station: Station, firstDate: LocalDate, endDate: LocalDate, from: Long, to: Long): List<Pair<Long, Long>> {
        val spans = mutableListOf<Pair<Long, Long>>()
        var d = firstDate.plus(-1, DateTimeUnit.DAY)
        while (d <= endDate) {
            val today = SunMoon.sunTimes(d, station.latitude, station.longitude)
            val tomorrow = SunMoon.sunTimes(d.plus(1, DateTimeUnit.DAY), station.latitude, station.longitude)
            val set = today.sunset?.epochSeconds
            val rise = tomorrow.sunrise?.epochSeconds
            if (set != null && rise != null && rise > from && set < to) {
                spans += maxOf(set, from) to minOf(rise, to)
            }
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return spans
    }
}
