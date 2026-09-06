package com.maryumcenter.apppackager.backup

import com.maryumcenter.apppackager.data.InstalledApp
import com.maryumcenter.apppackager.data.SplitApk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class XapkWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val icon = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private fun writer(obbRoot: File? = null) =
        XapkWriter(iconProvider = { icon }, obbRoot = { obbRoot })

    private fun apk(name: String, body: String): File =
        temp.newFile(name).apply { writeText(body) }

    private fun app(
        base: File,
        splits: List<SplitApk> = emptyList(),
        label: String = "Example App",
    ) = InstalledApp(
        packageName = "com.example.app",
        label = label,
        versionName = "2.5.1",
        versionCode = 251,
        minSdk = 24,
        targetSdk = 34,
        isSystem = false,
        isUpdatedSystem = false,
        baseApk = base.path,
        splits = splits,
        permissions = listOf("android.permission.INTERNET"),
        apkSize = base.length(),
        lastUpdated = 0L,
    )

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    @Test
    fun `split app is packaged as an xapk containing every apk`() {
        val base = apk("base_source.apk", "BASE-CONTENT")
        val splits = listOf(
            SplitApk("config.arm64_v8a", apk("abi.apk", "ABI-CONTENT").path),
            SplitApk("config.en", apk("lang.apk", "EN-CONTENT").path),
        )
        val writer = writer()
        val plan = writer.plan(app(base, splits), includeObb = true, forceXapk = false)

        assertTrue(plan.asXapk)
        assertEquals("Example-App_2.5.1_251.xapk", plan.fileName)

        val out = ByteArrayOutputStream()
        runBlocking { writer.write(plan, out) {} }
        val entries = readZip(out.toByteArray())

        assertEquals(
            setOf("base.apk", "config.arm64_v8a.apk", "config.en.apk", "icon.png", "manifest.json"),
            entries.keys,
        )
        assertEquals("BASE-CONTENT", String(entries.getValue("base.apk")))
        assertEquals("ABI-CONTENT", String(entries.getValue("config.arm64_v8a.apk")))
        assertEquals("EN-CONTENT", String(entries.getValue("config.en.apk")))
    }

    @Test
    fun `manifest describes the package and maps every split to its id`() {
        val base = apk("base_source.apk", "BASE")
        val splits = listOf(SplitApk("config.xxhdpi", apk("dpi.apk", "DPI").path))
        val writer = writer()
        val plan = writer.plan(app(base, splits), includeObb = false, forceXapk = false)

        val manifest = JSONObject(writer.manifestJson(plan))
        assertEquals(2, manifest.getInt("xapk_version"))
        assertEquals("com.example.app", manifest.getString("package_name"))
        assertEquals("Example App", manifest.getString("name"))
        assertEquals("251", manifest.getString("version_code"))
        assertEquals("2.5.1", manifest.getString("version_name"))
        assertEquals("24", manifest.getString("min_sdk_version"))
        assertEquals("34", manifest.getString("target_sdk_version"))
        assertEquals("icon.png", manifest.getString("icon"))
        assertEquals(7L, manifest.getLong("total_size"))

        val declared = manifest.getJSONArray("split_apks")
        assertEquals(2, declared.length())
        assertEquals("base.apk", declared.getJSONObject(0).getString("file"))
        assertEquals("base", declared.getJSONObject(0).getString("id"))
        assertEquals("config.xxhdpi.apk", declared.getJSONObject(1).getString("file"))
        assertEquals("config.xxhdpi", declared.getJSONObject(1).getString("id"))
        assertFalse(manifest.has("expansions"))
    }

    @Test
    fun `every file named in the manifest is actually in the archive`() {
        val base = apk("base_source.apk", "BASE")
        val splits = listOf(SplitApk("config.arm64_v8a", apk("abi.apk", "ABI").path))
        val obbRoot = temp.newFolder("sdcard")
        File(obbRoot, "Android/obb/com.example.app").apply { mkdirs() }
            .resolve("main.251.com.example.app.obb").writeText("OBB-DATA")

        val writer = writer(obbRoot)
        val plan = writer.plan(app(base, splits), includeObb = true, forceXapk = false)
        val out = ByteArrayOutputStream()
        runBlocking { writer.write(plan, out) {} }
        val entries = readZip(out.toByteArray())

        val manifest = JSONObject(String(entries.getValue("manifest.json")))
        val declared = manifest.getJSONArray("split_apks")
        for (i in 0 until declared.length()) {
            val file = declared.getJSONObject(i).getString("file")
            assertTrue("$file missing from archive", entries.containsKey(file))
        }
        val expansions = manifest.getJSONArray("expansions")
        assertEquals(1, expansions.length())
        val obbPath = expansions.getJSONObject(0).getString("file")
        assertEquals("Android/obb/com.example.app/main.251.com.example.app.obb", obbPath)
        assertEquals(obbPath, expansions.getJSONObject(0).getString("install_path"))
        assertEquals("OBB-DATA", String(entries.getValue(obbPath)))
    }

    @Test
    fun `obb files are skipped when shared storage is unreadable`() {
        val base = apk("base_source.apk", "BASE")
        val plan = writer(obbRoot = null).plan(app(base), includeObb = true, forceXapk = false)

        assertTrue(plan.obbEntries.isEmpty())
        assertFalse(plan.asXapk)
    }

    @Test
    fun `a lone apk is copied out verbatim rather than zipped`() {
        val base = apk("base_source.apk", "JUST-THE-APK")
        val writer = writer()
        val plan = writer.plan(app(base), includeObb = false, forceXapk = false)

        assertFalse(plan.asXapk)
        assertEquals("Example-App_2.5.1_251.apk", plan.fileName)

        val out = ByteArrayOutputStream()
        runBlocking { writer.write(plan, out) {} }
        assertEquals("JUST-THE-APK", String(out.toByteArray()))
    }

    @Test
    fun `an obb forces a lone apk into an xapk so the expansion travels with it`() {
        val base = apk("base_source.apk", "BASE")
        val obbRoot = temp.newFolder("sdcard")
        File(obbRoot, "Android/obb/com.example.app").apply { mkdirs() }
            .resolve("main.1.obb").writeText("DATA")

        val plan = writer(obbRoot).plan(app(base), includeObb = true, forceXapk = false)
        assertTrue(plan.asXapk)
        assertTrue(plan.fileName.endsWith(".xapk"))
    }

    @Test
    fun `forcing xapk wraps even a single apk`() {
        val base = apk("base_source.apk", "BASE")
        val plan = writer().plan(app(base), includeObb = false, forceXapk = true)
        assertTrue(plan.asXapk)
        assertEquals("Example-App_2.5.1_251.xapk", plan.fileName)
    }

    @Test
    fun `progress adds up to the total the plan advertised`() {
        val base = apk("base_source.apk", "0123456789")
        val splits = listOf(SplitApk("config.en", apk("lang.apk", "abcde").path))
        val writer = writer()
        val plan = writer.plan(app(base, splits), includeObb = false, forceXapk = false)

        val seen = mutableListOf<Long>()
        runBlocking { writer.write(plan, ByteArrayOutputStream()) { seen += it } }

        assertEquals(15L, plan.totalBytes)
        assertEquals(15L, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `names that are illegal on a fat32 sd card are cleaned up`() {
        val base = apk("base_source.apk", "BASE")
        val plan = writer().plan(
            app(base, label = "My App: v2 / \"beta\"  ★"),
            includeObb = false,
            forceXapk = true,
        )
        assertEquals("My-App-v2-beta_2.5.1_251.xapk", plan.fileName)
    }
}
