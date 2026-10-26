package com.xrmx.tides.data.sources

import com.xrmx.tides.data.Net
import com.xrmx.tides.data.SourceException
import com.xrmx.tides.data.asDouble
import com.xrmx.tides.data.asString
import com.xrmx.tides.engine.HarmonicFitter
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TideType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * WorldTides v3 API (worldwide, needs a paid key). We request 7 days of heights and
 * extremes for any coordinate, then fit our own model so it keeps working offline.
 */
internal class WorldTidesSource {
    class Result(val heights: List<HarmonicFitter.Sample>, val extremes: List<TideEvent>, val stationName: String?)

    suspend fun fetch(latitude: Double, longitude: Double, key: String, days: Int = 7): Result {
        val url = "https://www.worldtides.info/api/v3?heights&extremes&datum=CD&step=1800" +
            "&days=$days&lat=$latitude&lon=$longitude&key=${key.encodeURLParameter()}"
        val root = Net.getJson(url).jsonObject
        val status = root["status"].asDouble()?.toInt()
        if (status != null && status != 200) {
            throw SourceException("WorldTides: ${root["error"].asString() ?: "error $status"}")
        }
        val heights = root["heights"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val dt = o["dt"].asDouble()?.toLong() ?: return@mapNotNull null
            val h = o["height"].asDouble() ?: return@mapNotNull null
            HarmonicFitter.Sample(dt, h)
        }
        val extremes = root["extremes"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val dt = o["dt"].asDouble()?.toLong() ?: return@mapNotNull null
            val h = o["height"].asDouble() ?: return@mapNotNull null
            val type = if (o["type"].asString().equals("High", ignoreCase = true)) TideType.HIGH else TideType.LOW
            TideEvent(dt, h, type, reference = true)
        }
        return Result(heights, extremes, root["station"].asString())
    }
}
