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
        MealEntry::class,
        HeartRateBucket::class,
        RestingHeartRate::class,
        UserGoals::class,
        UserSettings::class,
    ],
    version = 5,
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
         * The v3 to v4 statements, exposed so a test can diff the schema they
         * produce against the one Room generates from the entities.
         *
         * Room compares the two on every launch and refuses to open the database
         * if they differ at all, so a typo here does not fail a build -- it bricks
         * the app for anyone upgrading. `exportSchema` is off, which rules out
         * Room's own MigrationTestHelper, hence checking them this way.
         *
         * Column types follow the converters: Instant is epoch millis (INTEGER)
         * and an enum is its name (TEXT).
         */
        internal val migration3To4Statements =
            listOf(
                "CREATE TABLE IF NOT EXISTS `MealEntry` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`calories` INTEGER, " +
                    "`proteinGrams` REAL, " +
                    "`carbGrams` REAL, " +
                    "`fatGrams` REAL, " +
                    "`name` TEXT, " +
                    "`source` TEXT NOT NULL, " +
                    "`externalId` TEXT)",
                "CREATE INDEX IF NOT EXISTS `index_MealEntry_timestamp` " +
                    "ON `MealEntry` (`timestamp`)",
                // Unique, so a repeated Health Connect sync cannot insert the same
                // meal twice. SQLite treats NULLs as distinct, which leaves
                // hand-entered rows unaffected.
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_MealEntry_externalId` " +
                    "ON `MealEntry` (`externalId`)",
                "CREATE TABLE IF NOT EXISTS `HeartRateBucket` (" +
                    "`bucketStartMillis` INTEGER NOT NULL, " +
                    "`bpm` INTEGER NOT NULL, " +
                    "`sampleCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`bucketStartMillis`))",
            )

        /**
         * Adds the two time series the master graph is drawn from: per-meal
         * macros, and heart rate averaged into five-minute buckets.
         *
         * Both are new tables, so nothing already stored is touched. The daily
         * macro totals on HealthDaySnapshot stay where they are -- MealEntry is
         * not a replacement for them but the same nutrition kept a second way,
         * because a daily total cannot say when the food was eaten.
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration3To4Statements.forEach(db::execSQL)
                }
            }

        /**
         * Renames KetoneReading.mmolL to ppm, keeping every stored value.
         *
         * Deliberately not a conversion. Breath acetone in ppm and blood
         * beta-hydroxybutyrate in mmol/L are different analytes with only a loose,
         * person-specific correlation, so there is no factor that could be applied
         * without inventing data. The readings already came off a ppm meter; only
         * the column name was wrong, so the numbers carry over untouched.
         *
         * Done by rebuild-and-copy rather than ALTER TABLE RENAME COLUMN, which
         * SQLite only gained in 3.25 -- that ships with API 30, and this app runs
         * back to 26.
         *
         * The old table is moved aside and the new one created under the real
         * name, rather than the other way round. Renaming a table *into* place
         * would work, but SQLite rewrites the stored DDL of a renamed table with
         * double-quoted identifiers, leaving a schema that differs from Room's
         * generated text in quoting alone -- harmless, since Room compares parsed
         * columns, but it makes the schema impossible to diff. Creating the final
         * table directly keeps the stored DDL identical to Room's.
         */
        internal val migration4To5Statements =
            listOf(
                // Indices follow their table through a rename and keep their
                // names, so this one has to go before the name is reused.
                "DROP INDEX IF EXISTS `index_KetoneReading_timestamp`",
                "ALTER TABLE `KetoneReading` RENAME TO `KetoneReading_old`",
                "CREATE TABLE IF NOT EXISTS `KetoneReading` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`ppm` REAL NOT NULL)",
                "INSERT INTO `KetoneReading` (`id`, `timestamp`, `ppm`) " +
                    "SELECT `id`, `timestamp`, `mmolL` FROM `KetoneReading_old`",
                "DROP TABLE `KetoneReading_old`",
                "CREATE INDEX IF NOT EXISTS `index_KetoneReading_timestamp` " +
                    "ON `KetoneReading` (`timestamp`)",
            )

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration4To5Statements.forEach(db::execSQL)
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
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                            .fallbackToDestructiveMigration(dropAllTables = true)
                            .build()
                            .also { instance = it }
                }
    }
}
