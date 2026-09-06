package com.mictech.apppackager.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppRepository(private val context: Context) {

    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val packages: List<PackageInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }

        packages.mapNotNull { info -> info.toInstalledApp(pm) }
            .sortedBy { it.label.lowercase() }
    }

    /** Re-reads a single package, so a long backup queue never holds a stale list. */
    suspend fun load(packageName: String): InstalledApp? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
        }.getOrNull()?.toInstalledApp(pm)
    }

    private fun PackageInfo.toInstalledApp(pm: PackageManager): InstalledApp? {
        val appInfo = applicationInfo ?: return null
        val base = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return null
        // Some packages report a path we cannot actually open (a few OEM system
        // apps, apps on a detached SD card). Backing those up would fail later,
        // so drop them here rather than showing a row that can only error.
        if (!File(base).canRead()) return null

        val splitPaths = appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs
        val names: Array<String>? = this.splitNames
        val splits = splitPaths.orEmpty().mapIndexedNotNull { index, path ->
            if (!File(path).canRead()) return@mapIndexedNotNull null
            SplitApk(names?.getOrNull(index) ?: "split_$index", path)
        }

        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

        return InstalledApp(
            packageName = packageName,
            label = pm.getApplicationLabel(appInfo).toString(),
            versionName = versionName ?: "",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                @Suppress("DEPRECATION") versionCode.toLong()
            },
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            isSystem = isSystem,
            isUpdatedSystem = isUpdatedSystem,
            baseApk = base,
            splits = splits,
            permissions = requestedPermissions?.toList().orEmpty(),
            apkSize = (listOf(base) + splits.map { it.path }).sumOf { File(it).length() },
            lastUpdated = lastUpdateTime,
        )
    }
}
