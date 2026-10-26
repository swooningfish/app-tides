package com.xrmx.tides.ui.components

import com.xrmx.tides.model.Units
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

const val METRES_TO_FEET = 3.28084

fun toDisplayUnits(metres: Double, units: Units): Double = if (units == Units.FEET) metres * METRES_TO_FEET else metres

fun unitSuffix(units: Units): String = if (units == Units.FEET) "ft" else "m"

fun formatHeight(metres: Double, units: Units): String = when (units) {
    Units.METRES -> "${formatDecimal(metres, 2)} m"
    Units.FEET -> "${formatDecimal(metres * METRES_TO_FEET, 1)} ft"
}

/** Locale-independent fixed-point formatting (String.format isn't available in common code). */
fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor
    val frac = scaled % factor
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}

private fun two(n: Int) = n.toString().padStart(2, '0')

fun formatTime(epochSeconds: Long, tz: TimeZone): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(tz)
    return "${two(t.hour)}:${two(t.minute)}"
}

fun formatTime(instant: Instant, tz: TimeZone): String = formatTime(instant.epochSeconds, tz)

fun shortDayName(date: LocalDate): String = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

fun shortMonthName(date: LocalDate): String = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

/** e.g. "Mon 26 Oct" */
fun formatShortDate(date: LocalDate): String = "${shortDayName(date)} ${date.dayOfMonth} ${shortMonthName(date)}"

/** e.g. "Monday 26 October 2026" */
fun formatLongDate(date: LocalDate): String {
    val day = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$day ${date.dayOfMonth} $month ${date.year}"
}

fun formatDuration(seconds: Long): String {
    val minutes = (seconds / 60).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun formatDateTime(epochSeconds: Long, tz: TimeZone): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(tz)
    return "${formatShortDate(t.date)} ${two(t.hour)}:${two(t.minute)}"
}
