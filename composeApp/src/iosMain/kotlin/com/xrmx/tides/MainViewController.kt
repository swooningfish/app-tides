package com.xrmx.tides

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.xrmx.tides.platform.IosPlatformServices

@Suppress("FunctionName", "unused") // Called from Swift
fun MainViewController() = ComposeUIViewController {
    val services = remember { IosPlatformServices() }
    App(services)
}
