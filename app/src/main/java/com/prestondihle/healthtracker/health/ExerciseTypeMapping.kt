package com.prestondihle.healthtracker.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.prestondihle.healthtracker.domain.TrainingType

/**
 * Health Connect's exercise type to the handful of kinds this app groups by.
 *
 * Here rather than in `domain/` because it is the one part of this feature that
 * has to know Health Connect's constants, and the domain package is deliberately
 * free of `androidx` imports. It is still a plain function over an `Int`, so it
 * is unit-testable without a device -- which matters, because a mis-mapped type
 * is invisible on screen: the session appears, under the wrong heading, with a
 * duration that looks entirely correct.
 *
 * Both variants of each activity fold together on purpose. A treadmill run and a
 * road run are both running to a weekly volume figure, and separating them would
 * split one habit across two rows. Where that judgement is less obvious it is
 * noted below.
 */
internal fun trainingTypeOf(exerciseType: Int): TrainingType =
    when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> TrainingType.RUN

        // The watch has no rucking type, so a ruck is logged as a hike and this
        // is as close as the data gets. It cannot know whether weight was
        // carried, and the label says "Rucks" because that is what these are on
        // this reader's phone -- not because the source said so.
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> TrainingType.RUCK

        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> TrainingType.WALK

        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> TrainingType.STRENGTH

        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> TrainingType.CYCLE

        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> TrainingType.SWIM

        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING ->
            TrainingType.HIIT

        // Everything else, including EXERCISE_TYPE_OTHER_WORKOUT and the eighty
        // or so types this app does not name. Falling through to OTHER rather
        // than dropping the session: an hour of training that happened and is not
        // shown is a worse error than an hour shown under a vague heading.
        else -> TrainingType.OTHER
    }
