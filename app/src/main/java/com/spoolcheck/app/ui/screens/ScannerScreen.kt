package com.spoolcheck.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.spoolcheck.app.R
import com.spoolcheck.app.core.CodeMatcher
import com.spoolcheck.app.core.FRAME_CONSENSUS_COUNT
import com.spoolcheck.app.core.FRAME_CONSENSUS_WINDOW
import com.spoolcheck.app.core.OCR_FRAME_INTERVAL_MS
import com.spoolcheck.app.core.SCAN_DEBOUNCE_MS
import com.spoolcheck.app.core.composeKey
import com.spoolcheck.app.data.AppDatabase
import com.spoolcheck.app.data.MasterItem
import com.spoolcheck.app.data.Scan
import com.spoolcheck.app.data.UnchartedItem
import com.spoolcheck.app.ui.StatusVerified
import com.spoolcheck.app.ui.launchSafe
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(nav: NavController, deliveryId: String) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val scope = rememberCoroutineScope()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(Unit) {
        if (!permissionGranted) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    var items by remember { mutableStateOf<List<MasterItem>>(emptyList()) }
    var matcher by remember { mutableStateOf<CodeMatcher?>(null) }

    LaunchedEffect(deliveryId) {
        items = db.items().listForDelivery(deliveryId)
        matcher = CodeMatcher(items.map {
            CodeMatcher.MasterEntry(
                drawing = it.drawing,
                spool = it.spool,
                itemId = it.id,
                isoNumber = it.isoNumber.orEmpty(),
            )
        })
        com.spoolcheck.app.core.DebugLog.log("SCAN",
            "scanner ready, master=${items.size} items: " +
                items.take(20).joinToString { "${it.drawing}|${it.spool.ifEmpty { "?" }}" } +
                if (items.size > 20) "  …" else "")
    }

    var pending by remember { mutableStateOf<PendingFlow?>(null) }
    val feed = remember { mutableStateListOf<FeedEntry>() }
    var preview by remember { mutableStateOf(CodeMatcher.LivePreview()) }
    var matchKey by remember { mutableStateOf("") }

    // Verification lock — after a successful match commit we pause the
    // camera and clear sticky/buffer state for ~1.5s so old frame reads
    // can't immediately fire a stale "Not on list" dialog while the user
    // is still moving the phone toward the next tag.
    var lockedUntil by remember { mutableStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(lockedUntil) {
        if (lockedUntil > 0L) {
            // `locked` is also set synchronously inside armLock() — this
            // effect only handles the unlock after the delay. Setting it
            // here too is harmless and keeps state coherent if the
            // effect runs first for some reason.
            locked = true
            val remaining = lockedUntil - System.currentTimeMillis()
            if (remaining > 0) kotlinx.coroutines.delay(remaining)
            locked = false
        }
    }

    // Sticky-field state per-field. Each field remembers its last
    // non-empty value with a timestamp; if it goes ~4 seconds without
    // being seen again we treat it as stale and clear it. This lets the
    // user "build up" a label across frames where a single frame rarely
    // catches every field cleanly.
    val sticky = remember { mutableStateMapOf<String, Pair<String, Long>>() }
    // Debounce map for accumulated-match commits (separate from the
    // per-frame consensus debounce inside CameraPreviewWithOcr).
    val accumDebounce = remember { mutableMapOf<String, Long>() }

    // After a commit, dismiss, or dialog open we clear the sticky state
    // so the next tag starts from scratch.
    fun resetSticky() {
        sticky.clear()
        preview = CodeMatcher.LivePreview()
        matchKey = ""
    }

    // Arm the verification lock — pauses the camera + clears state so a
    // stale frame can't immediately fire a "Not on list" or duplicate
    // "Match found" right after the user confirmed. The visual ✓
    // overlay makes the gap obvious. Critically, we set `locked = true`
    // synchronously here (not via the LaunchedEffect) so the very next
    // OCR frame after a Confirm-click already sees paused=true and
    // doesn't slip a duplicate match in before the DB update completes.
    fun armLock(durationMs: Long = 1500L) {
        resetSticky()
        accumDebounce.clear()
        locked = true
        lockedUntil = System.currentTimeMillis() + durationMs
    }

    val verified = items.count { it.status == "verified" }
    val total = items.size

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            if (permissionGranted && matcher != null) {
                CameraPreviewWithOcr(
                    matcher = matcher!!,
                    paused = pending != null || locked,
                    onPreview = { p ->
                        val now = System.currentTimeMillis()
                        val staleMs = 4_000L
                        // Merge each non-empty field into the sticky map.
                        if (p.drawing.isNotEmpty()) sticky["drawing"] = p.drawing to now
                        if (p.spool.isNotEmpty()) sticky["spool"] = p.spool to now
                        if (p.paint.isNotEmpty()) sticky["paint"] = p.paint to now
                        if (p.ral.isNotEmpty()) sticky["ral"] = p.ral to now
                        if (p.scope.isNotEmpty()) sticky["scope"] = p.scope to now
                        // Drop fields that haven't been re-seen for staleMs ms.
                        // Snapshot keys first to avoid any mutate-during-iterate
                        // surprise on the SnapshotStateMap.
                        val staleKeys = sticky.entries
                            .filter { (_, v) -> now - v.second > staleMs }
                            .map { it.key }
                        staleKeys.forEach { sticky.remove(it) }
                        // Build the displayed preview from sticky state.
                        preview = CodeMatcher.LivePreview(
                            drawing = sticky["drawing"]?.first.orEmpty(),
                            spool = sticky["spool"]?.first.orEmpty(),
                            paint = sticky["paint"]?.first.orEmpty(),
                            ral = sticky["ral"]?.first.orEmpty(),
                            scope = sticky["scope"]?.first.orEmpty(),
                        )
                        matchKey = if (preview.drawing.isNotEmpty())
                            composeKey(preview.drawing, preview.spool) else ""

                        // Accumulated match: if we now have both drawing AND
                        // spool sticky (possibly from different frames), try
                        // to fire a match. Same debounce as live consensus
                        // so we don't spam.
                        if (pending == null
                            && preview.drawing.isNotEmpty()
                            && preview.spool.isNotEmpty()
                        ) {
                            val key = composeKey(preview.drawing, preview.spool)
                            val last = accumDebounce[key] ?: 0L
                            if (now - last >= SCAN_DEBOUNCE_MS) {
                                matcher?.matchPair(preview.drawing, preview.spool)?.let { result ->
                                    accumDebounce[key] = now
                                    val snapshot = preview
                                    when (result.confidence) {
                                        CodeMatcher.Confidence.EXACT,
                                        CodeMatcher.Confidence.FUZZY -> {
                                            pending = PendingFlow.MatchFound(
                                                result,
                                                items.find { it.id == result.itemId },
                                                snapshot,
                                            )
                                            resetSticky()
                                        }
                                        CodeMatcher.Confidence.PARTIAL -> {
                                            pending = PendingFlow.Partial(result, snapshot)
                                            resetSticky()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onMatch = { result ->
                        // Avoid re-firing for the same key within debounce window.
                        val key = composeKey(result.drawing, result.spool)
                        val now = System.currentTimeMillis()
                        val last = feed.firstOrNull { it.key == key }?.time ?: 0L
                        if (now - last < SCAN_DEBOUNCE_MS) return@CameraPreviewWithOcr

                        // Already-verified guard. Two cases distinguished by
                        // whether the OCR-read spool matches the master row's
                        // stored spool:
                        //   - Same letter (or empty result.spool) → same
                        //     physical tag re-read → silent skip.
                        //   - Different letter → this is a *sibling* physical
                        //     tag for a drawing whose master only had one row
                        //     (e.g. wildcard match consumed it). Route to
                        //     off-list so the user can park this tag in
                        //     Uncharted; otherwise siblings vanish silently.
                        val matchedItem = items.find { it.id == result.itemId }
                        if (matchedItem?.status == "verified") {
                            val sameLetter = result.spool.isEmpty() ||
                                result.spool == matchedItem.spool
                            if (sameLetter) {
                                com.spoolcheck.app.core.DebugLog.log("SCAN",
                                    "skip already-verified ${result.drawing}|${result.spool}")
                                return@CameraPreviewWithOcr
                            }
                            com.spoolcheck.app.core.DebugLog.log("SCAN",
                                "verified row has different spool — off-list " +
                                    "(master='${matchedItem.spool.ifEmpty { "<empty>" }}', scanned='${result.spool}')")
                            pending = PendingFlow.NotFound(result.drawing, result.spool, null, preview)
                            return@CameraPreviewWithOcr
                        }

                        val snapshot = preview
                        when (result.confidence) {
                            CodeMatcher.Confidence.EXACT -> {
                                pending = PendingFlow.MatchFound(result, matchedItem, snapshot)
                            }
                            CodeMatcher.Confidence.FUZZY -> {
                                pending = PendingFlow.Fuzzy(result, matchedItem, snapshot)
                            }
                            CodeMatcher.Confidence.PARTIAL -> {
                                pending = PendingFlow.Partial(result, snapshot)
                            }
                        }
                    },
                    onOffList = { drawing, spool, rawText ->
                        val key = "offlist:$drawing|$spool"
                        val now = System.currentTimeMillis()
                        val last = feed.firstOrNull { it.key == key }?.time ?: 0L
                        if (now - last < SCAN_DEBOUNCE_MS) return@CameraPreviewWithOcr
                        com.spoolcheck.app.core.DebugLog.log("SCAN",
                            "off-list dialog → drawing=$drawing spool='${spool.ifEmpty { "?" }}'  " +
                                "(master has " +
                                "${items.count { it.drawing == drawing }} row(s) for this drawing: " +
                                items.filter { it.drawing == drawing }
                                    .joinToString { "spool=${it.spool.ifEmpty { "<empty>" }}" } +
                                ")")
                        pending = PendingFlow.NotFound(drawing, spool, rawText, preview)
                    },
                )
            }

            // Top bar overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "$verified / $total",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            // Live OCR preview — credit-card-scanner style. Updates every frame.
            // Sits below the top bar and above the reticle.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth(),
            ) {
                LivePreviewCard(
                    preview = preview,
                    matchKey = matchKey,
                    items = items,
                )
            }

            // Reticle — turns green while the verification lock is active.
            val reticleColor = if (locked) StatusVerified else Color.White.copy(alpha = 0.7f)
            Box(
                modifier = Modifier.align(Alignment.Center).size(width = 320.dp, height = 200.dp)
                    .border(if (locked) 4.dp else 2.dp, reticleColor, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (locked) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "✓",
                            color = StatusVerified,
                            fontSize = 96.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.scan_locked),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Feed
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.scan_aim),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (feed.isEmpty()) {
                    Text(
                        stringResource(R.string.scan_auto_ticks),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                } else {
                    feed.take(5).forEach { entry ->
                        FeedRow(entry)
                    }
                }
            }
        }
    }

    pending?.let { flow ->
        PendingDialog(
            flow = flow,
            onDismiss = { pending = null },
            onConfirm = { result ->
                pending = null
                // Arm the lock synchronously so in-flight ML Kit frames
                // can't open another dialog while DB writes are running.
                armLock()
                scope.launchSafe(ctx, "Scanner.onConfirm") {
                    val scan = Scan(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        deliveryId = deliveryId,
                        drawing = result.drawing,
                        spool = result.spool,
                        matchedItemId = result.itemId,
                        confidence = result.confidence.name.lowercase(),
                        confirmed = true,
                    )
                    db.scans().insert(scan)
                    // Two-route lookup: prefer (deliveryId, drawing, spool)
                    // for the typical exact-match path, but fall back to
                    // result.itemId so the wildcard / iso-redirect paths
                    // (where result.spool may not equal master's stored
                    // spool) still update the right row. Without this
                    // fallback, the wildcard match silently fails to mark
                    // the row verified and a re-scan reopens the dialog.
                    val mi = db.items().findByKey(deliveryId, result.drawing, result.spool)
                        ?: items.firstOrNull { it.id == result.itemId }
                    if (mi != null) {
                        db.items().update(mi.copy(
                            status = "verified",
                            verifiedAt = scan.timestamp,
                        ))
                        items = db.items().listForDelivery(deliveryId)
                    }
                    vibrate(ctx, 80)
                    feed.add(0, FeedEntry(
                        key = composeKey(result.drawing, result.spool),
                        time = scan.timestamp,
                        status = "verified",
                    ))
                }
            },
            onPickSpool = { drawing, spool, fallbackItemId ->
                pending = null
                // Arm the lock synchronously so any in-flight ML Kit frames
                // can't fire a duplicate dialog between this click and the
                // DB write completing (fixes the stale-items race).
                armLock()
                scope.launchSafe(ctx, "Scanner.onPickSpool") {
                    // Two-route lookup mirroring onConfirm: prefer the
                    // (drawing, spool) row; fall back to the matcher's
                    // result.itemId so a picker tap that doesn't map to a
                    // real master row (custom-typed spool, or master row
                    // with empty spool) still verifies *something* rather
                    // than silently doing nothing.
                    val mi = db.items().findByKey(deliveryId, drawing, spool)
                        ?: fallbackItemId?.let { id -> items.firstOrNull { it.id == id } }
                    if (mi == null) {
                        com.spoolcheck.app.core.DebugLog.log("SCAN",
                            "onPickSpool: no master row for $drawing|$spool — routing to off-list")
                        pending = PendingFlow.NotFound(drawing, spool, null, preview)
                        return@launchSafe
                    }
                    val scan = Scan(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        deliveryId = deliveryId,
                        drawing = drawing,
                        spool = spool,
                        matchedItemId = mi.id,
                        confidence = "partial",
                        confirmed = true,
                    )
                    db.scans().insert(scan)
                    db.items().update(mi.copy(
                        status = "verified",
                        verifiedAt = scan.timestamp,
                    ))
                    items = db.items().listForDelivery(deliveryId)
                    vibrate(ctx, 80)
                    feed.add(0, FeedEntry(
                        key = composeKey(drawing, spool),
                        time = scan.timestamp,
                        status = "verified",
                    ))
                }
            },
            onAddUncharted = { drawing, spool, rawText, sheet ->
                pending = null
                armLock()
                scope.launch {
                    try {
                        val now = System.currentTimeMillis()
                        val cleanDrawing = if (drawing.isBlank()) "(unreadable)" else drawing.trim()
                        val cleanSpool = spool.trim().uppercase()
                        val cleanSheet = sheet?.trim()?.takeIf { it.isNotEmpty() }
                        val scan = Scan(
                            id = UUID.randomUUID().toString(),
                            timestamp = now,
                            deliveryId = deliveryId,
                            drawing = cleanDrawing,
                            spool = cleanSpool,
                            rawText = rawText,
                            confidence = "none",
                            confirmed = false,
                        )
                        db.scans().insert(scan)
                        db.uncharted().insert(UnchartedItem(
                            id = UUID.randomUUID().toString(),
                            scanId = scan.id,
                            deliveryId = deliveryId,
                            timestamp = now,
                            drawing = cleanDrawing,
                            spool = cleanSpool,
                            sheet = cleanSheet,
                            notes = if (drawing.isBlank() && !rawText.isNullOrBlank())
                                "OCR: ${rawText.take(200)}" else null,
                        ))
                        vibrate(ctx, 200)
                        feed.add(0, FeedEntry(
                            key = composeKey(cleanDrawing, cleanSpool),
                            time = now,
                            status = "uncharted",
                        ))
                    } catch (e: Throwable) {
                        android.util.Log.e("Scanner", "Add to Uncharted failed", e)
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.scan_add_uncharted_failed_fmt, e.message ?: ""),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }
}

private data class FeedEntry(val key: String, val time: Long, val status: String)

/**
 * Tag-side context block for confirm dialogs. Shows what OCR captured
 * from the rest of the tag — sheet, scope, code — so the user can
 * cross-check the dialog's identity against the physical tag.
 */
@Composable
private fun TagContext(preview: CodeMatcher.LivePreview) {
    val sheetLabel = stringResource(R.string.scan_sheet)
    val scopeLabel = stringResource(R.string.scan_scope)
    val codeLabel = stringResource(R.string.scan_code)
    val rows = listOfNotNull(
        preview.sheet.takeIf { it.isNotEmpty() }?.let { sheetLabel to it },
        preview.scope.takeIf { it.isNotEmpty() }?.let { scopeLabel to it },
        preview.code.takeIf { it.isNotEmpty() }?.let { codeLabel to it },
    )
    if (rows.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Surface(
        color = Color(0xFFF3F4F6),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                stringResource(R.string.scan_tag),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray,
            )
            Spacer(Modifier.height(2.dp))
            rows.forEach { (label, value) ->
                Row {
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.width(50.dp),
                    )
                    Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/**
 * Real-time preview of what OCR is reading, rendered above the reticle.
 * Feels like a credit-card scanner: digits/letters fill in as you point
 * the camera. Color-coded:
 *   - green border = drawing recognised AND matches an entry on the list
 *   - amber border = drawing recognised but NOT on the list (off-list)
 *   - grey border  = nothing recognised yet
 */
@Composable
private fun LivePreviewCard(
    preview: CodeMatcher.LivePreview,
    matchKey: String,
    items: List<MasterItem>,
) {
    val matchedItem = items.firstOrNull {
        it.drawing == preview.drawing && it.spool == preview.spool
    }
    val onListExact = preview.drawing.isNotEmpty() && matchedItem != null
    val alreadyVerified = matchedItem?.status == "verified"
    val onListByDrawing = preview.drawing.isNotEmpty() &&
        items.any { it.drawing == preview.drawing }
    val borderColor = when {
        alreadyVerified -> Color(0xFF22C55E) // brighter green for already-done
        onListExact -> StatusVerified
        onListByDrawing -> Color(0xFFF59E0B)  // amber: drawing on list, spool unsure
        preview.drawing.isNotEmpty() -> Color(0xFFDC2626) // red: not on list
        else -> Color.White.copy(alpha = 0.4f)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.scan_reading),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (preview.drawing.isEmpty() && preview.spool.isEmpty()) {
                    Text(
                        stringResource(R.string.scan_aiming),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                    )
                } else {
                    Text(
                        when {
                            alreadyVerified -> stringResource(R.string.scan_already_verified)
                            onListExact -> stringResource(R.string.scan_on_list)
                            onListByDrawing -> stringResource(R.string.scan_drawing_only)
                            preview.drawing.isNotEmpty() -> stringResource(R.string.scan_off_list)
                            else -> ""
                        },
                        color = borderColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview.drawing.ifEmpty { "—" },
                    color = if (preview.drawing.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Spool slot is always visible — empty shows a "—" placeholder
                // so the user can tell at a glance whether OCR has captured the
                // spool letter yet, the same way the drawing slot behaves.
                Surface(
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "spool ",
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            preview.spool.ifEmpty { "—" },
                            color = if (preview.spool.isEmpty())
                                Color.White.copy(alpha = 0.4f) else Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            // Secondary fields — only shown when present. Paint, RAL,
            // Scope are off-screen (always available on the tag itself
            // and not used for matching). Sheet stays because it
            // disambiguates multi-page tags.
            val sheetFmt = stringResource(R.string.scan_sheet_fmt)
            val extras = listOfNotNull(
                preview.sheet.takeIf { it.isNotEmpty() }?.let { String.format(sheetFmt, it) },
            )
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    extras.joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun FeedRow(entry: FeedEntry) {
    val parts = entry.key.split('|')
    val drawing = parts.getOrNull(0).orEmpty()
    val spool = parts.getOrNull(1).orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (entry.status) {
                "verified" -> "✓"
                "uncharted" -> "⚠"
                else -> "✗"
            },
            color = Color.White,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            drawing,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (spool.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(spool, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

private sealed class PendingFlow {
    abstract val preview: CodeMatcher.LivePreview
    data class MatchFound(
        val result: CodeMatcher.Result,
        val item: MasterItem?,
        override val preview: CodeMatcher.LivePreview = CodeMatcher.LivePreview(),
    ) : PendingFlow()
    data class Fuzzy(
        val result: CodeMatcher.Result,
        val item: MasterItem?,
        override val preview: CodeMatcher.LivePreview = CodeMatcher.LivePreview(),
    ) : PendingFlow()
    data class Partial(
        val result: CodeMatcher.Result,
        override val preview: CodeMatcher.LivePreview = CodeMatcher.LivePreview(),
    ) : PendingFlow()
    data class NotFound(
        val drawing: String,
        val spool: String,
        val rawText: String?,
        override val preview: CodeMatcher.LivePreview = CodeMatcher.LivePreview(),
    ) : PendingFlow()
}

/**
 * Title row with a built-in X close icon. The X always calls onDismiss
 * — useful when a dialog pops up at an inconvenient moment and the
 * user wants out without picking any of the explicit action buttons.
 */
@Composable
private fun DialogTitleRow(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
            )
        }
    }
}

@Composable
private fun PendingDialog(
    flow: PendingFlow,
    onDismiss: () -> Unit,
    onConfirm: (CodeMatcher.Result) -> Unit,
    onPickSpool: (drawing: String, spool: String, fallbackItemId: String?) -> Unit,
    onAddUncharted: (drawing: String, spool: String, rawText: String?, sheet: String?) -> Unit,
) {
    when (flow) {
        is PendingFlow.MatchFound -> {
            // Always show the available spools as tappable chips.
            // OCR's read is pre-selected; user can tap a different
            // valid letter, OR type a custom one for the rare case
            // where the drawing OCR was right but the spool is
            // outside the master list (off-list spool variant).
            var chosenSpool by remember(flow) { mutableStateOf(flow.result.spool) }
            var custom by remember(flow) { mutableStateOf("") }
            val chips = flow.result.availableSpools.ifEmpty {
                if (flow.result.spool.isNotEmpty()) listOf(flow.result.spool) else emptyList()
            }
            AlertDialog(
                onDismissRequest = { /* locked — explicit buttons only */ },
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { DialogTitleRow(stringResource(R.string.scan_match_found), onDismiss) },
                text = {
                    Column {
                        Text(
                            flow.result.drawing,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        // Big readout of currently-selected spool, so it's
                        // unmissable before the user taps Confirm.
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.scan_spool_label), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    chosenSpool.ifEmpty { "—" },
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(stringResource(R.string.scan_tap_to_change), fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                        if (chips.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                chips.forEach { s ->
                                    val selected = s == chosenSpool
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            chosenSpool = s
                                            custom = ""
                                        },
                                        label = {
                                            Text(
                                                s.ifEmpty { "—" },
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        // Manual override — useful if OCR is wrong AND the
                        // master list happens to be missing the right letter.
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = custom,
                            onValueChange = {
                                val v = it.uppercase().take(2)
                                custom = v
                                if (v.isNotEmpty()) chosenSpool = v
                            },
                            label = { Text(stringResource(R.string.scan_custom_spool), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.width(180.dp),
                        )
                        // Tag-side context from the live OCR — sheet, scope, code.
                        // Helps user verify they're looking at the right tag before
                        // confirming.
                        TagContext(flow.preview)
                        flow.item?.let { mi ->
                            Spacer(Modifier.height(8.dp))
                            val dia = com.spoolcheck.app.core.effectiveDiameter(mi.diameter, mi.drawing)
                            if (!dia.isNullOrEmpty()) Text(
                                stringResource(R.string.scan_diameter_fmt, dia),
                                fontSize = 13.sp,
                            )
                            // List's ch.clean column carries the same number
                            // that appears on the tag as "Code:" or "Scope nr".
                            if (!mi.chClean.isNullOrEmpty()) Text(
                                stringResource(R.string.scan_chclean_fmt, mi.chClean),
                                fontSize = 13.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // If user picked a different spool, route through
                            // the spool-pick path so the right master row gets
                            // verified.
                            if (chosenSpool != flow.result.spool) {
                                onPickSpool(flow.result.drawing, chosenSpool, flow.result.itemId)
                            } else {
                                onConfirm(flow.result)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusVerified),
                    ) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_wrong_match)) }
                },
            )
        }
        is PendingFlow.Fuzzy -> AlertDialog(
            onDismissRequest = { /* locked */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = { DialogTitleRow(stringResource(R.string.scan_confirm_scan), onDismiss) },
            text = {
                Column {
                    Text(stringResource(R.string.scan_ocr_uncertain), fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${flow.result.drawing}${if (flow.result.spool.isNotEmpty()) "  ·  ${flow.result.spool}" else ""}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.scan_read_as_fmt, flow.result.observed),
                        fontSize = 11.sp,
                        color = Color.Gray,
                    )
                }
            },
            confirmButton = { Button(onClick = { onConfirm(flow.result) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.no)) } },
        )
        is PendingFlow.Partial -> AlertDialog(
            onDismissRequest = { /* locked */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = { DialogTitleRow(stringResource(R.string.scan_which_spool), onDismiss) },
            text = {
                Column {
                    Text(flow.result.drawing, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.scan_cant_read_spool), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        flow.result.availableSpools.forEach { s ->
                            OutlinedButton(onClick = { onPickSpool(flow.result.drawing, s, flow.result.itemId) }) {
                                Text(s, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        is PendingFlow.NotFound -> {
            var drawing by remember(flow) { mutableStateOf(flow.drawing) }
            var spool by remember(flow) { mutableStateOf(flow.spool) }
            AlertDialog(
                onDismissRequest = { /* locked */ },
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { DialogTitleRow(stringResource(R.string.scan_not_on_list), onDismiss) },
                text = {
                    Column {
                        Text(stringResource(R.string.scan_edit_then_add), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = drawing,
                            onValueChange = { drawing = it },
                            label = { Text(stringResource(R.string.scan_field_drawing)) },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = spool,
                            onValueChange = { spool = it.uppercase() },
                            label = { Text(stringResource(R.string.scan_field_spool)) },
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        // Pass through the Sheet field captured by live OCR
                        // (may be null — not every supplier prints one).
                        onAddUncharted(drawing, spool, flow.rawText, flow.preview.sheet.takeIf { it.isNotEmpty() })
                    }) {
                        Text(stringResource(R.string.scan_add_uncharted))
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_try_again)) } },
            )
        }
    }
}

@Composable
private fun CameraPreviewWithOcr(
    matcher: CodeMatcher,
    paused: Boolean,
    onPreview: (CodeMatcher.LivePreview) -> Unit,
    onMatch: (CodeMatcher.Result) -> Unit,
    onOffList: (drawing: String, spool: String, rawText: String) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Frame consensus state
    val consensusBuf = remember { mutableListOf<Set<String>>() }
    val candidates = remember { mutableMapOf<String, CodeMatcher.Result>() }
    val recentCommits = remember { mutableMapOf<String, Long>() }
    val offListBuf = remember { mutableListOf<String>() }
    val offListText = remember { mutableMapOf<String, String>() }
    var lastFrameAt by remember { mutableStateOf(0L) }

    // `paused` is a function parameter, captured by value when the
    // AndroidView factory below builds the analyzer lambda — and factory
    // only runs once. To make the analyzer actually respect parameter
    // changes on later recompositions, we mirror the flag into an
    // AtomicBoolean and update it on every recomposition. The analyzer
    // (running on its background executor) reads the atomic.
    val pausedRef = remember { java.util.concurrent.atomic.AtomicBoolean(paused) }
    SideEffect { pausedRef.set(paused) }

    // When the scanner pauses (dialog open or verification lock active),
    // wipe the consensus + off-list buffers AND the recentCommits
    // debounce map. Otherwise (a) frames captured right before the
    // pause can fire a stale commit/off-list dialog the moment the
    // camera resumes, and (b) recentCommits would grow unbounded over
    // a long shift.
    LaunchedEffect(paused) {
        if (paused) {
            consensusBuf.clear()
            candidates.clear()
            offListBuf.clear()
            offListText.clear()
            // Prune old commit timestamps (>60s); keep recent so the
            // 4s same-key debounce still works after resume.
            val cutoff = System.currentTimeMillis() - 60_000L
            recentCommits.entries.removeAll { it.value < cutoff }
        }
    }

    DisposableEffect(Unit) {
        // Tearing down: pause analysis, drop frame buffers, and stop
        // the camera. Without unbindAll() a fresh scan session that
        // rebinds CameraX could leave two preview pipelines alive.
        onDispose {
            try {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll()
            } catch (_: Throwable) { /* provider may already be torn down */ }
            executor.shutdownNow()
            recognizer.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (pausedRef.get() || now - lastFrameAt < OCR_FRAME_INTERVAL_MS) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    lastFrameAt = now
                    processFrame(
                        proxy = proxy,
                        recognizer = recognizer,
                        matcher = matcher,
                        consensusBuf = consensusBuf,
                        candidates = candidates,
                        recentCommits = recentCommits,
                        offListBuf = offListBuf,
                        offListText = offListText,
                        pausedRef = pausedRef,
                        onPreview = onPreview,
                        onMatch = onMatch,
                        onOffList = onOffList,
                    )
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processFrame(
    proxy: ImageProxy,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    matcher: CodeMatcher,
    consensusBuf: MutableList<Set<String>>,
    candidates: MutableMap<String, CodeMatcher.Result>,
    recentCommits: MutableMap<String, Long>,
    offListBuf: MutableList<String>,
    offListText: MutableMap<String, String>,
    pausedRef: java.util.concurrent.atomic.AtomicBoolean,
    onPreview: (CodeMatcher.LivePreview) -> Unit,
    onMatch: (CodeMatcher.Result) -> Unit,
    onOffList: (String, String, String) -> Unit,
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = try {
        InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    } catch (e: Throwable) {
        android.util.Log.e("Scanner", "InputImage build failed", e)
        proxy.close()
        return
    }
    recognizer.process(input)
        .addOnFailureListener { e ->
            android.util.Log.w("Scanner", "ML Kit recognize failed: ${e.message}")
        }
        .addOnSuccessListener { result ->
            // In-flight guard: a frame submitted to ML Kit before pause
            // was set will still complete here. Drop its result if we're
            // paused now — otherwise it would fire onMatch/onOffList
            // and open a duplicate dialog after Confirm.
            if (pausedRef.get()) return@addOnSuccessListener
            try {
            // Spatial reconstruction first: stitches blocks/lines that
            // ML Kit split apart back into logical rows so cell-layout
            // tags ("Spool:" in one cell, value letter in another)
            // reach the anchor matcher as a single line.
            val text = com.spoolcheck.app.core.reconstructSpatialText(result)
            // Live "what camera is reading" preview, every frame.
            onPreview(matcher.extractPreview(text))
            val matches = matcher.match(text)
            val keys = HashSet<String>()
            for (m in matches) {
                if (m.confidence == CodeMatcher.Confidence.PARTIAL) {
                    onMatch(m)
                    return@addOnSuccessListener
                }
                val k = composeKey(m.drawing, m.spool)
                keys.add(k)
                candidates[k] = m
            }
            consensusBuf.add(keys)
            while (consensusBuf.size > FRAME_CONSENSUS_WINDOW) consensusBuf.removeAt(0)

            val committable = computeCommittable(consensusBuf)
            for (k in committable) {
                val now = System.currentTimeMillis()
                val last = recentCommits[k] ?: 0L
                if (now - last >= SCAN_DEBOUNCE_MS) {
                    recentCommits[k] = now
                    candidates[k]?.let(onMatch)
                }
            }

            // Off-list detection
            if (committable.isEmpty()) {
                val pat = com.spoolcheck.app.core.DEFAULT_CODE_PATTERN
                // Include both `drawing` (canonical, may be iso-redirected
                // from a different observed string) and `observed` (the
                // raw regex hit) so an iso-redirect doesn't false-fire
                // off-list when the regex's first hit is the iso number
                // and the matcher returned its mapped drawing.
                val matched = matches.flatMap {
                    listOf(composeKey(it.drawing, it.spool), it.observed)
                }.toSet()
                val first = pat.find(text.uppercase())?.value
                val isOff = first != null && matched.none {
                    it == first || it.startsWith("$first|")
                }
                offListBuf.add(if (isOff) first!! else "")
                while (offListBuf.size > FRAME_CONSENSUS_WINDOW) offListBuf.removeAt(0)
                if (isOff) offListText[first!!] = text

                if (offListBuf.size >= com.spoolcheck.app.core.OFFLIST_CONSENSUS_COUNT) {
                    val recent = offListBuf.takeLast(com.spoolcheck.app.core.OFFLIST_CONSENSUS_COUNT)
                    val firstRecent = recent[0]
                    if (firstRecent.isNotEmpty() && recent.all { it == firstRecent }) {
                        val key = "offlist:$firstRecent"
                        val now = System.currentTimeMillis()
                        val last = recentCommits[key] ?: 0L
                        if (now - last >= SCAN_DEBOUNCE_MS) {
                            recentCommits[key] = now
                            val raw = offListText[firstRecent].orEmpty()
                            // Use anchor-based extraction (same as live preview)
                            // — picking just any lone letter from the OCR text
                            // grabs branding like "M" or "H" from "M H Service".
                            val spool = matcher.extractPreview(raw).spool
                            onOffList(firstRecent, spool, raw)
                        }
                    }
                }
            }
            } catch (e: Throwable) {
                android.util.Log.e("Scanner", "processFrame success body failed", e)
            }
        }
        .addOnCompleteListener { proxy.close() }
}

private fun computeCommittable(buf: List<Set<String>>): Set<String> {
    if (buf.size < FRAME_CONSENSUS_COUNT) return emptySet()
    val recent = buf.takeLast(FRAME_CONSENSUS_COUNT)
    if (recent[0].isEmpty()) return emptySet()
    var inter = recent[0].toMutableSet()
    for (i in 1 until recent.size) {
        inter.retainAll(recent[i])
        if (inter.isEmpty()) return emptySet()
    }
    return inter
}

private fun vibrate(ctx: android.content.Context, ms: Long) {
    // Respect the Settings → "Vibrate on match" toggle. Default is ON
    // (set in Prefs) but the user can silence the buzz when they're
    // doing rapid sweeps in a quiet area.
    if (!com.spoolcheck.app.core.Prefs.hapticOnMatch(ctx)) return
    // Wrapped defensively: VIBRATE permission is normal but absence has
    // crashed apps before; some devices (work profiles, GMSCore-stripped
    // ROMs) also throw from getSystemService. Haptics is non-essential.
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Throwable) {
        // Silently fall through — UX continues without the buzz.
    }
}
