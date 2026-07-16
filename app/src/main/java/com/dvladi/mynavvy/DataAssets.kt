package com.dvladi.mynavvy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * First-run downloader for the app's DATA artifacts (offline charts + routing mask).
 *
 * The APK ships only code; `charts.mbtiles` and `routing_grid.*` are published next to it by
 * `publish-mynavvy-data.ps1` and fetched into the app's external files dir on first launch, so a
 * remote tablet needs no adb. Each asset is verified against the SHA-256 in the manifest before it
 * is put in place, and a `<name>.sha` marker records what we have (so we never re-hash a 280 MB
 * file just to decide whether it is current).
 *
 * APK updates themselves are handled externally by Obtainium, not by this class.
 */
object DataAssets {
    private const val TAG = "DataAssets"
    const val MANIFEST_URL = "https://dist.darkclad.org/mynavvy/data/data.json"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Files the app needs before charts/routing work. Absence blocks the map (shows the loader). */
    private val REQUIRED = listOf("charts.mbtiles", "routing_grid.png", "routing_grid.json")

    /**
     * Nice-to-have downloads that must NEVER block first run. `basemap.mbtiles` is the OSM land
     * "home-region seed": present → land detail (roads/towns/marinas) offline from launch; absent →
     * charts still work and the basemap fills in online via [MbTilesServer]'s read-through cache.
     */
    private val OPTIONAL = listOf("basemap.mbtiles")

    /** Everything we will accept from the manifest (never write an arbitrary name from the network). */
    private val ALLOWED = REQUIRED + OPTIONAL

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "data-dl").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    data class Asset(val name: String, val url: String, val sha256: String, val size: Long)

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    /** True when any required data file is absent — i.e. the app can't show charts yet. */
    fun dataMissing(ctx: Context): Boolean = REQUIRED.any { !File(dir(ctx), it).exists() }

    /**
     * Fetch the manifest and report which assets still need downloading (missing, or a sha that
     * differs from our marker) plus their total size. Callback runs on the main thread.
     *
     * [force] ignores the local markers and returns every asset — for an explicit "reload charts",
     * which is the only cure when a file is present but corrupt (a truncated download, a half-copied
     * push) and therefore still looks "current".
     */
    fun checkPending(
        ctx: Context,
        force: Boolean = false,
        cb: (pending: List<Asset>, totalBytes: Long, error: String?) -> Unit
    ) {
        exec.execute {
            try {
                val all = fetchManifest()
                val pending = if (force) all else all.filter { !isCurrent(ctx, it) }
                val total = pending.sumOf { it.size }
                main.post { cb(pending, total, null) }
            } catch (t: Throwable) {
                Log.w(TAG, "manifest fetch failed: ${t.message}")
                main.post { cb(emptyList(), 0L, t.message ?: "failed") }
            }
        }
    }

    /** What we currently hold on disk, for the reload screen. */
    fun localSummary(ctx: Context): String {
        val d = dir(ctx)
        return ALLOWED.joinToString("\n") { name ->
            val f = File(d, name)
            when {
                f.exists() -> String.format("%s — %.1f MB", name, f.length() / 1048576.0)
                name in OPTIONAL -> "$name — optional, not downloaded"
                else -> "$name — missing"
            }
        }
    }

    /**
     * Download the given assets sequentially. [onProgress] gets (assetName, bytesDoneOverall,
     * totalOverall); [onDone] gets (success, message). Both run on the main thread.
     */
    fun download(
        ctx: Context,
        assets: List<Asset>,
        onProgress: (String, Long, Long) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        exec.execute {
            val total = assets.sumOf { it.size }
            var done = 0L
            try {
                for (a in assets) {
                    val base = done
                    fetchOne(ctx, a) { got -> main.post { onProgress(a.name, base + got, total) } }
                    done += a.size
                }
                main.post { onDone(true, "ok") }
            } catch (t: Throwable) {
                Log.e(TAG, "data download failed: ${t.message}")
                Diagnostics.capture(t)
                main.post { onDone(false, t.message ?: "download failed") }
            }
        }
    }

    // --- internals ----------------------------------------------------------

    private fun isCurrent(ctx: Context, a: Asset): Boolean {
        val f = File(dir(ctx), a.name)
        if (!f.exists()) return false
        // A marker we can't read (missing, or written by adb so owned by another uid -> EACCES)
        // just means "unknown" -> treat as not-current and re-download. Never let it throw and
        // abort the whole manifest check.
        return runCatching {
            File(dir(ctx), a.name + ".sha").takeIf { it.exists() }
                ?.readText()?.trim()?.equals(a.sha256, ignoreCase = true) == true
        }.getOrDefault(false)
    }

    private fun fetchManifest(): List<Asset> {
        val j = JSONObject(httpGetText(MANIFEST_URL))
        val pkg = j.optString("package")
        if (pkg != BuildConfig.APPLICATION_ID) throw IllegalStateException("manifest package mismatch: $pkg")
        val arr = j.getJSONArray("assets")
        val out = ArrayList<Asset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sha = o.getString("sha256").lowercase()
            if (sha.length != 64) throw IllegalStateException("bad sha256 for ${o.optString("name")}")
            out.add(Asset(o.getString("name"), o.getString("url"), sha, o.optLong("size", -1L)))
        }
        // Only ever write files we expect — never trust an arbitrary name from the network.
        return out.filter { it.name in ALLOWED }
    }

    /** Download to a .part file, verify sha256, then move into place and write the marker. */
    private fun fetchOne(ctx: Context, a: Asset, onBytes: (Long) -> Unit) {
        val dest = File(dir(ctx), a.name)
        // Unique temp name so we never collide with (or need write access to) a leftover .part
        // that a previous run — or an adb push — may have created under a different uid.
        val part = File(dir(ctx), a.name + ".part." + System.currentTimeMillis())
        part.delete()

        val conn = (URL(a.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode} for ${a.name}")
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L
            var lastTick = 0L
            conn.inputStream.use { ins ->
                part.outputStream().use { outs ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                        md.update(buf, 0, n)
                        got += n
                        if (got - lastTick > 1_000_000) { lastTick = got; onBytes(got) }
                    }
                }
            }
            val actual = md.digest().joinToString("") { String.format("%02x", it) }
            if (!actual.equals(a.sha256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("checksum mismatch for ${a.name}")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                // renameTo can fail across ownership boundaries; fall back to a copy.
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            // The .sha marker is only a cache hint so a 280 MB file isn't re-hashed on every
            // launch. Writing it must NEVER fail the download — if the real file is in place,
            // the download SUCCEEDED. (An adb-seeded marker owned by another uid throws EACCES.)
            runCatching {
                val marker = File(dir(ctx), a.name + ".sha")
                marker.delete()
                marker.writeText(a.sha256)
            }.onFailure { Log.w(TAG, "could not write ${a.name}.sha marker: ${it.message}") }
            onBytes(a.size)
            Log.i(TAG, "installed ${a.name} (${dest.length()} bytes)")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
