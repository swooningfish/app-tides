package com.xrmx.tides.engine

import com.xrmx.tides.model.AppSettings
import com.xrmx.tides.model.HarmonicModel

const val ESTIMATED_CHART_DATUM = "Estimated Chart Datum (lowest predicted tide)"

/**
 * The model as it should be displayed: when the user wants it and the source's datum isn't
 * Chart Datum, heights are shifted so zero is the lowest predicted tide, which is close to the
 * Chart Datum used in printed tide tables.
 */
fun HarmonicModel.forDisplay(settings: AppSettings): HarmonicModel {
    val lowest = lowestLevel ?: return this
    if (!settings.estimatedChartDatum) return this
    return copy(
        z0 = z0 - lowest,
        datum = ESTIMATED_CHART_DATUM,
        referenceEvents = referenceEvents.map { it.copy(height = it.height - lowest) },
        lowestLevel = null,
    )
}
