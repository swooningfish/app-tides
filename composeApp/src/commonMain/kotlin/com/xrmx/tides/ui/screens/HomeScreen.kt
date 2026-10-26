package com.xrmx.tides.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrmx.tides.engine.TidePredictor
import com.xrmx.tides.ui.TidesActions
import com.xrmx.tides.ui.UiState
import com.xrmx.tides.ui.ViewMode
import com.xrmx.tides.ui.Screen
import com.xrmx.tides.ui.components.DisclaimerBanner
import com.xrmx.tides.ui.components.formatShortDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    now: Instant,
    timeZone: TimeZone,
    actions: TidesActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.station?.name ?: "Tides",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.station?.let { s ->
                            Text(
                                listOf(s.source.label, s.area).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    state.station?.let { station ->
                        IconButton(onClick = { actions.toggleFavourite(station) }) {
                            Icon(
                                if (state.isFavourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (state.isFavourite) "Remove from favourites" else "Add to favourites",
                                tint = if (state.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ExportMenu(enabled = state.model != null && !state.exporting, actions = actions)
                    }
                    IconButton(onClick = { actions.navigate(Screen.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loadingModel || state.exporting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.station == null) {
                EmptyState(actions)
                return@Column
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ControlsRow(state, actions)
                DateRow(state, now, timeZone, actions)

                state.modelWarning?.let { MessageCard(it, isError = false) }
                val model = state.model
                if (model == null) {
                    if (state.modelError != null) {
                        MessageCard(state.modelError, isError = true)
                        Button(onClick = actions::resync) { Text("Try again") }
                    } else if (state.loadingModel) {
                        Text(
                            "Syncing with ${state.station.source.label} to calibrate predictions…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val predictor = remember(model) { TidePredictor(model) }
                    when (state.mode) {
                        ViewMode.DAY -> DayContent(state, model, predictor, now, timeZone, actions)
                        ViewMode.WEEK -> WeekContent(state, model, predictor, now, timeZone, actions)
                    }
                }
                DisclaimerBanner(onClick = actions::showDisclaimer)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ExportMenu(enabled: Boolean, actions: TidesActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Outlined.IosShare, contentDescription = "Share or save as image")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Share image") },
                leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                onClick = { open = false; actions.export(save = false) },
            )
            DropdownMenuItem(
                text = { Text("Save PNG to device") },
                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                onClick = { open = false; actions.export(save = true) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsRow(state: UiState, actions: TidesActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { actions.navigate(Screen.STATIONS) }) {
            Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Stations")
        }
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            ViewMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { actions.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, ViewMode.entries.size),
                ) {
                    Text(if (mode == ViewMode.DAY) "Day" else "Week")
                }
            }
        }
    }
}

@Composable
private fun DateRow(state: UiState, now: Instant, timeZone: TimeZone, actions: TidesActions) {
    val today = now.toLocalDateTime(timeZone).date
    val step = if (state.mode == ViewMode.DAY) 1 else 7
    val label = when (state.mode) {
        ViewMode.DAY -> if (state.selectedDate == today) "Today, ${formatShortDate(state.selectedDate)}" else formatShortDate(state.selectedDate)
        ViewMode.WEEK -> "${formatShortDate(state.selectedDate)} to ${formatShortDate(state.selectedDate.plus(6, DateTimeUnit.DAY))}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.shiftDate(-step) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { actions.shiftDate(step) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
        }
        if (state.selectedDate != today) {
            TextButton(onClick = actions::goToToday) { Text("Today") }
        }
    }
}

@Composable
internal fun MessageCard(text: String, isError: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun EmptyState(actions: TidesActions) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Waves, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Choose a tide station", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Find the station nearest you, or search UK & Ireland, US and worldwide locations.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { actions.navigate(Screen.STATIONS) }) {
            Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Find stations")
        }
        Spacer(Modifier.height(24.dp))
        DisclaimerBanner(onClick = actions::showDisclaimer)
    }
}
