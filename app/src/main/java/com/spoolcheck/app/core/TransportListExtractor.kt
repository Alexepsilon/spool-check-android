package com.spoolcheck.app.core

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Column-aware extractor for printed transport lists.
 *
 * Real-world Bakker Nedam / MOH lists have a fixed tabular layout with
 * columns: Project | Iso number | Drawing no. | Spool | Diameter |
 * Paint spec. | RAL | Ch.clean. | Remark. Earlier importer logic only
 * captured (drawing, spool) and missed everything else.
 *
 * Approach:
 *   1. Walk every ML Kit Line with its bounding box.
 *   2. Find the header row by looking for any line whose text matches
 *      a known column keyword (DRAWING, SPOOL, DIAMETER, RAL, etc.).
 *      Lines on the same Y as that line are also headers.
 *   3. Map each header to its X-center so we know where each column
 *      lives on the page.
 *   4. Y-group all non-header lines into rows. For each row, snap each
 *      cell to its nearest column by X-center.
 *   5. Build a row object with as many fields as we could classify.
 *
 * Fields that aren't found stay null/empty — caller decides whether
 * to use this result or fall back to the simpler drawing+spool parser.
 */
object TransportListExtractor {

    data class Row(
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

    private data class Cell(val text: String, val cy: Int, val cx: Int, val height: Int)

    /** Column keywords to header-key mapping (uppercased on lookup). */
    private val HEADER_KEYWORDS = mapOf(
        "DRAWING"     to "drawing",
        "DRAWING NO"  to "drawing",
        "TEK"         to "drawing",
        "TEK NR"      to "drawing",
        "TEKENING"    to "drawing",
        "ISO"         to "iso",
        "ISO NUMBER"  to "iso",
        "ISO NR"      to "iso",
        "SPOOL"       to "spool",
        "SPOOLNR"     to "spool",
        "STUK"        to "spool",
        "DIAMETER"    to "diameter",
        "Ø"           to "diameter",
        "PAINT"       to "paint",
        "PAINT SPEC"  to "paint",
        "VERFSYSTEEM" to "paint",
        "RAL"         to "ral",
        "CH.CLEAN"    to "chclean",
        "CH CLEAN"    to "chclean",
        "CHCLEAN"     to "chclean",
        "CH.CLEAN."   to "chclean",
        "SCOPE"       to "chclean",
        "SCOPE NR"    to "chclean",
        "CODE"        to "chclean",
        "PROJECT"     to "project",
        "AREA"        to "project",
        "REMARK"      to "remark",
        "OPMERKING"   to "remark",
        "REMARKS"     to "remark",
    )

    fun extract(mlText: Text): List<Row> {
        val cells = collectCells(mlText)
        if (cells.isEmpty()) return emptyList()

        // 1. Find header row — pick the Y row that contains the most
        //    header keyword matches.
        val headerRow = findHeaderRow(cells) ?: return emptyList()
        DebugLog.log("IMPORT", "header detected at Y=${headerRow.first} with " +
            "${headerRow.second.size} columns: " +
            headerRow.second.joinToString { "${it.first}@x=${it.second}" })

        val columns = headerRow.second  // List<Pair<columnKey, xCenter>>
        if (columns.size < 2) return emptyList()

        // 2. Group remaining cells into rows by Y, skip the header band.
        val headerY = headerRow.first
        val avgRowHeight = cells.map { it.height }.average().toInt().coerceAtLeast(20)
        val dataCells = cells.filter { abs(it.cy - headerY) > avgRowHeight }

        val rows = groupByRow(dataCells, avgRowHeight)
        DebugLog.log("IMPORT", "grouped into ${rows.size} data rows")

        // 3. For each row, snap each cell to its nearest column.
        val parsed = rows.mapNotNull { rowCells ->
            assembleRow(rowCells, columns)
        }
        DebugLog.log("IMPORT", "extracted ${parsed.size} master rows from table")
        return parsed
    }

    private fun collectCells(mlText: Text): List<Cell> {
        val out = mutableListOf<Cell>()
        for (block in mlText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val height = (box.bottom - box.top).coerceAtLeast(1)
                val cy = (box.top + box.bottom) / 2
                val cx = (box.left + box.right) / 2
                out.add(Cell(text, cy, cx, height))
            }
        }
        return out
    }

