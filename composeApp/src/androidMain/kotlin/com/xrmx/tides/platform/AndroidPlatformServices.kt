package com.xrmx.tides.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.xrmx.tides.alerts.AlertScheduler
import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.TideAlert
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** Wraps the activity-result permission API in a suspend function. */
class PermissionRequester(activity: ComponentActivity) {
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending?.complete(result)
        pending = null
    }

    suspend fun request(vararg permissions: String): Map<String, Boolean> {
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pending?.cancel()
        pending = deferred
        launcher.launch(arrayOf(*permissions))
        return deferred.await()
    }
}

class AndroidPlatformServices(
    private val activity: ComponentActivity,
    private val permissions: PermissionRequester,
) : PlatformServices {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): LatLon? {
        val hasPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            permissions.request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).values.any { it }
        if (!hasPermission) return null

        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true)
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) return last.toLatLon()

        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> providers.firstOrNull()
        } ?: return last?.toLatLon()

        val fresh = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(activity)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                    cont.invokeOnCancellation { manager.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
        return (fresh ?: last)?.toLatLon()
    }

    override fun shareImage(png: ByteArray, fileName: String, message: String) {
        val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName).apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share tide chart"))
    }

    override suspend fun saveImage(png: ByteArray, fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
            permissions.request(Manifest.permission.WRITE_EXTERNAL_STORAGE)[Manifest.permission.WRITE_EXTERNAL_STORAGE] != true
        ) {
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Tides")
                    }
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(png) } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun scheduleAlerts(alerts: List<TideAlert>): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            val ok = permissions.request(Manifest.permission.POST_NOTIFICATIONS)[Manifest.permission.POST_NOTIFICATIONS] == true
            if (!ok) return false
        }
        AlertScheduler.schedule(activity.applicationContext, alerts)
        return true
    }

    override fun cancelAlerts() = AlertScheduler.cancel(activity.applicationContext)

    private fun Location.toLatLon() = LatLon(latitude, longitude)
}
