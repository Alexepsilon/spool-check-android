package com.spoolcheck.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spoolcheck.app.R
import com.spoolcheck.app.core.ExportService
import com.spoolcheck.app.data.AppDatabase
import com.spoolcheck.app.data.MasterItem
import com.spoolcheck.app.ui.StatusDamaged
import com.spoolcheck.app.ui.StatusExpected
import com.spoolcheck.app.ui.StatusMissing
import com.spoolcheck.app.ui.StatusVerified
import com.spoolcheck.app.ui.launchSafe
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusBoardScreen(nav: NavController, deliveryId: String) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val scope = rememberCoroutineScope()

    val items by db.items().observeForDelivery(deliveryId).collectAsState(initial = emptyList())
    val unchartedCount by db.uncharted().observeCountForDelivery(deliveryId).collectAsState(initial = 0)
    var deliveryName by remember { mutableStateOf("") }

    LaunchedEffect(deliveryId) {
        db.deliveries().get(deliveryId)?.let { deliveryName = it.name }
    }

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(BoardFilter.All) }
    var actionItem by remember { mutableStateOf<MasterItem?>(null) }

    val visible = items.filter {
        val matchesFilter = when (filter) {
            BoardFilter.All -> true
            BoardFilter.Verified -> it.status == "verified"
            // "Missing" is anything that hasn't been scanned in:
            // expected (not yet scanned), missing (closeout not seen),
            // damaged, wrong_item — all forms of "didn't come / not OK".
            BoardFilter.Missing -> it.status != "verified"
        }
        if (!matchesFilter) return@filter false
        if (search.isEmpty()) return@filter true
        val q = search.lowercase()
        it.drawing.lowercase().contains(q) || it.spool.lowercase().contains(q)
    }

    val total = items.size
    val verified = items.count { it.status == "verified" }
    val missing = items.count { it.status == "missing" }
    val pct = if (total == 0) 0 else (verified * 100 / total)

    var menuOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deliveryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more), tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (unchartedCount > 0)
                                        stringResource(R.string.board_uncharted_fmt, unchartedCount)
                                    else stringResource(R.string.board_uncharted)
                                )
                            },
                            onClick = {
                                menuOpen = false
                                nav.navigate("uncharted/$deliveryId")
                            },
                        )
                        val sendReportLabel = stringResource(R.string.board_send_report)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_export)) },
                            onClick = {
                                menuOpen = false
                                exporting = true
                                scope.launch {
                                    try {
                                        val uri = ExportService.export(ctx, deliveryId)
                                        if (uri != null) {
                                            val name = "${deliveryName}.xlsx"
                                            ctx.startActivity(
                                                Intent.createChooser(
                                                    ExportService.shareIntent(uri, name),
                                                    sendReportLabel,
                                                )
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        android.widget.Toast.makeText(
                                            ctx,
                                            ctx.getString(R.string.board_export_failed_fmt, e.message ?: ""),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    } finally {
                                        exporting = false
                                    }
                                }
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    FilledTonalButton(
                        onClick = { nav.navigate("scan/$deliveryId") },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(stringResource(R.string.board_open_scanner))
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Progress strip
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        verified.toString(),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        " / $total",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$pct%",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        stringResource(R.string.board_remaining_fmt, total - verified - missing),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                    if (missing > 0) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.board_missing_fmt, missing),
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }

            // Filter bar
            Surface(tonalElevation = 1.dp) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.board_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BoardFilter.entries.forEach { f ->
                            val count = when (f) {
                                BoardFilter.All -> total
                                BoardFilter.Verified -> verified
                                // Anything not verified is "missing" for filter purposes.
                                BoardFilter.Missing -> total - verified
                            }
                            val label = stringResource(f.labelRes)
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(stringResource(R.string.board_filter_with_count_fmt, label, count)) },
                            )
                        }
                    }
                }
            }

            // Scrollable table
            val hScroll = rememberScrollState()
            Column(modifier = Modifier
                .weight(1f)
                .horizontalScroll(hScroll)) {
                ColumnHeader()
                LazyColumn {
                    items(visible, key = { it.id }) { item ->
                        ItemRow(item, onClick = { actionItem = item })
                        HorizontalDivider()
                    }
                    if (visible.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(R.string.board_no_matches), color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }

    actionItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionItem = null }) {
            Column(modifier = Modifier.padding(16.dp).padding(bottom = 24.dp)) {
                Text(
                    item.drawing,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                if (item.spool.isNotEmpty()) {
                    Text(
                        stringResource(R.string.uncharted_spool_fmt, item.spool),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Two action buttons + delete: Verified / Reset.
                // Missing and Damaged removed per user request — Reset is
                // enough to walk back a wrong tick; Damaged was rarely used
                // on site.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBtn(stringResource(R.string.board_mark_verified), StatusVerified) {
                        scope.launchSafe(ctx, "Board.Verified") {
                            db.items().update(
                                item.copy(status = "verified", verifiedAt = System.currentTimeMillis())
                            )
                            actionItem = null
                        }
                    }
                    StatusBtn(stringResource(R.string.board_mark_reset), StatusExpected) {
                        scope.launchSafe(ctx, "Board.Reset") {
                            db.items().update(item.copy(status = "expected", verifiedAt = null))
                            actionItem = null
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Delete row from the master list. Use sparingly — typically only
                // for rows imported by mistake. The scan log still keeps the audit trail.
                StatusBtn(stringResource(R.string.board_delete_label), Color(0xFFB91C1C), fillWidth = true) {
                    scope.launchSafe(ctx, "Board.Delete") {
                        db.items().deleteById(item.id)
                        actionItem = null
                    }
                }
            }
        }
    }
}

private enum class BoardFilter(val labelRes: Int) {
    All(R.string.board_filter_all),
    Verified(R.string.board_filter_verified),
    Missing(R.string.board_filter_missing),
}

@Composable
private fun ColumnHeader() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
    ) {
        // Trimmed column set — Paint, RAL, Iso no., Remark removed per
        // user request. Data still lives in the DB and goes to the
        // Excel export, but the on-screen board stays focused on the
        // QC-relevant columns.
        HeaderCell(stringResource(R.string.col_drawing), 300.dp)
        HeaderCell(stringResource(R.string.col_spool), 60.dp)
        HeaderCell(stringResource(R.string.col_diameter), 80.dp)
        HeaderCell(stringResource(R.string.col_chclean), 90.dp)
        HeaderCell(stringResource(R.string.col_project), 90.dp)
        HeaderCell(stringResource(R.string.col_verified_at), 130.dp)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        modifier = Modifier.width(width).padding(horizontal = 12.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ItemRow(item: MasterItem, onClick: () -> Unit) {
    // Yellow highlight on verified rows mirrors the marker-pen convention
    // on the printed transport list (already-checked items go yellow).
    val rowBg = if (item.status == "verified") Color(0xFFFFF59D) else Color.Transparent
    Row(
        modifier = Modifier
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drawing column with status dot.
        // Width chosen to fit a worst-case 25-character monospace drawing number
        // (e.g. "322-FLA-1001-SS-100-P-2") plus the leading status dot.
        Row(
            modifier = Modifier.width(300.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(10.dp).background(statusColor(item.status), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.drawing,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (item.status == "verified") TextDecoration.LineThrough else null,
                color = if (item.status == "verified") Color.Gray else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DataCell(item.spool, 60.dp, mono = true)
        // Effective diameter: stored value if the import captured it,
        // otherwise derived from the drawing string ("SS-N" / "CS-N").
        DataCell(
            com.spoolcheck.app.core.effectiveDiameter(item.diameter, item.drawing) ?: "—",
            80.dp,
        )
        DataCell(item.chClean ?: "—", 90.dp)
        DataCell(item.project ?: "—", 90.dp)
        DataCell(
            if (item.verifiedAt == null) "—"
            else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.verifiedAt)),
            130.dp,
        )
    }
}

@Composable
private fun DataCell(text: String, width: androidx.compose.ui.unit.Dp, mono: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width).padding(horizontal = 12.dp),
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatusBtn(text: String, color: Color, fillWidth: Boolean = false, onClick: () -> Unit) {
    val mod = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    FilledTonalButton(
        onClick = onClick,
        modifier = mod.height(48.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color,
            contentColor = Color.White,
        ),
    ) {
        Text(text)
    }
}

private fun statusColor(status: String): Color = when (status) {
    "verified" -> StatusVerified
    "missing" -> StatusMissing
    "damaged" -> StatusDamaged
    else -> StatusExpected
}
