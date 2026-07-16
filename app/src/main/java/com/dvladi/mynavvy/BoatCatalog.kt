package com.dvladi.mynavvy

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Offline boat catalogue. Reads the fuller `boats.json` downloaded next to the charts if present,
 * otherwise the copy bundled in assets. No live web lookup: it would be brittle, and it would fail
 * exactly when you need it — offline, at sea.
 *
 * Entries are per KEEL VARIANT because the keel (not the model year) sets the draft, and draft is
 * what the router uses to decide which water is safe.
 */
object BoatCatalog {
    private const val TAG = "BoatCatalog"

    data class Entry(
        val id: String,
        val make: String,
        val model: String,
        val keel: String,
        val yearFrom: Int,
        val yearTo: Int,
        val loaM: Double,
        val lwlM: Double,
        val beamM: Double,
        val draftM: Double,
        val displacementKg: Double,
        val source: String
    ) {
        fun label(useFeet: Boolean = false): String {
            val years = if (yearFrom > 0 && yearTo > 0) " $yearFrom-$yearTo" else ""
            val k = if (keel.isNotBlank()) " · $keel keel" else ""
            val draft = if (useFeet) "${String.format("%.1f", draftM * BoatProfile.M_TO_FT)} ft"
                        else "${String.format("%.2f", draftM)} m"
            return "$make $model$years$k · $draft draft"
        }

        fun matches(q: String): Boolean {
            val s = q.trim().lowercase()
            if (s.isEmpty()) return true
            val hay = "$make $model $keel $yearFrom $yearTo".lowercase()
            // every whitespace-separated token must appear somewhere, so "catalina 34 wing" works
            return s.split(Regex("\\s+")).all { tok ->
                hay.contains(tok) || (tok.toIntOrNull()?.let { it in yearFrom..yearTo } == true)
            }
        }

        /** Apply catalogue specs onto a profile, leaving user-owned fields (name, cruise, margin). */
        fun applyTo(p: BoatProfile): BoatProfile = p.copy(
            make = make, model = model, keel = keel,
            year = if (p.year in yearFrom..yearTo) p.year else yearFrom,
            loaM = loaM, lwlM = lwlM, beamM = beamM,
            draftM = draftM, displacementKg = displacementKg
        )
    }

    private var cache: List<Entry>? = null

    fun all(ctx: Context): List<Entry> = cache ?: load(ctx).also { cache = it }

    fun search(ctx: Context, query: String): List<Entry> = all(ctx).filter { it.matches(query) }

    private fun load(ctx: Context): List<Entry> {
        // Downloaded catalogue wins over the bundled seed.
        val downloaded = File(DataAssets.dir(ctx), "boats.json")
        val text = try {
            if (downloaded.exists()) downloaded.readText()
            else ctx.assets.open("boats.json").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "no boat catalogue: ${t.message}"); return emptyList()
        }
        return try {
            val arr = JSONObject(text).getJSONArray("boats")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id"),
                    make = o.optString("make"),
                    model = o.optString("model"),
                    keel = o.optString("keel"),
                    yearFrom = o.optInt("yearFrom", 0),
                    yearTo = o.optInt("yearTo", 0),
                    loaM = o.optDouble("loaM", 0.0),
                    lwlM = o.optDouble("lwlM", 0.0),
                    beamM = o.optDouble("beamM", 0.0),
                    draftM = o.optDouble("draftM", 0.0),
                    displacementKg = o.optDouble("displacementKg", 0.0),
                    source = o.optString("source")
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bad boats.json: ${t.message}"); emptyList()
        }
    }
}
