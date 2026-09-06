package com.mictech.apppackager.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Environment
import androidx.core.graphics.createBitmap
import com.mictech.apppackager.data.InstalledApp
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** One file that will be copied into the archive. */
data class PlannedEntry(val entryName: String, val source: File)

/**
 * Everything decided up front: what goes in, how big it is, and what the file
 * should be called. Deciding the name before opening the output stream matters
 * because the SAF document has to be created with its final name.
 */
data class BackupPlan(
    val app: InstalledApp,
    val entries: List<PlannedEntry>,
    val obbEntries: List<PlannedEntry>,
    val asXapk: Boolean,
    val fileName: String,
) {
    val totalBytes: Long get() = (entries + obbEntries).sumOf { it.source.length() }
}

/**
 * Packages an installed app into an XAPK (or a plain APK when there is only one
 * file to save).
 *
 * The two pieces that need Android — the launcher icon and the location of
 * shared storage — are injected, so the archive format itself can be tested on
 * a plain JVM.
 */
class XapkWriter internal constructor(
    private val iconProvider: (String) -> ByteArray?,
    private val obbRoot: () -> File?,
) {
    constructor(context: Context) : this(
        iconProvider = { packageName -> loadIconPng(context, packageName) },
        obbRoot = { if (hasObbAccess()) Environment.getExternalStorageDirectory() else null },
    )

    /**
     * A single-APK app with no expansion files is packaged as a plain `.apk`
     * unless [forceXapk] — a one-entry zip is just a worse APK, and a bare APK
     * can be installed by tapping it. Anything with splits must be an XAPK.
     */
    fun plan(app: InstalledApp, includeObb: Boolean, forceXapk: Boolean): BackupPlan {
        val apks = buildList {
            add(PlannedEntry("base.apk", File(app.baseApk)))
            app.splits.forEach { add(PlannedEntry("${sanitizeEntry(it.name)}.apk", File(it.path))) }
        }
        val obb = if (includeObb) findObbFiles(app.packageName) else emptyList()
        val asXapk = forceXapk || app.hasSplits || obb.isNotEmpty()
        val version = app.versionName.ifBlank { app.versionCode.toString() }
        val stem = "${sanitizeFileName(app.label)}_${sanitizeFileName(version)}_${app.versionCode}"
        return BackupPlan(
            app = app,
            entries = apks,
            obbEntries = obb,
            asXapk = asXapk,
            fileName = if (asXapk) "$stem.xapk" else "$stem.apk",
        )
    }

    /** Copies the planned files into [out]. [onBytes] receives cumulative bytes written. */
    suspend fun write(plan: BackupPlan, out: OutputStream, onBytes: (Long) -> Unit) {
        if (!plan.asXapk) {
            BufferedOutputStream(out, BUFFER).use { sink ->
                copy(plan.entries.single().source, sink, 0L, onBytes)
            }
            return
        }

        var written = 0L
        ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
            // APKs and OBBs are already deflated internally; re-compressing them
            // burns CPU for well under a percent of size. Store them raw and let
            // only the tiny metadata entries be compressed.
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (entry in plan.entries + plan.obbEntries) {
                coroutineContext.ensureActive()
                zip.putNextEntry(ZipEntry(entry.entryName).apply { time = entry.source.lastModified() })
                written = copy(entry.source, zip, written, onBytes)
                zip.closeEntry()
            }

            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            iconProvider(plan.app.packageName)?.let { png ->
                zip.putNextEntry(ZipEntry(ICON_ENTRY))
                zip.write(png)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestJson(plan).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private suspend fun copy(
        source: File,
        sink: OutputStream,
        startAt: Long,
        onBytes: (Long) -> Unit,
    ): Long {
        var total = startAt
        val buffer = ByteArray(BUFFER)
        source.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                total += read
                onBytes(total)
            }
        }
        return total
    }

    /** XAPK v2 manifest, matching what APKPure-style installers expect. */
    internal fun manifestJson(plan: BackupPlan): String {
        val app = plan.app
        return JSONObject().apply {
            put("xapk_version", 2)
            put("package_name", app.packageName)
            put("name", app.label)
            put("version_code", app.versionCode.toString())
            put("version_name", app.versionName)
            put("min_sdk_version", app.minSdk.toString())
            put("target_sdk_version", app.targetSdk.toString())
            put("icon", ICON_ENTRY)
            put("total_size", plan.totalBytes)
            put("permissions", JSONArray(app.permissions))
            put(
                "split_apks",
                JSONArray().apply {
                    plan.entries.forEachIndexed { index, entry ->
                        put(
                            JSONObject()
                                .put("file", entry.entryName)
                                .put("id", if (index == 0) "base" else app.splits[index - 1].name),
                        )
                    }
                },
            )
            if (plan.obbEntries.isNotEmpty()) {
                put(
                    "expansions",
                    JSONArray().apply {
                        plan.obbEntries.forEach { entry ->
                            put(
                                JSONObject()
                                    .put("file", entry.entryName)
                                    .put("install_location", "EXTERNAL_STORAGE")
                                    .put("install_path", entry.entryName),
                            )
                        }
                    },
                )
            }
        }.toString(2)
    }

    /**
     * OBBs live on shared storage, so on Android 11+ they are only reachable
     * with All-files access. Without it this quietly returns nothing and the
     * backup still contains every APK.
     */
    private fun findObbFiles(packageName: String): List<PlannedEntry> {
        val root = obbRoot() ?: return emptyList()
        val dir = File(root, "Android/obb/$packageName")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.canRead() }
            .sortedBy { it.name }
            .map { PlannedEntry("Android/obb/$packageName/${it.name}", it) }
    }

    internal companion object {
        private const val BUFFER = 256 * 1024
        const val ICON_ENTRY = "icon.png"
        const val MANIFEST_ENTRY = "manifest.json"

        fun sanitizeEntry(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun sanitizeFileName(name: String) =
            name.replace(Regex("[^A-Za-z0-9._-]+"), " ").trim().replace(' ', '-')
                .ifBlank { "app" }
                .take(64)

        private fun hasObbAccess(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        private fun loadIconPng(context: Context, packageName: String): ByteArray? = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 1).coerceIn(48, 512)
            val bitmap = (drawable as? BitmapDrawable)?.bitmap
                ?: createBitmap(size, size).also { bmp ->
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        }.getOrNull()
    }
}
