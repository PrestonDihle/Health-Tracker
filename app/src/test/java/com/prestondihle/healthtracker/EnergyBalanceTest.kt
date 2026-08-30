package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.EnergyBalance
import com.prestondihle.healthtracker.domain.ScatterPoint
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fit, and the far more important question of when it is allowed to speak.
 *
 * This is mostly refusals by design, `GoalProjectionTest`'s shape and for the
 * same reason: a maintenance figure is a specific, actionable-looking number
 * fitted to hand-logged food and a bathroom scale, and **a wrong one does not
 * look wrong, it looks like a plan.** Every test below that asserts null is
 * guarding a number the app would otherwise have printed with a straight face.
 */
class EnergyBalanceTest {

    private val day: LocalDate = LocalDate.of(2026, 1, 1)

    /**
     * A clean energy-balance dataset: maintenance at 2,500 kcal.
     *
     * Grams lost per day is the deficit converted at 7,700 kcal per kilogram, so
     * eating 2,000 loses about 65 g a day and eating 3,000 gains the same. The
     * line through it crosses y = 0 at exactly 2,500.
     */
    private fun cleanSeries(maintenance: Float = 2500f): List<ScatterPoint> =
        listOf(1800f, 2100f, 2400f, 2700f, 3000f, 3300f).mapIndexed { i, eaten ->
            ScatterPoint(
                date = day.plusDays(i.toLong() * 7),
                x = eaten,
                y = (maintenance - eaten) * 1000f / EnergyBalance.KCAL_PER_KG,
            )
        }

    @Test
    fun `a clean series finds the intake where the weight holds`() {
        val fit = EnergyBalance.fit(cleanSeries())
        assertNotNull(fit)
        val maintenance = EnergyBalance.maintenanceCalories(fit, caloriesOnX = true)

        assertNotNull(maintenance)
        // Within a few calories of 2,500, which is as exact as a float fit gets.
        assertTrue("got $maintenance", maintenance!! in 2_490..2_510)
    }

    @Test
    fun `more food means less weight lost, so the slope runs downhill`() {
        val fit = EnergyBalance.fit(cleanSeries())!!
        // The sign is the whole basis of the refusal below it. A positive slope
        // would say this reader lost more the more they ate.
        assertTrue("slope was ${fit.slope}", fit.slope < 0f)
    }

    @Test
    fun `four points are not enough for a line`() {
        assertNull(EnergyBalance.fit(cleanSeries().take(4)))
        assertNull(
            EnergyBalance.maintenanceCalories(
                EnergyBalance.fit(cleanSeries().take(4)),
                caloriesOnX = true,
            )
        )
    }

    @Test
    fun `a column of points at one intake has no slope to read`() {
        // Every week at 2,400 kcal and a different weight change each time. There
        // is no line through this, and the arithmetic divides by zero to find
        // that out rather than saying so.
        val points =
            (0..5).map {
                ScatterPoint(day.plusDays(it * 7L), x = 2_400f, y = it * 10f - 25f)
            }

        assertNull(EnergyBalance.fit(points))
    }

    @Test
    fun `a slope pointing the wrong way is refused`() {
        // Losing more the more they ate. Arithmetically a fine line, and its
        // crossing would be a maintenance figure below everything they logged --
        // a window in which something else moved, not a discovery about a
        // metabolism.
        val points =
            listOf(1800f, 2100f, 2400f, 2700f, 3000f, 3300f).mapIndexed { i, eaten ->
                ScatterPoint(day.plusDays(i.toLong() * 7), x = eaten, y = (eaten - 2000f) / 20f)
            }
        val fit = EnergyBalance.fit(points)

        assertNotNull(fit)
        assertTrue(fit!!.slope > 0f)
        assertNull(EnergyBalance.maintenanceCalories(fit, caloriesOnX = true))
    }

    @Test
    fun `a cloud too loose to read is drawn but not quoted`() {
        // Weight change essentially unrelated to intake. The line still exists --
        // seeing it lie flat through a scatter is how a reader learns not to
        // trust it -- but the number is withheld.
        val noise = listOf(40f, -35f, 30f, -45f, 38f, -30f, 42f, -40f)
        val points =
            noise.mapIndexed { i, y ->
                ScatterPoint(day.plusDays(i.toLong() * 7), x = 2_000f + i * 150f, y = y)
            }
        val fit = EnergyBalance.fit(points)

        assertNotNull(fit)
        assertTrue("r2 was ${fit!!.rSquared}", fit.rSquared < EnergyBalance.MIN_R_SQUARED)
        assertNull(EnergyBalance.maintenanceCalories(fit, caloriesOnX = true))
    }

    @Test
    fun `a crossing no diet goes near is refused`() {
        // A tight fit whose line crosses zero around 400 kcal. The arithmetic is
        // valid and the answer is about something other than energy balance --
        // which is exactly the case a plausibility bound catches and a
        // correlation threshold does not.
        val points =
            (0..5).map {
                val eaten = 1_800f + it * 100f
                ScatterPoint(day.plusDays(it * 7L), x = eaten, y = (400f - eaten) / 10f)
            }
        val fit = EnergyBalance.fit(points)

        assertNotNull(fit)
        assertTrue(fit!!.rSquared > 0.9f)
        assertNull(EnergyBalance.maintenanceCalories(fit, caloriesOnX = true))
    }

    @Test
    fun `a crossing means nothing when the x axis is not an intake`() {
        // The same clean numbers read as a heart rate. The line is identical and
        // the crossing is arithmetic with no meaning attached: there is no
        // "maintenance heart rate" to print.
        val fit = EnergyBalance.fit(cleanSeries())

        assertNotNull(fit)
        assertNull(EnergyBalance.maintenanceCalories(fit, caloriesOnX = false))
    }

    @Test
    fun `a perfectly flat y is a fit that explains nothing`() {
        // No spread in y at all. Reported as a perfect fit, which it is -- every
        // point is on the line -- with a slope of zero, which is what says it
        // explains nothing. The alternative is 0/0 in the correlation.
        val points = (0..5).map { ScatterPoint(day.plusDays(it * 7L), x = 2_000f + it * 100f, y = 0f) }
        val fit = EnergyBalance.fit(points)

        assertNotNull(fit)
        assertEquals(0f, fit!!.slope, 0.001f)
        assertEquals(1f, fit.rSquared, 0.001f)
        // And still refused, because a zero slope crosses nowhere.
        assertNull(EnergyBalance.maintenanceCalories(fit, caloriesOnX = true))
    }

    @Test
    fun `the x intercept is where the line crosses zero, not where the points stop`() {
        // The crossing is usually outside the cloud entirely -- somebody in a
        // steady deficit never ate at maintenance during the window being fitted.
        // Reading it off the nearest point instead would report the smallest
        // deficit they happened to run.
        val deficitOnly =
            (0..5).map {
                val eaten = 1_900f + it * 50f
                ScatterPoint(day.plusDays(it * 7L), x = eaten, y = (2_500f - eaten) / 7.7f)
            }
        val fit = EnergyBalance.fit(deficitOnly)!!

        assertTrue(deficitOnly.all { it.x < 2_200f })
        assertEquals(2_500f, fit.xIntercept!!, 15f)
    }
}
