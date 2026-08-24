package com.prestondihle.healthtracker.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.prestondihle.healthtracker.domain.Csv
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every table in the database, written out as CSV and zipped.
 *
 * One SQLite file on one phone is one corruption, one dropped handset or one
 * uninstall away from gone, and none of what is in it exists anywhere else:
 * fasting history, hand-typed weights and waists, blood sugar, the supplement
 * stack. The Health Connect caches are the only part that could be rebuilt, and
 * they are exported too because leaving them out would mean deciding, on every
 * future table, whether it counts -- and getting that wrong is silent.
 *
 * **The table list comes from `sqlite_master`, not from a list kept here.** A
 * hand-maintained list is correct on the day it is written and quietly
 * incomplete from the next migration onward, which is exactly the failure a
 * backup cannot afford.
 */
object CsvBackup {

    /** Room's own bookkeeping, and SQLite's. Neither is anybody's data. */
    private val INTERNAL_TABLES = setOf("room_master_table", "sqlite_sequence")

    private const val TABLE_LIST_SQL =
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name"

    /** Names of every table holding data, in a stable order. */
    fun tableNames(dao: TrackerDao): List<String> {
        val names = mutableListOf<String>()
        dao.rawCursor(SimpleSQLiteQuery(TABLE_LIST_SQL)).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name !in INTERNAL_TABLES) names.add(name)
            }
        }
        return names
    }

    /**
     * One table as CSV, header included.
     *
     * Column names and types come from the cursor rather than from the entity, so
     * a column added by a migration appears without anything here being told
     * about it. Values are taken as strings whatever their storage class --
     * epoch millis stay epoch millis, which is lossless and is what the app reads
     * back; formatting them as dates here would be a second date format to keep
     * in step with `Converters`, and the wrong place for one.
     */
    fun tableCsv(dao: TrackerDao, table: String): String {
        val header = mutableListOf<String>()
        val rows = mutableListOf<List<String?>>()
        dao.rawCursor(SimpleSQLiteQuery("SELECT * FROM `$table`")).use { cursor ->
            header.addAll(cursor.columnNames)
            while (cursor.moveToNext()) {
                rows.add((0 until cursor.columnCount).map { cursor.getString(it) })
            }
        }
        return Csv.table(header, rows)
    }

    /**
     * Writes every table into [destination] as a zip of CSVs.
     *
     * A zip rather than a folder of loose files because the share sheet moves one
     * attachment far more reliably than fifteen, and rather than a single
     * combined CSV because tables have different columns and merging them would
     * mean inventing a shape nothing reads.
     */
    suspend fun writeZip(dao: TrackerDao, destination: File) =
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(destination).buffered()).use { zip ->
                tableNames(dao).forEach { table ->
                    zip.putNextEntry(ZipEntry("$table.csv"))
                    zip.write(tableCsv(dao, table).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
}
