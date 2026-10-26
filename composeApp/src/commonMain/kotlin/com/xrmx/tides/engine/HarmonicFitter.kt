package com.xrmx.tides.engine

import com.xrmx.tides.model.ConstituentValue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Derives harmonic constants from reference data by least squares.
 *
 * Two kinds of input are accepted: a time series of observed or predicted heights, and
 * high/low water events. Each event contributes its height and a "rate is zero here"
 * constraint, which pins down the curve shape from a short list of events.
 */
internal object HarmonicFitter {
    class Sample(val epochSeconds: Long, val height: Double)

    class Result(
        val z0: Double,
        val constituents: List<ConstituentValue>,
        val rms: Double,
        val spanDays: Double,
        val inferred: List<String>,
    )

    private class Row(val values: DoubleArray, val target: Double, val isHeight: Boolean)

    fun fit(heights: List<Sample>, extremes: List<Sample> = emptyList()): Result {
        val times = (heights + extremes).map { it.epochSeconds }
        require(times.size >= 8) { "Not enough reference data to calibrate this station." }
        val spanHours = (times.max() - times.min()) / 3600.0
        require(spanHours >= 48) { "Reference data covers less than two days, which is too short to calibrate." }

        val longRecord = spanHours >= 24 * 14 && heights.isNotEmpty()
        val candidates = if (longRecord) Constituents.fitPriority else Constituents.shortRecord
        val rayleigh = if (longRecord) 1.0 else 0.45
        val rowCount = heights.size + extremes.size * 2
        val maxConstituents = min(if (longRecord) 24 else 7, (rowCount - 1) / 3)

        val chosen = mutableListOf<Constituent>()
        for (c in candidates) {
            if (chosen.size >= maxConstituents) break
            val resolvesFromMean = c.speed * spanHours >= 360 * rayleigh
            val resolvesFromOthers = chosen.all { abs(it.speed - c.speed) * spanHours >= 360 * rayleigh }
            if (resolvesFromMean && resolvesFromOthers) chosen += c
        }
        require(chosen.isNotEmpty() && chosen.first().name == "M2") { "Reference data could not resolve the main tide." }

        val links = linksFor(chosen)
        var rows = buildRows(chosen, links, heights, extremes)
        val ridge = (if (heights.isEmpty()) 0.01 else 0.001) * rows.size
        var solution = solveNormal(rows, chosen.size, ridge)
        var rms = rms(rows, solution)

        // One pass of outlier rejection (gauge spikes, bad readings).
        if (rms > 0) {
            val kept = rows.filter { !it.isHeight || abs(residual(it, solution)) <= 4 * rms }
            if (kept.size < rows.size && kept.size > chosen.size * 2 + 1) {
                rows = kept
                solution = solveNormal(rows, chosen.size, ridge)
                rms = rms(rows, solution)
            }
        }

        val values = mutableListOf<ConstituentValue>()
        val inferred = mutableListOf<String>()
        chosen.forEachIndexed { i, c ->
            val a = solution[1 + 2 * i]
            val b = solution[2 + 2 * i]
            val amplitude = hypot(a, b)
            val phase = normDeg(atan2(b, a) * 180.0 / kotlin.math.PI)
            values += ConstituentValue(c.name, amplitude, phase)
            for (link in links[i]) {
                values += ConstituentValue(link.constituent.name, amplitude * link.ratio, normDeg(phase - link.deltaDeg))
                inferred += link.constituent.name
            }
        }

        return Result(
            z0 = solution[0],
            constituents = values,
            rms = rms,
            spanDays = spanHours / 24.0,
            inferred = inferred,
        )
    }

    /**
     * A constituent the record is too short to separate, tied to a resolved neighbour with a
     * typical amplitude ratio and phase offset (standard tidal inference). Its amplitude is
     * ratio x the neighbour's, and its phase lag is the neighbour's minus [deltaDeg].
     */
    private class Link(val constituent: Constituent, val ratio: Double, val deltaDeg: Double)

    private fun link(name: String, ratio: Double, deltaDeg: Double) = Link(Constituents.named(name)!!, ratio, deltaDeg)

