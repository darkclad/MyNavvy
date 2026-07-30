package com.dvladi.mynavvy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * One installed chart region: a directory under `<external files>/regions/<id>/` holding
 * charts.mbtiles + routing_grid.png/json + region.json (+ optional basemap.mbtiles), produced
 * by charts-pipeline/build_region.sh and described by its region.json.
 */
data class Region(
    val id: String,
    val name: String,
    val west: Double, val south: Double, val east: Double, val north: Double,
    val centerLat: Double, val centerLon: Double,
    val syntheticMX: Boolean = false,
    val syntheticCA: Boolean = false,
    val generatedUtc: String? = null,
    val dir: File
) {
    val chartsFile: File get() = File(dir, "charts.mbtiles")
    val routingGridPng: File get() = File(dir, "routing_grid.png")
    val routingGridJson: File get() = File(dir, "routing_grid.json")
    val basemapFile: File get() = File(dir, "basemap.mbtiles")

    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    companion object {
        /** Parse a region.json (pipeline schema — keep in sync with make_region_json.py). */
        fun fromJson(json: String, dir: File): Region {
            val o = JSONObject(json)
            val b = o.getJSONObject("bounds")
            val c = o.getJSONObject("center")
            val s = o.optJSONObject("sources")
            return Region(
                id = o.getString("id"),
                name = o.getString("name"),
                west = b.getDouble("west"), south = b.getDouble("south"),
                east = b.getDouble("east"), north = b.getDouble("north"),
                centerLat = c.getDouble("lat"), centerLon = c.getDouble("lon"),
                syntheticMX = s?.optBoolean("syntheticMX") == true,
                syntheticCA = s?.optBoolean("syntheticCA") == true,
                generatedUtc = o.optString("generatedUtc").ifEmpty { null },
                dir = dir
            )
        }
    }
}

/**
 * Installed-region registry + active-region resolution.
 *
 * Active region rules: a manual pin (Config dialog) always wins; otherwise auto-by-GPS picks the
 * installed region containing the boat, with a debounce so a single stray fix can't flip regions.
 * With no pin and no fix, the last-active (or first installed) region is used.
 */
object Regions {
    private const val TAG = "Regions"
    private const val PREFS = "mynavvy_regions"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_AUTO = "auto_by_gps"
    const val LEGACY_ID = "legacy-socal"

    /** Auto-switch: boat must sit outside the active region AND inside the same other installed
     *  region for this long before we switch (one flaky fix must not flip the chart set). */
    private const val SWITCH_DEBOUNCE_MS = 60_000L

    fun baseDir(ctx: Context): File = File(DataAssets.dir(ctx), "regions")

    fun dirFor(ctx: Context, id: String): File = File(baseDir(ctx), id)

    /** Regions on disk that are actually usable (descriptor + charts + routing grid present). */
    fun installed(ctx: Context): List<Region> {
        val base = baseDir(ctx)
        val dirs = base.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val rj = File(d, "region.json")
            if (!rj.exists()) return@mapNotNull null
            runCatching { Region.fromJson(rj.readText(), d) }
                .onFailure { Log.w(TAG, "bad region.json in ${d.name}: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.chartsFile.exists() && it.routingGridPng.exists() && it.routingGridJson.exists() }
        }.sortedBy { it.name }
    }

    fun activeId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    fun setActiveId(ctx: Context, id: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }.apply()
    }

    /** True = follow the boat (auto-switch); false = the user pinned a region manually. */
    fun autoByGps(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoByGps(ctx: Context, auto: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, auto).apply()
    }

    /**
     * The region whose charts should be open right now. Prefers the remembered active id (which a
     * manual pin sets and an auto-switch updates); falls back to the region containing [lastLat]/
     * [lastLon] if given, else the first installed.
     */
    fun active(ctx: Context, lastLat: Double? = null, lastLon: Double? = null): Region? {
        val all = installed(ctx)
        if (all.isEmpty()) return null
        activeId(ctx)?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        if (lastLat != null && lastLon != null) {
            all.firstOrNull { it.contains(lastLat, lastLon) }?.let { setActiveId(ctx, it.id); return it }
        }
        return all.first().also { setActiveId(ctx, it.id) }
    }

    // --- GPS auto-switch (debounced) -----------------------------------------

    private var candidateId: String? = null
    private var candidateSinceMs: Long = 0L

