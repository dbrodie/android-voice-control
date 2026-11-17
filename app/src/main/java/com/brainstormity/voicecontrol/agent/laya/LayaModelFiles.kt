package com.brainstormity.voicecontrol.agent.laya

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.brainstormity.voicecontrol.agent.Availability
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The Laya-Android model files on the phone (not in the APK): `files/models/laya-android/`.
 *
 * The set ([REQUIRED], ~703 MB) is T14's recommended phone set plus the 611-byte table metadata; sizes and
 * sha256 come from `assets/laya/MANIFEST.json` (a copy of `tools/laya/out/MANIFEST.json`, regenerate both with
 * `tools/laya/manifest.py`). Single-window graphs (`laya_android_s{256,512,1024}_embeds_wfp16.tflite`) are optional
 * fallbacks the engine uses only if the multi-signature graph can't be compiled.
 *
 * Ways to get them there:
 *  - **Download** ([download]) from [AppSettings.layaModelBaseUrl] + file name (resumable, HTTP Range), each
 *    file verified against its sha256 before it's renamed into place;
 *  - **Import** ([importUris]) files or a .zip picked with ACTION_OPEN_DOCUMENT;
 *  - **dev**: `adb push` to the app-specific external dir, then the debug broadcast `IMPORT_LAYA` copies them in
 *    ([importDir]), or `run-as` (see docs/USER_GUIDE.md "On-device model (Laya)").
 *
 * Integrity: sizes are checked on every [status] (cheap, main thread OK); sha256 once per file version (first
 * engine load or after download/import), cached in `verified.txt` as `name size mtime`.
 */
object LayaModelFiles {
    private const val TAG = "Laya"
    const val DIR = "models/laya-android"
    const val GRAPH_MULTI = "laya_android_multi_embeds_wfp16.tflite"
    const val TABLE = "token_embeddings_fp16.bin"
    const val TABLE_META = "token_embeddings.json"
    const val TOKENIZER = "tokenizer.json"
    const val CONFIG = "rl_agent_config.json"
    val REQUIRED = listOf(GRAPH_MULTI, TABLE, TABLE_META, TOKENIZER, CONFIG)
    /** Optional single-window graphs (signature `serving_default`), by window. */
    val SINGLE_GRAPHS = linkedMapOf(
        256 to "laya_android_s256_embeds_wfp16.tflite",
        512 to "laya_android_s512_embeds_wfp16.tflite",
        1024 to "laya_android_s1024_embeds_wfp16.tflite",
    )
    private const val VERIFIED = "verified.txt"
    const val MIN_RAM_BYTES = 3L * 1000 * 1000 * 1000 // "3 GB" phones report ~2.7-2.9 GiB; compare in GB
    val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    class Spec(val name: String, val bytes: Long, val sha256: String)

    /** Which files are there with the right size. */
    class Status(val specs: List<Spec>, val present: Set<String>) {
        val missing: List<Spec> get() = specs.filter { it.name !in present }
        val totalBytes: Long get() = specs.sumOf { it.bytes }
        val missingBytes: Long get() = missing.sumOf { it.bytes }
        val complete: Boolean get() = missing.isEmpty()
    }

    // ------------------------------------------------------------------ manifest

    @Volatile private var manifestCache: Map<String, Spec>? = null

    fun manifest(ctx: Context): Map<String, Spec> = manifestCache ?: synchronized(this) {
        manifestCache ?: ctx.assets.open("laya/MANIFEST.json").use { parseManifest(it) }.also { manifestCache = it }
    }

    /** `MANIFEST.json` -> name -> Spec (JVM-testable). */
    fun parseManifest(input: InputStream): Map<String, Spec> {
        @Suppress("UNCHECKED_CAST")
        val root = LayaJsonReader(input.reader(Charsets.UTF_8)).readValue() as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val files = root["files"] as Map<String, Map<String, Any?>>
        return files.mapValues { (name, f) -> Spec(name, (f["bytes"] as Number).toLong(), f["sha256"] as String) }
    }

    fun requiredSpecs(manifest: Map<String, Spec>): List<Spec> = REQUIRED.map { manifest.getValue(it) }

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Size check of the required set (cheap). */
    fun status(ctx: Context): Status = statusOf(dir(ctx), requiredSpecs(manifest(ctx)))

    fun statusOf(dir: File, specs: List<Spec>): Status =
        Status(specs, specs.filter { File(dir, it.name).let { f -> f.isFile && f.length() == it.bytes } }.mapTo(HashSet()) { it.name })

    // ------------------------------------------------------------------ availability

    /** Device facts for [availabilityFor] (cheap). */
    class Device(val primaryAbi: String, val is64Bit: Boolean, val totalRamBytes: Long, val allowLowRam: Boolean)

    fun device(ctx: Context): Device {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return Device(Build.SUPPORTED_ABIS.firstOrNull() ?: "", android.os.Process.is64Bit(), mi.totalMem,
            AppSettings.layaAllowLowRam(ctx))
    }

