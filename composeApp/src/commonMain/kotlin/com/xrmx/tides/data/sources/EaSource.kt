package com.xrmx.tides.data.sources

import com.xrmx.tides.data.Net
import com.xrmx.tides.data.asDouble
import com.xrmx.tides.data.asString
import com.xrmx.tides.data.parseUtcEpochSeconds
import com.xrmx.tides.data.parsing
import com.xrmx.tides.data.toTitleCaseIfShouting
import com.xrmx.tides.engine.HarmonicFitter
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Environment Agency real-time flood-monitoring API (England, no key required).
 * Tide gauges report observed water level every 15 minutes, in metres above Ordnance Datum Newlyn.
 * We fit harmonic constants to the last few weeks of readings.
 */
internal class EaSource {
    private val base = "https://environment.data.gov.uk/flood-monitoring"

    suspend fun stations(): List<Station> =
        parseStations(Net.getJson("$base/id/stations?qualifier=Tidal%20Level&_limit=1000"))

    suspend fun readings(stationRef: String, days: Int = 28): List<HarmonicFitter.Sample> {
        val since = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - days * 86400L)
        return parseReadings(Net.getJson("$base/id/stations/$stationRef/readings?since=$since&_sorted&_limit=10000"))
    }

    fun parseStations(json: JsonElement): List<Station> = parsing {
        val items = json.jsonObject["items"]?.jsonArray ?: return@parsing emptyList()
        items.mapNotNull { item ->
            val o = item.jsonObject
            val ref = o["stationReference"].asString() ?: return@mapNotNull null
            val lat = o["lat"].asDouble() ?: return@mapNotNull null
            val lon = o["long"].asDouble() ?: return@mapNotNull null
            Station(
                id = "ea:$ref",
                name = (o["label"].asString() ?: ref).toTitleCaseIfShouting(),
                latitude = lat,
                longitude = lon,
                source = SourceType.EA,
                area = "England",
            )
        }.distinctBy { it.id }
    }

    fun parseReadings(json: JsonElement): List<HarmonicFitter.Sample> = parsing {
        val items = json.jsonObject["items"]?.jsonArray ?: return@parsing emptyList()
        val parsed = items.mapNotNull { item ->
            val o = item.jsonObject
            val measure = o["measure"].asString().orEmpty()
            val time = o["dateTime"].asString() ?: return@mapNotNull null
            val value = o["value"].asDouble() ?: return@mapNotNull null
            Triple(measure, parseUtcEpochSeconds(time), value)
        }
        // A station can carry several measures; prefer the tidal level one.
        val tidal = parsed.filter { it.first.contains("tidal", ignoreCase = true) }
        val chosen = tidal.ifEmpty { parsed }
        chosen
            .distinctBy { it.second }
            .sortedBy { it.second }
            .map { HarmonicFitter.Sample(it.second, it.third) }
    }
}
