package com.xrmx.tides.data

import com.xrmx.tides.data.sources.EaSource
import com.xrmx.tides.data.sources.NoaaSource
import com.xrmx.tides.data.sources.UkhoSource
import com.xrmx.tides.data.sources.WorldTidesSource
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.TideType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsers against trimmed copies of real API responses, so an upstream format change shows up here. */
class SourceParsingTest {
    private fun json(text: String) = Net.json.parseToJsonElement(text)

    @Test
    fun eaStationsAreParsedAndTitleCased() {
        val stations = EaSource().parseStations(
            json(
                """{"items":[
                  {"stationReference":"E70039","label":"NEWHAVEN","lat":50.782,"long":0.057},
                  {"stationReference":"E70039","label":"NEWHAVEN","lat":50.782,"long":0.057},
                  {"stationReference":"E7003X","label":"No coords"}
                ]}"""
            )
        )
        assertEquals(1, stations.size)
        assertEquals("ea:E70039", stations[0].id)
        assertEquals("Newhaven", stations[0].name)
        assertEquals(SourceType.EA, stations[0].source)
    }

    @Test
    fun eaReadingsPreferTheTidalMeasureAndSort() {
        val samples = EaSource().parseReadings(
            json(
                """{"items":[
                  {"measure":"http://x/E70039-level-tidal_level-Mean-15_min-mAOD","dateTime":"2026-06-15T10:15:00Z","value":1.5},
                  {"measure":"http://x/E70039-level-tidal_level-Mean-15_min-mAOD","dateTime":"2026-06-15T10:00:00Z","value":"1.2"},
                  {"measure":"http://x/E70039-level-stage-i-15_min-m","dateTime":"2026-06-15T10:00:00Z","value":9.9}
                ]}"""
            )
        )
        assertEquals(listOf(1.2, 1.5), samples.map { it.height })
        assertTrue(samples[0].epochSeconds < samples[1].epochSeconds)
    }

    @Test
    fun noaaHarmonicsSkipZeroAmplitudes() {
        val values = NoaaSource().parseHarmonics(
            json(
                """{"HarmonicConstituents":[
                  {"number":1,"name":"M2","amplitude":1.022,"phase_GMT":189.9},
                  {"number":2,"name":"S2","amplitude":0.0,"phase_GMT":0.0}
                ]}"""
            )
        )
        assertEquals(1, values.size)
        assertEquals("M2", values[0].name)
        assertEquals(189.9, values[0].phaseDeg)
    }

    @Test
    fun noaaDatumsGiveTheMllwShift() {
        val datum = NoaaSource().parseDatums(json("""{"datums":[{"name":"MLLW","value":1.0},{"name":"MSL","value":1.85}]}"""))!!
        assertEquals(0.85, datum.first, 1e-9)
        assertTrue(datum.second.contains("MLLW"))
        assertNull(NoaaSource().parseDatums(json("""{"datums":[{"name":"MSL","value":1.85}]}""")))
    }

    @Test
    fun noaaStationsNeedCoordinates() {
        val stations = NoaaSource().parseStations(
            json("""{"stations":[{"id":"9414290","name":"San Francisco","lat":37.8063,"lng":-122.4659,"state":"CA"},{"id":"1"}]}""")
        )
        assertEquals(listOf("noaa:9414290"), stations.map { it.id })
        assertEquals("CA", stations[0].area)
    }

    @Test
    fun ukhoStationsAndEventsAreParsed() {
        val stations = UkhoSource().parseStations(
            json(
                """{"type":"FeatureCollection","features":[
                  {"type":"Feature","geometry":{"type":"Point","coordinates":[-1.1167,50.8]},
                   "properties":{"Id":"0065","Name":"PORTSMOUTH","Country":"England"}}
                ]}"""
            )
        )
        assertEquals("ukho:0065", stations.single().id)
        assertEquals("Portsmouth", stations.single().name)
        assertEquals(50.8, stations.single().latitude)

        val events = UkhoSource().parseEvents(
            json(
                """[
                  {"EventType":"LowWater","DateTime":"2026-06-15T12:40:00","Height":0.9},
                  {"EventType":"HighWater","DateTime":"2026-06-15T06:12:00","Height":4.6},
                  {"EventType":"Unknown","DateTime":"2026-06-15T07:00:00","Height":1.0}
                ]"""
            )
        )
        assertEquals(listOf(TideType.HIGH, TideType.LOW), events.map { it.type })
        assertTrue(events.all { it.reference })
    }

    @Test
    fun worldTidesParsesHeightsAndExtremes() {
        val result = WorldTidesSource().parse(
            json(
                """{"status":200,"station":"Example","heights":[{"dt":1000,"height":1.2},{"dt":2800,"height":1.4}],
                   "extremes":[{"dt":5000,"height":2.0,"type":"High"},{"dt":9000,"height":0.2,"type":"Low"}]}"""
            )
        )
        assertEquals(2, result.heights.size)
        assertEquals(listOf(TideType.HIGH, TideType.LOW), result.extremes.map { it.type })
        assertEquals("Example", result.stationName)
    }

    @Test
    fun worldTidesErrorStatusIsReported() {
        val e = assertFailsWith<SourceException> {
            WorldTidesSource().parse(json("""{"status":400,"error":"Invalid key"}"""))
        }
        assertTrue(e.message!!.contains("Invalid key"))
    }

    @Test
    fun unexpectedShapesBecomeReadableErrors() {
        val e = assertFailsWith<SourceException> { UkhoSource().parseEvents(json("""{"message":"oops"}""")) }
        assertTrue(e.message!!.contains("unexpected response"))
    }

    @Test
    fun networkErrorsAreExplainedInPlainEnglish() {
        class UnknownHostException(message: String) : Exception(message)
        class SocketTimeoutException(message: String) : Exception(message)
        assertTrue(friendlyNetworkError(UnknownHostException("Unable to resolve host")).startsWith("No internet"))
        assertTrue(friendlyNetworkError(SocketTimeoutException("read timed out")).contains("too long"))
        assertTrue(friendlyNetworkError(Exception("boom", Exception("The Internet connection appears to be offline."))).startsWith("No internet"))
        assertTrue(friendlyNetworkError(IllegalStateException("weird")).startsWith("Couldn't connect"))
    }
}
