package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.CsvBackup
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.data.TrackerDao
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup, which is the one feature nobody checks until they need it.
 *
 * Two failures are worth guarding against and neither announces itself: a table
 * quietly missing from the export, and a value that breaks the row it is written
 * on. Both produce a file that opens perfectly well and is wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CsvBackupTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: TrackerDao

    private fun repository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.trackerDao()
        return TrackerRepository(dao, MockHealthDataSource(), ZoneId.of("UTC"))
    }

    @Test
    fun `every table in the schema is exported, and Room's bookkeeping is not`() {
        val repository = repository()
        runBlocking { repository.addSupplement("Zinc", "50 mg", SupplementSlot.EVENING) }

        val tables = CsvBackup.tableNames(dao)

        // A spread across the schema, including the two newest. The point is not
        // this list -- it is that nothing had to be added to the exporter when
        // these tables arrived, because it reads sqlite_master rather than a list
        // somebody has to remember to update.
        listOf(
                "DailyLog",
                "FastingSession",
                "WeightEntry",
                "BloodSugarReading",
                "MealEntry",
                "Supplement",
                "SupplementDose",
            )
            .forEach { assertTrue("$it missing from the backup", it in tables) }

        assertTrue("room_master_table is not data", "room_master_table" !in tables)
    }

    @Test
    fun `a value containing a comma survives the round trip`() {
        // Supplement names and doses are free text and reachable from the UI, so
        // this is the realistic way a row gets broken. Unquoted, the dose would
        // become a column of its own and shift the slot into it.
        val repository = repository()
        runBlocking { repository.addSupplement("Magnesium", "200 mg, glycinate", SupplementSlot.EVENING) }

        val csv = CsvBackup.tableCsv(dao, "Supplement")
        val dataRow = csv.trim().lines().last()

        assertTrue(csv.startsWith("id,name,dose,slot"))
        assertEquals("1,Magnesium,\"200 mg, glycinate\",EVENING", dataRow)
    }

    @Test
    fun `the zip holds one CSV per table`() {
        val repository = repository()
        val destination = File(context.cacheDir, "exports/test-backup.zip")

        runBlocking { repository.writeCsvBackup(destination) }

        val expected = CsvBackup.tableNames(dao).map { "$it.csv" }.toSet()
        ZipFile(destination).use { zip ->
            val entries = zip.entries().toList().map { it.name }.toSet()
            assertEquals(expected, entries)
            // And they are not empty shells: every one carries at least a header.
            zip.entries().toList().forEach {
                assertTrue("${it.name} is empty", zip.getInputStream(it).readBytes().isNotEmpty())
            }
        }
    }
}
