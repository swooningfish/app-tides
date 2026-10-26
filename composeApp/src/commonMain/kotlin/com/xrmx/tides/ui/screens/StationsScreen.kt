package com.xrmx.tides.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.Region
import com.xrmx.tides.model.SourceType
import com.xrmx.tides.model.Station
import com.xrmx.tides.ui.Screen
import com.xrmx.tides.ui.TidesActions
import com.xrmx.tides.ui.UiState
import com.xrmx.tides.ui.components.formatDecimal
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val MAX_RESULTS = 300

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsScreen(state: UiState, actions: TidesActions) {
    var query by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    val region = state.settings.region

    val inRegion = remember(state.catalog, region) {
        state.catalog.filter { s ->
            when (region) {
                Region.UK_IRELAND -> s.source == SourceType.UKHO || s.source == SourceType.EA || s.source == SourceType.WORLDTIDES
                Region.US -> s.source == SourceType.NOAA || s.source == SourceType.WORLDTIDES
                Region.WORLDWIDE -> true
            }
        }
    }
    val matches = remember(inRegion, query) {
        val q = query.trim()
        if (q.isEmpty()) inRegion else inRegion.filter { it.name.contains(q, ignoreCase = true) || it.area.contains(q, ignoreCase = true) }
    }
    val nearest = remember(inRegion, state.userLocation) {
        state.userLocation?.let { loc -> inRegion.sortedBy { distanceKm(loc, it) }.take(10) }.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tide stations") },
                navigationIcon = {
                    IconButton(onClick = { actions.navigate(Screen.HOME) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = actions::refreshCatalog, enabled = !state.catalogLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh station lists")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Search ports and places") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Region.entries.forEach { r ->
                            FilterChip(
                                selected = region == r,
                                onClick = { actions.updateSettings { it.copy(region = r) } },
                                label = { Text(r.label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = actions::locate, enabled = !state.locating) {
                            Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Nearest to me")
                        }
                        OutlinedButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Outlined.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Custom")
                        }
                        if (state.locating || state.catalogLoading) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    state.catalogMessages.forEach { MessageCard(it, isError = false) }
                }
            }

            if (query.isBlank() && state.favourites.isNotEmpty()) {
                item { SectionHeader("Favourites") }
                items(state.favourites, key = { "fav-" + it.id }) { s -> StationRow(s, state, actions) }
            }
            if (query.isBlank() && nearest.isNotEmpty()) {
                item { SectionHeader("Nearest to you") }
                items(nearest, key = { "near-" + it.id }) { s -> StationRow(s, state, actions) }
            }
            item {
                SectionHeader(
                    when {
                        state.catalogLoading && state.catalog.isEmpty() -> "Loading stations…"
                        query.isBlank() -> "All stations (${matches.size})"
                        else -> "Matches (${matches.size})"
                    }
                )
            }
            items(matches.take(MAX_RESULTS), key = { "all-" + it.id }) { s -> StationRow(s, state, actions) }
            if (matches.size > MAX_RESULTS) {
                item {
                    Text(
                        "Showing the first $MAX_RESULTS. Search to narrow the list.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddCustomLocationDialog(
            location = state.userLocation,
            hasKey = state.settings.worldTidesKey.isNotBlank(),
            onDismiss = { showAddDialog = false },
            onLocate = actions::locate,
            onAdd = { name, lat, lon -> showAddDialog = false; actions.addCustomLocation(name, lat, lon) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun StationRow(station: Station, state: UiState, actions: TidesActions) {
    val favourite = state.favourites.any { it.id == station.id }
    val distance = state.userLocation?.let { "${formatDecimal(distanceKm(it, station), 0)} km" }
    ListItem(
        modifier = Modifier.clickable { actions.selectStation(station) },
        headlineContent = { Text(station.name, fontWeight = if (station.id == state.station?.id) FontWeight.Bold else null) },
        supportingContent = {
            Text(listOfNotNull(station.source.label, station.area.ifBlank { null }, distance).joinToString(" · "))
        },
        trailingContent = {
            Row {
                if (station.source == SourceType.WORLDTIDES) {
                    IconButton(onClick = { actions.removeCustomLocation(station) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove custom location")
                    }
                }
                IconButton(onClick = { actions.toggleFavourite(station) }) {
                    Icon(
                        if (favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                        tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun AddCustomLocationDialog(
    location: LatLon?,
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onLocate: () -> Unit,
    onAdd: (String, Double, Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    // Fill in the coordinates once a location fix arrives.
    LaunchedEffect(location) {
        if (location != null) {
            lat = formatDecimal(location.latitude, 5)
            lon = formatDecimal(location.longitude, 5)
        }
    }
    val latValue = lat.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lonValue = lon.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hasKey) "Predictions for any coastal point worldwide, calibrated with WorldTides."
                    else "Custom locations use WorldTides. Add a WorldTides API key in Settings first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    lat, { lat = it }, label = { Text("Latitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    lon, { lon = it }, label = { Text("Longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(onClick = onLocate) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use my location")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), latValue!!, lonValue!!) },
                enabled = latValue != null && lonValue != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun distanceKm(from: LatLon, to: Station): Double {
    val r = 6371.0
    val dLat = (to.latitude - from.latitude) * kotlin.math.PI / 180
    val dLon = (to.longitude - from.longitude) * kotlin.math.PI / 180
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(from.latitude * kotlin.math.PI / 180) * cos(to.latitude * kotlin.math.PI / 180) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}
