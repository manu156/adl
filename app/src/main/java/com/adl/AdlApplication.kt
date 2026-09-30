package com.adl

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AdlApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize Chaquopy Python runtime
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