    /**
     * Find the header row. Returns (Y center, list of (columnKey, xCenter))
     * for the line that has the most header keyword matches.
     */
    private fun findHeaderRow(cells: List<Cell>): Pair<Int, List<Pair<String, Int>>>? {
        // Group cells by Y (within one line height) and score each group
        // by how many of its cells match header keywords.
        val avgH = cells.map { it.height }.average().toInt().coerceAtLeast(20)
        val rows = groupByRow(cells, avgH)
        var bestRow: List<Cell>? = null
        var bestCols: List<Pair<String, Int>> = emptyList()
        for (row in rows) {
            val cols = row.mapNotNull { cell ->
                classifyHeader(cell.text)?.let { key -> key to cell.cx }
            }
            if (cols.size > bestCols.size) {
                bestCols = cols
                bestRow = row
            }
        }
        if (bestRow == null || bestCols.size < 2) return null
        val cy = bestRow.map { it.cy }.average().toInt()
        return cy to bestCols.sortedBy { it.second }
    }

    private fun classifyHeader(text: String): String? {
        val upper = text.uppercase().trim().trim('.', ':', ' ')
        // Look for an exact keyword match first, then a startsWith match.
        HEADER_KEYWORDS[upper]?.let { return it }
        for ((kw, key) in HEADER_KEYWORDS) {
            if (upper.startsWith(kw)) return key
        }
        return null
    }

    /** Group cells into Y-rows, tolerance = avgH * 0.7. */
    private fun groupByRow(cells: List<Cell>, avgH: Int): List<List<Cell>> {
        if (cells.isEmpty()) return emptyList()
        val sorted = cells.sortedBy { it.cy }
        val tol = (avgH * 0.7).toInt()
        val rows = mutableListOf<MutableList<Cell>>()
        for (cell in sorted) {
            val placed = rows.firstOrNull { row ->
                val refY = row.first().cy
                abs(refY - cell.cy) <= tol
            }
            if (placed != null) placed.add(cell) else rows.add(mutableListOf(cell))
        }
        return rows.map { it.sortedBy { c -> c.cx } }
    }

    /**
     * Snap each cell in a row to its nearest column by X-center and
     * build a Row object. Returns null if the row has no drawing-shaped
     * cell (skips junk rows like footers).
     */
    private fun assembleRow(
        rowCells: List<Cell>,
        columns: List<Pair<String, Int>>,
    ): Row? {
        val byColumn = mutableMapOf<String, String>()
        for (cell in rowCells) {
            // Find the column whose X-center is closest to this cell's X.
            val nearest = columns.minByOrNull { abs(it.second - cell.cx) } ?: continue
            // If a column already has content, prefer the cell whose X is
            // closer to the column's center (mostly a tiebreak).
            val existing = byColumn[nearest.first]
            if (existing == null) {
                byColumn[nearest.first] = cell.text
            } else {
                // Heuristic: keep the one matching the column's value shape.
                byColumn[nearest.first] = pickBetter(nearest.first, existing, cell.text)
            }
        }

        val drawing = byColumn["drawing"]
            ?: byColumn["iso"]
            ?: return null

        // Sanity: drawing must roughly look like a drawing number.
        if (!DEFAULT_CODE_PATTERN.containsMatchIn(drawing.uppercase())) return null
        val drawingMatch = DEFAULT_CODE_PATTERN.find(drawing.uppercase())?.value ?: return null

        val spoolRaw = byColumn["spool"]
        val spool = if (spoolRaw != null && spoolRaw.length <= 3) {
            CodeMatcher.bareLetterLine(spoolRaw.uppercase())
                ?: CodeMatcher.firstLoneLetter(spoolRaw.uppercase())
                ?: ""
        } else ""

        return Row(
            drawing = drawingMatch,
            spool = spool,
            isoNumber = byColumn["iso"]?.let {
                DEFAULT_CODE_PATTERN.find(it.uppercase())?.value
            },
            project = byColumn["project"]?.takeUnless { isPlaceholder(it) },
            diameter = byColumn["diameter"]?.takeUnless { isPlaceholder(it) },
            paintSpec = byColumn["paint"]?.takeUnless { isPlaceholder(it) },
            ral = byColumn["ral"]?.takeUnless { isPlaceholder(it) },
            chClean = byColumn["chclean"]?.takeUnless { isPlaceholder(it) },
            remark = byColumn["remark"]?.takeUnless { isPlaceholder(it) },
        )
    }

    private fun pickBetter(column: String, a: String, b: String): String {
        // Prefer the value that looks right for the column.
        return when (column) {
            "drawing", "iso" -> if (DEFAULT_CODE_PATTERN.containsMatchIn(a.uppercase())) a else b
            "spool" -> if (a.length <= 3 && a.uppercase().any { it in 'A'..'Z' }) a else b
            "diameter" -> if (a.uppercase().startsWith("DN")) a else b
            "ral" -> if (a.matches(Regex("\\d{3,5}"))) a else b
            else -> a
        }
    }

    private fun isPlaceholder(text: String): Boolean {
        val u = text.uppercase().trim().trim('.', ' ')
        return u.isEmpty() || u == "N.A" || u == "N.A." || u == "N/A" || u == "—" || u == "-"
    }
}
