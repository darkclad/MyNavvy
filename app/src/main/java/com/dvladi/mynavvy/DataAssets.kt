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
 * Downloader for the app's DATA artifacts — now REGION packages (manifest v2).
 *
 * The APK ships only code; chart regions are published by `publish-mynavvy-data.ps1` under
 * `.../mynavvy/data/regions/<id>/` and listed in `data.json` (`manifestVersion: 2`, `regions[]`).
 * Each installed region lives in `<external files>/regions/<id>/` (see [Regions]); every asset is
 * verified against the manifest SHA-256 before it is put in place, and a `<name>.sha` marker
 * records what we have (so a 700 MB file is never re-hashed just to decide it's current).
 *
 * APK updates themselves are handled externally by Obtainium, not by this class.
 */
object DataAssets {
    private const val TAG = "DataAssets"
    const val MANIFEST_URL = "https://dist.darkclad.org/mynavvy/data/data.json"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Per-region files the region needs before its charts/routing work. */
    private val REQUIRED = listOf("region.json", "charts.mbtiles", "routing_grid.png", "routing_grid.json")

    /**
     * Nice-to-have downloads that must NEVER block a region install. `basemap.mbtiles` is the OSM
     * land seed: present → land detail offline from launch; absent → charts still work and the
     * basemap fills in online via [MbTilesServer]'s read-through cache.
     */
    private val OPTIONAL = listOf("basemap.mbtiles")

    /** Everything we will accept from the manifest (never write an arbitrary name from the network). */
    private val ALLOWED = REQUIRED + OPTIONAL

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "data-dl").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    data class Asset(val name: String, val url: String, val sha256: String, val size: Long)

    /** A published region as described by the manifest (not necessarily installed). */
    data class ManifestRegion(
        val id: String,
        val name: String,
        val sizeBytes: Long,
        val assets: List<Asset>
    )

    /** One outstanding download: an asset belonging to a region. */
    data class Pending(val regionId: String, val regionName: String, val asset: Asset)

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    /** True when no usable region is installed — i.e. the app can't show charts yet. */
    fun dataMissing(ctx: Context): Boolean = Regions.installed(ctx).isEmpty()

    /**
     * Fetch the manifest once and report BOTH: outstanding downloads for regions already installed
     * (missing files, or a sha drift = the pipeline republished), AND regions available to install.
     * Callback runs on the main thread. [force] ignores local markers for installed regions — the
     * "reload charts" cure for a present-but-corrupt file.
     */
    fun checkState(
        ctx: Context,
        force: Boolean = false,
        cb: (pending: List<Pending>, available: List<ManifestRegion>, pendingBytes: Long, error: String?) -> Unit
    ) {
        exec.execute {
            try {
                val manifest = fetchManifest()
                val installedIds = Regions.installed(ctx).map { it.id }.toSet()
                val pending = ArrayList<Pending>()
                for (r in manifest) {
                    if (r.id !in installedIds) continue
                    for (a in r.assets) {
                        if (force || !isCurrent(ctx, r.id, a)) pending.add(Pending(r.id, r.name, a))
                    }
                }
                val available = manifest.filter { it.id !in installedIds }
                val total = pending.sumOf { it.asset.size }
                main.post { cb(pending, available, total, null) }
            } catch (t: Throwable) {
                Log.w(TAG, "manifest fetch failed: ${t.message}")
                main.post { cb(emptyList(), emptyList(), 0L, t.message ?: "failed") }
            }
        }
    }

    /** Full install of one manifest region, as a Pending list for [download]. */
    fun regionDownloads(r: ManifestRegion): List<Pending> = r.assets.map { Pending(r.id, r.name, it) }

    /** What we currently hold on disk, for the reload screen. */
    fun localSummary(ctx: Context): String {
        val regions = Regions.installed(ctx)
        if (regions.isEmpty()) return "No chart regions installed"
        return regions.joinToString("\n\n") { r ->
            val files = ALLOWED.mapNotNull { name ->
                val f = File(r.dir, name)
                when {
                    f.exists() -> String.format("  %s — %.1f MB", name, f.length() / 1048576.0)
                    name in OPTIONAL -> null
                    else -> "  $name — missing"
                }
            }
            "${r.name} (${r.id})\n" + files.joinToString("\n")
        }
    }

    /**
     * Download the given assets sequentially into their region dirs. [onProgress] gets
     * (assetName, bytesDoneOverall, totalOverall); [onDone] gets (success, message). Main thread.
     */
    fun download(
        ctx: Context,
        items: List<Pending>,
        onProgress: (String, Long, Long) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        exec.execute {
            val total = items.sumOf { it.asset.size }
            var done = 0L
            try {
                for (p in items) {
                    val base = done
                    fetchOne(ctx, p) { got -> main.post { onProgress("${p.regionName}: ${p.asset.name}", base + got, total) } }
                    done += p.asset.size
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

    private fun isCurrent(ctx: Context, regionId: String, a: Asset): Boolean {
        val d = Regions.dirFor(ctx, regionId)
        val f = File(d, a.name)
        if (!f.exists()) return false
        // A marker we can't read (missing, or written by adb so owned by another uid -> EACCES)
        // just means "unknown" -> treat as not-current and re-download. Never let it throw and
        // abort the whole manifest check.
        return runCatching {
            File(d, a.name + ".sha").takeIf { it.exists() }
                ?.readText()?.trim()?.equals(a.sha256, ignoreCase = true) == true
        }.getOrDefault(false)
    }

    private fun fetchManifest(): List<ManifestRegion> {
        val j = JSONObject(httpGetText(MANIFEST_URL))
        val pkg = j.optString("package")
        if (pkg != BuildConfig.APPLICATION_ID) throw IllegalStateException("manifest package mismatch: $pkg")
        val ver = j.optInt("manifestVersion", 1)
        if (ver < 2) throw IllegalStateException("manifest v$ver — publish the v2 (regions) manifest")
        val regionsArr = j.getJSONArray("regions")
        val out = ArrayList<ManifestRegion>(regionsArr.length())
        for (i in 0 until regionsArr.length()) {
            val r = regionsArr.getJSONObject(i)
            val id = r.getString("id")
            if (!id.matches(Regex("[a-z0-9-]{1,64}"))) throw IllegalStateException("bad region id: $id")
            val arr = r.getJSONArray("assets")
            val assets = ArrayList<Asset>(arr.length())
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val sha = o.getString("sha256").lowercase()
                if (sha.length != 64) throw IllegalStateException("bad sha256 for ${o.optString("name")}")
                assets.add(Asset(o.getString("name"), o.getString("url"), sha, o.optLong("size", -1L)))
            }
            // Only ever write files we expect — never trust an arbitrary name from the network.
            val allowed = assets.filter { it.name in ALLOWED }
            out.add(ManifestRegion(id, r.optString("name", id), allowed.sumOf { it.size }, allowed))
        }
        return out
    }

    /** Download to a .part file, verify sha256, then move into place and write the marker. */
    private fun fetchOne(ctx: Context, p: Pending, onBytes: (Long) -> Unit) {
        val a = p.asset
        val d = Regions.dirFor(ctx, p.regionId).apply { mkdirs() }
        val dest = File(d, a.name)
        // Unique temp name so we never collide with (or need write access to) a leftover .part
        // that a previous run — or an adb push — may have created under a different uid.
        val part = File(d, a.name + ".part." + System.currentTimeMillis())
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
            // The .sha marker is only a cache hint so a 700 MB file isn't re-hashed on every
            // launch. Writing it must NEVER fail the download — if the real file is in place,
            // the download SUCCEEDED. (An adb-seeded marker owned by another uid throws EACCES.)
            runCatching {
                val marker = File(d, a.name + ".sha")
                marker.delete()
                marker.writeText(a.sha256)
            }.onFailure { Log.w(TAG, "could not write ${a.name}.sha marker: ${it.message}") }
            onBytes(a.size)
            Log.i(TAG, "installed ${p.regionId}/${a.name} (${dest.length()} bytes)")
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