    /**
     * Feed every accepted GPS fix here. Returns the region to switch TO when (a) auto mode is on,
     * (b) the fix has been outside the active region and inside the SAME other installed region
     * continuously for [SWITCH_DEBOUNCE_MS]. Caller performs the actual switch (recreate).
     */
    fun considerAutoSwitch(ctx: Context, activeRegion: Region?, lat: Double, lon: Double): Region? {
        if (!autoByGps(ctx)) { candidateId = null; return null }
        val act = activeRegion ?: return null
        if (act.contains(lat, lon)) { candidateId = null; return null }
        val target = installed(ctx).firstOrNull { it.id != act.id && it.contains(lat, lon) }
        if (target == null) { candidateId = null; return null }   // off all charts: stay put
        val now = System.currentTimeMillis()
        if (candidateId != target.id) {
            candidateId = target.id
            candidateSinceMs = now
            return null
        }
        return if (now - candidateSinceMs >= SWITCH_DEBOUNCE_MS) {
            candidateId = null
            setActiveId(ctx, target.id)
            target
        } else null
    }

    // --- Legacy migration -----------------------------------------------------

    /**
     * One-time move of the pre-multi-region layout (charts.mbtiles etc. directly in the files dir)
     * into regions/legacy-socal/, synthesizing a region.json from the mbtiles' own bounds metadata.
     * Keeps the boat working offline with zero re-download after the app update; a properly built
     * region replaces it via the normal manifest update path. Returns true if migration ran.
     */
    fun migrateLegacy(ctx: Context): Boolean {
        val filesDir = DataAssets.dir(ctx)
        val legacyCharts = File(filesDir, "charts.mbtiles")
        if (!legacyCharts.exists()) return false
        if (installed(ctx).isNotEmpty()) return false   // already migrated / real regions present

        val dest = dirFor(ctx, LEGACY_ID)
        dest.mkdirs()
        val names = listOf(
            "charts.mbtiles", "routing_grid.png", "routing_grid.json", "basemap.mbtiles",
            "charts.mbtiles.sha", "routing_grid.png.sha", "routing_grid.json.sha", "basemap.mbtiles.sha"
        )
        for (n in names) {
            val src = File(filesDir, n)
            if (!src.exists()) continue
            val dst = File(dest, n)
            if (!src.renameTo(dst)) {
                runCatching { src.copyTo(dst, overwrite = true); src.delete() }
                    .onFailure { Log.w(TAG, "migrate: couldn't move $n: ${it.message}") }
            }
        }

        // Bounds: prefer the mbtiles metadata (covers the whole chart set); routing_grid.json as
        // fallback (the old grid only covered a San Diego focus box, but it beats nothing).
        var w = -123.2; var s = 27.6; var e = -111.2; var n = 37.8   // last-ditch: known old SoCal box
        val fromMb = runCatching { mbtilesBounds(File(dest, "charts.mbtiles")) }.getOrNull()
        if (fromMb != null) { w = fromMb[0]; s = fromMb[1]; e = fromMb[2]; n = fromMb[3] }
        else runCatching {
            val g = JSONObject(File(dest, "routing_grid.json").readText())
            w = g.getDouble("west"); s = g.getDouble("south"); e = g.getDouble("east"); n = g.getDouble("north")
        }

        val doc = JSONObject()
            .put("id", LEGACY_ID)
            .put("name", "SoCal (migrated)")
            .put("radiusNm", 300.0)
            .put("center", JSONObject().put("lat", (s + n) / 2).put("lon", (w + e) / 2))
            .put("bounds", JSONObject().put("west", w).put("south", s).put("east", e).put("north", n))
            .put("sources", JSONObject().put("noaaCells", 0)
                .put("syntheticMX", false).put("syntheticCA", false))
        File(dest, "region.json").writeText(doc.toString())
        setActiveId(ctx, LEGACY_ID)
        Log.i(TAG, "migrated legacy chart set to regions/$LEGACY_ID (bounds $w,$s -> $e,$n)")
        return true
    }

    /** [west, south, east, north] from the mbtiles `metadata.bounds` row (tippecanoe writes it). */
    private fun mbtilesBounds(f: File): DoubleArray? {
        if (!f.exists()) return null
        val db = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            it.rawQuery("SELECT value FROM metadata WHERE name='bounds'", null).use { c ->
                if (!c.moveToFirst()) return null
                val parts = c.getString(0).split(",").map { p -> p.trim().toDouble() }
                return if (parts.size == 4) parts.toDoubleArray() else null
            }
        }
    }

    /** Delete an installed region's files. Refuses to delete the last one (no charts, no app). */
    fun delete(ctx: Context, id: String): Boolean {
        val all = installed(ctx)
        if (all.size <= 1) return false
        val dir = dirFor(ctx, id)
        if (!dir.exists()) return false
        dir.deleteRecursively()
        if (activeId(ctx) == id) setActiveId(ctx, null)
        return true
    }
}
