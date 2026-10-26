package com.xrmx.tides.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Nodal amplitude factor f and phase correction u (degrees). */
internal class Nodal(val f: Double, val u: Double)

/**
 * A tidal constituent defined by its Doodson coefficients on (tau, s, h, p, N, p1),
 * an extra phase offset (degrees) and its nodal correction as a function of the
 * Moon's node longitude N (degrees).
 */
internal class Constituent(
    val name: String,
    private val coefficients: IntArray,
    private val phaseOffset: Double,
    val nodal: (Double) -> Nodal,
) {
    /** Angular speed in degrees per hour. */
    val speed: Double = coefficients.indices.sumOf { coefficients[it] * Astro.RATES[it] }

    fun equilibriumArgument(astro: Astro): Double {
        var v = phaseOffset
        for (i in coefficients.indices) v += coefficients[i] * astro.argument(i)
        return v
    }
}

internal object Constituents {
    private fun cosN(n: Double, k: Int) = cos(rad(k * n))
    private fun sinN(n: Double, k: Int) = sin(rad(k * n))

    // Schureman-style approximations for the nodal factors.
    private val unity: (Double) -> Nodal = { Nodal(1.0, 0.0) }
    private val m2: (Double) -> Nodal = { n -> Nodal(1.0004 - 0.0373 * cosN(n, 1) + 0.0002 * cosN(n, 2), -2.14 * sinN(n, 1)) }
    private val k1: (Double) -> Nodal = { n ->
        Nodal(
            1.0060 + 0.1150 * cosN(n, 1) - 0.0088 * cosN(n, 2) + 0.0006 * cosN(n, 3),
            -8.86 * sinN(n, 1) + 0.68 * sinN(n, 2) - 0.07 * sinN(n, 3),
        )
    }
    private val o1: (Double) -> Nodal = { n ->
        Nodal(
            1.0089 + 0.1871 * cosN(n, 1) - 0.0147 * cosN(n, 2) + 0.0014 * cosN(n, 3),
            10.80 * sinN(n, 1) - 1.34 * sinN(n, 2) + 0.19 * sinN(n, 3),
        )
    }
    private val k2: (Double) -> Nodal = { n ->
        Nodal(
            1.0241 + 0.2863 * cosN(n, 1) + 0.0083 * cosN(n, 2) - 0.0015 * cosN(n, 3),
            -17.74 * sinN(n, 1) + 0.68 * sinN(n, 2) - 0.04 * sinN(n, 3),
        )
    }
    private val j1: (Double) -> Nodal = { n ->
        Nodal(
            1.1029 + 0.1676 * cosN(n, 1) - 0.0170 * cosN(n, 2) + 0.0016 * cosN(n, 3),
            -12.94 * sinN(n, 1) + 1.34 * sinN(n, 2) - 0.19 * sinN(n, 3),
        )
    }
    private val oo1: (Double) -> Nodal = { n ->
        Nodal(
            1.1027 + 0.6504 * cosN(n, 1) + 0.0317 * cosN(n, 2) - 0.0014 * cosN(n, 3),
            -36.68 * sinN(n, 1) + 4.02 * sinN(n, 2) - 0.57 * sinN(n, 3),
        )
    }
    private val mf: (Double) -> Nodal = { n -> Nodal(1.043 + 0.414 * cosN(n, 1), -23.7 * sinN(n, 1) + 2.7 * sinN(n, 2) - 0.4 * sinN(n, 3)) }
    private val mm: (Double) -> Nodal = { n -> Nodal(1.0 - 0.130 * cosN(n, 1), 0.0) }

    /** Nodal correction of a compound constituent: product of f^|k|, sum of k*u. */
    private fun compound(vararg parts: Pair<(Double) -> Nodal, Double>): (Double) -> Nodal = { n ->
        var f = 1.0
        var u = 0.0
        for ((nodal, k) in parts) {
            val c = nodal(n)
            f *= c.f.pow(abs(k))
            u += k * c.u
        }
        Nodal(f, u)
    }

    private fun c(name: String, d: IntArray, phase: Double, nodal: (Double) -> Nodal) = Constituent(name, d, phase, nodal)

