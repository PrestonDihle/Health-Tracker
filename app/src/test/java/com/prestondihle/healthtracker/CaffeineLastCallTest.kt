package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When to say "that is the last one".
 *
 * The question is about the *next* dose, not the current level, and the
 * difference is the whole point: told after the fact that bedtime caffeine is
 * too high there is nothing left to do about it. The useful moment is the one
 * before the cup that does it.
 */
class CaffeineLastCallTest {

    /** An ordinary cup, which is what "one more" means. */
    private val DOSE = 70

    private val now: Instant = Instant.parse("2026-08-24T14:00:00Z")
    private val bedtime: Instant = now.plus(Duration.ofHours(7))

    @Test
    fun `a clear morning leaves room for another`() {
        // One coffee eight hours ago is most of a half-life and a half gone. A
        // dose now still decays to well under the limit by bedtime.
        val doses = listOf(CaffeineDose(now.minus(Duration.ofHours(8)), 70))

        assertFalse(
            Caffeine.lastCallReached(doses, now, bedtime, limitMg = 50, nextDoseMg = DOSE)
        )
    }

    @Test
    fun `a full afternoon means this is the last one`() {
        // A large-ish dose two hours ago decays to about 29 mg by bedtime, which
        // is under the limit; another cup now adds about 27 more and carries it
        // over. That gap -- under on its own, over with one more -- is the whole
        // window this fires in, and it is the only moment the warning is any use.
        val doses = listOf(CaffeineDose(now.minus(Duration.ofHours(2)), 100))

        assertTrue(Caffeine.lastCallReached(doses, now, bedtime, limitMg = 40, nextDoseMg = DOSE))
    }

    @Test
    fun `already over the limit is not a last call`() {
        // Every remaining choice is equally too late by now, and a warning here
        // would be scolding rather than useful.
        val doses = listOf(CaffeineDose(now, 400))

        assertFalse(Caffeine.lastCallReached(doses, now, bedtime, limitMg = 40, nextDoseMg = DOSE))
    }

    @Test
    fun `bedtime already past is not a last call`() {
        // Nothing to project towards. Guarded because the worker runs on a
        // schedule and will meet this case every night.
        val doses = listOf(CaffeineDose(now.minus(Duration.ofHours(1)), 70))

        assertFalse(
            Caffeine.lastCallReached(
                doses,
                now,
                bedtime = now.minus(Duration.ofMinutes(1)),
                limitMg = 40,
                nextDoseMg = DOSE,
            )
        )
    }

    @Test
    fun `a higher limit forgives what a lower one would not`() {
        // The threshold is the reader's, and the same afternoon reads differently
        // against a different one -- which is why it is a setting and not a
        // constant.
        val doses = listOf(CaffeineDose(now.minus(Duration.ofHours(2)), 100))

        assertTrue(Caffeine.lastCallReached(doses, now, bedtime, limitMg = 40, nextDoseMg = DOSE))
        assertFalse(Caffeine.lastCallReached(doses, now, bedtime, limitMg = 150, nextDoseMg = DOSE))
    }
}
