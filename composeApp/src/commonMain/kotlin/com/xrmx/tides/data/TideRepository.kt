package com.xrmx.tides.data

import com.xrmx.tides.data.sources.EaSource
import com.xrmx.tides.data.sources.NoaaSource
import com.xrmx.tides.data.sources.UkhoSource
import com.xrmx.tides.data.sources.WorldTidesSource
import com.xrmx.tides.engine.HarmonicFitter
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

class ModelResult(val model: HarmonicModel, val warning: String?)

/**
 * Loads station lists and harmonic models. Models are cached on the device so predictions
 * keep working offline; they are re-synced against their source when stale.
 */
class TideRepository(private val storage: Storage) {
    private val ukho = UkhoSource()
    private val ea = EaSource()
    private val noaa = NoaaSource()
    private val worldTides = WorldTidesSource()

    suspend fun catalog(source: SourceType, settings: AppSettings, force: Boolean = false): List<Station> {
        if (source == SourceType.WORLDTIDES) return storage.customStations()
        val cached = storage.catalog(source)
        val now = Clock.System.now().epochSeconds
        if (!force && cached != null && now - cached.fetchedAtEpochSeconds < CATALOG_MAX_AGE) return cached.stations
        return try {
            val stations = when (source) {
                SourceType.UKHO -> {
                    if (settings.ukhoKey.isBlank()) throw SourceException("Add a UK Admiralty API key in Settings")
                    ukho.stations(settings.ukhoKey.trim())
                }
                SourceType.EA -> ea.stations()
                SourceType.NOAA -> noaa.stations()
                SourceType.WORLDTIDES -> emptyList()
            }
            storage.saveCatalog(source, CatalogCache(now, stations))
            stations
        } catch (e: Exception) {
            cached?.stations ?: throw e
        }
    }

    suspend fun model(station: Station, settings: AppSettings, force: Boolean): ModelResult {
        val cached = storage.model(station.id)
        if (!force && cached != null && !isStale(cached, station.source)) return ModelResult(cached, null)
        return try {
            val fresh = sync(station, settings)
            storage.saveModel(fresh)
            ModelResult(fresh, null)
        } catch (e: Exception) {
            if (cached != null) {
                ModelResult(cached, "Couldn't refresh from ${station.source.label} (${e.message}). Showing the last saved calibration.")
            } else {
                throw e
            }
        }
    }

    private fun isStale(model: HarmonicModel, source: SourceType): Boolean {
        val age = Clock.System.now().epochSeconds - model.syncedAtEpochSeconds
        val maxAge = when (source) {
            SourceType.NOAA -> 90 * DAY
            SourceType.UKHO -> 2 * DAY
            SourceType.EA, SourceType.WORLDTIDES -> 7 * DAY
        }
        return age > maxAge
    }

    private suspend fun sync(station: Station, settings: AppSettings): HarmonicModel {
        val now = Clock.System.now().epochSeconds
        return when (station.source) {
            SourceType.NOAA -> noaa.model(station)

            SourceType.EA -> {
                val samples = ea.readings(station.ref)
                val fit = withContext(Dispatchers.Default) { HarmonicFitter.fit(samples) }
                HarmonicModel(
                    stationId = station.id,
                    z0 = fit.z0,
                    constituents = fit.constituents,
                    datum = "Ordnance Datum Newlyn (mAOD)",
                    method = "Fitted to ${fit.spanDays.roundToInt()} days of observed water levels (${samples.size} readings)" + inferredNote(fit),
                    rmsError = fit.rms,
                    syncedAtEpochSeconds = now,
                )
            }

            SourceType.UKHO -> {
                if (settings.ukhoKey.isBlank()) throw SourceException("Add a UK Admiralty API key in Settings")
                val events = ukho.events(station.ref, settings.ukhoKey.trim())
                val fit = withContext(Dispatchers.Default) {
                    HarmonicFitter.fit(emptyList(), events.map { HarmonicFitter.Sample(it.epochSeconds, it.height) })
                }
                HarmonicModel(
                    stationId = station.id,
                    z0 = fit.z0,
                    constituents = fit.constituents,
                    datum = "Chart Datum",
                    method = "Calibrated to ${events.size} official high and low waters from UK Admiralty" + inferredNote(fit),
                    rmsError = fit.rms,
                    syncedAtEpochSeconds = now,
                    referenceEvents = events,
                )
            }

            SourceType.WORLDTIDES -> {
                if (settings.worldTidesKey.isBlank()) throw SourceException("Add a WorldTides API key in Settings")
                val result = worldTides.fetch(station.latitude, station.longitude, settings.worldTidesKey.trim())
                val fit = withContext(Dispatchers.Default) { HarmonicFitter.fit(result.heights) }
                HarmonicModel(
                    stationId = station.id,
                    z0 = fit.z0,
                    constituents = fit.constituents,
                    datum = "Chart Datum (WorldTides)",
                    method = "Fitted to ${fit.spanDays.roundToInt()} days of WorldTides predictions" +
                        (result.stationName?.let { " near $it" } ?: "") + inferredNote(fit),
                    rmsError = fit.rms,
                    syncedAtEpochSeconds = now,
                    referenceEvents = result.extremes,
                )
            }
        }
    }

    private fun inferredNote(fit: HarmonicFitter.Result): String =
        if (fit.inferred.isEmpty()) "" else "; ${fit.inferred.joinToString()} inferred"

    private companion object {
        const val DAY = 86400L
        const val CATALOG_MAX_AGE = 30 * DAY
    }
}
