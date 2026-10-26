package com.xrmx.tides.engine

import kotlin.math.PI

/**
 * Mean astronomical arguments (degrees) at an instant, used to compute the equilibrium
 * argument of each tidal constituent. Polynomials from Meeus, truncated to linear terms,
 * which is ample for tide prediction.
 */
internal class Astro(epochSeconds: Double) {
    /** Mean lunar time. */
    val tau: Double
    /** Mean longitude of the Moon. */
    val s: Double
    /** Mean longitude of the Sun. */
    val h: Double
    /** Longitude of lunar perigee. */
    val p: Double
    /** Longitude of the Moon's ascending node. */
    val n: Double
    /** Longitude of solar perigee. */
    val p1: Double

    init {
        val t = (epochSeconds - J2000_EPOCH_SECONDS) / 86400.0 / 36525.0
        s = normDeg(218.3164477 + 481267.88123421 * t)
        h = normDeg(280.46646 + 36000.76983 * t)
        p = normDeg(83.3532465 + 4069.0137287 * t)
        n = normDeg(125.04452 - 1934.136261 * t)
        p1 = normDeg(282.93735 + 1.71946 * t)
        val utHours = ((epochSeconds % 86400.0) + 86400.0) % 86400.0 / 3600.0
        val meanSunHourAngle = 180.0 + 15.0 * utHours
        tau = normDeg(meanSunHourAngle + h - s)
    }

    fun argument(i: Int): Double = when (i) {
        0 -> tau
        1 -> s
        2 -> h
        3 -> p
        4 -> n
        else -> p1
    }

    companion object {
        const val J2000_EPOCH_SECONDS = 946728000.0

        /** Rates of change of tau, s, h, p, N, p1 in degrees per hour. */
        val RATES = doubleArrayOf(14.4920521, 0.5490165, 0.0410686, 0.0046418, -0.0022064, 0.0000020)
    }
}

internal fun normDeg(deg: Double): Double {
    val r = deg % 360.0
    return if (r < 0) r + 360.0 else r
}

internal fun rad(deg: Double): Double = deg * PI / 180.0
