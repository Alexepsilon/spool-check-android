package com.spoolcheck.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spoolcheck.app.R
import com.spoolcheck.app.data.AppDatabase
import com.spoolcheck.app.data.Delivery
import com.spoolcheck.app.ui.launchSafe
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val scope = rememberCoroutineScope()
    val deliveries by db.deliveries().observeAll().collectAsState(initial = emptyList())
    var counts by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    var pendingDelete by remember { mutableStateOf<Delivery?>(null) }

    LaunchedEffect(deliveries) {
        val newCounts = mutableMapOf<String, Pair<Int, Int>>()
        for (d in deliveries) {
            val items = db.items().listForDelivery(d.id)
            val verified = items.count { it.status == "verified" }
            newCounts[d.id] = items.size to verified
        }
        counts = newCounts
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("import") },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.home_new)) },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        if (deliveries.isEmpty()) {
            EmptyHome(padding) { nav.navigate("import") }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(deliveries, key = { it.id }) { d ->
                    DeliveryRow(
                        d = d,
                        count = counts[d.id],
                        onClick = { nav.navigate("board/${d.id}") },
                        onLongClick = { pendingDelete = d },
                    )
                    HorizontalDivider()
                }
                item {
                    Text(
                        stringResource(R.string.home_long_press_hint),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    pendingDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.home_delete_title)) },
            text = {
                Text(stringResource(R.string.home_delete_body_fmt, d.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = d
                        pendingDelete = null
                        scope.launchSafe(ctx, "Home.DeleteDelivery") {
                            // Cascade-delete: master_items, scans, uncharted, then the delivery itself.
                            db.items().deleteForDelivery(toDelete.id)
                            db.scans().deleteForDelivery(toDelete.id)
                            db.uncharted().deleteForDelivery(toDelete.id)
                            db.deliveries().delete(toDelete.id)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyHome(padding: PaddingValues, onCreate: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📋", fontSize = 60.sp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onCreate) {
                Text(stringResource(R.string.home_new))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DeliveryRow(
    d: Delivery,
    count: Pair<Int, Int>?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val (total, verified) = count ?: (0 to 0)
    val pct = if (total == 0) 0 else (verified * 100 / total)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(d.importedAt))
            Text(
                stringResource(R.string.home_scanned_fmt, verified, total, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.secondary,
                strokeWidth = 3.dp,
                trackColor = Color.Transparent,
            )
            Text("$pct%", style = MaterialTheme.typography.labelSmall)
        }
    }
}
