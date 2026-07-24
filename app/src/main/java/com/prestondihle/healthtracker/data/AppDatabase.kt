package com.prestondihle.healthtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Adds dietary calories and synced weight to the Health Connect cache,
         * and the preferred step source to settings.
         *
         * All three are nullable additions, so plain `ALTER TABLE` covers it and
         * every already-logged row survives. Real fasting history and body
         * measurements exist on device by now; dropping them to add three
         * columns would not be a fair trade.
         */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE HealthDaySnapshot ADD COLUMN dietaryCalories INTEGER")
                    db.execSQL("ALTER TABLE HealthDaySnapshot ADD COLUMN weightKg REAL")
                    db.execSQL("ALTER TABLE UserSettings ADD COLUMN preferredStepsPackage TEXT")
                }
            }

        /**
         * Destructive fallback remains only for the v1 schema, which kept steps,
         * sleep, macros and rep counts on DailyLog and has no sensible
         * column-wise mapping to today's tables. Anything from v2 onward
         * migrates properly.
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
                            .addMigrations(MIGRATION_2_3)
                            .fallbackToDestructiveMigration(dropAllTables = true)
                            .build()
                            .also { instance = it }
                }
    }
}
