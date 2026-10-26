package com.xrmx.tides.export

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.xrmx.tides.AppInfo
import com.xrmx.tides.engine.TideCalculations
import com.xrmx.tides.engine.TidePredictor
import com.xrmx.tides.model.HarmonicModel
import com.xrmx.tides.model.Station
import com.xrmx.tides.model.TideType
import com.xrmx.tides.model.Units
import com.xrmx.tides.ui.ViewMode
import com.xrmx.tides.ui.components.AxisMode
import com.xrmx.tides.ui.components.ChartColors
import com.xrmx.tides.ui.components.ChartSpec
import com.xrmx.tides.ui.components.DISCLAIMER_PARAGRAPHS
import com.xrmx.tides.ui.components.drawTideChart
import com.xrmx.tides.ui.components.formatHeight
import com.xrmx.tides.ui.components.formatLongDate
import com.xrmx.tides.ui.components.formatShortDate
import com.xrmx.tides.ui.components.formatTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

data class ExportRow(val label: String, val value: String)

data class ExportContent(
    val title: String,
    val subtitle: String,
    val sourceLine: String,
    val chart: ChartSpec,
    val rows: List<ExportRow>,
    val extraLine: String?,
)

/** Renders a shareable PNG: header, tide chart, table of high/low waters and the safety disclaimer. */
object ExportRenderer {
    private const val WIDTH = 1080
    private const val MARGIN = 56f
    private val density = Density(2f)

    private val primary = Color(0xFF00658E)
    private val tertiary = Color(0xFF00897B)
    private val ink = Color(0xFF171C1F)
    private val muted = Color(0xFF41484D)
    private val warningBg = Color(0xFFFFF1D6)
    private val warningInk = Color(0xFF5C3B00)

    private val chartColors = ChartColors(
        line = primary,
        fillTop = primary.copy(alpha = 0.35f),
        fillBottom = primary.copy(alpha = 0.04f),
        grid = Color(0x4071787E),
        label = muted,
        night = Color(0x0F171C1F),
        now = Color(0xFFBA1A1A),
        high = primary,
        low = tertiary,
    )

    fun contentFor(
        station: Station,
        model: HarmonicModel,
        mode: ViewMode,
        date: LocalDate,
        now: Instant,
        tz: TimeZone,
        units: Units,
    ): ExportContent {
        val predictor = TidePredictor(model)
        val sourceLine = "${station.source.label} · heights above ${model.datum} · times ${tz.id}"
        return when (mode) {
            ViewMode.DAY -> {
                val day = TideCalculations.day(model, predictor, station, date, tz)
                val sun = listOfNotNull(
                    day.sun.sunrise?.let { "Sunrise ${formatTime(it, tz)}" },
                    day.sun.sunset?.let { "Sunset ${formatTime(it, tz)}" },
                )
                ExportContent(
                    title = station.name,
                    subtitle = formatLongDate(date),
                    sourceLine = sourceLine,
                    chart = ChartSpec(day.startEpoch, day.endEpoch, day.points, day.events, day.nights, now.epochSeconds, tz, units, AxisMode.HOURS, labelEvents = true),
                    rows = day.events.map { e ->
                        ExportRow(
                            "${if (e.type == TideType.HIGH) "High water" else "Low water"}   ${formatTime(e.epochSeconds, tz)}",
                            formatHeight(e.height, units),
                        )
                    },
                    extraLine = (sun + "${day.moon.phaseName} · ${day.moon.tideHint}").joinToString("   ·   "),
                )
            }
            ViewMode.WEEK -> {
                val week = TideCalculations.week(model, predictor, station, date, tz)
                ExportContent(
                    title = station.name,
                    subtitle = "${formatShortDate(date)} to ${formatShortDate(date.plus(6, DateTimeUnit.DAY))}",
                    sourceLine = sourceLine,
                    chart = ChartSpec(week.startEpoch, week.endEpoch, week.points, week.days.flatMap { it.events }, week.nights, now.epochSeconds, tz, units, AxisMode.DAYS, labelEvents = false),
                    rows = week.days.map { d ->
                        ExportRow(
                            formatShortDate(d.date),
                            d.events.joinToString("   ") { e ->
                                "${if (e.type == TideType.HIGH) "HW" else "LW"} ${formatTime(e.epochSeconds, tz)} ${formatHeight(e.height, units)}"
                            },
                        )
                    },
                    extraLine = null,
                )
            }
        }
    }

