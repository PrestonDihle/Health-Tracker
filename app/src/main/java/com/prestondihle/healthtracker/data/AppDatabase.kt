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
        WeightSubGoal::class,
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
        StepBucket::class,
        GripStrengthEntry::class,
        RestingHeartRate::class,
        UserGoals::class,
        UserSettings::class,
        Supplement::class,
        SupplementDose::class,
        SleepSessionEntry::class,
        SleepStageEntry::class,
        CardOrderEntry::class,
    ],
    version = 15,
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
         * The v5 to v6 statements, exposed for the same schema diff as the two
         * table-creating migrations above.
         *
         * Two new tables and three added columns. Nothing already stored is read
         * or rewritten, so there is no data-loss path here at all.
         *
         * The added columns carry SQLite defaults even though the entities do
         * not declare any. Room only compares a column's default when the entity
         * spells one out, so this is invisible to schema validation -- but
         * without it every row that already exists gets a NULL, and an upgrading
         * user would find the glucose target blank on a screen that is supposed
         * to arrive with a sensible one. `smoothGlucose` is NOT NULL because the
         * field is a non-null Boolean, and a NOT NULL column added to a
         * populated table has to say what the existing rows hold.
         */
        internal val migration5To6Statements =
            listOf(
                "CREATE TABLE IF NOT EXISTS `StepBucket` (" +
                    "`hourStartMillis` INTEGER NOT NULL, " +
                    "`steps` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`hourStartMillis`))",
                "CREATE TABLE IF NOT EXISTS `GripStrengthEntry` (" +
                    "`date` INTEGER NOT NULL, " +
                    "`dominantKg` REAL, " +
                    "`nonDominantKg` REAL, " +
                    "PRIMARY KEY(`date`))",
                "ALTER TABLE `UserGoals` ADD COLUMN `glucoseTargetLowMgDl` INTEGER DEFAULT 70",
                "ALTER TABLE `UserGoals` ADD COLUMN `glucoseTargetHighMgDl` INTEGER DEFAULT 140",
                "ALTER TABLE `UserSettings` ADD COLUMN `smoothGlucose` INTEGER NOT NULL DEFAULT 0",
            )

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration5To6Statements.forEach(db::execSQL)
                }
            }

        /**
         * Adds the flag that lets a synced meal stay deleted.
         *
         * `NOT NULL` because the field is a non-null Boolean, so the rows that
         * already exist have to be told what they hold: nothing has been deleted
         * yet, which is 0.
         */
        internal val migration6To7Statements =
            listOf("ALTER TABLE `MealEntry` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration6To7Statements.forEach(db::execSQL)
                }
            }

        /**
         * Adds the glucose reference line, seeded so it is there on first sight.
         *
         * A SQLite default again, for the reason the v6 columns carry one: the
         * entity default only applies to rows this app constructs, and an
         * upgrading user has a UserGoals row already. Without it their chart
         * would come back with the new line silently switched off.
         */
        internal val migration7To8Statements =
            listOf(
                "ALTER TABLE `UserGoals` ADD COLUMN `glucoseReferenceMgDl` INTEGER DEFAULT 100"
            )

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration7To8Statements.forEach(db::execSQL)
                }
            }

        /**
         * The rest of the adjustable reference lines, and the glucose plot
         * bounds.
         *
         * Every one of these carries a SQLite default for the reason the last
         * three migrations did: an upgrading user already has a UserGoals row,
         * and a column added without one arrives NULL, which each chart reads as
         * "no line" -- so the blood pressure rule an earlier version drew at a
         * hard-coded 120 would vanish on upgrade, and the glucose plot would
         * lose the bounds it has always had. The defaults are exactly the
         * figures those charts were previously fixed at, so nothing moves.
         */
        internal val migration8To9Statements =
            listOf(
                "ALTER TABLE `UserGoals` ADD COLUMN `glucosePlotMinMgDl` INTEGER DEFAULT 60",
                "ALTER TABLE `UserGoals` ADD COLUMN `glucosePlotMaxMgDl` INTEGER DEFAULT 180",
                "ALTER TABLE `UserGoals` ADD COLUMN `bloodPressureSystolicReference` INTEGER " +
                    "DEFAULT 120",
                "ALTER TABLE `UserGoals` ADD COLUMN `bloodPressureDiastolicReference` INTEGER " +
                    "DEFAULT 80",
                "ALTER TABLE `UserGoals` ADD COLUMN `sleepMinutesGoal` INTEGER DEFAULT 480",
            )

        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration8To9Statements.forEach(db::execSQL)
                }
            }

        /**
         * Staged weights on the way to the goal.
         *
         * A table rather than more `UserGoals` columns because there is no right
         * number of them -- see [WeightSubGoal]. The unique index on `kg` is what
         * makes adding the same mark twice a no-op rather than two rules drawn at
         * the same height.
         */
        internal val migration9To10Statements =
            listOf(
                "CREATE TABLE IF NOT EXISTS `WeightSubGoal` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kg` REAL NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_WeightSubGoal_kg` " +
                    "ON `WeightSubGoal` (`kg`)",
            )

        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration9To10Statements.forEach(db::execSQL)
                }
            }

        /**
         * The daily supplement stack, and a row per dose actually taken.
         *
         * Two tables rather than one, for the same reason the weight waypoints
         * were a table: the stack is a standing list and what was swallowed today
         * is an event, and folding the second into the first would mean a column
         * that has to be cleared at midnight by something. Nothing here runs at
         * midnight.
         *
         * `SupplementDose` carries no `taken` column -- the row's existence is the
         * fact. See the entity for why a boolean would be worse than no column.
         */
        internal val migration10To11Statements =
            listOf(
                "CREATE TABLE IF NOT EXISTS `Supplement` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`dose` TEXT NOT NULL, " +
                    "`slot` TEXT NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_Supplement_name_slot` " +
                    "ON `Supplement` (`name`, `slot`)",
                "CREATE TABLE IF NOT EXISTS `SupplementDose` (" +
                    "`supplementId` INTEGER NOT NULL, " +
                    "`date` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`supplementId`, `date`))",
                "CREATE INDEX IF NOT EXISTS `index_SupplementDose_date` " +
                    "ON `SupplementDose` (`date`)",
            )

        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration10To11Statements.forEach(db::execSQL)
                }
            }

        /**
         * The bedtime caffeine limit that drives the last-call notification.
         *
         * **Deliberately no SQLite `DEFAULT`**, which is the opposite of what
         * `MIGRATION_5_6` and `MIGRATION_8_9` do. Those seeded a value because
         * the column drove something already on screen and a NULL would have
         * visibly changed an existing user's charts. This one drives a
         * notification: a default would mean upgrading and then being
         * interrupted by something never asked for. Arriving NULL is exactly
         * right -- it means "say nothing", and the reader turns it on.
         */
        internal val migration11To12Statements =
            listOf("ALTER TABLE `UserGoals` ADD COLUMN `caffeineBedtimeLimitMg` INTEGER")

        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration11To12Statements.forEach(db::execSQL)
                }
            }

        /**
         * Sleep sessions and the stages inside them.
         *
         * Two new tables rather than columns on `HealthDaySnapshot`, for the same
         * reason `StepBucket` is not a column on it: the snapshot holds a day's
         * total and a total cannot say *when*. `sleepMinutes` stays exactly where
         * it is and goes on driving the trend chart and the sleep goal, so this
         * migration adds and changes nothing already on screen.
         *
         * Both tables are new, so the DDL is diffed directly against Room's own
         * `CREATE TABLE` -- there is no `ALTER TABLE`-added column here carrying a
         * SQLite default that Room's generated schema would omit.
         *
         * `SleepStageEntry`'s primary key is the **pair** of session and start.
         * Keyed on the start alone, two overlapping nights -- a watch and a phone
         * both recording the same hours -- would silently overwrite each other's
         * stretches.
         */
        internal val migration12To13Statements =
            listOf(
                // `PRIMARY KEY(...)` as a table constraint rather than inline on
                // the column: Room generates the constraint form even for a
                // single-column key, and Room compares the two schemas by text.
                "CREATE TABLE IF NOT EXISTS `SleepSessionEntry` (" +
                    "`startMillis` INTEGER NOT NULL, " +
                    "`endMillis` INTEGER NOT NULL, " +
                    "`externalId` TEXT, " +
                    "PRIMARY KEY(`startMillis`))",
                "CREATE INDEX IF NOT EXISTS `index_SleepSessionEntry_startMillis` " +
                    "ON `SleepSessionEntry` (`startMillis`)",
                "CREATE TABLE IF NOT EXISTS `SleepStageEntry` (" +
                    "`sessionStartMillis` INTEGER NOT NULL, " +
                    "`startMillis` INTEGER NOT NULL, " +
                    "`endMillis` INTEGER NOT NULL, " +
                    "`stage` TEXT NOT NULL, " +
                    "PRIMARY KEY(`sessionStartMillis`, `startMillis`))",
                "CREATE INDEX IF NOT EXISTS `index_SleepStageEntry_startMillis` " +
                    "ON `SleepStageEntry` (`startMillis`)",
                "CREATE INDEX IF NOT EXISTS `index_SleepStageEntry_sessionStartMillis` " +
                    "ON `SleepStageEntry` (`sessionStartMillis`)",
            )

        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration12To13Statements.forEach(db::execSQL)
                }
            }

        /**
         * The personal profile on `UserSettings`.
         *
         * Three nullable additions and one non-null. `sex` carries a SQLite
         * `DEFAULT 'UNSPECIFIED'` because the column is `NOT NULL` and existing
         * rows need a value -- matching how `smoothGlucose` seeded its `NOT NULL`
         * default. The others are left NULL, which is the "not set yet" the UI
         * reads as an empty profile.
         */
        internal val migration13To14Statements =
            listOf(
                "ALTER TABLE `UserSettings` ADD COLUMN `maxHeartRateBpm` INTEGER",
                "ALTER TABLE `UserSettings` ADD COLUMN `ageYears` INTEGER",
                "ALTER TABLE `UserSettings` ADD COLUMN `sex` TEXT NOT NULL DEFAULT 'UNSPECIFIED'",
                "ALTER TABLE `UserSettings` ADD COLUMN `heightCm` REAL",
            )

        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration13To14Statements.forEach(db::execSQL)
                }
            }

        /**
         * The saved per-tab card order.
         *
         * A new table, so the DDL is Room's own generated `CREATE TABLE` for the
         * composite key -- no `ALTER TABLE` default to keep in step. Empty to
         * start, which reads as "every tab in its built-in order".
         */
        internal val migration14To15Statements =
            listOf(
                "CREATE TABLE IF NOT EXISTS `CardOrderEntry` (" +
                    "`tab` TEXT NOT NULL, " +
                    "`cardId` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`tab`, `cardId`))"
            )

        private val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migration14To15Statements.forEach(db::execSQL)
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
                            .addMigrations(
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_4_5,
                                MIGRATION_5_6,
                                MIGRATION_6_7,
                                MIGRATION_7_8,
                                MIGRATION_8_9,
                                MIGRATION_9_10,
                                MIGRATION_10_11,
                                MIGRATION_11_12,
                                MIGRATION_12_13,
                                MIGRATION_13_14,
                                MIGRATION_14_15,
                            )
                            .fallbackToDestructiveMigration(dropAllTables = true)
                            .build()
                            .also { instance = it }
                }
    }
}
