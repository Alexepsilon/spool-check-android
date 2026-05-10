package com.spoolcheck.app.core

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR a photo of a printed transport list and extract (drawing, spool)
 * pairs from the recognised text.
 *
 * Strategy:
 *   - Each line of OCR text usually corresponds to one row of the list.
 *   - For every line: find a drawing-shaped match via the regex; look
 *     for a single uppercase letter that's NOT inside the drawing
 *     (excluding the drawing's own region) — that's the spool.
 *   - Dedup by composite (drawing, spool) so duplicates from re-OCR
 *     of stretched/blurry rows don't pollute the import.
 */
object PhotoListImporter {

    data class Result(
        val items: List<XlsxImporter.Imported>,
        /** A name suggested from the photo text, e.g. "Transport 30-04-2026"
         *  parsed from a "Note: TRANSPORT 30-04-2026" header. Null when
         *  no header was found. */
        val suggestedName: String? = null,
    )

    suspend fun importFromUri(ctx: Context, uri: Uri): Result {
        val image = InputImage.fromFilePath(ctx, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // Four views of the same OCR pass:
        //   - flat: ML Kit's default text(), used for the
        //     "Note: TRANSPORT ..." header extraction.
        //   - table rows: column-aware extractor that locates the header
        //     row and snaps cells to columns by X-center, producing
        //     fully-populated rows (drawing, spool, iso, diameter, ral,
        //     paint, ch.clean, remark, project).
        //   - spatial pairs: drawing/spool tuples paired by Y-coordinate
        //     directly from ML Kit bounding boxes — used as fallback for
        //     non-tabular layouts (e.g. tag photos used as a list).
        //   - reconstructed text: text-line fallback if both above miss.
        data class FourViews(
            val flat: String,
            val tableRows: List<TransportListExtractor.Row>,
            val pairs: List<Pair<String, String>>,
            val spatial: String,
        )
        val r = try {
            suspendCancellableCoroutine<FourViews> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener {
                        cont.resume(
                            FourViews(
                                flat = it.text,
                                tableRows = TransportListExtractor.extract(it),
                                pairs = extractDrawingSpoolPairs(it),
                                spatial = reconstructSpatialText(it),
                            )
                        )
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }

        // Use the column-aware table rows as primary if we got any.
        // They carry all fields (diameter, paint, ral, ch.clean, etc.).
        // Otherwise fall back to spatial pairs + text-parsed merge.
        val items: List<XlsxImporter.Imported> = if (r.tableRows.isNotEmpty()) {
            DebugLog.log("IMPORT", "primary: column-aware ${r.tableRows.size} rows")
            dedupNormalised(r.tableRows.map {
                XlsxImporter.Imported(
                    drawing = it.drawing,
                    spool = it.spool,
                    isoNumber = it.isoNumber,
                    project = it.project,
                    diameter = it.diameter,
                    paintSpec = it.paintSpec,
                    ral = it.ral,
                    chClean = it.chClean,
                    remark = it.remark,
                )
            })
        } else {
            DebugLog.log("IMPORT",
                "no table detected — fallback to spatial pairs + text parse")
            mergePairsAndText(r.pairs, parseText(r.spatial))
        }
        DebugLog.log("IMPORT",
            "final ${items.size} items: " +
                items.take(15).joinToString { "${it.drawing}|${it.spool.ifEmpty { "?" }}" } +
                if (items.size > 15) "  …" else "")
        return Result(items = items, suggestedName = extractTransportName(r.flat))
    }

    /** Collapse OCR-confused-char duplicates on a list of Imported. */
    private fun dedupNormalised(rows: List<XlsxImporter.Imported>): List<XlsxImporter.Imported> {
        val seen = HashSet<String>()
        val out = mutableListOf<XlsxImporter.Imported>()
        for (row in rows) {
            val key = CodeMatcher.normalise("${row.drawing}|${row.spool}")
            if (seen.add(key)) out.add(row)
        }
        return out
    }

    /**
     * Combine spatial pairs (primary) with text-parsed pairs (backstop).
     * For each drawing, prefer the spatial result. If spatial returned
     * spool="" but text parsing found a non-empty spool for the same
     * drawing, take the text parser's spool. Dedup with normalised key.
     */
    private fun mergePairsAndText(
        spatial: List<Pair<String, String>>,
        text: List<XlsxImporter.Imported>,
    ): List<XlsxImporter.Imported> {
        val out = mutableListOf<XlsxImporter.Imported>()
        val seen = HashSet<String>()
        for ((drawing, spool) in spatial) {
            val canon = CodeMatcher.normalise("$drawing|$spool")
            if (seen.add(canon)) {
                out.add(XlsxImporter.Imported(drawing = drawing, spool = spool))
            }
        }
        for (t in text) {
            val canon = CodeMatcher.normalise("${t.drawing}|${t.spool}")
            if (seen.add(canon)) out.add(t)
        }
        return out
    }

    /**
     * Pull a delivery name from the printed list's header. Real
     * Bakker Nedam transport sheets put "Note: TRANSPORT DD-MM-YYYY"
     * at the top-left. Variants tolerated: "Transport DD/MM/YYYY",
     * "TRANSPORT 30 04 2026", or just "TRANSPORT 30-04-2026" without
     * the "Note:" prefix.
     */
    private fun extractTransportName(text: String): String? {
        val upper = text.uppercase()
        // Look for "TRANSPORT" anywhere; capture the next date-shaped token.
        val re = Regex("""TRANSPORT[\s:.\-]*(\d{1,2}[\s./\-]\d{1,2}[\s./\-]\d{2,4})""")
        re.find(upper)?.let {
            val raw = it.groupValues[1]
            // Normalise separators to "-" for a tidy name.
            val normalised = raw.replace(Regex("[\\s./]"), "-")
            return "Transport $normalised"
        }
        // Fallback: any word after "TRANSPORT" (e.g. a name instead of a date).
        Regex("""TRANSPORT[\s:.\-]+([^\r\n]{1,30})""").find(upper)?.let {
            val rest = it.groupValues[1].trim().take(30)
            if (rest.isNotEmpty()) return "Transport $rest"
        }
        return null
    }

    private fun parseText(text: String): List<XlsxImporter.Imported> {
        val out = mutableListOf<XlsxImporter.Imported>()
        val seen = HashSet<String>()
        val pattern = Regex(DEFAULT_CODE_PATTERN.pattern)
        val lines = text.split(Regex("[\\r\\n]+"))

        DebugLog.log("IMPORT", "OCR ${text.length} chars, ${lines.size} lines")

        // Pre-scan: find every "bare letter" line (a line that is just
        // one A-Z letter once trimmed). When ML Kit splits a row's cells
        // onto separate lines, the spool value lands on its own line.
        // We pair each drawing-line with the nearest unused bare letter.
        val bareLetters: MutableList<Pair<Int, String>> = lines
            .mapIndexedNotNull { idx, l ->
                CodeMatcher.bareLetterLine(l.uppercase())?.let { idx to it }
            }
            .toMutableList()

        for ((rawIdx, rawLine) in lines.withIndex()) {
            val line = rawLine.uppercase()
            for (match in pattern.findAll(line)) {
                // Repair OCR-confused chars per position (digits in
                // letter slots, letters in digit slots) before deduping.
                val drawing = normalizeDrawing(match.value)
                // Look for a lone uppercase letter ELSEWHERE in the line
                // (excluding the drawing's own characters), since drawings
                // ending in "-T" would otherwise self-match.
                val masked = StringBuilder(line)
                for (i in match.range) masked.setCharAt(i, ' ')
                var spool = CodeMatcher.firstLoneLetter(masked.toString()).orEmpty()

                // Fallback: if no spool on the same line, look for the
                // nearest unused bare-letter line within ±2 of this row.
                // Handles ML Kit emitting cells on separate lines despite
                // spatial reconstruction — belt-and-braces.
                if (spool.isEmpty() && bareLetters.isNotEmpty()) {
                    val nearestIdx = bareLetters.indexOfFirst { (li, _) ->
                        kotlin.math.abs(li - rawIdx) <= 2
                    }
                    if (nearestIdx >= 0) {
                        spool = bareLetters[nearestIdx].second
                        // Mark consumed so the next drawing doesn't grab
                        // the same letter.
                        bareLetters.removeAt(nearestIdx)
                    }
                }

                // OCR-confused dedup: collapse "321-01L-..." / "321-0IL-..."
                // / "321-OIL-..." onto one canonical key by normalising
                // O↔0, I↔1↔L, S↔5, B↔8 — same logic the matcher uses.
                val canonicalKey = CodeMatcher.normalise("$drawing|$spool")
                if (seen.add(canonicalKey)) {
                    out.add(XlsxImporter.Imported(drawing = drawing, spool = spool))
                    DebugLog.log("IMPORT",
                        "row → drawing=$drawing spool='${spool.ifEmpty { "<empty>" }}'  " +
                            "from line: ${line.trim().take(80)}")
                }
            }
        }
        DebugLog.log("IMPORT", "parsed ${out.size} unique (drawing,spool) pairs " +
            "(${bareLetters.size} unused bare letters: " +
            bareLetters.joinToString { it.second } + ")")
        return out
    }
}
