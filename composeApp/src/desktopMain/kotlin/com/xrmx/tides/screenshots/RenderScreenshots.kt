package com.xrmx.tides.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.Density
import com.xrmx.tides.export.ExportRenderer
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.ConstituentValue
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import com.xrmx.tides.platform.encodePng
import com.xrmx.tides.ui.Screen
import com.xrmx.tides.ui.TidesActions
import com.xrmx.tides.ui.UiState
import com.xrmx.tides.ui.ViewMode
import com.xrmx.tides.ui.screens.DisclaimerScreen
import com.xrmx.tides.ui.screens.HomeScreen
import com.xrmx.tides.ui.screens.SettingsScreen
import com.xrmx.tides.ui.screens.StationsScreen
import com.xrmx.tides.ui.theme.TidesTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the README screenshots headlessly from the real Compose screens.
 * Uses a clearly-labelled demo station with made-up harmonic constants, so the
 * images never show real tide predictions.
 *
 * Run with: ./gradlew :composeApp:run
 */
private const val WIDTH = 1080
private const val HEIGHT = 2340
private val DENSITY = Density(2.625f)

private val demoStation = Station(
    id = "demo:example",
    name = "Example Harbour (demo)",
    latitude = 50.37,
    longitude = -4.14,
    source = SourceType.EA,
    area = "England",
)

private val demoModel = HarmonicModel(
    stationId = demoStation.id,
    z0 = 3.2,
    constituents = listOf(
        ConstituentValue("M2", 1.70, 150.0),
        ConstituentValue("S2", 0.60, 195.0),
        ConstituentValue("N2", 0.32, 130.0),
        ConstituentValue("K2", 0.17, 192.0),
        ConstituentValue("K1", 0.07, 110.0),
        ConstituentValue("O1", 0.06, 340.0),
        ConstituentValue("M4", 0.07, 95.0),
        ConstituentValue("MS4", 0.05, 150.0),
    ),
    datum = "Chart Datum",
    method = "Demo data for screenshots: not a real station",
    rmsError = 0.06,
    syncedAtEpochSeconds = LocalDateTime(2026, 6, 15, 8, 0).toInstant(TimeZone.UTC).epochSeconds,
)

private val demoCatalog = listOf(
    Station("ea:demo1", "Dover", 51.114, 1.323, SourceType.UKHO, "England"),
    Station("ea:demo2", "Newhaven", 50.782, 0.057, SourceType.EA, "England"),
    Station("ea:demo3", "Portsmouth", 50.802, -1.112, SourceType.UKHO, "England"),
    Station("ea:demo4", "Weymouth", 50.608, -2.448, SourceType.EA, "England"),
    Station("ea:demo5", "Plymouth (Devonport)", 50.368, -4.185, SourceType.EA, "England"),
    Station("ea:demo6", "Newlyn", 50.103, -5.543, SourceType.EA, "England"),
    Station("ea:demo7", "St Mary's, Isles of Scilly", 49.918, -6.317, SourceType.UKHO, "England"),
    Station("ea:demo8", "Milford Haven", 51.707, -5.052, SourceType.UKHO, "Wales"),
    demoStation,
)

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "docs/screenshots").apply { mkdirs() }
    val tz = TimeZone.of("Europe/London")
    val now = LocalDateTime(2026, 6, 15, 10, 30).toInstant(tz)
    val actions = object : TidesActions {}
    val base = UiState(
        disclaimerAccepted = true,
        station = demoStation,
        model = demoModel,
        selectedDate = LocalDate(2026, 6, 15),
        favourites = listOf(demoStation, demoCatalog[2]),
        settings = AppSettings(alertsEnabled = true),
    )

    shot(outDir, "01-disclaimer.png") { DisclaimerScreen(firstRun = true, onAccept = {}) }
    shot(outDir, "02-day.png") { HomeScreen(base, now, tz, actions) }
    shot(outDir, "03-week.png") { HomeScreen(base.copy(mode = ViewMode.WEEK), now, tz, actions) }
    shot(outDir, "04-stations.png") {
        StationsScreen(base.copy(screen = Screen.STATIONS, catalog = demoCatalog, userLocation = LatLon(50.37, -4.14)), actions)
    }
    shot(outDir, "05-settings.png") { SettingsScreen(base.copy(screen = Screen.SETTINGS), actions) }
    shot(outDir, "06-day-dark.png", dark = true) { HomeScreen(base, now, tz, actions) }

    // The exported share image, rendered exactly as the app produces it.
    var exported: ImageBitmap? = null
    val scene = ImageComposeScene(1, 1, DENSITY) {
        val resolver = LocalFontFamilyResolver.current
        exported = ExportRenderer.render(
            ExportRenderer.contentFor(demoStation, demoModel, ViewMode.DAY, base.selectedDate, now, tz, base.settings.units),
            resolver,
        )
    }
    scene.render()
    scene.close()
    File(outDir, "07-export.png").writeBytes(exported!!.encodePng())

    println("Screenshots written to ${outDir.absolutePath}")
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shot(dir: File, name: String, dark: Boolean = false, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(WIDTH, HEIGHT, DENSITY) {
        TidesTheme(darkTheme = dark) { content() }
    }
    scene.render(0)
    val image = scene.render(1_000_000_000L)
    File(dir, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("  $name")
}
