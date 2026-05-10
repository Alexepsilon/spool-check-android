package com.spoolcheck.app.core

/**
 * Match OCR text against a master list of (drawing, spool) pairs.
 *
 * Two extraction passes per call:
 *   1. Anchor-based — find label words like "TEK NR" / "SPOOL" on the
 *      tag, read the value adjacent. Robust on busy tags.
 *   2. Regex fallback — pull anything matching the drawing-number
 *      regex; scan its line for a lone uppercase letter as the spool.
 *
 * Either way, results land in the master set keyed by composite
 * (drawing, spool). Fuzzy fallback substitutes O↔0, I↔1, S↔5 etc.
 * when strict comparison misses by one character.
 */
class CodeMatcher(
    entries: List<MasterEntry>,
    customPattern: String? = null,
) {
    data class MasterEntry(
        val drawing: String,
        val spool: String,
        val itemId: String,
        /** Iso number of the same row, when the file has both columns.
         *  Fabricators sometimes stamp tags with the iso number instead
         *  of the drawing number, so we let the matcher hit on either. */
        val isoNumber: String = "",
    ) {
        val key: String get() = composeKey(drawing, spool)
    }

    enum class Confidence { EXACT, FUZZY, PARTIAL }

    /** Live preview of what OCR is reading. Updated per-frame. */
    data class LivePreview(
        val drawing: String = "",
        val spool: String = "",
        val paint: String = "",
        val ral: String = "",
        val scope: String = "",
        val sheet: String = "",
        /** "Code:" field on XYCLE/MOH yellow tags — sometimes appears
         *  instead of (or alongside) "Scope nr". Useful for verification. */
        val code: String = "",
    ) {
        val isEmpty: Boolean
            get() = drawing.isEmpty() && spool.isEmpty()
    }

    data class Result(
        val drawing: String,
        val spool: String,
        val observed: String,
        val confidence: Confidence,
        val itemId: String? = null,
        /** When confidence == PARTIAL, list of valid spool letters for the drawing. */
        val availableSpools: List<String> = emptyList(),
    ) {
        val key: String get() = composeKey(drawing, spool)
    }

    private val pattern: Regex =
        customPattern?.let { Regex(it) } ?: DEFAULT_CODE_PATTERN

    private val byKey: Map<String, MasterEntry> =
        entries.associateBy { it.key }

    private val normalisedToKey: Map<String, String> =
        entries.associate { normalise(it.key) to it.key }

    private val allKeys: Set<String> = byKey.keys

    /** Iso-number → MasterEntry lookup so a tag stamped with the iso
     *  number instead of the drawing number still matches its row. */
    private val byIso: Map<String, MasterEntry> =
        entries
            .filter { it.isoNumber.isNotEmpty() && it.isoNumber != it.drawing }
            .associateBy { it.isoNumber }

    /**
     * Run anchor extraction + regex fallback against the OCR text and
     * return de-duped resolved results.
     *
     * Anchor extraction is run for spool and drawing independently; if
     * the spool anchor fires but the drawing anchor doesn't, we still
     * use that spool letter when the regex fallback finds the drawing.
     */
    fun match(ocrText: String): List<Result> {
        if (ocrText.isEmpty()) return emptyList()
        val upper = ocrText.uppercase()
        val hits = HashSet<String>()
        val out = mutableListOf<Result>()

        val anchoredSpool: String? = findSpoolByAnchor(upper)
        val anchoredDrawing: String? = findDrawingByAnchor(upper)

        if (anchoredDrawing != null) {
            // If anchor didn't find a spool, fall back to "which of the
            // master's valid spool letters for this drawing appears in
            // the OCR text?" — handles ML Kit reordering the boxed cells.
            val spoolForAnchored = anchoredSpool
                ?: findSpoolFromValidSet(upper, anchoredDrawing)
            resolve(anchoredDrawing, spoolForAnchored, hits)?.let { out.add(it) }
        }

        // Regex fallback — covers tags where the drawing has no
        // "Tek nr" / "Drawing" label. Prefer the anchored spool letter
        // (e.g., from "Spoolnr. E") over a line-local lone letter,
        // since anchors are far more reliable than guessing.
        for (m in pattern.findAll(upper)) {
            val drawing = m.value
            val effectiveSpool = anchoredSpool
                ?: spoolOnLineExcluding(upper, m.range.first, m.range.last + 1)
                ?: findSpoolFromValidSet(upper, drawing)
            resolve(drawing, effectiveSpool, hits)?.let { out.add(it) }
        }

        // Diagnostic snapshot — only logs when there's something to say
        // (an anchor or regex hit), to keep the log readable.
        if (anchoredDrawing != null || anchoredSpool != null || out.isNotEmpty()) {
            DebugLog.log("MATCH",
                "anchorD=${anchoredDrawing ?: "-"} anchorS=${anchoredSpool ?: "-"} " +
                    "→ ${out.size} result(s): " +
                    out.joinToString { "${it.confidence.name}(${it.drawing}|${it.spool})" }
                        .ifEmpty { "<none>" })
        }

        return out
    }

    /** Find a drawing number near a "Tek nr / Drawing / etc." label. */
    private fun findDrawingByAnchor(upper: String): String? {
        val lines = upper.split(Regex("[\\r\\n]+"))
        for (i in lines.indices) {
            val line = lines[i]
            for (anchor in DRAWING_ANCHORS) {
                val idx = line.indexOf(anchor)
                if (idx < 0) continue
                val tail = line.substring(idx + anchor.length)
                pattern.find(tail)?.let { return it.value }
                if (i + 1 < lines.size) {
                    pattern.find(lines[i + 1])?.let { return it.value }
                }
            }
        }
        return null
    }

    /**
     * Resolve an accumulated (drawing, spool) tuple straight against the
     * master set, without running text extraction. Used by the live
     * preview's "sticky fields" pipeline: when the user has built up
     * a drawing from frame A and a spool from frame B, we can match
     * the combined tuple even though no single frame contained both.
     */
    fun matchPair(drawing: String, spool: String): Result? =
        resolve(drawing, spool, HashSet())

    /**
     * Pull every visible field from OCR text in one pass — used to drive
     * the live "what the camera is reading" preview on the scanner.
     * Doesn't try to match against the master list; just extracts.
     */
    fun extractPreview(ocrText: String): LivePreview {
        if (ocrText.isEmpty()) return LivePreview()
        val upper = ocrText.uppercase()
        val drawing = findDrawingByAnchor(upper) ?: pattern.find(upper)?.value
        // Anchor first; if that fails but we have a drawing, fall back to
        // intersecting OCR text with the master's valid spool letters
        // for that drawing — this is what surfaces the spool on tags
        // where ML Kit splits the labeled cell from its value.
        val spool = findSpoolByAnchor(upper)
            ?: drawing?.let { findSpoolFromValidSet(upper, it) }
        val paint = findValueAfterAnchor(upper, listOf("VERFSYSTEEM", "PAINT SPEC", "PAINT SPECIFICATION", "PAINT"))
        val ral = findValueAfterAnchor(upper, listOf("RAL"))?.let { v ->
            // RAL is typically 4 digits or "N.A.". Trim any trailing words.
            Regex("(\\d{3,5})|N\\.?A\\.?").find(v)?.value
        }
        val scope = findValueAfterAnchor(upper, listOf("SCOPE NR", "SCOPE NO", "SCOPE NUMBER", "SCOPE"))
        // Sheet — multi-page tag indicator (e.g., "Sheet: 3 van 3").
        val sheet = findValueAfterAnchor(upper, listOf("SHEET", "BLAD", "PAGE"))
        // Code — alternative scope-like number on some tags ("Code: 321-51").
        val code = findValueAfterAnchor(upper, listOf("CODE"))
        return LivePreview(
            drawing = drawing ?: "",
            spool = spool ?: "",
            paint = paint ?: "",
            ral = ral ?: "",
            scope = scope ?: "",
            sheet = sheet ?: "",
            code = code ?: "",
        )
    }

    /** Find the value following any of [anchors] on the same or next line. */
    private fun findValueAfterAnchor(upper: String, anchors: List<String>): String? {
        val lines = upper.split(Regex("[\\r\\n]+"))
        for (i in lines.indices) {
            val line = lines[i]
            for (a in anchors) {
                val idx = line.indexOf(a)
                if (idx < 0) continue
                val tail = line.substring(idx + a.length)
                    .trimStart(':', '.', ' ', '\t')
                if (tail.isNotEmpty()) return tail.takeWhile { it != ':' }.trim().take(40)
                if (i + 1 < lines.size) {
                    val next = lines[i + 1].trim().take(40)
                    if (next.isNotEmpty()) return next
                }
            }
        }
        return null
    }

    /**
     * Find a spool letter near a "Spoolnr / Spool / Stuk / etc." label.
     *
     * Looks on the same line first, then up to [SPOOL_LOOKAHEAD] lines
     * after — XYCLE-style tags put the value in a separate boxed cell, so
     * ML Kit often emits it as its own line. Accepts both:
     *   - a "naked" letter line (e.g. just "B" alone)        →  bareLetterLine
     *   - a lone letter inside other text                    →  firstLoneLetter
     */
    private fun findSpoolByAnchor(upper: String): String? {
        val lines = upper.split(Regex("[\\r\\n]+"))
        for (i in lines.indices) {
            val line = lines[i]
            for (anchor in SPOOL_ANCHORS) {
                val idx = line.indexOf(anchor)
                if (idx < 0) continue
                val tail = line.substring(idx + anchor.length)
                bareLetterLine(tail)?.let { return it }
                firstLoneLetter(tail)?.let { return it }
                // Look ahead a few lines — handles vertically-stacked
                // label/value layouts and ML Kit blank-line gaps.
                for (j in 1..SPOOL_LOOKAHEAD) {
                    if (i + j >= lines.size) break
                    val ahead = lines[i + j]
                    bareLetterLine(ahead)?.let { return it }
                    firstLoneLetter(ahead)?.let { return it }
                    // Stop scanning ahead if we hit another labeled field —
                    // the value can't be that far from its own label.
                    if (looksLikeLabel(ahead)) break
                }
            }
        }
        return null
    }

    /**
     * Pick a spool letter from anywhere in the OCR text by intersecting
     * single-letter occurrences with the set of valid spool letters for
     * the matched drawing. Used as a last-resort fallback when the
     * standard anchor lookup fails — turns "ML Kit reordered the blocks"
     * problems into "we know which letters could legally appear".
     *
     * Returns the candidate only when exactly ONE valid letter is present
     * in the OCR text; if multiple show up, we'd be guessing — let the
     * spool-picker dialog handle it.
     */
    private fun findSpoolFromValidSet(upper: String, drawing: String): String? {
        val valid = byKey.values
            .filter { it.drawing == drawing && it.spool.length == 1 }
            .map { it.spool }
            .toSet()
        if (valid.isEmpty()) return null
        // Strip the drawing's own characters from the search — drawings
        // like "352-CWS-1530-CS-150-N-1" contain lone letters between
        // dashes (the "N") that the regex would otherwise treat as
        // candidate spool letters.
        val haystack = upper.replace(drawing, " ".repeat(drawing.length))
        val present = valid.filter { letter ->
            // Letter must appear with non-alnum boundaries somewhere in the
            // OCR text, so we don't grab letters embedded in larger words
            // like the "B" inside a stray "BLAD".
            Regex("(?<![A-Z0-9])$letter(?![A-Z0-9])").containsMatchIn(haystack)
        }
        return present.singleOrNull()
    }

    /**
     * Find a lone letter on the same line as the regex-matched drawing,
     * but BLANK OUT the drawing's region first so its trailing letter
     * (e.g., the "-T" suffix of `321-OIL-0108-SS-15-T`) cannot be
     * mistaken for a spool letter.
     */
    private fun spoolOnLineExcluding(upper: String, drawingStart: Int, drawingEnd: Int): String? {
        val lineStart = upper.lastIndexOf('\n', drawingStart).let { if (it < 0) 0 else it + 1 }
        var lineEnd = upper.indexOf('\n', drawingStart)
        if (lineEnd < 0) lineEnd = upper.length
        val sb = StringBuilder(upper.substring(lineStart, lineEnd))
        // Replace the drawing characters with spaces so they're not eligible
        // for the lone-letter search.
        val from = drawingStart - lineStart
        val to = drawingEnd - lineStart
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(sb.length)) {
            sb.setCharAt(i, ' ')
        }
        return firstLoneLetter(sb.toString())
    }

    private fun resolve(drawing: String, spool: String?, hits: MutableSet<String>): Result? {
        val s = (spool ?: "").uppercase()
        val exactKey = composeKey(drawing, s)

        // Iso-number redirect: if the OCR'd drawing matches an iso number
        // on a row whose drawing column is different, treat that row as
        // the canonical one. Some tags are stamped with iso instead of
        // the drawing number; either form should resolve.
        val viaIso = byIso[drawing]
        if (viaIso != null) {
            val isoKey = composeKey(viaIso.drawing, s.ifEmpty { viaIso.spool })
            byKey[isoKey]?.let {
                if (!hits.add(isoKey)) return null
                val siblings = byKey.values.filter { e -> e.drawing == it.drawing }.map { e -> e.spool }.sorted()
                return Result(it.drawing, it.spool, drawing, Confidence.EXACT, it.itemId, siblings)
            }
        }

        byKey[exactKey]?.let {
            if (!hits.add(exactKey)) return null
            // List every valid spool letter for this drawing so the
            // confirm dialog can render a chip picker. The user can
            // override OCR's read in one tap if a similar-looking letter
            // (B/8, C/D, O/0) was misidentified.
            val siblings = byKey.values.filter { e -> e.drawing == drawing }.map { e -> e.spool }.sorted()
            return Result(it.drawing, it.spool, drawing, Confidence.EXACT, it.itemId, siblings)
        }

        if (s.isEmpty()) {
            // Drawing-only match — exact only if list has empty-spool rows
            byKey[drawing]?.let {
                if (!hits.add(drawing)) return null
                return Result(it.drawing, it.spool, drawing, Confidence.EXACT, it.itemId)
            }
            // Drawing matches some master entries but we didn't read the
            // spool letter. Return PARTIAL so the UI prompts the user to
            // pick from the valid spools for this drawing.
            val available = byKey.values.filter { it.drawing == drawing }.map { it.spool }
            if (available.isNotEmpty()) {
                return Result(drawing, "", drawing, Confidence.PARTIAL, availableSpools = available.sorted())
            }
        }

        if (s.isNotEmpty()) {
            // Match by drawing only, regardless of how each row stored its
            // spool — `key.startsWith("drawing|")` was missing rows whose
            // composite key is just `drawing` (when an import failed to
            // capture a spool letter). Falling through to off-list for a
            // drawing that's clearly on the list was the symptom.
            val available = byKey.values
                .filter { it.drawing == drawing }
                .map { it.spool }
            if (available.isNotEmpty()) {
                return Result(drawing, s, drawing, Confidence.PARTIAL, availableSpools = available.sorted())
            }
        }

        // Last shot: fuzzy on composite key.
        normalisedToKey[normalise(exactKey)]?.let { fuzzyKey ->
            val entry = byKey[fuzzyKey]
            if (entry != null && hits.add(fuzzyKey)) {
                return Result(entry.drawing, entry.spool, drawing, Confidence.FUZZY, entry.itemId)
            }
        }
        return null
    }

    companion object {
        /** Maximum lines after a SPOOL anchor to scan for the value. */
        private const val SPOOL_LOOKAHEAD = 3

        /**
         * If the line, after trimming whitespace and trivial punctuation,
         * is exactly one A-Z letter, return it. Catches the XYCLE-style
         * layout where ML Kit emits the spool value cell as its own line.
         */
        fun bareLetterLine(s: String): String? {
            val t = s.trim().trim(':', '.', '-', '·', '|', '*')
            return if (t.length == 1 && t[0] in 'A'..'Z') t else null
        }

        /**
         * Heuristic: is this line itself a labeled field (so we should
         * stop scanning past the previous label)? Catches lines like
         * "VERFSYSTEEM:" or "RAL: 7001".
         */
        fun looksLikeLabel(s: String): Boolean {
            val t = s.trim()
            if (t.length < 3) return false
            // A label line typically has a colon within the first ~14 chars
            // and starts with letters.
            val colon = t.indexOf(':')
            return colon in 2..13 && t.substring(0, colon).all { it.isLetter() || it == ' ' }
        }

        /** Find first standalone uppercase letter (A-Z) in a string. */
        fun firstLoneLetter(s: String): String? =
            Regex("(?<![A-Z0-9])([A-Z])(?![A-Z0-9])").find(s)?.groupValues?.getOrNull(1)

        /** Normalize for fuzzy comparison: uppercase, OCR-confused chars → digits. */
        fun normalise(input: String): String =
            input.uppercase()
                .replace(Regex("\\s+"), "")
                .replace('O', '0').replace('Q', '0').replace('D', '0')
                .replace('I', '1').replace('L', '1').replace('|', '1')
                .replace('Z', '2').replace('S', '5').replace('B', '8')
    }
}