    /**
     * Pure decision (unit-tested): Unsupported (ABI / RAM) > a failed integrity check (NeedsDownload) > files
     * missing (NeedsDownload, with download progress in the message) > Ready.
     */
    fun availabilityFor(
        device: Device, status: Status, download: LayaDownload.State?, verifyError: String?, engineLine: String?,
    ): Availability {
        if (device.primaryAbi !in SUPPORTED_ABIS || !device.is64Bit) {
            return Availability.Unsupported("needs a 64-bit ARM or x86-64 phone (this one is ${device.primaryAbi.ifEmpty { "unknown" }})")
        }
        if (device.totalRamBytes in 1 until MIN_RAM_BYTES && !device.allowLowRam) {
            return Availability.Unsupported("needs at least 3 GB of RAM (this phone has ${gb(device.totalRamBytes)})")
        }
        if (download is LayaDownload.State.Running) {
            return Availability.NeedsDownload(status.missingBytes,
                "downloading the on-device model: ${download.percent}% of ${mb(download.total)}")
        }
        if (verifyError != null) return Availability.NeedsDownload(status.totalBytes, verifyError)
        if (!status.complete) {
            val failed = (download as? LayaDownload.State.Failed)?.message
            return Availability.NeedsDownload(status.missingBytes,
                (if (status.present.isEmpty()) Availability.defaultDownloadMessage(status.missingBytes)
                else "Download the rest of the on-device model (${mb(status.missingBytes)} of ${mb(status.totalBytes)} missing)") +
                    (failed?.let { " (last download failed: $it)" } ?: ""))
        }
        return Availability.Ready(engineLine ?: "model ready (${mb(status.totalBytes)}), loads on first use")
    }

    fun mb(bytes: Long): String = "${Math.round(bytes / 1_000_000.0)} MB"
    private fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)

    // ------------------------------------------------------------------ sha256, once per file version

    /**
     * Verifies every required file's sha256 unless `verified.txt` says this exact (size, mtime) was verified.
     * Returns the names that failed (empty = all good). Blocking; seconds for 700 MB.
     */
    fun verify(dir: File, specs: List<Spec>, log: (String) -> Unit = {}): List<String> {
        val marker = File(dir, VERIFIED)
        val done = if (marker.isFile) marker.readLines().toMutableSet() else mutableSetOf()
        val bad = ArrayList<String>()
        for (s in specs) {
            val f = File(dir, s.name)
            val stamp = "${s.name} ${f.length()} ${f.lastModified()}"
            if (stamp in done) continue
            val started = System.nanoTime()
            val sha = sha256(f)
            log("sha256 ${s.name}: ${if (sha == s.sha256) "ok" else "MISMATCH"} in ${(System.nanoTime() - started) / 1_000_000} ms")
            if (sha == s.sha256) done += stamp else bad += s.name
        }
        marker.writeText(done.joinToString("\n"))
        return bad
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ delete / import

    fun delete(ctx: Context) {
        LayaEngine.releaseIfLoaded()
        dir(ctx).deleteRecursively()
        VcLog.i(TAG, "model files deleted")
    }

    /** Copies the known files found in [src] (debug IMPORT_LAYA / adb push path). Returns the names copied. */
    fun importDir(ctx: Context, src: File): List<String> {
        val known = REQUIRED + SINGLE_GRAPHS.values
        val out = ArrayList<String>()
        for (name in known) {
            val f = File(src, name)
            if (!f.isFile) continue
            f.inputStream().use { install(ctx, name, it) }
            out += name
        }
        return out
    }

    /**
     * Imports files picked with ACTION_OPEN_DOCUMENT: known file names are copied in, a `.zip` is scanned for
     * known names (any folder). Returns the names installed. Blocking (copying 700 MB takes a while).
     */
    fun importUris(ctx: Context, uris: List<Uri>): List<String> {
        val known = (REQUIRED + SINGLE_GRAPHS.values).toSet()
        val out = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(ctx, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: continue
            if (name.endsWith(".zip", ignoreCase = true)) {
                ctx.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw.buffered(1 shl 16)).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            val base = e.name.substringAfterLast('/')
                            if (!e.isDirectory && base in known) { install(ctx, base, zip); out += base }
                        }
                    }
                }
            } else if (name in known) {
                ctx.contentResolver.openInputStream(uri)?.use { install(ctx, name, it) }
                out += name
            } else {
                VcLog.w(TAG, "import: ignoring $name (not a Laya model file)")
            }
        }
        return out
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Writes [input] to `<name>.part` then renames it into place (never a half file under the real name). */
    private fun install(ctx: Context, name: String, input: InputStream) {
        if (name != TABLE_META && name != CONFIG) LayaEngine.releaseIfLoaded()
        val d = dir(ctx).apply { mkdirs() }
        val part = File(d, "$name.part")
        part.outputStream().use { input.copyTo(it, 1 shl 20) }
        val dest = File(d, name)
        dest.delete()
        check(part.renameTo(dest)) { "could not move $name into place" }
        VcLog.i(TAG, "installed $name (${dest.length()} bytes)")
    }

    internal fun resumeInto(part: File, append: Boolean): RandomAccessFile =
        RandomAccessFile(part, "rw").apply { if (append) seek(length()) else setLength(0) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun request(url: String, from: Long): Request = Request.Builder().url(url)
        .apply { if (from > 0) header("Range", "bytes=$from-") }.build()
}
