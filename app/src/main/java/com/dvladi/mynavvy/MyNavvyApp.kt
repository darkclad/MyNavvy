package com.dvladi.mynavvy

import android.app.Application

/**
 * Application entry point. Initializes remote diagnostics as early as possible
 * so crashes during startup are still captured.
 */
class MyNavvyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
    }
}