    private val inferenceRules: Map<String, List<Link>> by lazy {
        mapOf(
            "M2" to listOf(link("S2", 0.33, -35.0), link("N2", 0.194, 20.0)),
            "S2" to listOf(link("K2", 0.27, 0.0)),
            "K1" to listOf(link("P1", 0.33, 0.0)),
            "O1" to listOf(link("Q1", 0.19, 20.0)),
        )
    }

    private fun linksFor(chosen: List<Constituent>): List<List<Link>> {
        val names = chosen.map { it.name }.toSet()
        return chosen.map { c -> inferenceRules[c.name].orEmpty().filter { it.constituent.name !in names } }
    }

    private fun buildRows(chosen: List<Constituent>, links: List<List<Link>>, heights: List<Sample>, extremes: List<Sample>): List<Row> {
        val p = 1 + 2 * chosen.size
        val rows = ArrayList<Row>(heights.size + extremes.size * 2)
        // Each column pair is the primary constituent plus any linked ones, which share its unknowns.
        fun row(t: Long, rate: Boolean): DoubleArray {
            val astro = Astro(t.toDouble())
            val v = DoubleArray(p)
            if (!rate) v[0] = 1.0
            chosen.forEachIndexed { i, c ->
                val terms = listOf(Link(c, 1.0, 0.0)) + links[i]
                for (term in terms) {
                    val k = term.constituent
                    val nodal = k.nodal(astro.n)
                    val theta = rad(k.equilibriumArgument(astro) + nodal.u + term.deltaDeg)
                    val scale = term.ratio * nodal.f
                    if (rate) {
                        val omega = rad(k.speed)
                        v[1 + 2 * i] += -scale * omega * sin(theta)
                        v[2 + 2 * i] += scale * omega * cos(theta)
                    } else {
                        v[1 + 2 * i] += scale * cos(theta)
                        v[2 + 2 * i] += scale * sin(theta)
                    }
                }
            }
            return v
        }
        for (s in heights) rows += Row(row(s.epochSeconds, rate = false), s.height, true)
        for (s in extremes) {
            rows += Row(row(s.epochSeconds, rate = false), s.height, true)
            rows += Row(row(s.epochSeconds, rate = true), 0.0, false)
        }
        return rows
    }

    private fun solveNormal(rows: List<Row>, constituentCount: Int, ridge: Double): DoubleArray {
        val p = 1 + 2 * constituentCount
        val ata = Array(p) { DoubleArray(p) }
        val aty = DoubleArray(p)
        for (row in rows) {
            val v = row.values
            for (i in 0 until p) {
                if (v[i] == 0.0) continue
                aty[i] += v[i] * row.target
                for (j in 0 until p) ata[i][j] += v[i] * v[j]
            }
        }
        // Ridge-regularise everything except the mean level and M2 (indices 0..2).
        for (i in 3 until p) ata[i][i] += ridge
        return LinearAlgebra.solve(ata, aty)
    }

    private fun residual(row: Row, x: DoubleArray): Double {
        var s = 0.0
        for (i in x.indices) s += row.values[i] * x[i]
        return row.target - s
    }

    private fun rms(rows: List<Row>, x: DoubleArray): Double {
        val heightRows = rows.filter { it.isHeight }
        if (heightRows.isEmpty()) return 0.0
        return sqrt(heightRows.sumOf { residual(it, x).let { r -> r * r } } / heightRows.size)
    }
}

internal object LinearAlgebra {
    /** Solves A·x = b by Gaussian elimination with partial pivoting. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() }
        val y = b.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            require(abs(m[pivot][col]) > 1e-12) { "Calibration failed: reference data is degenerate." }
            if (pivot != col) {
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                val ty = y[col]; y[col] = y[pivot]; y[pivot] = ty
            }
            for (r in col + 1 until n) {
                val factor = m[r][col] / m[col][col]
                if (factor == 0.0) continue
                for (c in col until n) m[r][c] -= factor * m[col][c]
                y[r] -= factor * y[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = y[r]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }
}
