package com.xrmx.tides.engine

import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TidePoint
import com.xrmx.tides.model.TideType
import kotlin.math.cos
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

    /** Height in metres above the model datum. */
    fun height(epochSeconds: Double): Double {
        val astro = Astro(epochSeconds)
        var h = z0
        for (t in terms) {
            val nodal = t.constituent.nodal(astro.n)
            h += nodal.f * t.amplitude * cos(rad(t.constituent.equilibriumArgument(astro) + nodal.u - t.phaseDeg))
        }
        return h
    }

    /** Rate of change in metres per hour; positive while the tide is rising. */
    fun rate(epochSeconds: Double): Double {
        val astro = Astro(epochSeconds)
        var r = 0.0
        for (t in terms) {
            val nodal = t.constituent.nodal(astro.n)
            val omega = rad(t.constituent.speed)
            r -= nodal.f * t.amplitude * omega * sin(rad(t.constituent.equilibriumArgument(astro) + nodal.u - t.phaseDeg))
        }
        return r
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
