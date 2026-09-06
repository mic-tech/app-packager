package com.maryumcenter.apppackager.data

/**
 * One installed package, flattened to just what a backup needs.
 *
 * [baseApk] and [splits] are the *public* source dirs, which are the paths any
 * app is allowed to read — the private [android.content.pm.ApplicationInfo.sourceDir]
 * is identical for normal apps but is not the one to rely on.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val baseApk: String,
    val splits: List<SplitApk>,
    val permissions: List<String>,
    val apkSize: Long,
    val lastUpdated: Long,
) {
    val hasSplits: Boolean get() = splits.isNotEmpty()

    /** Every APK that makes up this app, base first. */
    val allApkPaths: List<String> get() = listOf(baseApk) + splits.map { it.path }
}

/** A single split APK: [name] is the split's own id, e.g. `config.arm64_v8a`. */
data class SplitApk(val name: String, val path: String)
