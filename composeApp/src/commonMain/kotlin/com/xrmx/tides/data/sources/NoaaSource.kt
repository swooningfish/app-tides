package com.xrmx.tides.data.sources

import com.xrmx.tides.data.Net
import com.xrmx.tides.data.asDouble
import com.xrmx.tides.data.asString
import com.xrmx.tides.model.ConstituentValue
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * NOAA CO-OPS metadata API (United States, no key required). NOAA publishes the
 * harmonic constants themselves, so no fitting is needed.
 */
internal class NoaaSource {
    private val base = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi"

    suspend fun stations(): List<Station> {
        val root = Net.getJson("$base/stations.json?type=harcon").jsonObject
        val items = root["stations"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
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

    suspend fun model(station: Station): HarmonicModel {
        val harcon = Net.getJson("$base/stations/${station.ref}/harcon.json?units=metric").jsonObject
        val constituents = harcon["HarmonicConstituents"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val name = o["name"].asString() ?: return@mapNotNull null
            val amplitude = o["amplitude"].asDouble() ?: return@mapNotNull null
            val phase = o["phase_GMT"].asDouble() ?: return@mapNotNull null
            if (amplitude <= 0.0) null else ConstituentValue(name, amplitude, phase)
        }
        if (constituents.isEmpty()) throw IllegalStateException("NOAA has no harmonic constants for this station.")

        // Harmonic constants are about mean sea level; shift to MLLW so heights match NOAA tables.
        var z0 = 0.0
        var datum = "Mean sea level"
        runCatching {
            val datums = Net.getJson("$base/stations/${station.ref}/datums.json?units=metric").jsonObject
            val values = datums["datums"]?.jsonArray.orEmpty().associate { d ->
                val o = d.jsonObject
                o["name"].asString().orEmpty() to o["value"].asDouble()
            }
            val msl = values["MSL"]
            val mllw = values["MLLW"]
            if (msl != null && mllw != null) {
                z0 = msl - mllw
                datum = "Mean Lower Low Water (MLLW)"
            }
        }

        return HarmonicModel(
            stationId = station.id,
            z0 = z0,
            constituents = constituents,
            datum = datum,
            method = "Published harmonic constants (${constituents.size} constituents) from NOAA",
            syncedAtEpochSeconds = Clock.System.now().epochSeconds,
        )
    }
}
