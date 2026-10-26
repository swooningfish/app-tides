package com.xrmx.tides.ui

import androidx.compose.ui.text.font.FontFamily
import com.xrmx.tides.data.Storage
import com.xrmx.tides.data.TideRepository
import com.xrmx.tides.engine.TideCalculations
import com.xrmx.tides.engine.TidePredictor
import com.xrmx.tides.export.ExportRenderer
import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.Region
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import com.xrmx.tides.model.TideAlert
import com.xrmx.tides.model.TideType
import com.xrmx.tides.platform.PlatformServices
import com.xrmx.tides.platform.encodePng
import com.xrmx.tides.ui.components.formatHeight
import com.xrmx.tides.ui.components.formatShortDate
import com.xrmx.tides.ui.components.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

enum class Screen { HOME, STATIONS, SETTINGS }

enum class ViewMode { DAY, WEEK }

data class UiState(
    val disclaimerAccepted: Boolean = false,
    val showingDisclaimer: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val screen: Screen = Screen.HOME,
    val mode: ViewMode = ViewMode.DAY,
    val station: Station? = null,
    val model: HarmonicModel? = null,
    val loadingModel: Boolean = false,
    val modelError: String? = null,
    val modelWarning: String? = null,
    val selectedDate: LocalDate,
    val favourites: List<Station> = emptyList(),
    val catalog: List<Station> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogMessages: List<String> = emptyList(),
    val userLocation: LatLon? = null,
    val locating: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
) {
    val isFavourite: Boolean get() = station != null && favourites.any { it.id == station.id }
}

/** Everything the screens can ask for. Defaults are no-ops so previews and screenshots can pass `object : TidesActions {}`. */
interface TidesActions {
    fun acceptDisclaimer() {}
    fun showDisclaimer() {}
    fun closeDisclaimer() {}
    fun navigate(screen: Screen) {}
    fun setMode(mode: ViewMode) {}
    fun shiftDate(days: Int) {}
    fun goToToday() {}
    fun openDay(date: LocalDate) {}
    fun selectStation(station: Station) {}
    fun toggleFavourite(station: Station) {}
    fun resync() {}
    fun refreshCatalog() {}
    fun locate() {}
    fun addCustomLocation(name: String, latitude: Double, longitude: Double) {}
    fun removeCustomLocation(station: Station) {}
    fun updateSettings(transform: (AppSettings) -> AppSettings) {}
    fun export(save: Boolean) {}
    fun consumeMessage() {}
}

