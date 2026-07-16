package com.dvladi.mynavvy

import android.util.Log
import java.io.File

/**
 * Append-only recorder of the boat's track, kept for a configurable retention window (default 6
 * months) so history is available later (a tracks browser is a future feature). Points are written
 * to one CSV file per UTC day (`track-<epochDay>.csv`, lines `epochMs,lat,lon,sogKn,cogDeg`), which
 * makes retention pruning a cheap whole-file delete rather than a rewrite. Recording is throttled by
 * distance/time so a stationary boat doesn't bloat the log.
 *
 * Not a live GPX export (that's the ● record button / RouteManager) — this is the always-on history
 * the WatchService keeps while it runs.
 */
class TrackStore(private val dir: File, var retentionDays: Int = DEFAULT_RETENTION_DAYS) {

    private var lastMs = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    init {
        if (!dir.exists()) dir.mkdirs()
        prune()
    }

    /** Record a fix if it's moved [MIN_MOVE_M] or [MIN_INTERVAL_MS] has passed since the last one. */
    fun add(ms: Long, lat: Double, lon: Double, sogKn: Double, cogDeg: Double?) {
        if (!lastLat.isNaN()) {
            val movedM = haversineM(lastLat, lastLon, lat, lon)
            if (movedM < MIN_MOVE_M && ms - lastMs < MIN_INTERVAL_MS) return
        }
        lastMs = ms; lastLat = lat; lastLon = lon
        val cog = cogDeg?.let { String.format("%.0f", it) } ?: ""
        try {
            File(dir, "track-${ms / DAY_MS}.csv").appendText(
                String.format("%d,%.6f,%.6f,%.1f,%s\n", ms, lat, lon, sogKn, cog)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "track append failed: ${t.message}")
        }
    }

    /** Delete day-files older than the retention window. */
    fun prune() {
        val cutoffDay = System.currentTimeMillis() / DAY_MS - retentionDays
        try {
            dir.listFiles { f -> f.name.startsWith("track-") && f.name.endsWith(".csv") }?.forEach { f ->
                val day = f.name.removePrefix("track-").removeSuffix(".csv").toLongOrNull() ?: return@forEach
                if (day < cutoffDay) f.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "track prune failed: ${t.message}")
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        private const val TAG = "TrackStore"
        private const val DAY_MS = 86_400_000L
        const val DEFAULT_RETENTION_DAYS = 183   // ~6 months
        private const val MIN_MOVE_M = 3.0        // don't log jitter at rest
        private const val MIN_INTERVAL_MS = 30_000L // …but log at least this often when stationary
    }
}
