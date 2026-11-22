package com.xrmx.tides.data

import com.russhwolf.settings.Settings
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import com.xrmx.tides.platform.FileStore
import com.xrmx.tides.platform.defaultFileStore
import com.xrmx.tides.platform.secretSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class CatalogCache(val fetchedAtEpochSeconds: Long, val stations: List<Station>)

/**
 * Device persistence.
 * - Small preferences (settings, favourites, selected station) in [settings]
 *   (SharedPreferences on Android, NSUserDefaults on iOS).
 * - API keys in [secrets], which is kept out of backups (Android) or in the Keychain (iOS).
 * - Large caches (station lists, calibrated models) as files in [files], so they aren't loaded
 *   into memory with the preferences. Models for stations not used recently are pruned.
 */
class Storage(
    private val settings: Settings = Settings(),
    private val files: FileStore = defaultFileStore(),
    private val secrets: Settings = secretSettings(),
) {
    private val json = Net.json
    private val stationList = ListSerializer(Station.serializer())
    private val idList = ListSerializer(String.serializer())

    var disclaimerAccepted: Boolean
        get() = settings.getBoolean(KEY_DISCLAIMER, false)
        set(value) = settings.putBoolean(KEY_DISCLAIMER, value)

    fun appSettings(): AppSettings {
        val stored = read(KEY_SETTINGS, AppSettings.serializer()) ?: AppSettings()
        // Versions up to 1.0.0 kept API keys inside the settings JSON; move them to the secret store.
        if (stored.ukhoKey.isNotBlank() || stored.worldTidesKey.isNotBlank()) {
            if (secrets.getStringOrNull(KEY_UKHO).isNullOrBlank()) secrets.putString(KEY_UKHO, stored.ukhoKey)
            if (secrets.getStringOrNull(KEY_WORLDTIDES).isNullOrBlank()) secrets.putString(KEY_WORLDTIDES, stored.worldTidesKey)
            write(KEY_SETTINGS, AppSettings.serializer(), stored.copy(ukhoKey = "", worldTidesKey = ""))
        }
        return stored.copy(
            ukhoKey = secrets.getStringOrNull(KEY_UKHO).orEmpty(),
            worldTidesKey = secrets.getStringOrNull(KEY_WORLDTIDES).orEmpty(),
        )
    }

    fun saveAppSettings(value: AppSettings) {
        secrets.putString(KEY_UKHO, value.ukhoKey)
        secrets.putString(KEY_WORLDTIDES, value.worldTidesKey)
        write(KEY_SETTINGS, AppSettings.serializer(), value.copy(ukhoKey = "", worldTidesKey = ""))
    }

    fun favourites(): List<Station> = read(KEY_FAVOURITES, stationList).orEmpty()
    fun saveFavourites(value: List<Station>) = write(KEY_FAVOURITES, stationList, value)

    fun selectedStation(): Station? = read(KEY_SELECTED, Station.serializer())
    fun saveSelectedStation(value: Station) = write(KEY_SELECTED, Station.serializer(), value)

    fun customStations(): List<Station> = read(KEY_CUSTOM, stationList).orEmpty()
    fun saveCustomStations(value: List<Station>) = write(KEY_CUSTOM, stationList, value)

    fun model(stationId: String): HarmonicModel? =
        readFile(modelFile(stationId), HarmonicModel.serializer())
            ?: migrateLegacy("model:$stationId", HarmonicModel.serializer()) { saveModel(it) }

    fun saveModel(value: HarmonicModel) {
        writeFile(modelFile(value.stationId), HarmonicModel.serializer(), value)
        val recent = listOf(value.stationId) + recentModels().filterNot { it == value.stationId }
        val keep = recent.take(MAX_RECENT_MODELS).toMutableSet()
        favourites().forEach { keep += it.id }
        selectedStation()?.let { keep += it.id }
        recent.filterNot { it in keep }.forEach { files.delete(modelFile(it)) }
        write(KEY_RECENT_MODELS, idList, recent.filter { it in keep })
    }

    fun catalog(source: SourceType): CatalogCache? =
        readFile(catalogFile(source), CatalogCache.serializer())
            ?: migrateLegacy("catalog:${source.name}", CatalogCache.serializer()) { saveCatalog(source, it) }

    fun saveCatalog(source: SourceType, value: CatalogCache) = writeFile(catalogFile(source), CatalogCache.serializer(), value)

    private fun recentModels(): List<String> = read(KEY_RECENT_MODELS, idList).orEmpty()

    /** Moves a value that older versions kept in preferences into a file. */
    private fun <T> migrateLegacy(key: String, serializer: KSerializer<T>, save: (T) -> Unit): T? {
        val legacy = read(key, serializer)
        if (settings.hasKey(key)) settings.remove(key)
        return legacy?.also(save)
    }

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        settings.getStringOrNull(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) =
        settings.putString(key, json.encodeToString(serializer, value))

    private fun <T> readFile(name: String, serializer: KSerializer<T>): T? =
        files.read(name)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> writeFile(name: String, serializer: KSerializer<T>, value: T) =
        files.write(name, json.encodeToString(serializer, value))

    private companion object {
        const val KEY_DISCLAIMER = "disclaimer_accepted_v1"
        const val KEY_SETTINGS = "settings"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_SELECTED = "selected_station"
        const val KEY_CUSTOM = "custom_stations"
        const val KEY_RECENT_MODELS = "recent_models"
        const val KEY_UKHO = "ukho_key"
        const val KEY_WORLDTIDES = "worldtides_key"
        const val MAX_RECENT_MODELS = 12

        fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        fun modelFile(stationId: String) = "model-${safeName(stationId)}.json"
        fun catalogFile(source: SourceType) = "catalog-${source.name}.json"
    }
}
