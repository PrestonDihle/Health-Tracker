package com.prestondihle.healthtracker.domain

/**
 * How well the day's food was actually logged, as judged by whoever logged it.
 *
 * **Self-rated and not derived, which is the whole design.** The app can see
 * whether meals exist, whether their macros are filled in and whether the
 * day's intake is implausibly low -- and none of that answers the question. A
 * day of restaurant meals entered from memory is *complete*: every meal present,
 * every macro filled, nothing the app holds says otherwise, and every gram of it
 * is a guess. Only the person who ate it knows that, so only they can score it.
 *
 * That also keeps this on the right side of `Readiness`' rule about composite
 * scores. This is not a blend of things measured differently with weights nobody
 * published; it is one person's answer to one question, stored as given.
 *
 * **Five named levels rather than a bare 1-10.** The number has to survive being
 * read months later in a spreadsheet, where "6" means nothing and "everything
 * logged, portions eyeballed" means what it says. Five is as many distinctions
 * as anyone can make honestly about their own logging; ten invites a precision
 * that is not there.
 *
 * The [score] is what is stored and what the CSV export carries, so filtering a
 * year of days on "at least [ESTIMATED]" is a comparison against a number rather
 * than a string match.
 */
enum class FoodLogConfidence(
    val score: Int,
    val label: String,
    val meaning: String,
) {
    BARELY(1, "Barely", "little or nothing logged — don't read anything into this day"),
    GUESSED(2, "Guessed", "logged from memory afterwards, portions not measured"),
    ESTIMATED(3, "Estimated", "everything logged as it happened, portions eyeballed"),
    MOSTLY(4, "Mostly weighed", "weighed or scanned apart from a meal or two"),
    WEIGHED(5, "Weighed", "everything weighed or scanned"),
    ;

    companion object {
        /**
         * The level a stored score names, or null.
         *
         * Null for an unrated day **and** for a score outside the scale, which is
         * the same answer to two different questions on purpose: both mean "no
         * level to show", and a reader has no use for the difference between
         * never rated and rated with a number this app cannot draw. The second
         * is only reachable from a hand-edited database.
         */
        fun of(score: Int?): FoodLogConfidence? = entries.firstOrNull { it.score == score }
    }
}
