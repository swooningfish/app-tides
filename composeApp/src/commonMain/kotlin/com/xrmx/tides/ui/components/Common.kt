package com.xrmx.tides.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xrmx.tides.engine.MoonInfo
import com.xrmx.tides.engine.SunMoon
import com.xrmx.tides.ui.theme.WarningColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

const val DISCLAIMER_SHORT =
    "Predictions only. Your local lifeboat station, lifeguards or coastguard will be more accurate. Use this only as a guide."

val DISCLAIMER_PARAGRAPHS = listOf(
    "Tides uses predictive software to estimate tide times and heights. Predictions can be wrong: weather, storm surges, river flow and air pressure all change real tides, and the model itself is an approximation.",
    "Your local lifeboat station, lifeguards or coastguard (maritime) station will have more accurate, local information. Check with them before going on or near the water.",
    "Use this app only as a guide. Never rely on it for navigation or for decisions where safety is at stake.",
    "In an emergency at the coast in the UK or Ireland, call 999 or 112 and ask for the Coastguard.",
)

@Composable
fun DisclaimerBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (dark) WarningColors.darkContainer else WarningColors.lightContainer,
            contentColor = if (dark) WarningColors.darkContent else WarningColors.lightContent,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                DISCLAIMER_SHORT,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun MoonPhaseIcon(moon: MoonInfo, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val lit = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Canvas(modifier.size(size)) { drawMoon(moon, lit, dark) }
}

/** Draws the lit part of the Moon (northern hemisphere orientation). */
fun DrawScope.drawMoon(moon: MoonInfo, lit: Color, dark: Color) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(dark, r, c)
    val k = cos(2 * PI * moon.ageDays / SunMoon.SYNODIC_MONTH).toFloat()
    val circle = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
    val ellipse = Rect(c.x - abs(k) * r, c.y - r, c.x + abs(k) * r, c.y + r)
    val crescent = k > 0
    val path = Path()
    if (moon.waxing) {
        path.arcTo(circle, -90f, 180f, true)
        path.arcTo(ellipse, 90f, if (crescent) -180f else 180f, false)
    } else {
        path.arcTo(circle, 90f, 180f, true)
        path.arcTo(ellipse, 270f, if (crescent) -180f else 180f, false)
    }
    path.close()
    drawPath(path, lit)
}
