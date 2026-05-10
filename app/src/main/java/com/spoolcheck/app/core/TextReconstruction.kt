package com.spoolcheck.app.core

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Re-flow ML Kit's recognized text into spatial-aware logical rows.
 *
 * Why we don't just use [Text.getText]:
 *   ML Kit groups characters into Elements → Lines → Blocks based on
 *   proximity. On tags with cell-style layouts (each field in its own
 *   black-bordered box, label and value separated by wide whitespace),
 *   the label "Spool:" often lands in one block while its value "A"
 *   lands in a *different* block — even though the user's eye reads
 *   them as one row. `Text.text` then emits them on different lines.
 *
 * What this does:
 *   Iterate every Line across every Block. Group Lines whose vertical
 *   centers are within ~60% of a line height of each other (i.e. they
 *   share a row visually). Within each row, sort by X (left→right) and
 *   join with two spaces. Output rows are joined by newlines.
 *
 * Net effect: cell-layout tags read like "Spool:  A" on one logical
 * line, so the existing anchor logic in CodeMatcher works unchanged.
 */
fun reconstructSpatialText(mlText: Text): String {
    data class L(val text: String, val cy: Int, val height: Int, val left: Int)

    val items = mutableListOf<L>()
    for (block in mlText.textBlocks) {
        for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val height = (box.bottom - box.top).coerceAtLeast(1)
            val cy = (box.top + box.bottom) / 2
            items.add(L(line.text, cy, height, box.left))
        }
    }
    if (items.isEmpty()) return mlText.text

    // Sort by Y center so we can do a single greedy sweep.
    items.sortBy { it.cy }

    val rows = mutableListOf<MutableList<L>>()
    for (item in items) {
        // Tolerance scales with current line's height — wider tolerance
        // than seems strictly necessary because cell-layout transport
        // lists pair a tall drawing-text cell with a much smaller spool
        // letter cell on the same row, and the smaller cell's Y center
        // can drift well past 0.6×height from the drawing's center.
        val tol = item.height * 1.2
        val placed = rows.firstOrNull { row ->
            val ref = row.first()
            abs(ref.cy - item.cy) < tol
        }
        if (placed != null) placed.add(item) else rows.add(mutableListOf(item))
    }

    return rows.joinToString("\n") { row ->
        row.sortedBy { it.left }.joinToString("  ") { it.text }
    }
}

/**
 * Spatial pairing for transport-list photos. Independent of how ML Kit
 * orders its blocks/lines: we use only Y coordinates to pair drawings
 * with spool letters that visually sit on the same row.
 *
 * Algorithm:
 *  1. Walk every Line in every TextBlock with its bounding box.
 *  2. Classify each line: "drawing" (matches the drawing regex),
 *     "bare letter" (one A-Z after trimming), or "other".
 *  3. For each drawing, pick the bare letter whose Y-center is closest
 *     within roughly one row height. Each bare letter is paired at most
 *     once — already-claimed letters are removed from the pool, so two
 *     adjacent drawings get the two adjacent letters even if ML Kit
 *     emitted everything in column-major order far apart in `text`.
 *  4. Drawings that find no bare letter within range get spool="" — the
 *     scanner will then surface them as PARTIAL with valid variants.
 */
fun extractDrawingSpoolPairs(mlText: com.google.mlkit.vision.text.Text):
    List<Pair<String, String>> {
    data class Item(val text: String, val cy: Int, val height: Int)

    val drawings = mutableListOf<Item>()
    val letters  = mutableListOf<Item>()
    val drawingPattern = DEFAULT_CODE_PATTERN

    for (block in mlText.textBlocks) {
        for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val height = (box.bottom - box.top).coerceAtLeast(1)
            val cy = (box.top + box.bottom) / 2
            val upper = line.text.uppercase()

            val drawing = drawingPattern.find(upper)?.value?.let { normalizeDrawing(it) }
            if (drawing != null) {
                drawings.add(Item(drawing, cy, height))
                continue
            }
            val letter = CodeMatcher.bareLetterLine(upper)
            if (letter != null) {
                letters.add(Item(letter, cy, height))
            }
        }
    }

    val available = letters.toMutableList()
    val out = mutableListOf<Pair<String, String>>()
    for (d in drawings) {
        // Tolerance: ~150% of the drawing line's height gives plenty of
        // wiggle room for spool cells that sit slightly above/below their
        // drawing because of font-size differences inside the cell box.
        val tol = (d.height * 1.5).toInt().coerceAtLeast(40)
        val nearestIdx = available.indices
            .filter { kotlin.math.abs(available[it].cy - d.cy) <= tol }
            .minByOrNull { kotlin.math.abs(available[it].cy - d.cy) }
        val spool = if (nearestIdx != null) {
            val letter = available[nearestIdx].text
            available.removeAt(nearestIdx)
            letter
        } else ""
        out.add(d.text to spool)
    }
    return out
}
