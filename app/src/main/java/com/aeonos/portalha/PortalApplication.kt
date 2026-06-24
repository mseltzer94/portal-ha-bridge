package com.aeonos.portalha

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PortalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.forceDarkMode) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
