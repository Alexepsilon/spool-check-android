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
const val FRAME_CONSENSUS_WINDOW = 5
const val SCAN_DEBOUNCE_MS = 4000L
const val OCR_FRAME_INTERVAL_MS = 250L

/** Composite key helper. */
fun composeKey(drawing: String, spool: String?): String {
    val s = (spool ?: "").trim().uppercase()
    return if (s.isEmpty()) drawing else "$drawing|$s"
}
