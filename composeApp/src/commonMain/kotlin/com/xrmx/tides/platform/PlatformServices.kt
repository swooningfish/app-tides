package com.xrmx.tides.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.TideAlert

/** Device features each platform provides. Methods that may prompt for permission are suspending. */
interface PlatformServices {
    /** Current position, asking for location permission if needed; null if unavailable or refused. */
    suspend fun currentLocation(): LatLon?

    /** Opens the system share sheet with a PNG image. */
    fun shareImage(png: ByteArray, fileName: String, message: String)

    /** Saves a PNG to the photo library / Pictures folder. */
    suspend fun saveImage(png: ByteArray, fileName: String): Boolean

    /** Replaces any scheduled tide alerts. Returns false if notification permission was refused. */
    suspend fun scheduleAlerts(alerts: List<TideAlert>): Boolean

    fun cancelAlerts()
}

expect fun ImageBitmap.encodePng(): ByteArray

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
