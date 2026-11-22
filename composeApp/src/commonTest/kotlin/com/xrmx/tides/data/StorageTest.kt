package com.xrmx.tides.data

import com.russhwolf.settings.MapSettings
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.CatalogTestData
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import com.xrmx.tides.platform.MemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
    private val prefs = MapSettings()
    private val files = MemoryFileStore()
    private val secrets = MapSettings()
    private val storage = Storage(prefs, files, secrets)

    private fun model(id: String) = HarmonicModel(id, 0.0, emptyList(), "CD", "test", syncedAtEpochSeconds = 0)

    @Test
    fun apiKeysAreKeptOutOfTheSettingsJson() {
        storage.saveAppSettings(AppSettings(ukhoKey = "ukho-secret", worldTidesKey = "wt-secret"))
        assertFalse(prefs.getString("settings", "").contains("secret"))
        assertEquals("ukho-secret", secrets.getString("ukho_key", ""))
        assertEquals("ukho-secret", storage.appSettings().ukhoKey)
        assertEquals("wt-secret", storage.appSettings().worldTidesKey)
    }

    @Test
    fun keysFromOlderVersionsAreMigrated() {
        prefs.putString("settings", """{"ukhoKey":"old-key","units":"FEET"}""")
        val settings = storage.appSettings()
        assertEquals("old-key", settings.ukhoKey)
        assertEquals("old-key", secrets.getString("ukho_key", ""))
        assertFalse(prefs.getString("settings", "").contains("old-key"))
    }

    @Test
    fun modelsLiveInFilesAndLegacyOnesAreMoved() {
        prefs.putString("model:ea:1", Net.json.encodeToString(HarmonicModel.serializer(), model("ea:1")))
        assertNotNull(storage.model("ea:1"))
        assertFalse(prefs.hasKey("model:ea:1"))
        assertTrue(files.list().any { it.startsWith("model-ea_1") })
    }

    @Test
    fun oldModelsArePrunedButFavouritesAndSelectedAreKept() {
        val favourite = Station("ea:fav", "Fav", 0.0, 0.0, SourceType.EA)
        val selected = Station("ea:sel", "Sel", 0.0, 0.0, SourceType.EA)
        storage.saveModel(model(favourite.id))
        storage.saveModel(model(selected.id))
        storage.saveFavourites(listOf(favourite))
        storage.saveSelectedStation(selected)
        repeat(20) { storage.saveModel(model("ea:$it")) }
        assertNotNull(storage.model(favourite.id))
        assertNotNull(storage.model(selected.id))
        assertNotNull(storage.model("ea:19"))
        assertNull(storage.model("ea:0"))
        assertTrue(files.list().count { it.startsWith("model-") } <= 14)
    }

    @Test
    fun catalogsLiveInFiles() {
        storage.saveCatalog(SourceType.NOAA, CatalogCache(5, CatalogTestData.stations))
        assertEquals(CatalogTestData.stations, storage.catalog(SourceType.NOAA)?.stations)
        assertFalse(prefs.keys.any { it.startsWith("catalog") })
    }
}
