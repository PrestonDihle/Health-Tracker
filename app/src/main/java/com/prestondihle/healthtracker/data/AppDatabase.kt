package com.prestondihle.healthtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DailyLog::class,
        HealthDaySnapshot::class,
        BloodPressureReading::class,
        WeightEntry::class,
        WaistEntry::class,
        HydrationEntry::class,
        ExerciseSet::class,
        CaffeineIntake::class,
        CreatineIntake::class,
        FastingSession::class,
        FastingPlanDay::class,
        PlannedExtendedFast::class,
        WeeklyPerformance::class,
        BloodSugarReading::class,
        KetoneReading::class,
        RestingHeartRate::class,
        UserGoals::class,
        UserSettings::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Destructive migration is intentional while the app is pre-release: the
         * v1 schema kept steps, sleep, macros and rep counts on DailyLog, which
         * have since moved to Health Connect and to their own tables. There is no
         * sensible column-wise mapping, and no shipped build to migrate from.
         */
        fun getDatabase(context: Context): AppDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                "tracker_database",
                            )
                            .fallbackToDestructiveMigration(dropAllTables = true)
                            .build()
                            .also { instance = it }
                }
    }
}
