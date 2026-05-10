package com.spoolcheck.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.spoolcheck.app.core.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(nav: NavController) {
    val ctx = LocalContext.current
    val entries = DebugLog.entries
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_log_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        copyToClipboard(ctx, DebugLog.dump())
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.debug_log_copied),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Text(
                            stringResource(R.string.debug_log_copy),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    TextButton(onClick = { DebugLog.clear() }) {
                        Text(
                            stringResource(R.string.debug_log_clear),
                            color = MaterialTheme.colorScheme.onPrimary,
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
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    stringResource(R.string.debug_log_count_fmt, entries.size),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.debug_log_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.time.toString() + it.message.hashCode() }) { e ->
                        EntryRow(e, timeFmt)
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: DebugLog.Entry, timeFmt: SimpleDateFormat) {
    val accent = when (entry.category) {
        "IMPORT" -> Color(0xFF2563EB) // blue
        "MATCH"  -> Color(0xFF059669) // green
        "SCAN"   -> Color(0xFFEA580C) // orange
        "DB"     -> Color(0xFF7C3AED) // purple
        else     -> Color.Gray
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            timeFmt.format(Date(entry.time)),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.width(80.dp),
        )
        Box(
            modifier = Modifier
                .background(accent, RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                entry.category,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Spool Check debug log", text))
}
