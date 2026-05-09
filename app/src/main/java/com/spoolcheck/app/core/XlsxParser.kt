package com.spoolcheck.app.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal .xlsx reader. xlsx is a ZIP of XML files; we only need
 * `xl/sharedStrings.xml` and `xl/worksheets/sheet1.xml`. Avoids
 * pulling in Apache POI's 15 MB of dependencies.
 *
 * Returns a list of rows, each row a list of cell strings. Column
 * indices are derived from each cell's `r` attribute (e.g., `B5` →
 * column 1) so blank cells are handled correctly.
 */
object XlsxReader {
    fun read(input: InputStream): List<List<String>> {
        val files = unzip(input)
        val sharedStrings = files["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetXml = files["xl/worksheets/sheet1.xml"]
            ?: files.entries.firstOrNull { it.key.startsWith("xl/worksheets/") }?.value
            ?: return emptyList()
        return parseSheet(sheetXml, sharedStrings)
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val out = mutableListOf<String>()
        var current: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> { /* text accumulated in TEXT */ }
                }
                XmlPullParser.TEXT -> {
                    if (current != null && parser.depth >= 3) current.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "si" -> { current?.let { out.add(it.toString()) }; current = null }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val rows = mutableListOf<MutableList<String>>()
        var currentRow: MutableList<String>? = null
        var cellRef: String? = null
        var cellType: String? = null
        var cellValue: StringBuilder? = null
        var inV = false
        var inIs = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r")
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = StringBuilder()
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> { /* nested in <is> for inline strings */ }
                }
                XmlPullParser.TEXT -> {
                    if (inV || inIs) cellValue?.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inV = false
                    "is" -> inIs = false
                    "c" -> {
                        val rawValue = cellValue?.toString().orEmpty()
                        val value = when (cellType) {
                            "s" -> {
                                val idx = rawValue.toIntOrNull() ?: -1
                                if (idx in sharedStrings.indices) sharedStrings[idx] else ""
                            }
                            "inlineStr" -> rawValue
                            "str" -> rawValue
                            else -> rawValue
                        }
                        // Pad row up to column index from cellRef.
                        val colIdx = colIndexFromRef(cellRef)
                        if (currentRow != null && colIdx >= 0) {
                            while (currentRow!!.size < colIdx) currentRow!!.add("")
                            if (currentRow!!.size == colIdx) currentRow!!.add(value)
                            else currentRow!![colIdx] = value
                        } else if (currentRow != null) {
                            currentRow!!.add(value)
                        }
                    }
                    "row" -> {
                        if (currentRow != null) rows.add(currentRow!!)
                        currentRow = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    /** "B5" → 1, "AA10" → 26, etc. */
    private fun colIndexFromRef(ref: String?): Int {
        if (ref == null) return -1
        var col = 0
        for (c in ref) {
            if (c in 'A'..'Z') col = col * 26 + (c - 'A' + 1)
            else if (c in 'a'..'z') col = col * 26 + (c - 'a' + 1)
            else break
        }
        return col - 1
    }
}

/** Column-aware structured xlsx import — the same logic as the PWA's importer. */
object XlsxImporter {
    data class Imported(
        val drawing: String,
        val spool: String,
        val isoNumber: String? = null,
        val project: String? = null,
        val diameter: String? = null,
        val paintSpec: String? = null,
        val ral: String? = null,
        val chClean: String? = null,
        val remark: String? = null,
    )

    private val standardFieldHeaders = mapOf(
        "drawing" to DRAWING_HEADERS,
        "spool" to SPOOL_HEADERS,
        "isoNumber" to listOf("iso number", "iso no", "iso nummer", "iso nr"),
        "project" to listOf("project", "area", "gebied", "job", "project nr"),
        "diameter" to listOf("diameter", "dia", "size", "maat", "dn"),
        "paintSpec" to listOf("paint spec", "paint spec.", "paint specification", "paint", "verfsysteem", "verfspec", "verfspecificatie"),
        "ral" to listOf("ral", "ral colour", "ral color", "kleur"),
        // The same column may be labelled Ch.clean., Code, Scope nr,
        // or various Dutch equivalents depending on the fabricator —
        // they're all the same scope/cleanliness identifier.
        "chClean" to listOf(
            "ch.clean.", "ch clean", "cleanliness",
            "code", "code nr", "code no", "code number",
            "scope", "scope nr", "scope no", "scope number",
            "reiniging", "schoonheid",
        ),
        "remark" to listOf("remark", "note", "remarks", "opmerking", "opmerkingen", "notities"),
    )

    fun parse(input: InputStream): List<Imported> {
        val rows = XlsxReader.read(input)
        if (rows.isEmpty()) return emptyList()

        val headerInfo = findHeader(rows) ?: return looseExtract(rows)
        val (rowIdx, columnMap) = headerInfo
        val drawingCol = columnMap["drawing"] ?: -1
        if (drawingCol < 0) return looseExtract(rows)
        val spoolCol = columnMap["spool"] ?: -1

        val items = mutableListOf<Imported>()
        val seen = HashSet<String>()
        var blankStreak = 0
        for (r in (rowIdx + 1) until rows.size) {
            val row = rows[r]
            val drawing = safeCell(row, drawingCol).trim()
            val spool = safeCell(row, spoolCol).trim().uppercase()
            if (drawing.isEmpty() && spool.isEmpty()) {
                if (++blankStreak >= 2) break
                continue
            }
            blankStreak = 0
            if (drawing.isEmpty() || !looksLikeCode(drawing)) continue
            val k = "$drawing|$spool"
            if (!seen.add(k)) continue
            items.add(
                Imported(
                    drawing = drawing,
                    spool = spool,
                    isoNumber = safeCell(row, columnMap["isoNumber"] ?: -1).ifEmpty { null },
                    project = safeCell(row, columnMap["project"] ?: -1).ifEmpty { null },
                    diameter = safeCell(row, columnMap["diameter"] ?: -1).ifEmpty { null },
                    paintSpec = safeCell(row, columnMap["paintSpec"] ?: -1).ifEmpty { null },
                    ral = safeCell(row, columnMap["ral"] ?: -1).ifEmpty { null },
                    chClean = safeCell(row, columnMap["chClean"] ?: -1).ifEmpty { null },
                    remark = safeCell(row, columnMap["remark"] ?: -1).ifEmpty { null },
                )
            )
        }
        return items
    }

    private fun findHeader(rows: List<List<String>>): Pair<Int, Map<String, Int>>? {
        val maxScan = minOf(rows.size, 30)
        for (r in 0 until maxScan) {
            val row = rows[r]
            val map = mutableMapOf<String, Int>()
            for (c in row.indices) {
                val cell = row[c].trim()
                if (cell.isEmpty()) continue
                for ((field, candidates) in standardFieldHeaders) {
                    if (map.containsKey(field)) continue
                    if (matchHeader(cell, candidates)) {
                        map[field] = c
                        break
                    }
                }
            }
            if (map.containsKey("drawing")) return r to map
        }
        return null
    }

    private fun looseExtract(rows: List<List<String>>): List<Imported> {
        val seen = HashSet<String>()
        val out = mutableListOf<Imported>()
        for (row in rows) {
            for (cell in row) {
                val s = cell.trim()
                if (!looksLikeCode(s)) continue
                if (!DEFAULT_CODE_PATTERN.containsMatchIn(s)) continue
                if (seen.add(s)) out.add(Imported(drawing = s, spool = ""))
            }
        }
        return out
    }

    private fun safeCell(row: List<String>, idx: Int): String =
        if (idx < 0 || idx >= row.size) "" else row[idx]

    private fun matchHeader(value: String, candidates: List<String>): Boolean {
        val v = value.lowercase().replace(Regex("[\\.\\s_]+"), "")
        return candidates.any { c ->
            val cn = c.lowercase().replace(Regex("[\\.\\s_]+"), "")
            v == cn || v.contains(cn) || cn.contains(v)
        }
    }

    private fun looksLikeCode(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3 || t.length > 64) return false
        if (t.contains(' ')) return false
        if (!t.contains('-')) return false
        return t.any { it.isDigit() } && t.any { it.isLetter() }
    }
}
