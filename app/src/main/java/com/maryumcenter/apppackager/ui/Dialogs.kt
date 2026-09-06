package com.maryumcenter.apppackager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maryumcenter.apppackager.backup.BackupOutcome

@Composable
fun OptionsSheet(
    showSystem: Boolean,
    includeObb: Boolean,
    forceXapk: Boolean,
    obbAccessGranted: Boolean,
    onShowSystem: (Boolean) -> Unit,
    onIncludeObb: (Boolean) -> Unit,
    onForceXapk: (Boolean) -> Unit,
    onRequestObbAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OptionRow(
                    title = "Show system apps",
                    subtitle = "Preinstalled apps are hidden by default. Backing one up works, " +
                        "but it may refuse to install on another device.",
                    checked = showSystem,
                    onCheckedChange = onShowSystem,
                )
                OptionRow(
                    title = "Always package as XAPK",
                    subtitle = "Off: an app with no splits and no OBB is saved as a plain .apk " +
                        "you can just tap to install.",
                    checked = forceXapk,
                    onCheckedChange = onForceXapk,
                )
                OptionRow(
                    title = "Include OBB expansion files",
                    subtitle = "Large games keep data in Android/obb. Reading it needs " +
                        "all-files access.",
                    checked = includeObb,
                    onCheckedChange = onIncludeObb,
                )
                if (includeObb && !obbAccessGranted) {
                    TextButton(onClick = onRequestObbAccess) {
                        Text("Grant all-files access")
                    }
                    Text(
                        "Without it, OBB files are skipped and the APKs are still backed up.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Backups contain the app's APKs only — not its saved data, accounts or " +
                        "settings, which Android does not let one app read from another.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun ResultsDialog(outcomes: List<BackupOutcome>, onDismiss: () -> Unit) {
    val ok = outcomes.count { it.ok }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("$ok of ${outcomes.size} backed up") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(outcomes) { outcome ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Icon(
                            if (outcome.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (outcome.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(outcome.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                outcome.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Turns a tree URI into something a person recognises, e.g. `Download/Backups`. */
fun folderLabel(uri: String): String {
    val decoded = java.net.URLDecoder.decode(uri, "UTF-8")
    val tail = decoded.substringAfterLast("/tree/", decoded)
    return tail.substringAfter(':').ifBlank { tail }.ifBlank { "chosen folder" }
}
