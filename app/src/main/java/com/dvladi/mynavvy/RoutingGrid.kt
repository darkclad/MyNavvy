package com.dvladi.mynavvy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * Draft-aware navigability sampled from routing_grid.png:
 *   pixel 0      = land         -> blocked
 *   pixel 255    = unknown/deep -> navigable
 *   pixel 1..254 = depth m      -> depth = (v-1)/253 * depthMaxM
 */
class RoutingGrid private constructor(
    private val bmp: Bitmap,
    private val west: Double, private val east: Double,
    private val south: Double, private val north: Double,
    private val w: Int, private val h: Int,
    private val depthMaxM: Double
) {
    /** Charted depth (m); null = unknown/outside; -1 = land. */
    fun depthAt(lat: Double, lon: Double): Double? {
        if (lon < west || lon > east || lat < south || lat > north) return null
        val px = (((lon - west) / (east - west)) * (w - 1)).toInt().coerceIn(0, w - 1)
        val py = (((north - lat) / (north - south)) * (h - 1)).toInt().coerceIn(0, h - 1)
        val v = bmp.getPixel(px, py) and 0xFF
        return when (v) {
            0 -> -1.0
            255 -> null
            else -> (v - 1) / 253.0 * depthMaxM
        }
    }

    fun navigable(lat: Double, lon: Double, draftM: Double, marginM: Double = 0.5): Boolean {
        val d = depthAt(lat, lon) ?: return true   // unknown/outside -> assume navigable
        if (d < 0) return false                     // land
        return d >= draftM + marginM
    }

    companion object {
        fun load(pngFile: File, jsonFile: File): RoutingGrid? {
            if (!pngFile.exists() || !jsonFile.exists()) return null
            val bmp = BitmapFactory.decodeFile(pngFile.path) ?: return null
            val j = JSONObject(jsonFile.readText())
            return RoutingGrid(
                bmp,
                j.getDouble("west"), j.getDouble("east"),
                j.getDouble("south"), j.getDouble("north"),
                j.getInt("width"), j.getInt("height"),
                j.optDouble("depthMaxM", 50.0)
            )
        }
    }
}
