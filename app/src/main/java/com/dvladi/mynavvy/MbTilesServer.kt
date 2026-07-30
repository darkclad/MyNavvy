package com.dvladi.mynavvy

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny localhost HTTP server that serves map tiles straight out of an MBTiles
 * (SQLite) file, so the app renders NOAA charts fully offline — no cloud, no
 * Play Services. MapLibre points its style source at http://127.0.0.1:<port>/tiles/{z}/{x}/{y}.pbf
 *
 * MBTiles stores rows in TMS scheme (y=0 at the bottom); MapLibre requests XYZ
 * (y=0 at the top), so we flip the row: tms_y = (2^z - 1) - y.
 *
 * ## Read-through cache (basemap only)
 * When [remoteTilesUrl] is set (the OSM land basemap), a tile miss on the seed file is fetched
 * from our own remote tile server and **persisted into [cacheFile]** before being served. This is
 * the "grow as you cruise" model: any area you view while online is added to the offline basemap
 * for good. Charts pass neither a cache nor a remote, so they behave exactly as before (204 on miss).
 */
class MbTilesServer(
    port: Int,
    /** Offline seed tiles. May be null / not-yet-downloaded: with a [remoteTilesUrl] the server then
     *  runs cache-only, bootstrapping every tile from the network into [cacheFile] (fresh-install land map). */
    seedFile: File?,
    /** Writable side-DB that accumulates tiles fetched from [remoteTilesUrl]. null = no caching. */
    cacheFile: File? = null,
    /** XYZ tile URL template, e.g. "https://basemap.darkclad.org/tiles/{z}/{x}/{y}.pbf". null = offline-only. */
    private val remoteTilesUrl: String? = null,
    /** Called before any remote fetch; skip the network entirely when this returns false. */
    private val isOnline: () -> Boolean = { false }
) : NanoHTTPD("127.0.0.1", port) {

    /** The offline seed DB — null when there's no seed file yet (cache-only / remote-bootstrap mode). */
    private val db: SQLiteDatabase? = seedFile?.takeIf { it.exists() }?.let {
        try { SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY) }
        catch (e: Exception) { Log.w(TAG, "seed open failed (${it.name}): ${e.message}"); null }
    }

    /** Fetched-tile cache. Created on demand so a fresh install starts caching immediately. */
    private val cache: SQLiteDatabase? = cacheFile?.let { openOrCreateCache(it) }

    /** "pbf" (gzipped vector, MVT) or "png"/"jpg" (raster), read from metadata. */
    private val tileFormat: String = readMetadata("format")?.lowercase() ?: "pbf"

    /**
     * The zoom range this file actually contains. The style MUST declare these, not hardcoded
     * values: if the style claims a higher maxzoom than the tiles hold, MapLibre requests tiles
     * that don't exist and the chart goes blank instead of overzooming the deepest ones.
     */
    val minZoom: Int = readMetadata("minzoom")?.toIntOrNull() ?: queryZoom("MIN") ?: 0
    val maxZoom: Int = readMetadata("maxzoom")?.toIntOrNull() ?: queryZoom("MAX") ?: 14

    private fun queryZoom(fn: String): Int? = try {
        db?.rawQuery("SELECT $fn(zoom_level) FROM tiles", null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun readMetadata(name: String): String? = try {
        db?.rawQuery("SELECT value FROM metadata WHERE name = ?", arrayOf(name))?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri // e.g. /tiles/12/704/1614.pbf
        val m = TILE_RE.matchEntire(uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no")

        val z = m.groupValues[1].toInt()
        val x = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt()
        val tmsY = (1 shl z) - 1 - y

        // 1) seed file, 2) already-cached tile, 3) fetch from remote and cache it.
        var data = db?.let { queryTile(it, z, x, tmsY) }
        if (data == null && cache != null) data = queryTile(cache, z, x, tmsY)
        if (data == null && remoteTilesUrl != null && isOnline()) data = fetchRemote(z, x, y, tmsY)

        if (data == null) {
            // 204: MapLibre treats an empty/absent tile as "nothing here", not an error.
            return newFixedLengthResponse(Response.Status.NO_CONTENT, mime(), null, 0)
        }

        val resp = newFixedLengthResponse(
            Response.Status.OK, mime(), ByteArrayInputStream(data), data.size.toLong()
        )
        // Vector tiles in MBTiles are gzip-compressed; tell the client so.
        if (isVector) resp.addHeader("Content-Encoding", "gzip")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun queryTile(src: SQLiteDatabase, z: Int, x: Int, tmsY: Int): ByteArray? = try {
        src.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsY.toString())
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    } catch (e: Exception) {
        Log.e(TAG, "tile query failed", e); null
    }

    /**
     * Fetch one XYZ tile from the remote server and store it in the cache (as TMS). Returns the
     * tile bytes, or null if the remote has no tile there / the fetch failed. Used both by
     * [serve] (on-demand read-through) and [cacheRemoteTile] (bulk prefetch).
     */
    private fun fetchRemote(z: Int, x: Int, y: Int, tmsY: Int): ByteArray? {
        val url = remoteTilesUrl!!
            .replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_CONNECT_MS
                readTimeout = REMOTE_READ_MS
                requestMethod = "GET"
                // Take the stored blob verbatim (gzipped MVT); don't let the stack gunzip it, or it
                // won't match our "Content-Encoding: gzip" when we re-serve it.
                setRequestProperty("Accept-Encoding", "identity")
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            cache?.let { storeTile(it, z, x, tmsY, bytes) }
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "remote fetch $z/$x/$y failed: ${e.message}"); null
        } finally {
            conn.disconnect()
        }
    }

    private fun storeTile(dst: SQLiteDatabase, z: Int, x: Int, tmsY: Int, bytes: ByteArray) {
        try {
            dst.insertWithOnConflict("tiles", null, ContentValues().apply {
                put("zoom_level", z); put("tile_column", x); put("tile_row", tmsY); put("tile_data", bytes)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.w(TAG, "cache insert $z/$x/$tmsY failed: ${e.message}")
        }
    }

    /**
     * Prefetch a single XYZ tile into the cache (skips work if already held). Returns true if a
     * tile is now present locally for that coordinate. Called by the "download this area" button.
     */
    fun cacheRemoteTile(z: Int, x: Int, y: Int): Boolean {
        if (remoteTilesUrl == null || cache == null) return false
        val tmsY = (1 shl z) - 1 - y
        if (db?.let { queryTile(it, z, x, tmsY) } != null || queryTile(cache, z, x, tmsY) != null) return true
        if (!isOnline()) return false
        return fetchRemote(z, x, y, tmsY) != null
    }

    /** True when this server can pull new tiles from a remote (i.e. it's the basemap, not charts). */
    val canPrefetch: Boolean get() = remoteTilesUrl != null && cache != null

    private val isVector get() = tileFormat.startsWith("pbf") || tileFormat == "mvt"

    private fun mime(): String = when {
        isVector -> "application/x-protobuf"
        tileFormat == "jpg" || tileFormat == "jpeg" -> "image/jpeg"
        else -> "image/png"
    }

    private fun openOrCreateCache(f: File): SQLiteDatabase? = try {
        SQLiteDatabase.openOrCreateDatabase(f, null).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, " +
                    "tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))"
            )
            try { enableWriteAheadLogging() } catch (_: Exception) {}
        }
    } catch (e: Exception) {
        Log.w(TAG, "tile cache open failed (${f.name}); read-through disabled: ${e.message}"); null
    }

    override fun stop() {
        super.stop()
        try { db?.close() } catch (_: Exception) {}
        try { cache?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MbTilesServer"
        private val TILE_RE = Regex("""/tiles/(\d+)/(\d+)/(\d+)\.\w+""")
        private const val REMOTE_CONNECT_MS = 6_000
        private const val REMOTE_READ_MS = 8_000
    }
}
