package com.xrmx.tides.data.sources

import com.xrmx.tides.data.Net
import com.xrmx.tides.data.asDouble
import com.xrmx.tides.data.SourceException
import com.xrmx.tides.data.asString
import com.xrmx.tides.data.parsing
import com.xrmx.tides.model.ConstituentValue
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * NOAA CO-OPS metadata API (United States, no key required). NOAA publishes the
 * harmonic constants themselves, so no fitting is needed.
 */
internal class NoaaSource {
    private val base = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi"

    suspend fun stations(): List<Station> = parseStations(Net.getJson("$base/stations.json?type=harcon"))

    suspend fun model(station: Station): HarmonicModel {
        val constituents = parseHarmonics(Net.getJson("$base/stations/${station.ref}/harcon.json?units=metric"))
        if (constituents.isEmpty()) throw SourceException("NOAA has no harmonic constants for this station.")
        val datum = runCatching { parseDatums(Net.getJson("$base/stations/${station.ref}/datums.json?units=metric")) }.getOrNull()
        return HarmonicModel(
            stationId = station.id,
            z0 = datum?.first ?: 0.0,
            constituents = constituents,
            datum = datum?.second ?: "Mean sea level",
            method = "Published harmonic constants (${constituents.size} constituents) from NOAA",
            syncedAtEpochSeconds = Clock.System.now().epochSeconds,
        )
    }

    fun parseStations(json: JsonElement): List<Station> = parsing {
        val items = json.jsonObject["stations"]?.jsonArray ?: return@parsing emptyList()
        items.mapNotNull { item ->
            val o = item.jsonObject
            val id = o["id"].asString() ?: return@mapNotNull null
            Station(
                id = "noaa:$id",
                name = o["name"].asString() ?: id,
                latitude = o["lat"].asDouble() ?: return@mapNotNull null,
                longitude = o["lng"].asDouble() ?: return@mapNotNull null,
                source = SourceType.NOAA,
                area = o["state"].asString().orEmpty(),
            )
        }
    }

    fun parseHarmonics(json: JsonElement): List<ConstituentValue> = parsing {
        json.jsonObject["HarmonicConstituents"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val name = o["name"].asString() ?: return@mapNotNull null
            val amplitude = o["amplitude"].asDouble() ?: return@mapNotNull null
            val phase = o["phase_GMT"].asDouble() ?: return@mapNotNull null
            if (amplitude <= 0.0) null else ConstituentValue(name, amplitude, phase)
        }
    }

    /**
     * Harmonic constants are about mean sea level, while NOAA tables use MLLW. Returns the shift
     * (MSL above MLLW) and the datum name, or null if the station doesn't publish both.
     */
    fun parseDatums(json: JsonElement): Pair<Double, String>? = parsing {
        val values = json.jsonObject["datums"]?.jsonArray.orEmpty().associate { d ->
            val o = d.jsonObject
            o["name"].asString().orEmpty() to o["value"].asDouble()
        }
        val msl = values["MSL"]
        val mllw = values["MLLW"]
        if (msl != null && mllw != null) (msl - mllw) to "Mean Lower Low Water (MLLW)" else null
    }
}
