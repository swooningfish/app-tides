package com.xrmx.tides.data

import com.russhwolf.settings.Settings
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class CatalogCache(val fetchedAtEpochSeconds: Long, val stations: List<Station>)

/** Small key-value persistence (SharedPreferences on Android, NSUserDefaults on iOS). */
class Storage(private val settings: Settings = Settings()) {
    private val json = Net.json
    private val stationList = ListSerializer(Station.serializer())

    var disclaimerAccepted: Boolean
        get() = settings.getBoolean(KEY_DISCLAIMER, false)
        set(value) = settings.putBoolean(KEY_DISCLAIMER, value)

    fun appSettings(): AppSettings = read(KEY_SETTINGS, AppSettings.serializer()) ?: AppSettings()
    fun saveAppSettings(value: AppSettings) = write(KEY_SETTINGS, AppSettings.serializer(), value)

    fun favourites(): List<Station> = read(KEY_FAVOURITES, stationList).orEmpty()
    fun saveFavourites(value: List<Station>) = write(KEY_FAVOURITES, stationList, value)

    fun selectedStation(): Station? = read(KEY_SELECTED, Station.serializer())
    fun saveSelectedStation(value: Station) = write(KEY_SELECTED, Station.serializer(), value)

    fun customStations(): List<Station> = read(KEY_CUSTOM, stationList).orEmpty()
    fun saveCustomStations(value: List<Station>) = write(KEY_CUSTOM, stationList, value)

    fun model(stationId: String): HarmonicModel? = read("model:$stationId", HarmonicModel.serializer())
    fun saveModel(value: HarmonicModel) = write("model:${value.stationId}", HarmonicModel.serializer(), value)

    fun catalog(source: SourceType): CatalogCache? = read("catalog:${source.name}", CatalogCache.serializer())
    fun saveCatalog(source: SourceType, value: CatalogCache) = write("catalog:${source.name}", CatalogCache.serializer(), value)

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        settings.getStringOrNull(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) =
        settings.putString(key, json.encodeToString(serializer, value))

    private companion object {
        const val KEY_DISCLAIMER = "disclaimer_accepted_v1"
        const val KEY_SETTINGS = "settings"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_SELECTED = "selected_station"
        const val KEY_CUSTOM = "custom_stations"
    }
}
