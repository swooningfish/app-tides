package com.xrmx.tides

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xrmx.tides.platform.AndroidPlatformServices
import com.xrmx.tides.platform.PermissionRequester

class MainActivity : ComponentActivity() {
    // Must be registered before the activity starts.
    private val permissions = PermissionRequester(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val services = AndroidPlatformServices(this, permissions)
        setContent { App(services) }
    }
}
