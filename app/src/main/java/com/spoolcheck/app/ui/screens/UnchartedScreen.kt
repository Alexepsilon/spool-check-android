package com.spoolcheck.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spoolcheck.app.R
import com.spoolcheck.app.data.AppDatabase
import com.spoolcheck.app.data.UnchartedItem
import com.spoolcheck.app.ui.StatusDamaged
import com.spoolcheck.app.ui.StatusPending
import com.spoolcheck.app.ui.StatusVerified
import com.spoolcheck.app.ui.launchSafe
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnchartedScreen(nav: NavController, deliveryId: String) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val scope = rememberCoroutineScope()

    val items by db.uncharted().observeForDelivery(deliveryId)
        .collectAsState(initial = emptyList())

    var pickFor by remember { mutableStateOf<UnchartedItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.uncharted_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Light amber banner with explicit dark text — without an
            // explicit foreground, the inherited content color flipped
            // near-white on dark theme and the hint became unreadable.
            Surface(color = Color(0xFFFEF3C7)) {
                Text(
                    stringResource(R.string.uncharted_hint),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF78350F),
                )
            }
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.uncharted_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(items, key = { it.id }) { u ->
                        UnchartedRow(u, onClick = { pickFor = u })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pickFor?.let { item ->
        ModalBottomSheet(onDismissRequest = { pickFor = null }) {
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
                // Sheet line — populated only when the tag actually carried
                // a Sheet field at scan time. Always rendered so the user can
                // see at a glance whether a sheet was captured (some supplier
                // tags omit it entirely).
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.uncharted_sheet_label) + ": ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!item.sheet.isNullOrEmpty()) {
                        Text(
                            item.sheet,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            stringResource(R.string.uncharted_no_sheet),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.uncharted_set_disposition), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DispBtn(stringResource(R.string.uncharted_wrong_project), Color(0xFF7C3AED)) {
                        scope.launchSafe(ctx, "Uncharted.Disp") {
                            db.uncharted().update(item.copy(disposition = "wrong_project"))
                            pickFor = null
                        }
                    }
                    DispBtn(stringResource(R.string.uncharted_other_system), Color(0xFF3B82F6)) {
                        scope.launchSafe(ctx, "Uncharted.Disp") {
                            db.uncharted().update(item.copy(disposition = "other_system"))
                            pickFor = null
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DispBtn(stringResource(R.string.uncharted_investigate), StatusDamaged) {
                        scope.launchSafe(ctx, "Uncharted.Disp") {
                            db.uncharted().update(item.copy(disposition = "investigate"))
                            pickFor = null
                        }
                    }
                    DispBtn(stringResource(R.string.uncharted_advance), StatusPending) {
                        scope.launchSafe(ctx, "Uncharted.Disp") {
                            db.uncharted().update(item.copy(disposition = "advance_delivery"))
                            pickFor = null
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                DispBtn(stringResource(R.string.uncharted_resolved), StatusVerified, fillWidth = true) {
                    scope.launch {
                        db.uncharted().update(
                            item.copy(
                                disposition = "resolved",
                                resolvedAt = System.currentTimeMillis(),
                            )
                        )
                        pickFor = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Permanently remove this uncharted item. The underlying scan
                // record stays in the scans log (audit trail) — only the
                // uncharted entry is dropped.
                DispBtn(stringResource(R.string.uncharted_delete), Color(0xFFB91C1C), fillWidth = true) {
                    scope.launch {
                        db.uncharted().deleteById(item.id)
                        pickFor = null
                    }
                }
            }
        }
    }
}

@Composable
private fun UnchartedRow(u: UnchartedItem, onClick: () -> Unit) {
    val dot = when (u.disposition) {
        "wrong_project" -> Color(0xFF7C3AED)
        "other_system" -> Color(0xFF3B82F6)
        "advance_delivery" -> StatusPending
        "investigate" -> StatusDamaged
        "resolved" -> StatusVerified
        else -> Color.Gray
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                u.drawing,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Row {
                if (u.spool.isNotEmpty()) {
                    Text(
                        stringResource(R.string.uncharted_spool_fmt, u.spool) + "  ·  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Inline sheet only when present — keeps the row compact for
                // tags that don't carry a sheet number.
                if (!u.sheet.isNullOrEmpty()) {
                    Text(
                        stringResource(R.string.uncharted_sheet_fmt, u.sheet) + "  ·  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(u.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            u.notes?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            u.disposition.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DispBtn(text: String, color: Color, fillWidth: Boolean = false, onClick: () -> Unit) {
    val mod = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    FilledTonalButton(
        onClick = onClick,
        modifier = mod.height(46.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color,
            contentColor = Color.White,
        ),
    ) {
        Text(text)
    }
}
