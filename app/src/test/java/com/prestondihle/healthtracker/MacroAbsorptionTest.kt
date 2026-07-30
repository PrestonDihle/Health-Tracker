package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.domain.MacroServing
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroAbsorptionTest {

    private val noon: Instant = Instant.parse("2026-07-24T12:00:00Z")

    private fun mealAt(at: Instant, protein: Float = 0f, carbs: Float = 0f, fat: Float = 0f) =
        MacroServing(at, proteinGrams = protein, carbGrams = carbs, fatGrams = fat)

    private fun minutes(m: Long) = Duration.ofMinutes(m)

    @Test
    fun `nothing is absorbed during the gastric lag`() {
        val meals = listOf(mealAt(noon, carbs = 60f))
        // Carbs lag 15 min. At 14 minutes the food is still in the stomach.
        assertEquals(0f, MacroAbsorption.rateAt(meals, Macro.CARB, noon.plus(minutes(14))), 0.001f)
        assertTrue(MacroAbsorption.rateAt(meals, Macro.CARB, noon.plus(minutes(20))) > 0f)
    }

    @Test
    fun `a meal contributes nothing before it is eaten`() {
        val meals = listOf(mealAt(noon, carbs = 60f, protein = 40f, fat = 30f))
        Macro.entries.forEach { macro ->
            assertEquals(
                "$macro should be absent an hour before the meal",
                0f,
                MacroAbsorption.rateAt(meals, macro, noon.minus(Duration.ofHours(1))),
                0.001f,
            )
        }
    }

    @Test
    fun `each macro peaks at its published time to peak`() {
        val meals = listOf(mealAt(noon, carbs = 60f, protein = 40f, fat = 30f))

        Macro.entries.forEach { macro ->
            val peak = MacroAbsorption.kinetics(macro).timeToPeak
            val atPeak = MacroAbsorption.rateAt(meals, macro, noon.plus(peak))
            val before = MacroAbsorption.rateAt(meals, macro, noon.plus(peak.minusMinutes(20)))
            val after = MacroAbsorption.rateAt(meals, macro, noon.plus(peak.plusMinutes(20)))

            assertTrue(
                "$macro should be highest at its time to peak",
                atPeak > before && atPeak > after,
            )
        }
    }

    @Test
    fun `carbs peak before protein which peaks before fat`() {
        // The ordering is the whole point of the model: a glucose rise should
        // line up with the carb curve, not the fat one.
        val carbPeak = MacroAbsorption.kinetics(Macro.CARB).timeToPeak
        val proteinPeak = MacroAbsorption.kinetics(Macro.PROTEIN).timeToPeak
        val fatPeak = MacroAbsorption.kinetics(Macro.FAT).timeToPeak

        assertTrue(carbPeak < proteinPeak)
        assertTrue(proteinPeak < fatPeak)
    }

    @Test
    fun `the area under a curve is the grams eaten`() {
        // The normalisation that makes the chart honest: integrating grams-per-hour
        // over the whole curve has to return the meal, or the height means nothing.
        val grams = 60f
        val meals = listOf(mealAt(noon, carbs = grams))

        val stepMinutes = 1L
        val total =
            (0 until 60 * 12 / stepMinutes)
                .sumOf { step ->
                    val at = noon.plus(minutes(step * stepMinutes))
                    MacroAbsorption.rateAt(meals, Macro.CARB, at).toDouble()
                }
                .let { it * (stepMinutes / 60.0) }

        assertEquals(grams.toDouble(), total, 0.5)
    }

    @Test
    fun `overlapping meals add`() {
        val single = listOf(mealAt(noon, carbs = 30f))
        val doubled = listOf(mealAt(noon, carbs = 30f), mealAt(noon, carbs = 30f))
        val at = noon.plus(minutes(45))

        assertEquals(
            MacroAbsorption.rateAt(single, Macro.CARB, at) * 2f,
            MacroAbsorption.rateAt(doubled, Macro.CARB, at),
            0.01f,
        )
    }

    @Test
    fun `a meal eaten earlier still contributes to a later window`() {
        // Why the query reaches back past the left edge of the plot: a fat curve
        // from four hours ago is near its peak, not finished.
        val meals = listOf(mealAt(noon, fat = 40f))
        assertTrue(MacroAbsorption.rateAt(meals, Macro.FAT, noon.plus(Duration.ofHours(4))) > 1f)
    }

    @Test
    fun `absorbed fraction runs from zero to nearly all of the meal`() {
        assertEquals(0f, MacroAbsorption.absorbedFraction(Macro.CARB, noon, noon), 0.001f)
        assertEquals(
            0f,
            MacroAbsorption.absorbedFraction(Macro.CARB, noon, noon.plus(minutes(10))),
            0.001f,
        )

        val halfway = MacroAbsorption.absorbedFraction(Macro.CARB, noon, noon.plus(minutes(45)))
        assertTrue("about a quarter to a half in at the peak", halfway in 0.2f..0.6f)

        val late = MacroAbsorption.absorbedFraction(Macro.CARB, noon, noon.plus(Duration.ofHours(6)))
        assertTrue("essentially complete by six hours", late > 0.99f)
    }

    @Test
    fun `absorbed fraction rises monotonically`() {
        var previous = 0f
        for (step in 0..48) {
            val at = noon.plus(minutes(step * 15L))
            val fraction = MacroAbsorption.absorbedFraction(Macro.FAT, noon, at)
            assertTrue("fraction should never fall", fraction >= previous - 0.0001f)
            previous = fraction
        }
        assertTrue(previous > 0.9f)
    }

    @Test
    fun `fat is most of the way absorbed by eight hours`() {
        // The claim the four-compartment count was chosen for: chylomicron
        // triglyceride is measured back near baseline by 6-8 hours, which a
        // two-compartment curve peaking at 3.5 h badly overshoots.
        val atEight = MacroAbsorption.absorbedFraction(Macro.FAT, noon, noon.plus(Duration.ofHours(8)))
        assertTrue("expected ~95% by eight hours, got $atEight", atEight in 0.9f..0.99f)
    }

    @Test
    fun `history horizon covers a fat curve to insignificance`() {
        val meals = listOf(mealAt(noon, fat = 100f))
        val horizon = noon.plus(Duration.ofHours(MacroAbsorption.RELEVANT_HISTORY_HOURS))
        val peak = MacroAbsorption.rateAt(meals, Macro.FAT, noon.plus(MacroAbsorption.kinetics(Macro.FAT).timeToPeak))

        assertTrue(
            "a meal at the horizon should be negligible against its own peak",
            MacroAbsorption.rateAt(meals, Macro.FAT, horizon) < peak * 0.05f,
        )
    }

    @Test
    fun `a macro with no grams draws nothing`() {
        val meals = listOf(mealAt(noon, carbs = 60f))
        assertEquals(0f, MacroAbsorption.rateAt(meals, Macro.FAT, noon.plus(minutes(90))), 0.001f)
    }

    @Test
    fun `curve spans the requested window and starts at the left edge`() {
        val meals = listOf(mealAt(noon, carbs = 60f))
        val from = noon.minus(Duration.ofHours(2))
        val to = noon.plus(Duration.ofHours(4))

        val curve = MacroAbsorption.curve(meals, Macro.CARB, from, to, minutes(10))

        assertEquals(from, curve.first().first)
        assertEquals(to, curve.last().first)
        assertEquals(0f, curve.first().second, 0.001f)
        assertTrue("should have sampled the whole window", curve.size > 30)
    }

    @Test
    fun `an inverted window yields no curve`() {
        val meals = listOf(mealAt(noon, carbs = 60f))
        assertTrue(MacroAbsorption.curve(meals, Macro.CARB, noon, noon).isEmpty())
        assertTrue(
            MacroAbsorption.curve(meals, Macro.CARB, noon, noon.minus(minutes(30))).isEmpty()
        )
    }

    @Test
    fun `no meals means a flat zero curve`() {
        val curve = MacroAbsorption.curve(emptyList(), Macro.CARB, noon, noon.plus(minutes(60)))
        assertTrue(curve.isNotEmpty())
        assertTrue(curve.all { it.second == 0f })
    }
}
