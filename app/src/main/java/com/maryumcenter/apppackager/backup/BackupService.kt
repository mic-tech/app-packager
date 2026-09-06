package com.maryumcenter.apppackager.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.maryumcenter.apppackager.MainActivity
import com.maryumcenter.apppackager.R
import com.maryumcenter.apppackager.data.AppRepository
import com.maryumcenter.apppackager.data.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs the backup queue in the foreground so a multi-gigabyte copy survives the
 * user leaving the app. Progress is published through [BackupState]; nothing
 * binds to this service.
 */
class BackupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            worker?.cancel(CancellationException("cancelled by user"))
            return START_NOT_STICKY
        }

        // startForegroundService() obliges us to go foreground within a few
        // seconds even if the request turns out to be malformed, so promote
        // first and validate after.
        startForegroundWith(buildNotification("Starting backup", "", 0, 0, indeterminate = true))

        val packages = intent?.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        val destination = intent?.getStringExtra(EXTRA_DESTINATION)?.let(Uri::parse)
        if (packages.isEmpty() || destination == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY

        val includeObb = intent.getBooleanExtra(EXTRA_INCLUDE_OBB, true)
        val forceXapk = intent.getBooleanExtra(EXTRA_FORCE_XAPK, false)

        worker = scope.launch {
            try {
                runQueue(packages, destination, includeObb, forceXapk)
            } finally {
                BackupState.setProgress(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runQueue(
        packages: List<String>,
        destination: Uri,
        includeObb: Boolean,
        forceXapk: Boolean,
    ) {
        val repo = AppRepository(this)
        val writer = XapkWriter(this)
        BackupState.beginRun()

        val tree = DocumentFile.fromTreeUri(this, destination)
        if (tree == null || !tree.canWrite()) {
            packages.forEach {
                BackupState.addOutcome(
                    BackupOutcome(it, "Cannot write to the chosen folder", ok = false),
                )
            }
            postSummary()
            return
        }
        // Listing a SAF directory is a full query, and findFile() repeats it per
        // lookup — one snapshot up front keeps a long queue from going quadratic.
        val existing = tree.listFiles().mapNotNull { file -> file.name?.let { it to file } }.toMap()

        var cancelled = false
        packages.forEachIndexed { index, packageName ->
            if (cancelled) return@forEachIndexed
            val app = repo.load(packageName)
            if (app == null) {
                BackupState.addOutcome(BackupOutcome(packageName, "No longer installed", ok = false))
                return@forEachIndexed
            }

            val plan = writer.plan(app, includeObb, forceXapk)
            BackupState.setProgress(
                BackupProgress(index, packages.size, app.label, plan.fileName, 0, plan.totalBytes),
            )
            notify(buildNotification(app.label, plan.fileName, 0, plan.totalBytes, false, index, packages.size))

            var created: DocumentFile? = null
            try {
                // Overwrite rather than pile up "file (1).xapk" copies: a repeat
                // backup of the same version is meant to replace the old one.
                existing[plan.fileName]?.delete()
                val file = tree.createFile(MIME_BINARY, plan.fileName)
                    ?: throw IOException("Could not create ${plan.fileName}")
                created = file

                var lastNotifiedAt = 0L
                contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                    writer.write(plan, out) { bytes ->
                        BackupState.setProgress(
                            BackupProgress(index, packages.size, app.label, plan.fileName, bytes, plan.totalBytes),
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastNotifiedAt > NOTIFY_INTERVAL_MS) {
                            lastNotifiedAt = now
                            notify(
                                buildNotification(
                                    app.label, plan.fileName, bytes, plan.totalBytes,
                                    indeterminate = false, index = index, total = packages.size,
                                ),
                            )
                        }
                    }
                } ?: throw IOException("Could not open ${plan.fileName} for writing")

                BackupState.addOutcome(
                    BackupOutcome(app.label, "${plan.fileName} · ${formatSize(plan.totalBytes)}", ok = true),
                )
            } catch (e: CancellationException) {
                created?.delete()
                BackupState.addOutcome(BackupOutcome(app.label, "Cancelled", ok = false))
                cancelled = true
            } catch (e: Exception) {
                created?.delete()
                BackupState.addOutcome(
                    BackupOutcome(app.label, e.message ?: e.javaClass.simpleName, ok = false),
                )
            }
        }
        postSummary()
    }

    private fun postSummary() {
        val outcomes = BackupState.lastRun.value.orEmpty()
        val ok = outcomes.count { it.ok }
        val failed = outcomes.size - ok
        val text = buildString {
            append("$ok backed up")
            if (failed > 0) append(", $failed failed")
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("Backup finished")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(DONE_NOTIFICATION_ID, notification)
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        fileName: String,
        bytes: Long,
        totalBytes: Long,
        indeterminate: Boolean,
        index: Int = 0,
        total: Int = 0,
    ): Notification {
        createChannels()
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, BackupService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subtitle = buildString {
            if (total > 1) append("${index + 1}/$total · ")
            append(fileName.ifBlank { "Preparing…" })
            if (totalBytes > 0) append(" · ${formatSize(bytes)} / ${formatSize(totalBytes)}")
        }
        val percent = if (totalBytes > 0) ((bytes * 100) / totalBytes).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(notification: Notification) = notifyIfAllowed(PROGRESS_NOTIFICATION_ID, notification)

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(id, notification) }
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Backup finished", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        BackupState.setProgress(null)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CANCEL = "com.maryumcenter.apppackager.CANCEL"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_INCLUDE_OBB = "include_obb"
        private const val EXTRA_FORCE_XAPK = "force_xapk"
        private const val CHANNEL_PROGRESS = "backup_progress"
        private const val CHANNEL_DONE = "backup_done"
        private const val PROGRESS_NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val NOTIFY_INTERVAL_MS = 500L
        private const val MIME_BINARY = "application/octet-stream"

        fun start(
            context: Context,
            packages: List<String>,
            destination: Uri,
            includeObb: Boolean,
            forceXapk: Boolean,
        ) {
            val intent = Intent(context, BackupService::class.java)
                .putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
                .putExtra(EXTRA_DESTINATION, destination.toString())
                .putExtra(EXTRA_INCLUDE_OBB, includeObb)
                .putExtra(EXTRA_FORCE_XAPK, forceXapk)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, BackupService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