class TidesViewModel(
    private val services: PlatformServices,
    private val storage: Storage,
    private val scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : TidesActions {
    private val repository = TideRepository(storage)
    private var modelJob: Job? = null
    private var catalogJob: Job? = null

    /** Set by the UI; text layout for PNG export needs the platform font resolver. */
    var fontResolver: FontFamily.Resolver? = null

    private val _state = MutableStateFlow(
        UiState(
            disclaimerAccepted = storage.disclaimerAccepted,
            settings = storage.appSettings(),
            station = storage.selectedStation(),
            favourites = storage.favourites(),
            selectedDate = Clock.System.todayIn(timeZone),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value.station?.let { loadModel(it, force = false) }
    }

    override fun acceptDisclaimer() {
        storage.disclaimerAccepted = true
        _state.update { it.copy(disclaimerAccepted = true, showingDisclaimer = false) }
    }

    override fun showDisclaimer() = _state.update { it.copy(showingDisclaimer = true) }

    override fun closeDisclaimer() = _state.update { it.copy(showingDisclaimer = false) }

    override fun navigate(screen: Screen) {
        _state.update { it.copy(screen = screen) }
        if (screen == Screen.STATIONS && _state.value.catalog.isEmpty()) loadCatalog(force = false)
    }

    override fun setMode(mode: ViewMode) = _state.update { it.copy(mode = mode) }

    override fun shiftDate(days: Int) = _state.update { it.copy(selectedDate = it.selectedDate.plus(days, DateTimeUnit.DAY)) }

    override fun goToToday() = _state.update { it.copy(selectedDate = Clock.System.todayIn(timeZone)) }

    override fun openDay(date: LocalDate) = _state.update { it.copy(selectedDate = date, mode = ViewMode.DAY) }

    override fun selectStation(station: Station) {
        storage.saveSelectedStation(station)
        _state.update { it.copy(station = station, model = null, modelError = null, modelWarning = null, screen = Screen.HOME) }
        loadModel(station, force = false)
    }

    override fun toggleFavourite(station: Station) {
        val current = _state.value.favourites
        val updated = if (current.any { it.id == station.id }) current.filterNot { it.id == station.id } else current + station
        storage.saveFavourites(updated)
        _state.update { it.copy(favourites = updated) }
    }

    override fun resync() {
        _state.value.station?.let { loadModel(it, force = true) }
    }

    override fun refreshCatalog() = loadCatalog(force = true)

    override fun locate() {
        if (_state.value.locating) return
        scope.launch {
            _state.update { it.copy(locating = true) }
            val location = runCatching { services.currentLocation() }.getOrNull()
            _state.update {
                it.copy(
                    locating = false,
                    userLocation = location ?: it.userLocation,
                    message = if (location == null) "Location unavailable. Check location permission and try again." else null,
                )
            }
        }
    }

    override fun addCustomLocation(name: String, latitude: Double, longitude: Double) {
        val station = Station(
            id = "wt:${latitude},${longitude}",
            name = name.ifBlank { "Custom location" },
            latitude = latitude,
            longitude = longitude,
            source = SourceType.WORLDTIDES,
            area = "Custom",
        )
        val updated = storage.customStations().filterNot { it.id == station.id } + station
        storage.saveCustomStations(updated)
        _state.update { s -> s.copy(catalog = (s.catalog.filterNot { it.id == station.id } + station).sortedBy { it.name.lowercase() }) }
        selectStation(station)
    }

    override fun removeCustomLocation(station: Station) {
        storage.saveCustomStations(storage.customStations().filterNot { it.id == station.id })
        _state.update { s -> s.copy(catalog = s.catalog.filterNot { it.id == station.id }) }
    }

    override fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        val after = transform(before)
        if (after == before) return
        storage.saveAppSettings(after)
        _state.update { it.copy(settings = after) }
        val alertsChanged = before.alertsEnabled != after.alertsEnabled || before.alertHigh != after.alertHigh ||
            before.alertLow != after.alertLow || before.alertLeadMinutes != after.alertLeadMinutes ||
            before.units != after.units
        if (alertsChanged) rescheduleAlerts()
        if (before.region != after.region || before.ukhoKey != after.ukhoKey) {
            if (_state.value.screen == Screen.STATIONS) loadCatalog(force = false) else _state.update { it.copy(catalog = emptyList()) }
        }
    }

    override fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun export(save: Boolean) {
        val s = _state.value
        val station = s.station ?: return
        val model = s.model ?: return
        val resolver = fontResolver ?: return
        if (s.exporting) return
        scope.launch {
            _state.update { it.copy(exporting = true) }
            try {
                val now = Clock.System.now()
                val png = withContext(Dispatchers.Default) {
                    val content = ExportRenderer.contentFor(station, model, s.mode, s.selectedDate, now, timeZone, s.settings.units)
                    ExportRenderer.render(content, resolver).encodePng()
                }
                val name = "tides-${station.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${s.selectedDate}.png"
                if (save) {
                    val ok = services.saveImage(png, name)
                    _state.update { it.copy(message = if (ok) "Image saved" else "Couldn't save the image") }
                } else {
                    services.shareImage(png, name, "Tide predictions for ${station.name}. Predictions only: check with your local lifeboat station, lifeguards or coastguard.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Export failed: ${e.message}") }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private fun loadModel(station: Station, force: Boolean) {
        modelJob?.cancel()
        modelJob = scope.launch {
            _state.update { it.copy(loadingModel = true, modelError = null, modelWarning = null) }
            try {
                val result = repository.model(station, _state.value.settings, force)
                if (_state.value.station?.id != station.id) return@launch
                _state.update {
                    it.copy(
                        model = result.model,
                        loadingModel = false,
                        modelWarning = result.warning,
                        message = if (force && result.warning == null) "Synced with ${station.source.label}" else it.message,
                    )
                }
                rescheduleAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingModel = false, modelError = e.message ?: "Couldn't load tide data") }
            }
        }
    }

    private fun loadCatalog(force: Boolean) {
        catalogJob?.cancel()
        catalogJob = scope.launch {
            _state.update { it.copy(catalogLoading = true, catalogMessages = emptyList()) }
            val settings = _state.value.settings
            val sources = when (settings.region) {
                Region.UK_IRELAND -> listOf(SourceType.UKHO, SourceType.EA, SourceType.WORLDTIDES)
                Region.US -> listOf(SourceType.NOAA, SourceType.WORLDTIDES)
                Region.WORLDWIDE -> listOf(SourceType.UKHO, SourceType.EA, SourceType.NOAA, SourceType.WORLDTIDES)
            }
            val stations = mutableListOf<Station>()
            val messages = mutableListOf<String>()
            for (source in sources) {
                if (source == SourceType.UKHO && settings.ukhoKey.isBlank()) {
                    messages += "Add a free UK Admiralty API key in Settings to list about 600 UK & Ireland ports. Environment Agency gauges work without a key."
                    continue
                }
                try {
                    stations += repository.catalog(source, settings, force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages += "${source.label}: ${e.message ?: "unavailable"}"
                }
            }
            _state.update {
                it.copy(
                    catalog = stations.distinctBy { s -> s.id }.sortedBy { s -> s.name.lowercase() },
                    catalogLoading = false,
                    catalogMessages = messages,
                )
            }
        }
    }

    private fun rescheduleAlerts() {
        val s = _state.value
        val station = s.station
        val model = s.model
        if (!s.settings.alertsEnabled || station == null || model == null) {
            services.cancelAlerts()
            return
        }
        scope.launch {
            val settings = s.settings
            val nowSec = Clock.System.now().epochSeconds
            val lead = settings.alertLeadMinutes * 60L
            val alerts = withContext(Dispatchers.Default) {
                val predictor = TidePredictor(model)
                TideCalculations.events(model, predictor, nowSec, nowSec + 7 * 86400L)
                    .filter { (it.type == TideType.HIGH && settings.alertHigh) || (it.type == TideType.LOW && settings.alertLow) }
                    .filter { it.epochSeconds - lead > nowSec }
                    .mapIndexed { i, e ->
                        val kind = if (e.type == TideType.HIGH) "High" else "Low"
                        val day = formatShortDate(Instant.fromEpochSeconds(e.epochSeconds).toLocalDateTime(timeZone).date)
                        TideAlert(
                            id = i,
                            atEpochMillis = (e.epochSeconds - lead) * 1000,
                            title = "$kind tide at ${station.name} in ${settings.alertLeadMinutes} min",
                            body = "$kind water $day ${formatTime(e.epochSeconds, timeZone)}, ${formatHeight(e.height, settings.units)} (predicted). Use as a guide only.",
                        )
                    }
            }
            val ok = services.scheduleAlerts(alerts)
            if (!ok) {
                updateSettings { it.copy(alertsEnabled = false) }
                _state.update { it.copy(message = "Notifications are turned off for Tides, so alerts are disabled.") }
            }
        }
    }
}
