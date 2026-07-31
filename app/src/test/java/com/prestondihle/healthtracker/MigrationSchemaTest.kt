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

    @Test
    fun `migration builds MealEntry exactly as Room expects`() {
        assertEquals(
            roomSchema("MealEntry"),
            migrationSchema("MealEntry", AppDatabase.migration3To4Statements),
        )
    }

    @Test
    fun `migration builds HeartRateBucket exactly as Room expects`() {
        assertEquals(
            roomSchema("HeartRateBucket"),
            migrationSchema("HeartRateBucket", AppDatabase.migration3To4Statements),
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
