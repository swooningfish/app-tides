package com.xrmx.tides.platform

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosFileStore : FileStore {
    private val dir: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val path = "$base/tides-data"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        // Caches can be re-downloaded, so keep them out of iCloud backups.
        NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        path
    }

    override fun read(name: String): String? =
        NSString.stringWithContentsOfFile("$dir/$name", encoding = NSUTF8StringEncoding, error = null)

    override fun write(name: String, text: String) {
        NSString.create(string = text).writeToFile("$dir/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtPath("$dir/$name", error = null)
    }

    override fun list(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null)?.map { it as String }.orEmpty()
}

actual fun defaultFileStore(): FileStore = IosFileStore()

@OptIn(ExperimentalSettingsImplementation::class)
actual fun secretSettings(): Settings = KeychainSettings(service = "com.xrmx.tides.keys")
