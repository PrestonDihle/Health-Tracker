package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a backup has to survive.
 *
 * Every failure here is silent: a mis-escaped field produces a file that loads
 * without complaint into a spreadsheet and is wrong, with one row's columns
 * shifted by one from the point of the mistake onward. That is worse than an
 * export that fails outright, because nobody checks a backup until they need it.
 */
class CsvTest {

    @Test
    fun `an ordinary value is written bare`() {
        assertEquals("Creatine", Csv.field("Creatine"))
    }

    @Test
    fun `a value containing a comma is quoted`() {
        // The case that shifts every later column on the row. Supplement names
        // and doses are free text, so this is reachable from the UI.
        assertEquals("\"Vitamin D3, 5000 IU\"", Csv.field("Vitamin D3, 5000 IU"))
    }

    @Test
    fun `a quote inside a value is doubled and the value quoted`() {
        assertEquals("\"6\"\" waist\"", Csv.field("6\" waist"))
    }

    @Test
    fun `a newline inside a value is quoted rather than breaking the row`() {
        // A note typed with a line break would otherwise end the record early and
        // turn the rest of it into a row of its own.
        assertEquals("\"two\nlines\"", Csv.field("two\nlines"))
    }

    @Test
    fun `null and empty are the same empty cell`() {
        // A text file has no way to tell them apart, so pretending otherwise
        // would only mean inventing a distinction on the way back in.
        assertEquals("", Csv.field(null))
        assertEquals("", Csv.field(""))
    }

    @Test
    fun `a table is its header and rows, CRLF throughout`() {
        val csv =
            Csv.table(
                header = listOf("id", "name"),
                rows = listOf(listOf("1", "Zinc"), listOf("2", null)),
            )

        assertEquals("id,name\r\n1,Zinc\r\n2,\r\n", csv)
    }
}