    val all: List<Constituent> = listOf(
        c("M2", intArrayOf(2, 0, 0, 0, 0, 0), 0.0, m2),
        c("S2", intArrayOf(2, 2, -2, 0, 0, 0), 0.0, unity),
        c("N2", intArrayOf(2, -1, 0, 1, 0, 0), 0.0, m2),
        c("K2", intArrayOf(2, 2, 0, 0, 0, 0), 0.0, k2),
        c("K1", intArrayOf(1, 1, 0, 0, 0, 0), -90.0, k1),
        c("O1", intArrayOf(1, -1, 0, 0, 0, 0), 90.0, o1),
        c("P1", intArrayOf(1, 1, -2, 0, 0, 0), 90.0, unity),
        c("Q1", intArrayOf(1, -2, 0, 1, 0, 0), 90.0, o1),
        c("M4", intArrayOf(4, 0, 0, 0, 0, 0), 0.0, compound(m2 to 2.0)),
        c("MS4", intArrayOf(4, 2, -2, 0, 0, 0), 0.0, compound(m2 to 1.0)),
        c("MN4", intArrayOf(4, -1, 0, 1, 0, 0), 0.0, compound(m2 to 2.0)),
        c("M6", intArrayOf(6, 0, 0, 0, 0, 0), 0.0, compound(m2 to 3.0)),
        c("2N2", intArrayOf(2, -2, 0, 2, 0, 0), 0.0, m2),
        c("NU2", intArrayOf(2, -1, 2, -1, 0, 0), 0.0, m2),
        c("MU2", intArrayOf(2, -2, 2, 0, 0, 0), 0.0, m2),
        c("L2", intArrayOf(2, 1, 0, -1, 0, 0), 180.0, m2),
        c("T2", intArrayOf(2, 2, -3, 0, 0, 1), 0.0, unity),
        c("LAM2", intArrayOf(2, 1, -2, 1, 0, 0), 180.0, m2),
        c("R2", intArrayOf(2, 2, -1, 0, 0, -1), 180.0, unity),
        c("2SM2", intArrayOf(2, 4, -4, 0, 0, 0), 0.0, compound(m2 to -1.0)),
        c("J1", intArrayOf(1, 2, 0, -1, 0, 0), -90.0, j1),
        c("OO1", intArrayOf(1, 3, 0, 0, 0, 0), -90.0, oo1),
        c("2Q1", intArrayOf(1, -3, 0, 2, 0, 0), 90.0, o1),
        c("RHO", intArrayOf(1, -2, 2, -1, 0, 0), 90.0, o1),
        c("M3", intArrayOf(3, 0, 0, 0, 0, 0), 0.0, compound(m2 to 1.5)),
        c("MK3", intArrayOf(3, 1, 0, 0, 0, 0), -90.0, compound(m2 to 1.0, k1 to 1.0)),
        c("2MK3", intArrayOf(3, -1, 0, 0, 0, 0), 90.0, compound(m2 to 2.0, k1 to -1.0)),
        c("S4", intArrayOf(4, 4, -4, 0, 0, 0), 0.0, unity),
        c("S6", intArrayOf(6, 6, -6, 0, 0, 0), 0.0, unity),
        c("M8", intArrayOf(8, 0, 0, 0, 0, 0), 0.0, compound(m2 to 4.0)),
        c("MSF", intArrayOf(0, 2, -2, 0, 0, 0), 0.0, compound(m2 to -1.0)),
        c("MF", intArrayOf(0, 2, 0, 0, 0, 0), 0.0, mf),
        c("MM", intArrayOf(0, 1, 0, -1, 0, 0), 0.0, mm),
        c("SSA", intArrayOf(0, 0, 2, 0, 0, 0), 0.0, unity),
        c("SA", intArrayOf(0, 0, 1, 0, 0, 0), 0.0, unity),
    )

    private val byName: Map<String, Constituent> = all.associateBy { it.name }

    fun named(name: String): Constituent? = byName[name.uppercase().replace("LDA2", "LAM2")]

    /** Order in which constituents are added when fitting a long (two weeks or more) record. */
    val fitPriority: List<Constituent> = listOf(
        "M2", "S2", "K1", "O1", "N2", "M4", "K2", "P1", "Q1", "MS4", "MN4", "M6", "2N2", "NU2", "L2", "MU2",
        "MK3", "M3", "S4", "2MK3", "M8", "J1", "OO1", "T2", "LAM2", "2SM2", "MSF", "MF", "MM", "SSA", "SA",
    ).map { byName.getValue(it) }

    /** Constituents that can be resolved from about a week of data. */
    val shortRecord: List<Constituent> = listOf("M2", "S2", "K1", "O1", "M4", "MS4", "M6").map { byName.getValue(it) }
}
