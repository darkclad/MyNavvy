package com.dvladi.mynavvy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * On-chart saved points of interest ("marks"). Mirrors [AnchorWatch]/[RouteManager]: created once
 * after the style has loaded, owning a single symbol layer (pin icon + name label). Distinct from
 * route *waypoints* (which are transient planning points) — a mark is a named place you save and
 * keep. MainActivity drives it (drop at the boat, tap to manage) and owns the prefs blob; this class
 * holds the list, draws it, and serialises to/from JSON.
 */
class MarkStore(style: Style) {

    data class Mark(val id: Long, val lat: Double, val lon: Double, var name: String, val tMs: Long)

    private val marks = ArrayList<Mark>()
    private var seq = 0                       // running counter for auto-names ("Mark N")
    private val src = GeoJsonSource(SRC)

    init {
        style.addImage(ICON, makePinIcon())
        style.addSource(src)
        // Pin tip sits on the exact point (bottom-anchored); the name rides just below it.
        style.addLayer(
            SymbolLayer(LYR, SRC).withProperties(
                PropertyFactory.iconImage(ICON),
                PropertyFactory.iconSize(0.9f),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get(PROP_NAME)),
                PropertyFactory.textFont(arrayOf("NotoSans-Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#ffffff"),
                PropertyFactory.textHaloColor("#101418"),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.4f)),
                PropertyFactory.textOptional(true),
                PropertyFactory.textAllowOverlap(false)
            )
        )
    }

    /** Drop a new mark at [lat]/[lon] with an auto name ("Mark N"); returns the name. */
    fun add(lat: Double, lon: Double, tMs: Long): String {
        val name = "Mark ${++seq}"
        marks.add(Mark(tMs, lat, lon, name, tMs))   // millis timestamp doubles as a stable id
        redraw()
        return name
    }

    fun rename(id: Long, name: String) {
        marks.firstOrNull { it.id == id }?.let { it.name = name.trim().ifEmpty { it.name } }
        redraw()
    }

    fun remove(id: Long) {
        marks.removeAll { it.id == id }
        redraw()
    }

    fun byId(id: Long): Mark? = marks.firstOrNull { it.id == id }

    /** Serialise the whole store (marks + name counter) for SharedPreferences. */
    fun toJson(): String {
        val arr = JSONArray()
        for (m in marks) arr.put(JSONObject().apply {
            put("id", m.id); put("lat", m.lat); put("lon", m.lon); put("name", m.name); put("t", m.tMs)
        })
        return JSONObject().apply { put("seq", seq); put("marks", arr) }.toString()
    }

    /** Replace the store from a [toJson] blob (empty/garbage → no marks). */
    fun loadJson(json: String) {
        marks.clear(); seq = 0
        if (json.isNotBlank()) try {
            val root = JSONObject(json)
            seq = root.optInt("seq", 0)
            val arr = root.optJSONArray("marks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                marks.add(Mark(o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                    o.optString("name", "Mark"), o.optLong("t", o.getLong("id"))))
            }
        } catch (_: Exception) { marks.clear(); seq = 0 }
        redraw()
    }

    private fun redraw() {
        src.setGeoJson(FeatureCollection.fromFeatures(marks.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
                addNumberProperty(PROP_ID, m.id)
                addStringProperty(PROP_NAME, m.name)
            }
        }))
    }

    /** An amber teardrop pin with a white centre dot, on a faint halo so it reads over any chart. */
    private fun makePinIcon(): Bitmap {
        val s = 56
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8F00"); style = Paint.Style.FILL }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2.4f
        }
        // Teardrop: a circle head with a tapered tail down to the tip at the bottom.
        val headCy = s * 0.36f
        val headR = s * 0.26f
        val pin = Path().apply {
            addCircle(cx, headCy, headR, Path.Direction.CW)
            moveTo(cx - headR * 0.72f, headCy + headR * 0.70f)
            lineTo(cx, s * 0.96f)                       // tip on the point
            lineTo(cx + headR * 0.72f, headCy + headR * 0.70f)
            close()
        }
        c.drawPath(pin, body)
        c.drawPath(pin, edge)
        c.drawCircle(cx, headCy, headR * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        })
        return bmp
    }

    companion object {
        const val LYR = "mark-layer"
        const val PROP_ID = "mid"
        private const val SRC = "mark-src"
        private const val PROP_NAME = "mname"
        private const val ICON = "mark-icon"
    }
}
