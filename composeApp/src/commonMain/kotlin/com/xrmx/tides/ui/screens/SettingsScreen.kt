package com.xrmx.tides.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xrmx.tides.AppInfo
import com.xrmx.tides.model.Region
import com.xrmx.tides.model.Units
import com.xrmx.tides.ui.Screen
import com.xrmx.tides.ui.TidesActions
import com.xrmx.tides.ui.UiState
import com.xrmx.tides.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: UiState, actions: TidesActions) {
    val settings = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { actions.navigate(Screen.HOME) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Units") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.units == Units.METRES, { actions.updateSettings { it.copy(units = Units.METRES) } }, { Text("Metres") })
                    FilterChip(settings.units == Units.FEET, { actions.updateSettings { it.copy(units = Units.FEET) } }, { Text("Feet") })
                }
            }

            SectionCard("Region") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Region.entries.forEach { r ->
                        FilterChip(settings.region == r, { actions.updateSettings { it.copy(region = r) } }, { Text(r.label) })
                    }
                }
                Hint("Sets which station lists appear. UK & Ireland uses UK Admiralty and Environment Agency data, the US uses NOAA, and Worldwide adds everything.")
            }

            SectionCard("Tide alerts") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify me before tides at my selected station", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(settings.alertsEnabled, { on -> actions.updateSettings { it.copy(alertsEnabled = on) } })
                }
                if (settings.alertsEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(settings.alertHigh, { actions.updateSettings { it.copy(alertHigh = !it.alertHigh) } }, { Text("High tide") })
                        FilterChip(settings.alertLow, { actions.updateSettings { it.copy(alertLow = !it.alertLow) } }, { Text("Low tide") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (minutes, label) ->
                            FilterChip(settings.alertLeadMinutes == minutes, { actions.updateSettings { it.copy(alertLeadMinutes = minutes) } }, { Text(label) })
                        }
                    }
                    Hint("Alerts are scheduled for the next 7 days and refreshed each time you open the app. They are predictions too, so use them only as a guide.")
                }
            }

            ApiKeysCard(state, actions)

            SectionCard("Data sources") {
                Hint("UK & Ireland: ADMIRALTY UK Tidal API (UK Hydrographic Office, key required) and the Environment Agency real-time tide gauges in England (no key needed).")
                Hint("United States: NOAA CO-OPS published harmonic constants (no key needed).")
                Hint("Worldwide: WorldTides (key required) for custom locations.")
                Hint("Tides syncs with these sources to calibrate its own prediction model, which then works offline. Contains Environment Agency data licensed under the Open Government Licence v3.0. Contains ADMIRALTY data © Crown copyright. NOAA data is in the public domain.")
            }

            SectionCard("Disclaimer") {
                Hint("Tides is predictive software. Your local lifeboat station, lifeguards or coastguard will be more accurate. Use this only as a guide.")
                OutlinedButton(onClick = actions::showDisclaimer) { Text("Read the full disclaimer") }
            }

            SectionCard("About") {
                Text("${AppInfo.NAME} ${AppInfo.VERSION}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Developed by ${AppInfo.AUTHOR}", style = MaterialTheme.typography.bodyMedium)
                Hint(AppInfo.COPYRIGHT)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ApiKeysCard(state: UiState, actions: TidesActions) {
    var ukho by remember(state.settings.ukhoKey) { mutableStateOf(state.settings.ukhoKey) }
    var worldTides by remember(state.settings.worldTidesKey) { mutableStateOf(state.settings.worldTidesKey) }
    var reveal by remember { mutableStateOf(false) }
    val changed = ukho.trim() != state.settings.ukhoKey || worldTides.trim() != state.settings.worldTidesKey
    SectionCard("API keys") {
        Hint("Keys stay on this device and are only sent to the service they belong to.")
        KeyField("UK Admiralty (UKHO) key", ukho, reveal) { ukho = it }
        Hint("Free \"Discovery\" subscription to the UK Tidal API at admiraltyapi.portal.azure-api.net.")
        KeyField("WorldTides key", worldTides, reveal) { worldTides = it }
        Hint("Paid credits from worldtides.info, used for custom locations worldwide.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.updateSettings { it.copy(ukhoKey = ukho.trim(), worldTidesKey = worldTides.trim()) } },
                enabled = changed,
            ) { Text("Save keys") }
            OutlinedButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                Text(if (reveal) "  Hide" else "  Show")
            }
        }
    }
}

@Composable
private fun KeyField(label: String, value: String, reveal: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
