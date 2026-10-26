package com.xrmx.tides.model

import kotlinx.serialization.Serializable

/** Where a station's tide data comes from. */
@Serializable
enum class SourceType(val label: String, val needsKey: Boolean) {
    UKHO("UK Admiralty", true),
    EA("Environment Agency", false),
    NOAA("NOAA", false),
    WORLDTIDES("WorldTides", true),
}

@Serializable
data class Station(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val source: SourceType,
    val area: String = "",
) {
    /** The station reference used by the upstream API (the id without its source prefix). */
    val ref: String get() = id.substringAfter(':')
}

@Serializable
enum class TideType { HIGH, LOW }

/** A high or low water. [reference] marks events supplied by the upstream source rather than our model. */
@Serializable
data class TideEvent(
    val epochSeconds: Long,
    val height: Double,
    val type: TideType,
    val reference: Boolean = false,
)

data class TidePoint(val epochSeconds: Long, val height: Double)

@Serializable
data class ConstituentValue(val name: String, val amplitude: Double, val phaseDeg: Double)

/**
 * A harmonic tide model for one station. Heights are in metres above [datum].
 * Phases are Greenwich phase lags (UTC).
 */
@Serializable
data class HarmonicModel(
    val stationId: String,
    val z0: Double,
    val constituents: List<ConstituentValue>,
    val datum: String,
    val method: String,
    val rmsError: Double? = null,
    val syncedAtEpochSeconds: Long,
    val referenceEvents: List<TideEvent> = emptyList(),
)

@Serializable
enum class Units { METRES, FEET }

@Serializable
enum class Region(val label: String) {
    UK_IRELAND("UK & Ireland"),
    US("United States"),
    WORLDWIDE("Worldwide"),
}

@Serializable
data class AppSettings(
    val units: Units = Units.METRES,
    val region: Region = Region.UK_IRELAND,
    val ukhoKey: String = "",
    val worldTidesKey: String = "",
    val alertsEnabled: Boolean = false,
    val alertHigh: Boolean = true,
    val alertLow: Boolean = false,
    val alertLeadMinutes: Int = 60,
)

data class LatLon(val latitude: Double, val longitude: Double)

@Serializable
data class TideAlert(val id: Int, val atEpochMillis: Long, val title: String, val body: String)
