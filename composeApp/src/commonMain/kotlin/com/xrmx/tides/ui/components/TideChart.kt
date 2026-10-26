package com.xrmx.tides.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.xrmx.tides.model.TideEvent
import com.xrmx.tides.model.TidePoint
import com.xrmx.tides.model.TideType
import com.xrmx.tides.model.Units
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

enum class AxisMode { HOURS, DAYS }

data class ChartSpec(
    val startEpoch: Long,
    val endEpoch: Long,
    val points: List<TidePoint>,
    val events: List<TideEvent>,
    val nights: List<Pair<Long, Long>>,
    val nowEpoch: Long?,
    val timeZone: TimeZone,
    val units: Units,
    val axis: AxisMode,
    val labelEvents: Boolean,
)

data class ChartColors(
    val line: Color,
    val fillTop: Color,
    val fillBottom: Color,
    val grid: Color,
    val label: Color,
    val night: Color,
    val now: Color,
    val high: Color,
    val low: Color,
)

@Composable
fun chartColorsFromTheme(): ChartColors {
    val c = MaterialTheme.colorScheme
    return ChartColors(
        line = c.primary,
        fillTop = c.primary.copy(alpha = 0.35f),
        fillBottom = c.primary.copy(alpha = 0.04f),
        grid = c.outline.copy(alpha = 0.25f),
        label = c.onSurfaceVariant,
        night = c.onSurface.copy(alpha = 0.06f),
        now = c.error,
        high = c.primary,
        low = c.tertiary,
    )
}

@Composable
fun TideChart(spec: ChartSpec, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = chartColorsFromTheme()
    val labelStyle = remember(colors) { TextStyle(color = colors.label, fontSize = 11.sp) }
    Canvas(modifier) {
        drawTideChart(spec, colors, measurer, labelStyle, eventFontSize = 11.sp)
    }
}

