package com.xrmx.tides.platform

import com.xrmx.tides.model.LatLon
import com.xrmx.tides.model.TideAlert
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum
import platform.UIKit.UIViewController
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import kotlin.coroutines.resume

class IosPlatformServices : PlatformServices {

    override suspend fun currentLocation(): LatLon? = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val manager = CLLocationManager()
            var delegate: LocationDelegate? = null
            delegate = LocationDelegate { result ->
                manager.delegate = null
                delegate = null
                if (cont.isActive) cont.resume(result)
            }
            manager.delegate = delegate
            manager.desiredAccuracy = kCLLocationAccuracyKilometer
            cont.invokeOnCancellation {
                manager.stopUpdatingLocation()
                manager.delegate = null
            }
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
                kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> {
                    manager.delegate = null
                    cont.resume(null)
                }
                else -> manager.requestLocation()
            }
        }
    }

    override fun shareImage(png: ByteArray, fileName: String, message: String) {
        val image = UIImage.imageWithData(png.toNSData()) ?: return
        val root = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = listOf(image, message), applicationActivities = null)
        // Required on iPad, where the sheet is a popover.
        sheet.popoverPresentationController?.sourceView = root.view
        root.presentViewController(sheet, animated = true, completion = null)
    }

    override suspend fun saveImage(png: ByteArray, fileName: String): Boolean {
        val image = UIImage.imageWithData(png.toNSData()) ?: return false
        UIImageWriteToSavedPhotosAlbum(image, null, null, null)
        return true
    }

    override suspend fun scheduleAlerts(alerts: List<TideAlert>): Boolean {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val granted = suspendCancellableCoroutine { cont ->
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { ok, _ ->
                if (cont.isActive) cont.resume(ok)
            }
        }
        if (!granted) return false
        center.removeAllPendingNotificationRequests()
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        // iOS keeps at most 64 pending notifications per app.
        alerts.filter { it.atEpochMillis > nowMillis + 1000 }.take(60).forEach { alert ->
            val content = UNMutableNotificationContent().apply {
                setTitle(alert.title)
                setBody(alert.body)
                setSound(UNNotificationSound.defaultSound)
            }
            val seconds = (alert.atEpochMillis - nowMillis) / 1000.0
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier("tide-${alert.id}", content, trigger)
            center.addNotificationRequest(request) { _ -> }
        }
        return true
    }

    override fun cancelAlerts() {
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
    }

    private fun topViewController(): UIViewController? {
        @Suppress("DEPRECATION")
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LocationDelegate(private val onResult: (LatLon?) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation
        onResult(location?.coordinate?.useContents { LatLon(latitude, longitude) })
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onResult(null)
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> manager.requestLocation()
            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> onResult(null)
            else -> Unit
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}
