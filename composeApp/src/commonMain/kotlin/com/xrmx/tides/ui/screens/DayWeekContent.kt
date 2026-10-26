package com.xrmx.tides.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xrmx.tides.engine.DayData
import com.xrmx.tides.engine.TideCalculations
import com.xrmx.tides.engine.TidePredictor
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TideType
import com.xrmx.tides.model.Units
import com.xrmx.tides.ui.TidesActions
import com.xrmx.tides.ui.UiState
import com.xrmx.tides.ui.components.AxisMode
import com.xrmx.tides.ui.components.ChartSpec
import com.xrmx.tides.ui.components.MoonPhaseIcon
import com.xrmx.tides.ui.components.SectionCard
import com.xrmx.tides.ui.components.TideChart
import com.xrmx.tides.ui.components.formatDateTime
import com.xrmx.tides.ui.components.formatDecimal
import com.xrmx.tides.ui.components.formatDuration
import com.xrmx.tides.ui.components.formatHeight
import com.xrmx.tides.ui.components.formatShortDate
import com.xrmx.tides.ui.components.formatTime
import com.xrmx.tides.ui.components.toDisplayUnits
import com.xrmx.tides.ui.components.unitSuffix
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

@Composable
internal fun DayContent(
    state: UiState,
    model: HarmonicModel,
    predictor: TidePredictor,
    now: Instant,
    timeZone: TimeZone,
    actions: TidesActions,
) {
    val station = state.station ?: return
    val units = state.settings.units
    val day = remember(predictor, station, state.selectedDate, timeZone) {
        TideCalculations.day(model, predictor, station, state.selectedDate, timeZone)
    }
    val isToday = now.epochSeconds in day.startEpoch until day.endEpoch

    if (isToday) {
        val status = remember(predictor, now) { TideCalculations.now(model, predictor, now) }
        NowCard(status.height, status.rising, status.next, now, timeZone, units)
    }

    Card(Modifier.fillMaxWidth()) {
        TideChart(
            ChartSpec(
                startEpoch = day.startEpoch,
                endEpoch = day.endEpoch,
                points = day.points,
                events = day.events,
                nights = day.nights,
                nowEpoch = if (isToday) now.epochSeconds else null,
                timeZone = timeZone,
                units = units,
                axis = AxisMode.HOURS,
                labelEvents = true,
            ),
            Modifier.fillMaxWidth().height(280.dp).padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        )
    }

    SectionCard("High & low water") {
        if (day.events.isEmpty()) {
            Text("No high or low water on this day.", style = MaterialTheme.typography.bodyMedium)
        }
        day.events.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider()
            EventRow(e, timeZone, units)
        }
    }

    SunMoonCard(day, timeZone)
    StationInfoCard(state, model, timeZone, actions)
}

@Composable
private fun NowCard(height: Double, rising: Boolean, next: TideEvent?, now: Instant, timeZone: TimeZone, units: Units) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatHeight(height, units), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (rising) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = if (rising) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (rising) " Rising (flood)" else " Falling (ebb)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (next != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (next.type == TideType.HIGH) "Next high" else "Next low",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(formatTime(next.epochSeconds, timeZone), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatHeight(next.height, units)} · in ${formatDuration(next.epochSeconds - now.epochSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: TideEvent, timeZone: TimeZone, units: Units) {
    val high = e.type == TideType.HIGH
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (high) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = null,
            tint = if (high) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            if (high) "High water" else "Low water",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        if (e.reference) {
            Text(
                "Official",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        Text(formatTime(e.epochSeconds, timeZone), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            formatHeight(e.height, units),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(76.dp).padding(start = 12.dp),
        )
    }
}

@Composable
private fun SunMoonCard(day: DayData, timeZone: TimeZone) {
    SectionCard("Sun & moon") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFE0A030))
                    Text("  Sunrise ${day.sun.sunrise?.let { formatTime(it, timeZone) } ?: "none"}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WbTwilight, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFD0703A))
                    Text("  Sunset ${day.sun.sunset?.let { formatTime(it, timeZone) } ?: "none"}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                    Text(day.moon.phaseName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${(day.moon.illumination * 100).roundToInt()}% lit", style = MaterialTheme.typography.bodySmall)
                }
                MoonPhaseIcon(day.moon)
            }
        }
        Text(day.moon.tideHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StationInfoCard(state: UiState, model: HarmonicModel, timeZone: TimeZone, actions: TidesActions) {
    val station = state.station ?: return
    SectionCard("About this prediction") {
        InfoLine("Source", station.source.label)
        InfoLine("Heights", "${unitSuffix(state.settings.units)} above ${model.datum}")
        InfoLine("Times", timeZone.id)
        InfoLine("Method", model.method)
        model.rmsError?.let { InfoLine("Fit error", "± ${formatDecimal(toDisplayUnits(it, state.settings.units), 2)} ${unitSuffix(state.settings.units)} (RMS)") }
        InfoLine("Last synced", formatDateTime(model.syncedAtEpochSeconds, timeZone))
        TextButton(onClick = actions::resync, enabled = !state.loadingModel) {
            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Sync now")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekContent(
    state: UiState,
    model: HarmonicModel,
    predictor: TidePredictor,
    now: Instant,
    timeZone: TimeZone,
    actions: TidesActions,
) {
    val station = state.station ?: return
    val units = state.settings.units
    val week = remember(predictor, station, state.selectedDate, timeZone) {
        TideCalculations.week(model, predictor, station, state.selectedDate, timeZone)
    }
    Card(Modifier.fillMaxWidth()) {
        TideChart(
            ChartSpec(
                startEpoch = week.startEpoch,
                endEpoch = week.endEpoch,
                points = week.points,
                events = week.days.flatMap { it.events },
                nights = week.nights,
                nowEpoch = now.epochSeconds,
                timeZone = timeZone,
                units = units,
                axis = AxisMode.DAYS,
                labelEvents = false,
            ),
            Modifier.fillMaxWidth().height(220.dp).padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        )
    }
    for (day in week.days) {
        Card(Modifier.fillMaxWidth().clickable { actions.openDay(day.date) }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatShortDate(day.date), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(day.moon.phaseName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    MoonPhaseIcon(day.moon, size = 18.dp)
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    day.events.forEach { e ->
                        val high = e.type == TideType.HIGH
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (high) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = if (high) "High" else "Low",
                                tint = if (high) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                " ${formatTime(e.epochSeconds, timeZone)}  ${formatHeight(e.height, units)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
