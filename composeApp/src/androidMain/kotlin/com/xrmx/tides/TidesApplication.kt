package com.xrmx.tides

import android.app.Application
import android.content.Context
import com.xrmx.tides.alerts.AlertScheduler

class TidesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        // Keeps alerts topped up even if the app isn't opened for days.
        AlertScheduler.ensureDailyRefresh(this)
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
