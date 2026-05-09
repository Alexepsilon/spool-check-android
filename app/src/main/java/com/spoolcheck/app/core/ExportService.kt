package com.spoolcheck.app.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.spoolcheck.app.data.AppDatabase
import com.spoolcheck.app.data.Delivery
import com.spoolcheck.app.data.MasterItem
import com.spoolcheck.app.data.UnchartedItem
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Build a two-sheet xlsx report (Master list + Uncharted) for a delivery
 * and either save it to the Downloads folder or hand it back as a Uri
 * for an Android share intent.
 */
object ExportService {

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val FILE_FMT = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())

    suspend fun export(
        ctx: Context,
        deliveryId: String,
    ): Uri? {
        val db = AppDatabase.get(ctx)
        val delivery = db.deliveries().get(deliveryId) ?: return null
        val items = db.items().listForDelivery(deliveryId)
        val unchartedRows = db.uncharted().listForDelivery(deliveryId)

        val sheets = listOf(
            buildMasterSheet(delivery, items),
            buildUnchartedSheet(unchartedRows),
        )

        val safeName = delivery.name.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        val fileName = "${safeName}_${FILE_FMT.format(Date())}.xlsx"
        return saveToDownloads(ctx, fileName, sheets)
    }

    private fun buildMasterSheet(delivery: Delivery, items: List<MasterItem>): XlsxWriter.Sheet {
        val rows = mutableListOf<List<String>>()
        rows.add(listOf("Note:", "Spool Check export — ${delivery.name}"))
        rows.add(listOf("Total:", items.size.toString(),
            "Verified:", items.count { it.status == "verified" }.toString(),
            "Missing:", items.count { it.status == "missing" }.toString(),
            "Damaged:", items.count { it.status == "damaged" }.toString(),
        ))
        rows.add(emptyList())
        rows.add(listOf(
            "Project", "Iso number", "Drawing no.", "Spool", "Diameter",
            "Paint spec.", "RAL", "Ch.clean.", "Remark",
            "Status", "Verified at", "Notes",
        ))
        for (i in items) {
            rows.add(listOf(
                i.project ?: "",
                i.isoNumber ?: "",
                i.drawing,
                i.spool,
                i.diameter ?: "",
                i.paintSpec ?: "",
                i.ral ?: "",
                i.chClean ?: "",
                i.remark ?: "",
                i.status,
                i.verifiedAt?.let { DATE_FMT.format(Date(it)) } ?: "",
                i.notes ?: "",
            ))
        }
        return XlsxWriter.Sheet("Master list", rows)
    }

    private fun buildUnchartedSheet(items: List<UnchartedItem>): XlsxWriter.Sheet {
        val rows = mutableListOf<List<String>>()
        rows.add(listOf(
            "Scan ID", "Timestamp", "Drawing no. (scanned)", "Spool",
            "Disposition", "Notes",
        ))
        if (items.isEmpty()) {
            rows.add(listOf("(no uncharted scans)"))
        } else {
            for (u in items) {
                rows.add(listOf(
                    u.scanId,
                    DATE_FMT.format(Date(u.timestamp)),
                    u.drawing,
                    u.spool,
                    u.disposition,
                    u.notes ?: "",
                ))
            }
        }
        return XlsxWriter.Sheet("Uncharted Items", rows)
    }

    /**
     * Save the xlsx to Downloads. Returns a content:// Uri usable by
     * Intent.ACTION_SEND. On Android 10+, uses MediaStore so no
     * WRITE_EXTERNAL_STORAGE is needed.
     */
    private fun saveToDownloads(
        ctx: Context,
        fileName: String,
        sheets: List<XlsxWriter.Sheet>,
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                XlsxWriter().write(out, sheets)
            }
            uri
        } else {
            // Pre-Android 10: save to app-private files dir, share via FileProvider.
            val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                XlsxWriter().write(out, sheets)
            }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }
    }

    /** Build an Intent for sharing the saved file (email, Drive, Save As). */
    fun shareIntent(uri: Uri, fileName: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Spool Check export: $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
