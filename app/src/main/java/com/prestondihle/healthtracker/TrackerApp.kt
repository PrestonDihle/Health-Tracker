package com.prestondihle.healthtracker

import android.app.Application
import com.prestondihle.healthtracker.di.AppContainer
import com.prestondihle.healthtracker.di.DefaultAppContainer

class TrackerApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
