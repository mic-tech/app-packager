package com.maryumcenter.apppackager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.maryumcenter.apppackager.backup.BackupService
import com.maryumcenter.apppackager.ui.AppListScreen
import com.maryumcenter.apppackager.ui.AppListViewModel
import com.maryumcenter.apppackager.ui.AppPackagerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppListViewModel by viewModels()

    /** Set when the user hit "Back up" before choosing a folder, so the backup
     *  can continue by itself once the picker returns. */
    private var backupAfterFolderPick = false

    private var obbAccessGranted by mutableStateOf(false)

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) {
            backupAfterFolderPick = false
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.setDestination(uri)
        if (backupAfterFolderPick) {
            backupAfterFolderPick = false
            startBackup()
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsIfNeeded()

        setContent {
            AppPackagerTheme {
                AppListScreen(
                    viewModel = viewModel,
                    obbAccessGranted = obbAccessGranted,
                    onPickFolder = { pickFolder.launch(null) },
                    onRequestObbAccess = ::requestObbAccess,
                    onBackup = ::startBackup,
                    onCancelBackup = { BackupService.cancel(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        obbAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
    }

    private fun startBackup() {
        val packages = viewModel.selectedInOrder()
        if (packages.isEmpty()) return

        val destination = viewModel.destination.value?.takeIf { hasPersistedAccess(it) }
        if (destination == null) {
            Toast.makeText(this, "Choose a folder to save backups in", Toast.LENGTH_SHORT).show()
            backupAfterFolderPick = true
            pickFolder.launch(null)
            return
        }

        BackupService.start(
            context = this,
            packages = packages,
            destination = destination,
            includeObb = viewModel.includeObb.value,
            forceXapk = viewModel.forceXapk.value,
        )
        viewModel.clearSelection()
    }

    /** A folder chosen on an earlier run can have been revoked or deleted since. */
    private fun hasPersistedAccess(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun requestObbAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:$packageName".toUri(),
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
