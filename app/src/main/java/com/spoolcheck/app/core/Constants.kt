package com.spoolcheck.app.core

/**
 * Default drawing-number regex.
 *
 * Real-world tag formats observed at Bakker Nedam style sites:
 *   7-part: `322-FLA-1001-SS-100-P-2`, `322-GAS-0206-SS-80-N-1.2`
 *   6-part: `321-OIL-0108-SS-15-T`     (XYCLE / MOH yellow tags)
 */
val DEFAULT_CODE_PATTERN: Regex =
    Regex("""\b\d{3}(?:-[A-Z0-9]{1,6}(?:\.\d+)?){3,6}\b""")

val DRAWING_ANCHORS = listOf(
    // Dutch
    "TEKENINGNUMMER", "TEKENING NR", "TEKENING NUMMER", "TEKENING",
    "TEK NR", "TEK.NR", "TEKNR", "TEK",
    // English
    "DRAWING NO", "DRAWING NUMBER", "DRAWING",
    "DRG NO", "DRG.NO", "DWG NO", "DWG", "DRG",
)

val SPOOL_ANCHORS = listOf(
    // Dutch
    "SPOOLNR", "SPOOL NR", "SPOOL NUMMER", "SPOOL LETTER", "STUK",
    // English
    "SPOOL", "PIECE NO", "PIECE", "S/N",
)

/** Excel column header strings — case-insensitive substring match. */
val DRAWING_HEADERS = listOf(
    "drawing no", "drawing no.", "drawing number", "drawing", "dwg", "dwg no",
    "iso number", "iso no",
    "tekening", "tekeningnummer", "tekening nr", "tekening nummer",
    "tek nr", "tek.nr", "tek nummer",
)
val SPOOL_HEADERS = listOf(
    "spool", "spool no", "spool number", "spool letter",
    "piece", "piece no",
    "spoolnr", "spoolnummer", "stuk",
)

const val FRAME_CONSENSUS_COUNT = 2
const val FRAME_CONSENSUS_WINDOW = 8
const val SCAN_DEBOUNCE_MS = 4000L
const val OCR_FRAME_INTERVAL_MS = 250L

// Off-list firing is intentionally slower than match commits. A single
// poorly-lit frame can produce a regex-shaped read that doesn't match
// the master, so we require N consistent off-list reads (~1.25s at the
// 250ms frame interval) before the "Not on the list" dialog opens. Match
// auto-tick stays at FRAME_CONSENSUS_COUNT (2) for snappy verification.
const val OFFLIST_CONSENSUS_COUNT = 5

/** Composite key helper. */
fun composeKey(drawing: String, spool: String?): String {
    val s = (spool ?: "").trim().uppercase()
    return if (s.isEmpty()) drawing else "$drawing|$s"
}

/**
 * Pull the DN diameter out of a Bakker Nedam drawing string. The DN
 * value is the numeric segment immediately after the material code
 * ("SS" for stainless, "CS" for carbon steel):
 *   "321-OIL-0108-SS-15-T"        → "DN15"
 *   "352-CWS-1530-CS-150-N-1"     → "DN150"
 *   "322-FLA-1001-SS-100-P-2"     → "DN100"
 *
 * Returns null when no SS/CS segment is found (drawing doesn't follow
 * the format, or material code uses a less common abbreviation).
 */
private val DIAMETER_FROM_DRAWING = Regex("""-(?:SS|CS)-(\d+)\b""")

fun deriveDiameter(drawing: String): String? {
    // Use lastOrNull so a drawing with multiple material segments
    // (unusual but possible) takes the rightmost one — that's where
    // the actual pipe diameter sits in Bakker Nedam's tag format.
    val n = DIAMETER_FROM_DRAWING.findAll(drawing.uppercase())
        .lastOrNull()?.groupValues?.get(1) ?: return null
    return "DN$n"
}

/**
 * Returns the effective diameter for display: stored value first,
 * derived from drawing as fallback. Used by the Status Board and
 * the match-found dialog so older imports that didn't capture a
 * Diameter column still surface a value on screen.
 */
fun effectiveDiameter(stored: String?, drawing: String): String? =
    stored?.takeIf { it.isNotEmpty() } ?: deriveDiameter(drawing)
