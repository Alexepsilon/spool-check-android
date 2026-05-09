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
        // Tolerance scales with current line's height — small text rows
        // pack tighter than headline rows.
        val tol = item.height * 0.6
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
