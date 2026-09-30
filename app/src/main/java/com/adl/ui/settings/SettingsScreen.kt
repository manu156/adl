package com.adl.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Settings saved")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    IconButton(onClick = viewModel::resetToDefaults) {
                        Icon(Icons.Default.Restore, contentDescription = "Reset to defaults")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {

            // ─ Browser Settings ─────────────────────────────────────────────────
            SettingsSection(title = "Browser") {
                OutlinedTextField(
                    value = uiState.homepage,
                    onValueChange = viewModel::updateHomepage,
                    label = { Text("Homepage URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = viewModel::clearBrowserHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Clear URL History (Last 5 URLs)", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ─ Gallery Settings ──────────────────────────────────────────────────
            SettingsSection(title = "Gallery") {
                Text(
                    text = "Grid columns: ${uiState.gridColumns}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = uiState.gridColumns.toFloat(),
                    onValueChange = { viewModel.updateGridColumns(it.roundToInt()) },
                    valueRange = 2f..5f,
                    steps = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ─ Download Settings ─────────────────────────────────────────────────
            SettingsSection(title = "Download") {
                // Download Threads
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download threads: ${uiState.downloadThreads}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Parallel worker threads downloading images simultaneously (1–8)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Slider(
                    value = uiState.downloadThreads.toFloat(),
                    onValueChange = { viewModel.updateDownloadThreads(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                // Sleep Interval
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sleep interval: ${uiState.sleepInterval}s",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Delay between requests to avoid rate limits or IP bans (0 = no delay)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Slider(
                    value = (uiState.sleepInterval.toFloatOrNull() ?: 0.5f).coerceIn(0f, 5f),
                    onValueChange = {
                        val rounded = (it * 10).roundToInt() / 10f
                        viewModel.updateSleepInterval(if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}" else "$rounded")
                    },
                    valueRange = 0f..5f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.sleepInterval,
                    onValueChange = viewModel::updateSleepInterval,
                    label = { Text("Exact sleep interval (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.filenameTemplate,
                    onValueChange = viewModel::updateFilenameTemplate,
                    label = { Text("Filename template") },
                    supportingText = { Text("e.g. {filename}.{extension}") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Retry attempts: ${uiState.retries}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = uiState.retries.toFloat(),
                    onValueChange = { viewModel.updateRetries(it.roundToInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ─ Advanced: gallery-dl Config JSON ──────────────────────────────────
            SettingsSection(title = "Advanced: gallery-dl Config JSON") {
                Text(
                    text = "Directly configure gallery-dl extractor settings. Must be valid JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.configJson,
                    onValueChange = viewModel::updateConfigJson,
                    label = { Text("gallery-dl config JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    isError = uiState.configValidationError.isNotBlank(),
                    supportingText = if (uiState.configValidationError.isNotBlank()) {
                        { Text(uiState.configValidationError, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }

            Spacer(Modifier.height(24.dp))

            // ─ Save + Reset buttons ───────────────────────────────────────────────
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Restore, null)
                    Text("Reset", modifier = Modifier.padding(start = 4.dp))
                }
                FilledTonalButton(
                    onClick = viewModel::validateAndSave,
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isSaving,
                ) {
                    Icon(Icons.Default.Save, null)
                    Text("Save", modifier = Modifier.padding(start = 4.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            content()
        }
    }
}
