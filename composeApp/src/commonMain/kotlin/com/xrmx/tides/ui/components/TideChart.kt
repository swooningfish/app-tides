package com.xrmx.tides.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
    val readoutBackground: Color = Color(0xFF1B2023),
    val readoutText: Color = Color.White,
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
        readoutBackground = c.inverseSurface,
        readoutText = c.inverseOnSurface,
    )
}

/**
 * The on-screen chart. Touch or drag to read the predicted height at any time; tap again to clear.
 * [description] is read out by screen readers in place of the drawing.
 */
@Composable
fun TideChart(spec: ChartSpec, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = chartColorsFromTheme()
    val labelStyle = remember(colors) { TextStyle(color = colors.label, fontSize = 11.sp) }
    var selected by remember(spec.startEpoch, spec.endEpoch) { mutableStateOf<Long?>(null) }
    fun epochAt(x: Float, width: Int): Long {
        val (left, right) = plotXRange(measurer, labelStyle, width.toFloat())
        val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return spec.startEpoch + ((spec.endEpoch - spec.startEpoch) * f).toLong()
    }
    Canvas(
        modifier
            .clearAndSetSemantics { contentDescription = description }
            .pointerInput(spec.startEpoch, spec.endEpoch) {
                detectTapGestures { offset ->
                    val t = epochAt(offset.x, size.width)
                    val current = selected
                    // Tapping close to the current readout clears it.
                    selected = if (current != null && kotlin.math.abs(current - t) < (spec.endEpoch - spec.startEpoch) / 40) null else t
                }
            }
            .pointerInput(spec.startEpoch, spec.endEpoch) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> selected = epochAt(offset.x, size.width) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        selected = epochAt(change.position.x, size.width)
                    },
                )
            },
    ) {
        drawTideChart(spec, colors, measurer, labelStyle, eventFontSize = 11.sp, selectedEpoch = selected)
    }
}

/** Left and right edges of the plotting area, shared by drawing and touch handling. */
private fun plotXRange(measurer: TextMeasurer, labelStyle: TextStyle, width: Float): Pair<Float, Float> {
    val sample = measurer.measure("00.0", labelStyle)
    return (sample.size.width + 8f) to (width - 4f)
}

/** Height at [epoch] by linear interpolation between chart points. */
internal fun interpolateHeight(points: List<TidePoint>, epoch: Long): Double? {
    if (points.isEmpty()) return null
    if (epoch <= points.first().epochSeconds) return points.first().height
    if (epoch >= points.last().epochSeconds) return points.last().height
    var lo = 0
    var hi = points.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (points[mid].epochSeconds <= epoch) lo = mid else hi = mid
    }
    val a = points[lo]
    val b = points[hi]
    val f = (epoch - a.epochSeconds).toDouble() / (b.epochSeconds - a.epochSeconds)
    return a.height + (b.height - a.height) * f
}

/** Draws the tide curve. Shared by the on-screen chart and the PNG export. */
fun DrawScope.drawTideChart(
    spec: ChartSpec,
    colors: ChartColors,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    eventFontSize: TextUnit,
    selectedEpoch: Long? = null,
) {
    if (spec.points.size < 2) return
    val sample = measurer.measure("00.0", labelStyle)
    val bottomPad = sample.size.height + 8f
    val topPad = if (spec.labelEvents) sample.size.height * 2.6f else sample.size.height * 0.6f
    val (plotLeft, plotRight) = plotXRange(measurer, labelStyle, size.width)
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

    // Touch readout
    selectedEpoch?.let { t ->
        val h = interpolateHeight(spec.points, t) ?: return@let
        val xx = x(t)
        val center = Offset(xx, y(toDisplayUnits(h, spec.units)))
        drawLine(colors.label, Offset(xx, plotTop), Offset(xx, plotBottom), strokeWidth = 1f * density)
        drawCircle(colors.line, radius = 5f * density, center = center)
        drawCircle(Color.White, radius = 2.5f * density, center = center)
        val label = measurer.measure(
            "${formatTime(t, spec.timeZone)}  ${formatHeight(h, spec.units)}",
            labelStyle.copy(color = colors.readoutText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        )
        val padH = 6f * density
        val padV = 3f * density
        val w = label.size.width + 2 * padH
        val hgt = label.size.height + 2 * padV
        val bx = (xx - w / 2f).coerceIn(plotLeft, plotRight - w)
        val by = plotTop
        drawRoundRect(colors.readoutBackground, topLeft = Offset(bx, by), size = Size(w, hgt), cornerRadius = CornerRadius(6f * density))
        drawText(label, topLeft = Offset(bx + padH, by + padV))
    }
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
}
