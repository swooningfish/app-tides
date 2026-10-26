package com.xrmx.tides.engine

import com.xrmx.tides.model.ConstituentValue
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.TideType
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TideEngineTest {
    private val start = 1_780_000_000L // mid-2026

    private val truth = HarmonicModel(
        stationId = "test",
        z0 = 3.0,
        constituents = listOf(
            ConstituentValue("M2", 1.8, 150.0),
            ConstituentValue("S2", 0.6, 190.0),
            ConstituentValue("N2", 0.35, 122.0),
            ConstituentValue("K2", 0.16, 200.0),
            ConstituentValue("K1", 0.08, 110.0),
            ConstituentValue("P1", 0.025, 105.0),
            ConstituentValue("O1", 0.07, 330.0),
            ConstituentValue("M4", 0.08, 90.0),
        ),
        datum = "test",
        method = "test",
        syncedAtEpochSeconds = 0,
    )

    @Test
    fun constituentSpeedsMatchPublishedValues() {
        assertClose(28.9841042, Constituents.named("M2")!!.speed, 1e-6)
        assertClose(30.0, Constituents.named("S2")!!.speed, 1e-6)
        assertClose(15.0410686, Constituents.named("K1")!!.speed, 1e-6)
        assertClose(13.9430356, Constituents.named("O1")!!.speed, 1e-6)
        assertClose(28.4397295, Constituents.named("N2")!!.speed, 1e-6)
    }

    @Test
    fun semidiurnalTideHasAboutTwoHighsADay() {
        val predictor = TidePredictor(truth)
        val events = predictor.extremes(start, start + 7 * 86400)
        val highs = events.count { it.type == TideType.HIGH }
        assertTrue(highs in 13..15, "expected ~14 highs in a week, got $highs")
        // Highs and lows alternate.
        events.zipWithNext().forEach { (a, b) -> assertTrue(a.type != b.type) }
    }

    @Test
    fun rateIsZeroAtExtremes() {
        val predictor = TidePredictor(truth)
        predictor.extremes(start, start + 2 * 86400).forEach { e ->
            assertTrue(abs(predictor.rate(e.epochSeconds.toDouble())) < 0.02, "rate at extreme should be ~0")
        }
    }

    @Test
    fun fitRecoversConstantsFromFourWeeksOfReadings() {
        val predictor = TidePredictor(truth)
        val samples = predictor.series(start, start + 28 * 86400, 900).map { HarmonicFitter.Sample(it.epochSeconds, it.height) }
        val fit = HarmonicFitter.fit(samples)
        assertClose(3.0, fit.z0, 0.02)
        val m2 = fit.constituents.first { it.name == "M2" }
        assertClose(1.8, m2.amplitude, 0.02)
        assertClose(150.0, m2.phaseDeg, 1.0)
        val s2 = fit.constituents.first { it.name == "S2" }
        assertClose(0.6, s2.amplitude, 0.03)
        assertTrue(fit.rms < 0.02, "rms ${fit.rms}")
    }

    @Test
    fun fitFromAWeekOfHighAndLowWatersPredictsTimesClosely() {
        val predictor = TidePredictor(truth)
        val events = predictor.extremes(start, start + 7 * 86400)
        val fit = HarmonicFitter.fit(emptyList(), events.map { HarmonicFitter.Sample(it.epochSeconds, it.height) })
        val fitted = TidePredictor(truth.copy(z0 = fit.z0, constituents = fit.constituents))
        // Within the calibration window, high/low water times should agree to within ~15 minutes.
        val refit = fitted.extremes(start + 3600, start + 6 * 86400)
        refit.forEach { e ->
            val nearest = events.minBy { abs(it.epochSeconds - e.epochSeconds) }
            assertTrue(abs(nearest.epochSeconds - e.epochSeconds) < 15 * 60, "time off by ${abs(nearest.epochSeconds - e.epochSeconds) / 60} min")
            assertClose(nearest.height, e.height, 0.15)
        }
    }

    @Test
    fun sunTimesForLondonInJuneAreReasonable() {
        val sun = SunMoon.sunTimes(LocalDate(2026, 6, 21), 51.5, -0.12)
        val rise = assertNotNull(sun.sunrise).epochSeconds % 86400 / 3600.0
        val set = assertNotNull(sun.sunset).epochSeconds % 86400 / 3600.0
        // Midsummer London: sunrise ~03:43 UTC, sunset ~20:21 UTC.
        assertClose(3.72, rise, 0.1)
        assertClose(20.35, set, 0.1)
    }

    @Test
    fun knownFullMoonIsDetected() {
        // Full moon on 2026-01-03 10:03 UTC.
        val moon = SunMoon.moon(kotlinx.datetime.Instant.parse("2026-01-03T10:03:00Z"))
        assertEquals("Full moon", moon.phaseName)
        assertTrue(moon.illumination > 0.98)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance but was $actual")
    }
}
