package com.xrmx.tides.engine

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class SunTimes(val sunrise: Instant?, val sunset: Instant?)

data class MoonInfo(
    val phaseName: String,
    /** Fraction of the disc lit, 0..1. */
    val illumination: Double,
    /** Days since new moon. */
    val ageDays: Double,
    val waxing: Boolean,
    val tideHint: String,
)

object SunMoon {
    const val SYNODIC_MONTH = 29.530588853
    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5

    /** Sunrise and sunset for a calendar date at a location (sunrise equation, ~1 minute accuracy). */
    fun sunTimes(date: LocalDate, latitude: Double, longitude: Double): SunTimes {
        val n = (date.toEpochDays() - 10957).toDouble()
        val jStar = n - longitude / 360.0
        val m = normDeg(357.5291 + 0.98560028 * jStar)
        val c = 1.9148 * sin(rad(m)) + 0.0200 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = normDeg(m + c + 180.0 + 102.9372)
        val jTransit = J2000 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val sinDecl = sin(rad(lambda)) * sin(rad(23.4397))
        val decl = asin(sinDecl)
        val cosOmega = (sin(rad(-0.833)) - sin(rad(latitude)) * sinDecl) / (cos(rad(latitude)) * cos(decl))
        if (cosOmega < -1 || cosOmega > 1) return SunTimes(null, null)
        val omegaDeg = acos(cosOmega) * 180.0 / PI
        return SunTimes(
            sunrise = julianToInstant(jTransit - omegaDeg / 360.0),
            sunset = julianToInstant(jTransit + omegaDeg / 360.0),
        )
    }

    fun moon(at: Instant): MoonInfo {
        val jd = at.epochSeconds / 86400.0 + UNIX_EPOCH_JD
        val age = ((jd - 2451550.1) % SYNODIC_MONTH + SYNODIC_MONTH) % SYNODIC_MONTH
        val angle = 2 * PI * age / SYNODIC_MONTH
        val illumination = (1 - cos(angle)) / 2
        val eighth = SYNODIC_MONTH / 8
        val name = when {
            age < eighth / 2 -> "New moon"
            age < eighth * 1.5 -> "Waxing crescent"
            age < eighth * 2.5 -> "First quarter"
            age < eighth * 3.5 -> "Waxing gibbous"
            age < eighth * 4.5 -> "Full moon"
            age < eighth * 5.5 -> "Waning gibbous"
            age < eighth * 6.5 -> "Last quarter"
            age < eighth * 7.5 -> "Waning crescent"
            else -> "New moon"
        }
        // Spring tides lag new and full moon by roughly a day and a half.
        val half = SYNODIC_MONTH / 2
        val x = ((age - 1.5) % half + half) % half
        val fromSprings = min(x, half - x)
        val hint = when {
            fromSprings < 2.5 -> "Spring tides: larger range"
            fromSprings > half / 2 - 2.5 -> "Neap tides: smaller range"
            abs(x) < half / 2 -> "Heading towards neaps"
            else -> "Heading towards springs"
        }
        return MoonInfo(name, illumination, age, age < half, hint)
    }

    private fun julianToInstant(jd: Double): Instant =
        Instant.fromEpochSeconds(((jd - UNIX_EPOCH_JD) * 86400.0).toLong())
}
