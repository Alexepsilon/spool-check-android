package com.spoolcheck.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One imported transport list. Multiple deliveries can coexist; only
 * one is "active" at a time during scanning.
 */
@Entity(tableName = "deliveries")
data class Delivery(
    @PrimaryKey val id: String,
    val name: String,
    val importedAt: Long,
    val sourceType: String, // "xlsx" / "csv" / "photo" / "manual"
    val status: String,     // "open" / "closed"
    val codePattern: String? = null,
)

/**
 * One row on a master list. Unique per (deliveryId, drawing, spool).
 */
@Entity(
    tableName = "master_items",
    indices = [
        Index(value = ["deliveryId"]),
        Index(value = ["deliveryId", "drawing", "spool"], unique = true),
        Index(value = ["status"]),
    ],
)
data class MasterItem(
    @PrimaryKey val id: String,
    val deliveryId: String,
    val drawing: String,
    val spool: String, // "" if list has no spool column
    val isoNumber: String? = null,
    val project: String? = null,
    val diameter: String? = null,
    val paintSpec: String? = null,
    val ral: String? = null,
    val chClean: String? = null,
    val remark: String? = null,
    val status: String = "expected", // expected / verified / missing / damaged / wrong_item
    val verifiedAt: Long? = null,
    val notes: String? = null,
)

/** Append-only scan log; ground truth audit trail. */
@Entity(
    tableName = "scans",
    indices = [
        Index(value = ["deliveryId"]),
        Index(value = ["timestamp"]),
    ],
)
data class Scan(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val deliveryId: String?,
    val drawing: String,
    val spool: String,
    val rawText: String? = null,
    val matchedItemId: String? = null,
    val confidence: String, // exact / fuzzy / partial / none
    val confirmed: Boolean,
)

@Entity(
    tableName = "uncharted",
    indices = [Index(value = ["deliveryId"])],
)
data class UnchartedItem(
    @PrimaryKey val id: String,
    val scanId: String,
    val deliveryId: String?,
    val timestamp: Long,
    val drawing: String,
    val spool: String,
    // Optional Sheet field captured from the tag at scan time. Some
    // suppliers don't print a sheet number, so this stays null when
    // OCR didn't see one.
    val sheet: String? = null,
    val disposition: String = "unassigned",
    val notes: String? = null,
    val resolvedAt: Long? = null,
)
