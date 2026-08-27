package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.ui.wellness.WellnessUiState
import java.time.Instant
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
}
