package com.xrmx.tides.model

object CatalogTestData {
    val stations = listOf(
        Station("noaa:9414290", "San Francisco", 37.8, -122.46, SourceType.NOAA, "CA"),
        Station("noaa:8518750", "The Battery", 40.7, -74.01, SourceType.NOAA, "NY"),
    )

    /** A made-up semidiurnal station, the same shape as the screenshot demo. */
    val demoModel = HarmonicModel(
        stationId = "ea:demo",
        z0 = 0.2,
        constituents = listOf(
            ConstituentValue("M2", 1.70, 150.0),
            ConstituentValue("S2", 0.60, 195.0),
            ConstituentValue("N2", 0.32, 130.0),
            ConstituentValue("K1", 0.07, 110.0),
            ConstituentValue("O1", 0.06, 340.0),
        ),
        datum = "Ordnance Datum Newlyn (mAOD)",
        method = "test",
        syncedAtEpochSeconds = 0,
    )

    val demoStation = Station("ea:demo", "Demo", 50.37, -4.14, SourceType.EA, "England")
}
