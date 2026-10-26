package com.xrmx.tides.data.sources

import com.xrmx.tides.data.Net
import com.xrmx.tides.data.asDouble
import com.xrmx.tides.data.asString
import com.xrmx.tides.data.parseUtcEpochSeconds
import com.xrmx.tides.data.toTitleCaseIfShouting
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TideType
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * ADMIRALTY UK Tidal API (UK Hydrographic Office). The free "Discovery" tier gives
 * 7 days of high and low waters for around 600 UK and Ireland ports. Heights are above Chart Datum.
 */
internal class UkhoSource {
    private val base = "https://admiraltyapi.azure-api.net/uktidalapi/api/V1"

    private fun headers(key: String) = mapOf("Ocp-Apim-Subscription-Key" to key)

    suspend fun stations(key: String): List<Station> {
        val root = Net.getJson("$base/Stations", headers(key)).jsonObject
        val features = root["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { feature ->
            val obj = feature.jsonObject
            val props = obj["properties"]?.jsonObject ?: return@mapNotNull null
            val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val id = props["Id"].asString() ?: return@mapNotNull null
            val lon = coords.getOrNull(0).asDouble() ?: return@mapNotNull null
            val lat = coords.getOrNull(1).asDouble() ?: return@mapNotNull null
            Station(
                id = "ukho:$id",
                name = (props["Name"].asString() ?: id).toTitleCaseIfShouting(),
                latitude = lat,
                longitude = lon,
                source = SourceType.UKHO,
                area = props["Country"].asString().orEmpty(),
            )
        }
    }

    suspend fun events(stationRef: String, key: String): List<TideEvent> {
        val arr = Net.getJson("$base/Stations/$stationRef/TidalEvents?duration=7", headers(key)).jsonArray
        return arr.mapNotNull { e ->
            val o = e.jsonObject
            val type = when (o["EventType"].asString()) {
                "HighWater" -> TideType.HIGH
                "LowWater" -> TideType.LOW
                else -> return@mapNotNull null
            }
            val time = o["DateTime"].asString() ?: return@mapNotNull null
            val height = o["Height"].asDouble() ?: return@mapNotNull null
            TideEvent(parseUtcEpochSeconds(time), height, type, reference = true)
        }.sortedBy { it.epochSeconds }
    }
}
