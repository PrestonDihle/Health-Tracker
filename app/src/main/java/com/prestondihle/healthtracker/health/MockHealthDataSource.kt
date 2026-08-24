package com.prestondihle.healthtracker.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

    /**
     * A CGM sample every 5 minutes, drifting around a fasting baseline with a
     * gentle post-meal rise in the afternoon.
     *
     * Seeded from the date and given ids derived from it, so the same day always
     * produces the same readings however it is asked for. A gap re-read that
     * invented fresh ids would defeat the deduplication it is supposed to be
     * exercising and double every reading it touched.
     */
    private fun glucoseFor(date: LocalDate): List<GlucoseSample> {
        val random = Random(date.toEpochDay())
        val startOfDay = date.atStartOfDay(zoneId).toInstant()
        return (0 until 288).map { index ->
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
    }

    override suspend fun readGlucose(from: Instant, to: Instant): List<GlucoseSample> {
        var date = from.atZone(zoneId).toLocalDate()
        val lastDate = to.atZone(zoneId).toLocalDate()
        val samples = mutableListOf<GlucoseSample>()
        while (!date.isAfter(lastDate)) {
            samples += glucoseFor(date)
            date = date.plusDays(1)
        }
        return samples.filter { !it.time.isBefore(from) && !it.time.isAfter(to) }.sortedBy { it.time }
    }

    override suspend fun readDay(date: LocalDate, preferredStepsPackage: String?): HealthDay {
        val random = Random(date.toEpochDay())
        val samples = glucoseFor(date)

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

    /**
     * Three meals a day at 08:00, 13:00 and 19:00.
     *
     * Fixed times rather than random ones: the master graph is read by comparing
     * an absorption curve against a glucose trace, and a meal that moves between
     * renders makes that comparison impossible to check.
     */
    override suspend fun readMeals(from: Instant, to: Instant): List<MealSample> {
        val firstDay = from.atZone(zoneId).toLocalDate()
        val lastDay = to.atZone(zoneId).toLocalDate()

        return generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDay) }
            .flatMap { date ->
                val random = Random(date.toEpochDay())
                listOf(8 to "Breakfast", 13 to "Lunch", 19 to "Dinner").map { (hour, label) ->
                    val protein = random.nextInt(25, 60).toFloat()
                    val carbs = random.nextInt(5, 70).toFloat()
                    val fat = random.nextInt(20, 60).toFloat()
                    MealSample(
                        time = date.atTime(hour, 0).atZone(zoneId).toInstant(),
                        calories = (protein * 4 + carbs * 4 + fat * 9).toInt(),
                        proteinGrams = protein,
                        carbGrams = carbs,
                        fatGrams = fat,
                        name = label,
                        externalId = "mock-meal-$date-$hour",
                    )
                }
            }
            .filter { !it.time.isBefore(from) && it.time.isBefore(to) }
            .toList()
    }

    /** A sample a minute, drifting around a resting rate with a daytime rise. */
    override suspend fun readHeartRate(from: Instant, to: Instant): List<HeartRateSample> {
        val minutes = Duration.between(from, to).toMinutes().coerceAtMost(60 * 48)
        val random = Random(from.epochSecond)
        return (0 until minutes).map { minute ->
            val time = from.plusSeconds(minute * 60)
            val hourOfDay = time.atZone(zoneId).hour + time.atZone(zoneId).minute / 60.0
            val awake = 14.0 * sin((hourOfDay - 4.0) / 24.0 * 2 * Math.PI).coerceAtLeast(0.0)
            HeartRateSample(time = time, bpm = (58 + awake + random.nextInt(-3, 4)).toInt())
        }
    }

    /**
     * A waking day of walking: nothing overnight, a commute-shaped morning and
     * evening, and a trickle in between.
     *
     * Zero hours are emitted rather than omitted, because a chart that draws a
     * gap where the answer is "none" is saying something different.
     */
    override suspend fun readStepsByHour(
        from: Instant,
        to: Instant,
        preferredStepsPackage: String?,
    ): List<HourlySteps> {
        val start = from.atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant()
        if (!start.isBefore(to)) return emptyList()

        return generateSequence(start) { it.plus(Duration.ofHours(1)) }
            .takeWhile { it.isBefore(to) }
            .map { hourStart ->
                val local = hourStart.atZone(zoneId)
                val random = Random(hourStart.epochSecond)
                val steps =
                    when (local.hour) {
                        in 0..5 -> 0
                        7, 8, 17, 18 -> random.nextInt(900, 2_200)
                        in 6..21 -> random.nextInt(60, 700)
                        else -> random.nextInt(0, 120)
                    }
                HourlySteps(hourStart, steps)
            }
            .toList()
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
