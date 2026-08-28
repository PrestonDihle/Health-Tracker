package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.mealPresets
import com.prestondihle.healthtracker.ui.wellness.WellnessUiState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a measured meal time from a stamped one.
 *
 * The distinction decides whether the screen offers to correct a meal, and it is
 * a judgement made from the data rather than anything the source declares — so
 * the line it draws is worth pinning in both directions.
 */
class MealTimeStampTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun meal(id: Long, at: String, calories: Int = 500) =
        MealEntry(
            id = id,
            timestamp = Instant.parse(at),
            calories = calories,
            source = DataSourceEnum.HEALTH_CONNECT,
            externalId = "hc-$id",
        )

    /**
     * The Log tab's meal state. Its meal list is the last 24 hours, so the meals
     * seeded here sit inside that window; the stamped-time detection itself is
     * judged over every loaded meal regardless of the window.
     */
    private fun state(vararg meals: MealEntry) =
        WellnessUiState(
            now = Instant.parse("2026-08-20T18:00:00Z"),
            meals = meals.toList(),
            zoneId = zone,
        )

    @Test
    fun `a time of day shared to the second by two meals is a stamp`() {
        // The shape found on a real phone: several meals on one day, every one
        // stamped at exactly 10:00:00. Genuine timestamps do not repeat to the
        // second, so a repeat means the source knew only the date. Different
        // calories keep them three distinct meals rather than one collapsed.
        val meals =
            arrayOf(
                meal(1, "2026-08-20T10:00:00Z", calories = 240),
                meal(2, "2026-08-20T10:00:00Z", calories = 328),
                meal(3, "2026-08-20T10:00:00Z", calories = 1248),
            )
        val uiState = state(*meals)

        meals.forEach { assertFalse("${it.calories} kcal", uiState.hasClockTime(it)) }
        assertEquals(3, uiState.undatedMealsInWindow.size)
    }

    @Test
    fun `a time of day no other meal shares is a real reading`() {
        val lunch = meal(1, "2026-08-20T12:37:41Z")
        val uiState = state(lunch, meal(2, "2026-08-20T08:14:02Z"))

        assertTrue(uiState.hasClockTime(lunch))
        assertEquals(emptyList<MealEntry>(), uiState.undatedMealsInWindow)
    }

    @Test
    fun `midnight is a stamp even on its own`() {
        // A lone meal at exactly 00:00:00 has no repeat to give it away, but a
        // date rendered as an instant is the most common shape of all.
        val meal = meal(1, "2026-08-20T00:00:00Z")

        assertFalse(state(meal).hasClockTime(meal))
    }

    @Test
    fun `repeated records of one meal do not make its time look stamped`() {
        // Three copies of a single meal collapse to one before this is judged.
        // Counting them as three would have every duplicated meal accusing its
        // own timestamp of being invented.
        val eaten = meal(1, "2026-08-20T12:37:41Z", calories = 602)
        val uiState =
            state(
                eaten,
                meal(2, "2026-08-20T12:37:41Z", calories = 602),
                meal(3, "2026-08-20T12:37:41Z", calories = 602),
            )

        assertEquals(1, uiState.mealsInWindow.size)
        assertEquals(2, uiState.duplicatesCollapsed)
        assertTrue(uiState.hasClockTime(eaten))
    }

    @Test
    fun `two different meals eaten at the same second are still treated as stamped`() {
        // Accepted false positive. Two genuinely different meals sharing a second
        // is far rarer than a source stamping a fixed time, and the cost of being
        // wrong is an offer to correct a time that was already right.
        val a = meal(1, "2026-08-20T12:37:41Z", calories = 300)
        val b = meal(2, "2026-08-20T12:37:41Z", calories = 700)

        assertFalse(state(a, b).hasClockTime(a))
    }

    /**
     * The repeat that proves a stamp may be older than the list that displays it.
     *
     * Found on the phone rather than reasoned about. The source stamps 10:00:00
     * roughly once a day, so inside the list's own 24 hours there is exactly one
     * of them and nothing to repeat against -- the meal was read as a
     * measurement, printed a plausible clock time instead of "set time", and
     * once response scoring landed it also printed a rise measured from an hour
     * nobody ate in. The view model therefore loads two weeks of meals to judge
     * this and still shows one day of them.
     */
    @Test
    fun `a stamp is still a stamp when its only repeat is older than the list`() {
        val yesterdaysStamp = meal(1, "2026-08-19T10:00:00Z", calories = 700)
        val todaysStamp = meal(2, "2026-08-20T10:00:00Z", calories = 820)

        // `now` is 18:00 on the 20th, so the meal from the 19th is more than 24
        // hours old and does not appear in the list at all.
        val uiState = state(yesterdaysStamp, todaysStamp)

        assertEquals(listOf(todaysStamp), uiState.mealsInWindow)
        assertFalse(uiState.hasClockTime(todaysStamp))
        assertEquals(listOf(todaysStamp), uiState.undatedMealsInWindow)
    }

    @Test
    fun `the merged-records count stops at the window the list shows`() {
        // Two duplicate pairs, one inside the displayed day and one outside it.
        // The sentence under the list explains the rows above it, so it must
        // count only the merge the reader can actually see.
        val olderPair =
            listOf(meal(1, "2026-08-18T09:15:11Z"), meal(2, "2026-08-18T09:15:11Z"))
        val todaysPair =
            listOf(
                meal(3, "2026-08-20T13:22:07Z", calories = 640),
                meal(4, "2026-08-20T13:22:07Z", calories = 640),
            )

        val uiState = state(*(olderPair + todaysPair).toTypedArray())

        assertEquals(1, uiState.mealsInWindow.size)
        assertEquals(1, uiState.duplicatesCollapsed)
    }

    /**
     * The presets offered to fix a stamped meal, in the order they are eaten.
     *
     * The three are set in separate fields and nothing stops a night-shift reader
     * putting "breakfast" after "dinner", so the row is sorted rather than
     * declared -- a chip row running backwards reads as a bug in the app instead
     * of a choice in the settings.
     */
    @Test
    fun `the meal presets read through the day whatever order they were set in`() {
        val settings =
            UserSettings(
                mealPresetBreakfast = LocalTime.of(22, 0),
                mealPresetLunch = LocalTime.of(6, 0),
                mealPresetDinner = LocalTime.of(14, 0),
            )

        assertEquals(
            listOf(LocalTime.of(6, 0), LocalTime.of(14, 0), LocalTime.of(22, 0)),
            settings.mealPresets,
        )
    }

    @Test
    fun `two presets set to the same time are offered once`() {
        // Two identical chips are one chip drawn twice, and the second is a tap
        // that cannot do anything the first did not.
        val settings =
            UserSettings(
                mealPresetBreakfast = LocalTime.of(7, 0),
                mealPresetLunch = LocalTime.of(7, 0),
                mealPresetDinner = LocalTime.of(19, 0),
            )

        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(19, 0)), settings.mealPresets)
    }

    /** The shipped defaults, which are what an upgrading user's migration seeds. */
    @Test
    fun `the presets default to breakfast lunch and dinner`() {
        assertEquals(
            listOf(LocalTime.of(6, 30), LocalTime.of(12, 0), LocalTime.of(18, 30)),
            UserSettings().mealPresets,
        )
    }
}