/** Draws the tide curve. Shared by the on-screen chart and the PNG export. */
fun DrawScope.drawTideChart(
    spec: ChartSpec,
    colors: ChartColors,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    eventFontSize: TextUnit,
) {
    if (spec.points.size < 2) return
    val sample = measurer.measure("00.0", labelStyle)
    val leftPad = sample.size.width + 8f
    val bottomPad = sample.size.height + 8f
    val topPad = if (spec.labelEvents) sample.size.height * 2.6f else sample.size.height * 0.6f
    val plotLeft = leftPad
    val plotRight = size.width - 4f
    val plotTop = topPad
    val plotBottom = size.height - bottomPad - (if (spec.labelEvents) sample.size.height * 2.2f else 0f)
    val axisBottom = size.height - bottomPad
    if (plotRight <= plotLeft || plotBottom <= plotTop) return

    val values = spec.points.map { toDisplayUnits(it.height, spec.units) }
    val rawMin = values.min()
    val rawMax = values.max()
    val pad = ((rawMax - rawMin) * 0.08).coerceAtLeast(0.1)
    val step = niceStep((rawMax - rawMin + 2 * pad) / 4)
    val yMin = floor((rawMin - pad) / step) * step
    val yMax = ceil((rawMax + pad) / step) * step

    fun x(epoch: Long): Float =
        plotLeft + (plotRight - plotLeft) * ((epoch - spec.startEpoch).toFloat() / (spec.endEpoch - spec.startEpoch).toFloat())
    fun y(value: Double): Float = plotBottom - (plotBottom - plotTop) * ((value - yMin) / (yMax - yMin)).toFloat()

    // Night shading
    for ((from, to) in spec.nights) {
        drawRect(colors.night, topLeft = Offset(x(from), 0f), size = Size(x(to) - x(from), axisBottom))
    }

    // Horizontal grid and height labels
    var v = yMin
    while (v <= yMax + step / 2) {
        val yy = y(v)
        drawLine(colors.grid, Offset(plotLeft, yy), Offset(plotRight, yy), strokeWidth = 1f)
        val text = measurer.measure(formatDecimal(v, if (step < 1) 1 else 0), labelStyle)
        drawText(text, topLeft = Offset(plotLeft - text.size.width - 6f, yy - text.size.height / 2f))
        v += step
    }

    // Time axis
    val startDate = Instant.fromEpochSeconds(spec.startEpoch).toLocalDateTime(spec.timeZone).date
    when (spec.axis) {
        AxisMode.HOURS -> {
            val midnight = startDate.atStartOfDayIn(spec.timeZone).epochSeconds
            for (hour in 0..24 step 3) {
                val t = midnight + hour * 3600L
                if (t < spec.startEpoch || t > spec.endEpoch) continue
                val xx = x(t)
                drawLine(colors.grid, Offset(xx, plotTop), Offset(xx, axisBottom), strokeWidth = 1f)
                if (hour in 1..23) {
                    val text = measurer.measure(hour.toString().padStart(2, '0'), labelStyle)
                    drawText(text, topLeft = Offset(xx - text.size.width / 2f, axisBottom + 4f))
                }
            }
        }
        AxisMode.DAYS -> {
            var d = startDate
            while (true) {
                val t = d.atStartOfDayIn(spec.timeZone).epochSeconds
                if (t >= spec.endEpoch) break
                val next = d.plus(1, DateTimeUnit.DAY).atStartOfDayIn(spec.timeZone).epochSeconds
                val xx = x(t)
                drawLine(colors.grid, Offset(xx, plotTop), Offset(xx, axisBottom), strokeWidth = 1f)
                val text = measurer.measure(shortDayName(d), labelStyle)
                val mid = (x(t) + x(minOf(next, spec.endEpoch))) / 2f
                drawText(text, topLeft = Offset(mid - text.size.width / 2f, axisBottom + 4f))
                d = d.plus(1, DateTimeUnit.DAY)
            }
        }
    }

    // Curve and fill
    val line = Path()
    spec.points.forEachIndexed { i, p ->
        val px = x(p.epochSeconds)
        val py = y(values[i])
        if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
    }
    val fill = Path().apply {
        addPath(line)
        lineTo(x(spec.points.last().epochSeconds), plotBottom)
        lineTo(x(spec.points.first().epochSeconds), plotBottom)
        close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(colors.fillTop, colors.fillBottom), startY = plotTop, endY = plotBottom))
    drawPath(line, colors.line, style = Stroke(width = 2.5f * density))

    // Now marker
    spec.nowEpoch?.takeIf { it in spec.startEpoch..spec.endEpoch }?.let { now ->
        val xx = x(now)
        drawLine(
            colors.now, Offset(xx, plotTop - 4f), Offset(xx, plotBottom),
            strokeWidth = 1.5f * density,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density)),
        )
        val text = measurer.measure("Now", labelStyle.copy(color = colors.now, fontWeight = FontWeight.Bold))
        val tx = (xx - text.size.width / 2f).coerceIn(plotLeft, plotRight - text.size.width)
        drawText(text, topLeft = Offset(tx, (plotTop - text.size.height - 2f).coerceAtLeast(0f)))
    }

    // High and low water markers
    val eventStyle = labelStyle.copy(fontSize = eventFontSize, fontWeight = FontWeight.SemiBold)
    for (e in spec.events) {
        if (e.epochSeconds !in spec.startEpoch..spec.endEpoch) continue
        val color = if (e.type == TideType.HIGH) colors.high else colors.low
        val center = Offset(x(e.epochSeconds), y(toDisplayUnits(e.height, spec.units)))
        drawCircle(Color.White, radius = 4.5f * density, center = center)
        drawCircle(color, radius = 3.5f * density, center = center)
        if (spec.labelEvents) {
            val label = "${formatTime(e.epochSeconds, spec.timeZone)}\n${formatHeight(e.height, spec.units)}"
            val text = measurer.measure(label, eventStyle.copy(color = color))
            val tx = (center.x - text.size.width / 2f).coerceIn(plotLeft + 2f, size.width - text.size.width)
            val ty = if (e.type == TideType.HIGH) center.y - text.size.height - 6f else center.y + 6f
            drawText(text, topLeft = Offset(tx, ty.coerceIn(0f, size.height - text.size.height)))
        }
    }
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
}
