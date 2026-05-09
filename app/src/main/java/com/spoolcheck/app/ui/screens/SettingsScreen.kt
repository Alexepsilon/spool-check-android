package com.spoolcheck.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import com.spoolcheck.app.R
import com.spoolcheck.app.core.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current

    // Read the current applied app locale. We only support en + nl now —
    // if no app locale has been set yet (fresh install, "system default"),
    // resolve to nl when the device is Dutch, otherwise en.
    var lang by remember {
        mutableStateOf(
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
                .substringBefore('-')
                .ifEmpty {
                    val sys = java.util.Locale.getDefault().language
                    if (sys == "nl") "nl" else "en"
                }
        )
    }

    var hapticOn by remember { mutableStateOf(Prefs.hapticOnMatch(ctx)) }

    fun setLang(tag: String) {
        lang = tag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
        ) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Card {
                Column(modifier = Modifier.padding(8.dp)) {
                    LangRow(stringResource(R.string.settings_lang_english), "en", lang) { setLang(it) }
                    LangRow(stringResource(R.string.settings_lang_dutch), "nl", lang) { setLang(it) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_scanner), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_haptic_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.settings_haptic_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = hapticOn,
                        onCheckedChange = {
                            hapticOn = it
                            Prefs.setHapticOnMatch(ctx, it)
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_about_version), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_about_blurb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LangRow(
    label: String,
    tag: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = current == tag,
            onClick = { onSelect(tag) },
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
