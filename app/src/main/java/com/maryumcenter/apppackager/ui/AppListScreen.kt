package com.maryumcenter.apppackager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maryumcenter.apppackager.backup.BackupProgress
import com.maryumcenter.apppackager.backup.BackupState
import com.maryumcenter.apppackager.data.InstalledApp
import com.maryumcenter.apppackager.data.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel,
    obbAccessGranted: Boolean,
    onPickFolder: () -> Unit,
    onRequestObbAccess: () -> Unit,
    onBackup: () -> Unit,
    onCancelBackup: () -> Unit,
) {
    val apps by viewModel.visibleApps.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val selectedBytes by viewModel.selectedBytes.collectAsStateWithLifecycle()
    val showSystem by viewModel.showSystem.collectAsStateWithLifecycle()
    val includeObb by viewModel.includeObb.collectAsStateWithLifecycle()
    val forceXapk by viewModel.forceXapk.collectAsStateWithLifecycle()
    val progress by BackupState.progress.collectAsStateWithLifecycle()
    val lastRun by BackupState.lastRun.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("App Packager")
                        Text(
                            destination?.let { "Saving to ${folderLabel(it.toString())}" }
                                ?: "No output folder chosen",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload app list")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Choose output folder") },
                            onClick = { menuOpen = false; onPickFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text("Select all shown") },
                            onClick = { menuOpen = false; viewModel.selectAllVisible() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear selection") },
                            onClick = { menuOpen = false; viewModel.clearSelection() },
                        )
                        DropdownMenuItem(
                            text = { Text("Options") },
                            onClick = { menuOpen = false; settingsOpen = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomBar(
                selectedCount = selected.size,
                selectedBytes = selectedBytes,
                busy = progress != null,
                onBackup = onBackup,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            progress?.let { ProgressCard(it, onCancelBackup) }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in selected,
                            onToggle = { viewModel.toggle(app.packageName) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        OptionsSheet(
            showSystem = showSystem,
            includeObb = includeObb,
            forceXapk = forceXapk,
            obbAccessGranted = obbAccessGranted,
            onShowSystem = viewModel::setShowSystem,
            onIncludeObb = viewModel::setIncludeObb,
            onForceXapk = viewModel::setForceXapk,
            onRequestObbAccess = onRequestObbAccess,
            onDismiss = { settingsOpen = false },
        )
    }

    val results = lastRun
    if (results != null && progress == null && results.isNotEmpty()) {
        ResultsDialog(results) { BackupState.clearLastRun() }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AppIcon(app.packageName, Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v${app.versionName.ifBlank { app.versionCode.toString() }} · ${formatSize(app.apkSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.hasSplits) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onToggle,
                        label = { Text("${app.splits.size} splits", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(),
                        modifier = Modifier.height(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selectedCount: Int, selectedBytes: Long, busy: Boolean, onBackup: () -> Unit) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                if (selectedCount == 0) "Nothing selected" else "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (selectedCount > 0) {
                Text(
                    "about ${formatSize(selectedBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onBackup, enabled = selectedCount > 0 && !busy) {
            Text(if (busy) "Backing up…" else "Back up")
        }
    }
}

@Composable
private fun ProgressCard(progress: BackupProgress, onCancel: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${progress.label} (${progress.currentIndex + 1}/${progress.total})",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatSize(progress.bytesDone)} / ${formatSize(progress.bytesTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
