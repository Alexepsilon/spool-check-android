package com.spoolcheck.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spoolcheck.app.R
import com.spoolcheck.app.core.PhotoListImporter
import com.spoolcheck.app.core.XlsxImporter
import com.spoolcheck.app.data.AppDatabase
import com.spoolcheck.app.data.Delivery
import com.spoolcheck.app.data.MasterItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(ctx) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun saveDelivery(
        sourceType: String,
        suggestedName: String,
        items: List<XlsxImporter.Imported>,
    ): String? {
        if (items.isEmpty()) {
            error = ctx.getString(R.string.import_no_codes)
            return null
        }
        val deliveryId = UUID.randomUUID().toString()
        db.deliveries().upsert(
            Delivery(
                id = deliveryId,
                name = suggestedName,
                importedAt = System.currentTimeMillis(),
                sourceType = sourceType,
                status = "open",
            )
        )
        db.items().insertAll(
            items.map {
                MasterItem(
                    id = UUID.randomUUID().toString(),
                    deliveryId = deliveryId,
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
            }
        )
        return deliveryId
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            try {
                val items = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri).use { input ->
                        if (input == null) emptyList() else XlsxImporter.parse(input)
                    }
                }
                val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".xlsx")
                    ?: ctx.getString(R.string.import_default_name)
                val deliveryId = saveDelivery("xlsx", name, items)
                if (deliveryId != null) {
                    nav.navigate("board/$deliveryId") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            } catch (e: Exception) {
                error = ctx.getString(R.string.import_failed_fmt, e.message ?: "")
            } finally {
                busy = false
            }
        }
    }

    val pickPhotos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            try {
                val combinedItems = mutableListOf<com.spoolcheck.app.core.XlsxImporter.Imported>()
                val seen = HashSet<String>()
                var suggestedName: String? = null
                val failures = mutableListOf<String>()
                // Each photo is independent — if one fails (corrupted, OCR
                // throws, etc.) the rest of the batch should still import.
                for ((idx, uri) in uris.withIndex()) {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            PhotoListImporter.importFromUri(ctx, uri)
                        }
                        if (suggestedName == null) suggestedName = result.suggestedName
                        for (it in result.items) {
                            val key = "${it.drawing}|${it.spool}"
                            if (seen.add(key)) combinedItems.add(it)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("Import", "Photo ${idx + 1} failed", e)
                        failures.add("page ${idx + 1}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                if (failures.isNotEmpty()) {
                    error = ctx.getString(R.string.import_pages_failed_fmt, failures.joinToString("; "))
                    if (combinedItems.isEmpty()) {
                        busy = false
                        return@launch
                    }
                }
                // Prefer the first photo's "Note: TRANSPORT DD-MM-YYYY" header
                // for the delivery name; fall back to a timestamp.
                val name = suggestedName ?: ctx.getString(
                    R.string.import_transport_prefix,
                    java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date()),
                )
                val deliveryId = saveDelivery("photo", name, combinedItems)
                if (deliveryId != null) {
                    nav.navigate("board/$deliveryId") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            } catch (e: Exception) {
                error = ctx.getString(R.string.import_photo_failed_fmt, e.message ?: "")
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.import_xlsx_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = {
                    pickFile.launch(arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    ))
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.import_pick_xlsx))
            }
            Spacer(Modifier.height(12.dp))
            // Photo-of-list import: pick one or many pages of a printed
            // transport list. ML Kit OCRs each, parser extracts
            // (drawing, spool) pairs, results are merged.
            FilledTonalButton(
                onClick = { pickPhotos.launch("image/*") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.import_pick_photos))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.import_photos_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
