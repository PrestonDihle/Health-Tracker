package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the hand-written migration SQL against what Room generates from the
 * entities.
 *
 * Room compares the two on every launch and refuses to open the database if they
 * differ by so much as a column's nullability -- which means a typo here does not
 * fail a test, it bricks the installed app for anyone upgrading. Since
 * `exportSchema` is off, Room's own MigrationTestHelper is unavailable, so this
 * builds both schemas at runtime and diffs them directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationSchemaTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** The `sql` SQLite records for one table and its indices, normalised for comparison. */
    private fun schemaOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val statements = mutableListOf<String>()
        db.query(
                "SELECT type, name, sql FROM sqlite_master " +
                    "WHERE tbl_name = ? AND sql IS NOT NULL ORDER BY type, name",
                arrayOf(table),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    // Collapse whitespace and drop the IF NOT EXISTS that only the
                    // migration spells out; neither changes the resulting schema.
                    statements.add(
                        cursor
                            .getString(2)
                            .replace("IF NOT EXISTS ", "")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    )
                }
            }
        return statements
    }

    /** A Room-built database, whose schema is by definition the one Room will demand. */
    private fun roomSchema(table: String): List<String> {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return try {
            schemaOf(db.openHelper.writableDatabase, table)
        } finally {
            db.close()
        }
    }

    /**
     * The same tables as produced by the migration's own statements.
     *
     * Applied to a bare database rather than a v3 one: what is being checked is
     * the shape of the two new tables, and neither depends on anything already
     * present.
     */
    private fun migrationSchema(table: String, statements: List<String>): List<String> {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("DROP TABLE IF EXISTS `$table`")
            statements.forEach { raw.execSQL(it) }
            schemaOf(raw, table)
        } finally {
            db.close()
        }
    }

    /**
     * MealEntry is built by v3-to-v4 and then altered by v6-to-v7, so it has to
     * be checked across both.
     *
     * By columns rather than by DDL text, for the reason [columnsOf] explains: a
     * column added by `ALTER TABLE` carries a SQLite default the migration spells
     * out and Room's own `CREATE TABLE` does not, so the two texts differ by
     * design. Indices are unaffected by an ALTER and are still diffed literally.
     */
    @Test
    fun `the migrations build MealEntry exactly as Room expects`() {
        val expectedColumns = roomColumns("MealEntry")
        val expectedIndices = roomSchema("MealEntry").filter { it.startsWith("CREATE INDEX") ||
            it.startsWith("CREATE UNIQUE INDEX") }

        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("DROP TABLE IF EXISTS `MealEntry`")
            AppDatabase.migration3To4Statements
                .filter { it.contains("MealEntry") }
                .forEach { raw.execSQL(it) }
            AppDatabase.migration6To7Statements.forEach { raw.execSQL(it) }

            assertEquals(expectedColumns, columnsOf(raw, "MealEntry"))
            assertEquals(
                expectedIndices,
                schemaOf(raw, "MealEntry").filter {
                    it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX")
                },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration builds HeartRateBucket exactly as Room expects`() {
        assertEquals(
            roomSchema("HeartRateBucket"),
            migrationSchema("HeartRateBucket", AppDatabase.migration3To4Statements),
        )
    }

    /**
     * A new table, so this one can be diffed as DDL text rather than by columns:
     * nothing was added by `ALTER TABLE`, so there is no SQLite default for the
     * migration's text to carry and Room's to omit. The unique index on `kg` is
     * the part worth pinning -- without it the same waypoint can be staged twice
     * and drawn as two rules at one height.
     */
    @Test
    fun `migration builds WeightSubGoal exactly as Room expects`() {
        assertEquals(
            roomSchema("WeightSubGoal"),
            migrationSchema("WeightSubGoal", AppDatabase.migration9To10Statements),
        )
    }

    /**
     * The supplement stack and the per-day ticks against it.
     *
     * Both are new tables, so their DDL is diffed directly -- there is no
     * `ALTER TABLE`-added column here carrying a SQLite default that Room's own
     * `CREATE TABLE` would omit.
     *
     * Two things are worth the pinning. `Supplement` is unique on name and slot
     * together, which is what makes the same thing morning and evening two
     * tickable rows and the same thing twice in one morning a single one. And
     * `SupplementDose` has a *composite* primary key rather than a generated id,
     * which is what makes ticking a box twice idempotent -- get that wrong and
     * every stray tap is another row saying the same thing.
     */
    @Test
    fun `migration builds the supplement tables exactly as Room expects`() {
        assertEquals(
            roomSchema("Supplement"),
            migrationSchema("Supplement", AppDatabase.migration10To11Statements),
        )
        assertEquals(
            roomSchema("SupplementDose"),
            migrationSchema("SupplementDose", AppDatabase.migration10To11Statements),
        )
    }

    /**
     * The two sleep tables added at v13.
     *
     * `SleepStageEntry`'s primary key is the **pair** of session and start, and
     * that is the half worth pinning: keyed on the start alone, two overlapping
     * nights -- a watch and a phone both recording the same hours -- would
     * silently overwrite each other's stretches, and the hypnogram would come out
     * as a single mangled night that looked entirely plausible.
     *
     * Both tables are new, so the DDL is diffed directly. There is no
     * `ALTER TABLE`-added column here carrying a SQLite default that Room's
     * `CREATE TABLE` would omit, which is the case that forces the
     * `PRAGMA table_info` comparison further down.
     */
    @Test
    fun `migration builds the sleep tables exactly as Room expects`() {
        assertEquals(
            roomSchema("SleepSessionEntry"),
            migrationSchema("SleepSessionEntry", AppDatabase.migration12To13Statements),
        )
        assertEquals(
            roomSchema("SleepStageEntry"),
            migrationSchema("SleepStageEntry", AppDatabase.migration12To13Statements),
        )
    }

    /**
     * The v4 KetoneReading table, as Room built it before the rename.
     *
     * Spelled out here rather than derived, because the point of the test is to
     * migrate from the real old shape -- deriving it from today's entity would
     * make the test pass by construction.
     */
    private val ketoneV4 =
        listOf(
            "CREATE TABLE IF NOT EXISTS `KetoneReading` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`mmolL` REAL NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_KetoneReading_timestamp` " +
                "ON `KetoneReading` (`timestamp`)",
        )

    @Test
    fun `ketone rename lands on exactly the schema Room expects`() {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val migrated =
            try {
                val raw = db.openHelper.writableDatabase
                raw.execSQL("DROP TABLE IF EXISTS `KetoneReading`")
                ketoneV4.forEach { raw.execSQL(it) }
                AppDatabase.migration4To5Statements.forEach { raw.execSQL(it) }
                schemaOf(raw, "KetoneReading")
            } finally {
                db.close()
            }

        assertEquals(roomSchema("KetoneReading"), migrated)
    }

    @Test
    fun `ketone rename carries every reading across unchanged`() {
        // The rename is explicitly not a unit conversion -- ppm and mmol/L measure
        // different analytes -- so a value that changed on the way through would be
        // fabricated data, not a rescale.
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("DROP TABLE IF EXISTS `KetoneReading`")
            ketoneV4.forEach { raw.execSQL(it) }
            raw.execSQL("INSERT INTO `KetoneReading` (`id`, `timestamp`, `mmolL`) VALUES (7, 1000, 12.5)")
            raw.execSQL("INSERT INTO `KetoneReading` (`id`, `timestamp`, `mmolL`) VALUES (8, 2000, 31.0)")

            AppDatabase.migration4To5Statements.forEach { raw.execSQL(it) }

            raw.query("SELECT `id`, `timestamp`, `ppm` FROM `KetoneReading` ORDER BY `id`").use {
                assertEquals(2, it.count)
                it.moveToNext()
                assertEquals(7, it.getLong(0))
                assertEquals(1000, it.getLong(1))
                assertEquals(12.5f, it.getFloat(2), 0.001f)
                it.moveToNext()
                assertEquals(8, it.getLong(0))
                assertEquals(31.0f, it.getFloat(2), 0.001f)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration builds StepBucket exactly as Room expects`() {
        assertEquals(
            roomSchema("StepBucket"),
            migrationSchema("StepBucket", AppDatabase.migration5To6Statements.forTable("StepBucket")),
        )
    }

    @Test
    fun `migration builds GripStrengthEntry exactly as Room expects`() {
        assertEquals(
            roomSchema("GripStrengthEntry"),
            migrationSchema(
                "GripStrengthEntry",
                AppDatabase.migration5To6Statements.forTable("GripStrengthEntry"),
            ),
        )
    }

    /**
     * Only the statements that touch one table.
     *
     * The v5-to-v6 migration both creates tables and alters two that already
     * exist, and replaying an `ALTER TABLE ADD COLUMN` against a database Room
     * has already built fails on the duplicate column.
     */
    private fun List<String>.forTable(table: String): List<String> = filter { it.contains(table) }

    /**
     * Name, type, nullability and primary-key position for every column.
     *
     * What the two table-creating tests diff is the stored DDL text, which cannot
     * be used for a column added by `ALTER TABLE`: the migration spells out a
     * SQLite default and Room's own `CREATE TABLE` does not, so the text differs
     * by design. This compares the four properties Room actually validates
     * instead. A default is deliberately not among them -- Room only enforces one
     * when the entity declares it, and neither of these does.
     */
    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val declaredType = cursor.getString(2)
                val notNull = cursor.getInt(3)
                val primaryKeyPosition = cursor.getInt(5)
                columns.add("$name|$declaredType|$notNull|$primaryKeyPosition")
            }
        }
        return columns.sorted()
    }

    /**
     * The two settings tables as Room built them at v5.
     *
     * Written out rather than derived from today's entities, for the same reason
     * [ketoneV4] is: a schema generated from the current code would make the
     * migration pass against itself.
     */
    private val settingsV5 =
        listOf(
            "CREATE TABLE IF NOT EXISTS `UserGoals` (" +
                "`id` INTEGER NOT NULL, " +
                "`goalWeightKg` REAL, " +
                "`goalWaistCm` REAL, " +
                "`dailyPushupGoal` INTEGER, " +
                "`weeklyPushupGoal` INTEGER, " +
                "`dailySquatGoal` INTEGER, " +
                "`weeklySquatGoal` INTEGER, " +
                "`weeklyRunMinutesGoal` INTEGER, " +
                "`dailyStepGoal` INTEGER, " +
                "`dailyWaterMlGoal` INTEGER, " +
                "`dailyCalorieTarget` INTEGER, " +
                "`dailyProteinTarget` INTEGER, " +
                "`dailyPagesGoal` INTEGER, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `UserSettings` (" +
                "`id` INTEGER NOT NULL, " +
                "`unitSystem` TEXT NOT NULL, " +
                "`weekStartsOn` TEXT NOT NULL, " +
                "`preferredStepsPackage` TEXT, " +
                "PRIMARY KEY(`id`))",
        )

    @Test
    fun `the added settings columns land on the shape Room expects`() {
        val expectedGoals = roomColumns("UserGoals")
        val expectedSettings = roomColumns("UserSettings")

        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("DROP TABLE IF EXISTS `UserGoals`")
            raw.execSQL("DROP TABLE IF EXISTS `UserSettings`")
            settingsV5.forEach { raw.execSQL(it) }

            // Every migration that alters these tables, in order: UserGoals is
            // added to three times over, and replaying only some of them leaves
            // it short of what Room now builds.
            AppDatabase.migration5To6Statements
                .filter { it.startsWith("ALTER TABLE") }
                .forEach { raw.execSQL(it) }
            AppDatabase.migration7To8Statements.forEach { raw.execSQL(it) }
            AppDatabase.migration8To9Statements.forEach { raw.execSQL(it) }
            // The fourth: the bedtime caffeine limit. Unlike the three above it
            // carries no SQLite default, which is deliberate -- it drives a
            // notification, and a default would interrupt an upgrading user with
            // something they never asked for. NULL is what keeps it silent until
            // it is switched on, and that is part of what this pins.
            AppDatabase.migration11To12Statements.forEach { raw.execSQL(it) }

            assertEquals(expectedGoals, columnsOf(raw, "UserGoals"))
            assertEquals(expectedSettings, columnsOf(raw, "UserSettings"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the added settings columns carry defaults onto rows that already exist`() {
        // An upgrading user has a UserGoals row already. Without the SQLite
        // defaults the new columns arrive null on it, and the glucose target that
        // is supposed to ship pre-set would be blank on their phone alone.
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("DROP TABLE IF EXISTS `UserGoals`")
            raw.execSQL("DROP TABLE IF EXISTS `UserSettings`")
            settingsV5.forEach { raw.execSQL(it) }
            raw.execSQL("INSERT INTO `UserGoals` (`id`, `dailyStepGoal`) VALUES (1, 12000)")
            raw.execSQL(
                "INSERT INTO `UserSettings` (`id`, `unitSystem`, `weekStartsOn`) " +
                    "VALUES (1, 'IMPERIAL', 'MONDAY')"
            )

            // Every migration that alters these tables, in order: UserGoals is
            // added to three times over, and replaying only some of them leaves
            // it short of what Room now builds.
            AppDatabase.migration5To6Statements
                .filter { it.startsWith("ALTER TABLE") }
                .forEach { raw.execSQL(it) }
            AppDatabase.migration7To8Statements.forEach { raw.execSQL(it) }
            AppDatabase.migration8To9Statements.forEach { raw.execSQL(it) }

            raw.query(
                    "SELECT `dailyStepGoal`, `glucoseTargetLowMgDl`, `glucoseTargetHighMgDl`, " +
                        "`glucoseReferenceMgDl`, `glucosePlotMinMgDl`, `glucosePlotMaxMgDl`, " +
                        "`bloodPressureSystolicReference`, `bloodPressureDiastolicReference`, " +
                        "`sleepMinutesGoal` FROM `UserGoals`"
                )
                .use {
                    it.moveToNext()
                    assertEquals(12_000, it.getInt(0))
                    assertEquals(70, it.getInt(1))
                    assertEquals(140, it.getInt(2))
                    assertEquals(100, it.getInt(3))
                    // The plot bounds and the blood pressure rules were hard-coded
                    // before they were settings, so an upgrade that left these
                    // null would change what an existing user's charts look like
                    // -- which is the one thing adding a setting must not do.
                    assertEquals(60, it.getInt(4))
                    assertEquals(180, it.getInt(5))
                    assertEquals(120, it.getInt(6))
                    assertEquals(80, it.getInt(7))
                    assertEquals(480, it.getInt(8))
                }
            raw.query("SELECT `smoothGlucose` FROM `UserSettings`").use {
                it.moveToNext()
                assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    private fun roomColumns(table: String): List<String> {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return try {
            columnsOf(db.openHelper.writableDatabase, table)
        } finally {
            db.close()
        }
    }

    /**
     * MealEntry as Room built it at v6, before deletion became possible.
     *
     * Spelled out rather than derived, for the same reason [ketoneV4] is.
     */
    private val mealV6 =
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
            "CREATE INDEX IF NOT EXISTS `index_MealEntry_timestamp` ON `MealEntry` (`timestamp`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_MealEntry_externalId` " +
                "ON `MealEntry` (`externalId`)",
        )

    @Test
    fun `the hidden column lands on the shape Room expects and leaves meals visible`() {
        val expected = roomColumns("MealEntry")

        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL("DROP TABLE IF EXISTS `MealEntry`")
            mealV6.forEach { raw.execSQL(it) }
            raw.execSQL(
                "INSERT INTO `MealEntry` (`timestamp`, `calories`, `source`, `externalId`) " +
                    "VALUES (1000, 602, 'HEALTH_CONNECT', 'hc-1')"
            )

            AppDatabase.migration6To7Statements.forEach { raw.execSQL(it) }

            assertEquals(expected, columnsOf(raw, "MealEntry"))
            // Nothing has been deleted yet, so every meal already logged has to
            // come through the migration still visible.
            raw.query("SELECT `calories`, `hidden` FROM `MealEntry`").use {
                it.moveToNext()
                assertEquals(602, it.getInt(0))
                assertEquals(0, it.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `the meal external id index is unique`() {
        // What makes a repeated Health Connect sync idempotent. A non-unique index
        // here would still open fine and silently duplicate every meal.
        val statements = roomSchema("MealEntry")
        assertTrue(
            "expected a unique index on externalId, got $statements",
            statements.any {
                it.contains("UNIQUE INDEX", ignoreCase = true) && it.contains("externalId")
            },
        )
    }
}
