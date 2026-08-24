package com.prestondihle.healthtracker.domain

/**
 * CSV writing, to RFC 4180.
 *
 * Separated from the export itself because the escaping is the part that is easy
 * to get subtly wrong and impossible to notice: a supplement called `Vitamin D3,
 * 5000 IU` written unquoted silently becomes two columns, and every column after
 * it on that row shifts by one. A backup that loads without complaint and is
 * wrong is worse than one that fails.
 */
object Csv {

    /** Excel and most importers expect CRLF, and tolerate LF. Nothing tolerates neither. */
    private const val NEWLINE = "\r\n"

    /**
     * One value, quoted only where it has to be.
     *
     * Quoting everything would be simpler and is what most hand-rolled writers
     * do; it also turns an empty cell into `""`, which some importers read as an
     * empty string and others as a quoted nothing. Left bare, a null and an empty
     * string are the same cell -- which they are, once written to a text file
     * that has no way to tell them apart.
     */
    fun field(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun row(values: List<String?>): String = values.joinToString(",", transform = ::field)

    /** A whole table: the header, then a row each, newline-terminated throughout. */
    fun table(header: List<String>, rows: List<List<String?>>): String =
        buildString {
            append(row(header))
            append(NEWLINE)
            rows.forEach {
                append(row(it))
                append(NEWLINE)
            }
        }
}