    fun render(content: ExportContent, fontResolver: FontFamily.Resolver): ImageBitmap {
        val measurer = TextMeasurer(fontResolver, density, LayoutDirection.Ltr)
        val inner = (WIDTH - 2 * MARGIN).toInt()
        fun measure(text: String, style: TextStyle, width: Int = inner): TextLayoutResult =
            measurer.measure(text, style, constraints = Constraints(maxWidth = width))

        val appLabel = measure(AppInfo.NAME.uppercase() + "  ·  TIDE PREDICTIONS", TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
        val title = measure(content.title, TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold))
        val subtitle = measure(content.subtitle, TextStyle(color = Color.White, fontSize = 15.sp))
        val source = measure(content.sourceLine, TextStyle(color = muted, fontSize = 10.sp))
        val rowStyle = TextStyle(color = ink, fontSize = 13.sp)
        val rowLabelWidth = (inner * 0.32f).toInt()
        val rowLayouts = content.rows.map { row ->
            val isWeek = content.chart.axis == AxisMode.DAYS
            val label = measure(row.label, rowStyle.copy(fontWeight = FontWeight.SemiBold), if (isWeek) rowLabelWidth else (inner * 0.7f).toInt())
            val value = measure(row.value, rowStyle, if (isWeek) inner - rowLabelWidth else (inner * 0.3f).toInt())
            label to value
        }
        val extra = content.extraLine?.let { measure(it, TextStyle(color = muted, fontSize = 11.sp)) }
        val disclaimerTitle = measure("Use as a guide only", TextStyle(color = warningInk, fontSize = 13.sp, fontWeight = FontWeight.Bold), inner - 48)
        val disclaimer = measure(DISCLAIMER_PARAGRAPHS.take(3).joinToString("\n\n"), TextStyle(color = warningInk, fontSize = 10.sp), inner - 48)
        val footer = measure("Created with ${AppInfo.NAME} by ${AppInfo.AUTHOR}", TextStyle(color = muted, fontSize = 10.sp))

        val headerHeight = 48f + appLabel.size.height + 12f + title.size.height + 8f + subtitle.size.height + 40f
        val chartHeight = 620f
        val rowsHeight = rowLayouts.sumOf { (l, v) -> (maxOf(l.size.height, v.size.height) + 28).toDouble() }.toFloat()
        val disclaimerHeight = 32f + disclaimerTitle.size.height + 12f + disclaimer.size.height + 32f
        val totalHeight = headerHeight + 24f + source.size.height + 24f + chartHeight + 32f + rowsHeight +
            (extra?.let { it.size.height + 32f } ?: 0f) + disclaimerHeight + 32f + footer.size.height + 48f

        val bitmap = ImageBitmap(WIDTH, totalHeight.toInt())
        val canvas = Canvas(bitmap)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(WIDTH.toFloat(), totalHeight)) {
            drawRect(Color.White)
            drawRect(primary, size = Size(WIDTH.toFloat(), headerHeight))
            var y = 48f
            drawText(appLabel, topLeft = Offset(MARGIN, y)); y += appLabel.size.height + 12f
            drawText(title, topLeft = Offset(MARGIN, y)); y += title.size.height + 8f
            drawText(subtitle, topLeft = Offset(MARGIN, y))
            y = headerHeight + 24f
            drawText(source, topLeft = Offset(MARGIN, y)); y += source.size.height + 24f

            translate(MARGIN, y) {
                drawIntoChart(content, measurer, Size(inner.toFloat(), chartHeight))
            }
            y += chartHeight + 32f

            for ((i, pair) in rowLayouts.withIndex()) {
                val (label, value) = pair
                val h = maxOf(label.size.height, value.size.height) + 28f
                if (i % 2 == 0) {
                    drawRoundRect(Color(0xFFF0F4F8), topLeft = Offset(MARGIN - 12f, y), size = Size(inner + 24f, h), cornerRadius = CornerRadius(12f))
                }
                drawText(label, topLeft = Offset(MARGIN, y + 14f))
                val valueX = if (content.chart.axis == AxisMode.DAYS) MARGIN + rowLabelWidth else MARGIN + inner - value.size.width
                drawText(value, topLeft = Offset(valueX, y + 14f))
                y += h
            }
            if (extra != null) {
                y += 16f
                drawText(extra, topLeft = Offset(MARGIN, y)); y += extra.size.height + 16f
            }
            y += 16f
            drawRoundRect(warningBg, topLeft = Offset(MARGIN, y), size = Size(inner.toFloat(), disclaimerHeight - 16f), cornerRadius = CornerRadius(20f))
            drawRoundRect(Color(0xFFE0A030), topLeft = Offset(MARGIN, y), size = Size(inner.toFloat(), disclaimerHeight - 16f), cornerRadius = CornerRadius(20f), style = Stroke(width = 3f))
            drawText(disclaimerTitle, topLeft = Offset(MARGIN + 24f, y + 24f))
            drawText(disclaimer, topLeft = Offset(MARGIN + 24f, y + 24f + disclaimerTitle.size.height + 12f))
            y += disclaimerHeight + 16f
            drawText(footer, topLeft = Offset(MARGIN, y))
        }
        return bitmap
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoChart(content: ExportContent, measurer: TextMeasurer, chartSize: Size) {
        // DrawScope.size is the whole image, so draw the chart into a nested scope of the right size.
        val scope = CanvasDrawScope()
        scope.draw(this, layoutDirection, drawContext.canvas, chartSize) {
            drawTideChart(
                spec = content.chart,
                colors = chartColors,
                measurer = measurer,
                labelStyle = TextStyle(color = chartColors.label, fontSize = 11.sp),
                eventFontSize = 11.sp,
            )
        }
    }
}
