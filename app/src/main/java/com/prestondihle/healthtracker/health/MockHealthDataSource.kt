package com.prestondihle.healthtracker.health

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic stand-in for Health Connect, used by previews, screenshot tests
 * and emulators without the Health Connect provider installed.
 *
 * Values are seeded from the date so the same day always renders identically.
 */
class MockHealthDataSource(private val zoneId: ZoneId = ZoneId.systemDefault()) : HealthDataSource {

    override fun requiredPermissions(): Set<String> = emptySet()

    override suspend fun permissionState(): HealthPermissionState = HealthPermissionState.GRANTED

    override suspend fun missingPermissions(): Set<String> = emptySet()

    override suspend fun readDay(date: LocalDate, preferredStepsPackage: String?): HealthDay {
        val random = Random(date.toEpochDay())
        val startOfDay = date.atStartOfDay(zoneId).toInstant()

        // A CGM sample every 5 minutes, drifting around a fasting baseline with
        // a gentle post-meal rise in the afternoon.
        val samples =
            (0 until 288).map { index ->
                val minutes = index * 5L
                val hour = minutes / 60.0
                val meal = 18.0 * sin((hour - 6.0) / 24.0 * 2 * Math.PI).coerceAtLeast(0.0)
                val jitter = random.nextInt(-4, 5)
                GlucoseSample(
                    time = startOfDay.plusSeconds(minutes * 60),
                    mgDl = (88 + meal + jitter).toInt(),
                    externalId = "mock-${date}-$index",
                )
            }

        return HealthDay(
            date = date,
            steps = random.nextInt(3_000, 14_000),
            restingHeartRateBpm = random.nextInt(52, 64),
            averageHeartRateBpm = random.nextInt(66, 82),
            sleepMinutes = random.nextInt(320, 480),
            totalCalories = random.nextInt(2_100, 2_900),
            activeCalories = random.nextInt(300, 900),
            dietaryCalories = random.nextInt(1_400, 2_400),
            proteinGrams = random.nextInt(110, 190).toFloat(),
            carbGrams = random.nextInt(20, 120).toFloat(),
            fatGrams = random.nextInt(90, 170).toFloat(),
            weightKg = 82f + random.nextInt(-15, 16) / 10f,
            bestMileSeconds = random.nextInt(7 * 60, 11 * 60),
            glucoseSamples = samples,
        )
    }

    /** Two sources that disagree, which is the situation the picker exists to resolve. */
    override suspend fun readStepSources(date: LocalDate): List<StepSource> {
        val random = Random(date.toEpochDay())
        val watch = random.nextInt(3_000, 14_000)
        return listOf(
            StepSource("com.garmin.android.apps.connectmobile", "Garmin Connect", watch),
            StepSource("com.sec.android.app.shealth", "Samsung Health", (watch * 0.8f).toInt()),
        )
    }
}
