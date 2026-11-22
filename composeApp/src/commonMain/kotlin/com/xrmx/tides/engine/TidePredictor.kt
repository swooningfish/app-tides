package com.xrmx.tides.engine

import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TidePoint
import com.xrmx.tides.model.TideType
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Predicts water levels from a [HarmonicModel]:
 * h(t) = Z0 + Σ f·H·cos(V(t) + u − g)
 */
class TidePredictor(model: HarmonicModel) {
    private class Term(val constituent: Constituent, val amplitude: Double, val phaseDeg: Double)

    private val z0 = model.z0
    private val terms: List<Term> = model.constituents.mapNotNull { c ->
        Constituents.named(c.name)?.let { Term(it, c.amplitude, c.phaseDeg) }
    }

    private val omegas = DoubleArray(terms.size) { rad(terms[it].constituent.speed) }

    /**
     * Nodal factors change over an 18.6-year cycle, so one set per UTC day is plenty and saves
     * most of the trigonometry. Swapped as a whole, so concurrent readers never see a half-built set.
     */
    private class NodalDay(val day: Long, val f: DoubleArray, val u: DoubleArray)

    private var nodalCache: NodalDay? = null

    private fun nodalFor(epochSeconds: Double, astro: Astro): NodalDay {
        val day = floor(epochSeconds / 86400.0).toLong()
        nodalCache?.let { if (it.day == day) return it }
        val f = DoubleArray(terms.size)
        val u = DoubleArray(terms.size)
        terms.forEachIndexed { i, t ->
            val nodal = t.constituent.nodal(astro.n)
            f[i] = nodal.f
            u[i] = nodal.u
        }
        return NodalDay(day, f, u).also { nodalCache = it }
    }

    /** Height in metres above the model datum. */
    fun height(epochSeconds: Double): Double {
        val astro = Astro(epochSeconds)
        val nodal = nodalFor(epochSeconds, astro)
        var h = z0
        for (i in terms.indices) {
            val t = terms[i]
            h += nodal.f[i] * t.amplitude * cos(rad(t.constituent.equilibriumArgument(astro) + nodal.u[i] - t.phaseDeg))
        }
        return h
    }

    /** Rate of change in metres per hour; positive while the tide is rising. */
    fun rate(epochSeconds: Double): Double {
        val astro = Astro(epochSeconds)
        val nodal = nodalFor(epochSeconds, astro)
        var r = 0.0
        for (i in terms.indices) {
            val t = terms[i]
            r -= nodal.f[i] * t.amplitude * omegas[i] * sin(rad(t.constituent.equilibriumArgument(astro) + nodal.u[i] - t.phaseDeg))
        }
        return r
    }

    /** Lowest level over [days] from [fromEpoch]: an estimate of Lowest Astronomical Tide (Chart Datum). */
    fun lowestLevel(fromEpoch: Long, days: Int = 366): Double {
        var minT = fromEpoch
        var minH = Double.MAX_VALUE
        val end = fromEpoch + days * 86400L
        var t = fromEpoch
        while (t < end) {
            val h = height(t.toDouble())
            if (h < minH) { minH = h; minT = t }
            t += 900
        }
        // Refine around the coarse minimum to the minute.
        var r = minT - 900
        while (r <= minT + 900) {
            minH = minOf(minH, height(r.toDouble()))
            r += 60
        }
        return minH
    }

    fun series(fromEpoch: Long, toEpoch: Long, stepSeconds: Long): List<TidePoint> {
        val points = ArrayList<TidePoint>(((toEpoch - fromEpoch) / stepSeconds + 1).toInt())
        var t = fromEpoch
        while (t <= toEpoch) {
            points += TidePoint(t, height(t.toDouble()))
            t += stepSeconds
        }
        return points
    }

    /** High and low waters in [fromEpoch, toEpoch), found where the rate changes sign. */
    fun extremes(fromEpoch: Long, toEpoch: Long): List<TideEvent> {
        val step = 600L
        val events = mutableListOf<TideEvent>()
        var t0 = fromEpoch.toDouble()
        var r0 = rate(t0)
        while (t0 < toEpoch) {
            val t1 = t0 + step
            val r1 = rate(t1)
            val type = when {
                r0 > 0 && r1 <= 0 -> TideType.HIGH
                r0 < 0 && r1 >= 0 -> TideType.LOW
                else -> null
            }
            if (type != null) {
                var lo = t0
                var hi = t1
                var rLo = r0
                repeat(12) {
                    val mid = (lo + hi) / 2
                    val rMid = rate(mid)
                    if ((rLo > 0) == (rMid > 0)) {
                        lo = mid
                        rLo = rMid
                    } else {
                        hi = mid
                    }
                }
                val te = (lo + hi) / 2
                if (te >= fromEpoch && te < toEpoch) {
                    events += TideEvent(te.toLong(), height(te), type)
                }
            }
            t0 = t1
            r0 = r1
        }
        return events
    }
}
