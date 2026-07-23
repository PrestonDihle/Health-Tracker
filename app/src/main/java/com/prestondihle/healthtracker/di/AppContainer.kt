package com.prestondihle.healthtracker.di

import android.content.Context
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.HealthConnectDataSource
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository

interface AppContainer {
    val trackerRepository: TrackerRepository
}

/**
 * The real Health Connect source is always used. When the provider is missing it
 * reports [com.prestondihle.healthtracker.health.HealthPermissionState.UNAVAILABLE]
 * and returns empty days, so no mock fallback is needed outside previews.
 */
class DefaultAppContainer(private val context: Context) : AppContainer {
    private val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }

    private val healthDataSource: HealthDataSource by lazy { HealthConnectDataSource(context) }

    override val trackerRepository: TrackerRepository by lazy {
        TrackerRepository(database.trackerDao(), healthDataSource)
    }
}
