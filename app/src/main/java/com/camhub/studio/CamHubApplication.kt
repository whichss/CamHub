package com.camhub.studio

import android.app.Application
import com.camhub.studio.data.network.SrtTransport
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CamHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SrtTransport.startup()
    }
}
