package com.xrmx.tides.platform

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

// The desktop target only renders screenshots and runs tests, so caches needn't persist.
actual fun defaultFileStore(): FileStore = MemoryFileStore()

actual fun secretSettings(): Settings = PreferencesSettings(Preferences.userRoot().node("com/xrmx/tides/secrets"))
