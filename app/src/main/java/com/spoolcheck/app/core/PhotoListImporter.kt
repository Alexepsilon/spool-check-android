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
        val text = try {
            suspendCancellableCoroutine<String> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
        return Result(items = parseText(text), suggestedName = extractTransportName(text))
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

        // Process line by line. OCR usually preserves rows.
        for (rawLine in text.split(Regex("[\\r\\n]+"))) {
            val line = rawLine.uppercase()
            for (match in pattern.findAll(line)) {
                val drawing = match.value
                // Look for a lone uppercase letter ELSEWHERE in the line
                // (excluding the drawing's own characters), since drawings
                // ending in "-T" would otherwise self-match.
                val masked = StringBuilder(line)
                for (i in match.range) masked.setCharAt(i, ' ')
                val spool = CodeMatcher.firstLoneLetter(masked.toString()).orEmpty()
                val key = "$drawing|$spool"
                if (seen.add(key)) {
                    out.add(XlsxImporter.Imported(drawing = drawing, spool = spool))
                }
            }
        }
        return out
    }
}
