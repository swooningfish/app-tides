package com.xrmx.tides.engine

import com.russhwolf.settings.MapSettings
import com.xrmx.tides.alerts.AlertPlanner
import com.xrmx.tides.data.Storage
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.CatalogTestData
import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.TideAlert
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TidePoint
import com.xrmx.tides.model.TideType
import com.xrmx.tides.platform.MemoryFileStore
import com.xrmx.tides.platform.PlatformServices
import com.xrmx.tides.ui.TidesViewModel
import com.xrmx.tides.ui.components.interpolateHeight
import com.xrmx.tides.widget.WidgetSummaries
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeaturesTest {
    private val model = CatalogTestData.demoModel
    private val station = CatalogTestData.demoStation
    private val t0 = LocalDateTime(2026, 6, 15, 0, 0).toInstant(TimeZone.UTC).epochSeconds

    @Test
    fun cachedNodalFactorsMatchFullComputation() {
        // A new predictor per call never reuses the cache; one predictor walks across days.
        val shared = TidePredictor(model)
        var t = t0
        while (t < t0 + 3 * 86400) {
            assertEquals(TidePredictor(model).height(t.toDouble()), shared.height(t.toDouble()), 1e-3)
            t += 3 * 3600 + 17
        }
    }

    @Test
    fun lowestLevelIsBelowEveryPredictedLowWater() {
        val predictor = TidePredictor(model)
        val lowest = predictor.lowestLevel(t0)
        val lows = predictor.extremes(t0, t0 + 30 * 86400).filter { it.type == TideType.LOW }
        assertTrue(lows.all { it.height >= lowest - 1e-6 })
        // Bounded by the sum of amplitudes below the mean level.
        assertTrue(lowest >= model.z0 - model.constituents.sumOf { it.amplitude } - 1e-6)
    }

    @Test
    fun estimatedChartDatumShiftsHeightsOnlyWhenEnabled() {
        val withLowest = model.copy(lowestLevel = -2.5)
        val shifted = withLowest.forDisplay(AppSettings(estimatedChartDatum = true))
        assertEquals(model.z0 + 2.5, shifted.z0, 1e-9)
        assertEquals(ESTIMATED_CHART_DATUM, shifted.datum)
        // Applying twice must not shift twice.
        assertEquals(shifted.z0, shifted.forDisplay(AppSettings()).z0)
        assertEquals(model.z0, withLowest.forDisplay(AppSettings(estimatedChartDatum = false)).z0)
        assertEquals(model.z0, model.forDisplay(AppSettings()).z0)
    }

    @Test
    fun referenceEventsReplaceModelEventsWhereTheyApply() {
        val predictor = TidePredictor(model)
        val predicted = predictor.extremes(t0, t0 + 3 * 86400)
        val refs = predicted.filter { it.epochSeconds < t0 + 86400 }.map { TideEvent(it.epochSeconds + 120, it.height + 0.1, it.type, reference = true) }
        val withRefs = model.copy(referenceEvents = refs)
        val events = TideCalculations.events(withRefs, predictor, t0, t0 + 3 * 86400)
        assertTrue(events.filter { it.epochSeconds < t0 + 86400 - 4 * 3600 }.all { it.reference })
        assertTrue(events.filter { it.epochSeconds > t0 + 2 * 86400 }.none { it.reference })
        // No duplicated tides where the two meet.
        events.zipWithNext().forEach { (a, b) -> assertTrue(b.epochSeconds - a.epochSeconds > 3 * 3600, "events too close: $a $b") }
    }

    @Test
    fun alertPlannerHonoursTypeAndLeadTime() {
        val settings = AppSettings(alertsEnabled = true, alertHigh = true, alertLow = false, alertLeadMinutes = 120)
        val alerts = AlertPlanner.plan(station, model, settings, t0, TimeZone.UTC, horizonDays = 3, maxAlerts = 4)
        assertEquals(4, alerts.size)
        assertTrue(alerts.all { it.title.startsWith("High tide") && it.title.endsWith("in 2 hours") })
        assertTrue(alerts.all { it.atEpochMillis > t0 * 1000 })
        assertEquals(emptyList(), AlertPlanner.plan(station, model, settings.copy(alertsEnabled = false), t0, TimeZone.UTC, 3, 10))
    }

    @Test
    fun alertsCanBePlannedFromSavedDataAlone() {
        val storage = Storage(MapSettings(), MemoryFileStore(), MapSettings())
        storage.saveAppSettings(AppSettings(alertsEnabled = true, alertLow = true))
        storage.saveSelectedStation(station)
        storage.saveModel(model)
        val alerts = AlertPlanner.planFromStorage(storage, t0, TimeZone.UTC, 2, 40)
        assertNotNull(alerts)
        assertTrue(alerts.size >= 6, "expected about 7 tides in 2 days, got ${alerts.size}")
        assertNotNull(WidgetSummaries.fromStorage(storage, Instant.fromEpochSeconds(t0), TimeZone.UTC)).also {
            assertEquals(2, it.upcoming.size)
        }
    }

    @Test
    fun chartReadoutInterpolatesBetweenPoints() {
        val points = listOf(TidePoint(0, 1.0), TidePoint(100, 2.0), TidePoint(200, 0.0))
        assertEquals(1.5, interpolateHeight(points, 50)!!, 1e-9)
        assertEquals(1.0, interpolateHeight(points, 150)!!, 1e-9)
        assertEquals(0.0, interpolateHeight(points, 500)!!, 1e-9)
    }

    private class FakeServices : PlatformServices {
        override suspend fun currentLocation(): LatLon? = null
        override fun shareImage(png: ByteArray, fileName: String, message: String) = Unit
        override suspend fun saveImage(png: ByteArray, fileName: String) = true
        override suspend fun scheduleAlerts(alerts: List<TideAlert>) = true
        override fun cancelAlerts() = Unit
    }

    @Test
    fun todayMovesOnAtMidnightOnlyIfTodayWasShowing() {
        val tz = TimeZone.UTC
        val vm = TidesViewModel(FakeServices(), Storage(MapSettings(), MemoryFileStore(), MapSettings()), TestScope(), tz)
        val today = Clock.System.todayIn(tz)
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        vm.onTick(tomorrow.atStartOfDayIn(tz) + kotlin.time.Duration.parse("1m"))
        assertEquals(tomorrow, vm.state.value.selectedDate)

        vm.goToDate(today.plus(10, DateTimeUnit.DAY))
        val dayAfter = tomorrow.plus(1, DateTimeUnit.DAY)
        vm.onTick(dayAfter.atStartOfDayIn(tz) + kotlin.time.Duration.parse("1m"))
        assertEquals(today.plus(10, DateTimeUnit.DAY), vm.state.value.selectedDate)
    }
}
