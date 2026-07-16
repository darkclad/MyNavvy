package com.dvladi.mynavvy

import android.util.Log
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reader + chart overlay for the always-on [TrackStore] history (one CSV per UTC day).
 * The last N day-tracks are drawn as thin dashed lines with the day's date riding along
 * each line; the tracks browser (menu → Tracks) lists every stored day with share /
 * export / delete. Replaces the old manual ● record button — recording never stops.
 */
object TrackHistory {

    const val DAY_MS = 86_400_000L
    private const val TAG = "TrackHistory"
    /** A gap this long (or a jump this far) splits a day into separate drawn segments. */
    private const val SEGMENT_GAP_MS = 15 * 60_000L
    private const val SEGMENT_JUMP_M = 1_000.0

    data class TrackPoint(val ms: Long, val lat: Double, val lon: Double,
                          val sogKn: Double, val cogDeg: Double?)

    data class DayTrack(val epochDay: Long, val points: List<TrackPoint>) {
        val file: String get() = "track-$epochDay.csv"
        fun distanceNm(): Double {
            var d = 0.0
            for (i in 1 until points.size)
                d += GeoUtils.distanceNm(
                    org.maplibre.android.geometry.LatLng(points[i - 1].lat, points[i - 1].lon),
                    org.maplibre.android.geometry.LatLng(points[i].lat, points[i].lon))
            return d
        }
        /** Points split into contiguous runs (new run after a long pause or a big jump). */
        fun segments(): List<List<TrackPoint>> {
            val out = ArrayList<List<TrackPoint>>()
            var cur = ArrayList<TrackPoint>()
            for (p in points) {
                val last = cur.lastOrNull()
                if (last != null && (p.ms - last.ms > SEGMENT_GAP_MS ||
                        GeoUtils.distanceNm(
                            org.maplibre.android.geometry.LatLng(last.lat, last.lon),
                            org.maplibre.android.geometry.LatLng(p.lat, p.lon)) * 1852.0 > SEGMENT_JUMP_M)) {
                    if (cur.size >= 2) out.add(cur)
                    cur = ArrayList()
                }
                cur.add(p)
            }
            if (cur.size >= 2) out.add(cur)
            return out
        }
    }

    /** Epoch-days that have a stored track, newest first. */
    fun listDays(dir: File): List<Long> =
        dir.listFiles { f -> f.name.startsWith("track-") && f.name.endsWith(".csv") }
            ?.mapNotNull { it.name.removePrefix("track-").removeSuffix(".csv").toLongOrNull() }
            ?.sortedDescending() ?: emptyList()

    fun loadDay(dir: File, epochDay: Long): DayTrack {
        val pts = ArrayList<TrackPoint>()
        val f = File(dir, "track-$epochDay.csv")
        try {
            f.forEachLine { line ->
                val c = line.split(',')
                if (c.size >= 4) {
                    val ms = c[0].toLongOrNull() ?: return@forEachLine
                    val lat = c[1].toDoubleOrNull() ?: return@forEachLine
                    val lon = c[2].toDoubleOrNull() ?: return@forEachLine
                    pts.add(TrackPoint(ms, lat, lon,
                        c[3].toDoubleOrNull() ?: 0.0, c.getOrNull(4)?.toDoubleOrNull()))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load $f failed: ${t.message}")
        }
        return DayTrack(epochDay, pts)
    }

    private fun utcFmt(pattern: String) = SimpleDateFormat(pattern, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Short on-chart label, e.g. "Jul 15". */
    fun dayLabel(epochDay: Long): String = utcFmt("MMM d").format(Date(epochDay * DAY_MS))
    /** Browser row date, e.g. "Wed, Jul 15 2026". */
    fun dayTitle(epochDay: Long): String = utcFmt("EEE, MMM d yyyy").format(Date(epochDay * DAY_MS))

    /** The whole day as a JSON blob (for share / export). */
    fun toJson(t: DayTrack): String {
        val sb = StringBuilder()
        sb.append("{\"app\":\"MyNavvy\",\"type\":\"track\",\"date\":\"")
            .append(utcFmt("yyyy-MM-dd").format(Date(t.epochDay * DAY_MS)))
            .append("\",\"points\":[")
        t.points.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append(String.format(Locale.US, "{\"t\":%d,\"lat\":%.6f,\"lon\":%.6f,\"sog\":%.1f", p.ms, p.lat, p.lon, p.sogKn))
            p.cogDeg?.let { sb.append(String.format(Locale.US, ",\"cog\":%.0f", it)) }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    // --- Chart overlay --------------------------------------------------------

    /** Thin dashed history lines + a date label riding along each line. Create while the style
     *  loads, BEFORE RouteManager, so history draws beneath the live trail/route. */
    class Overlay(style: org.maplibre.android.maps.Style) {
        private val source = GeoJsonSource(SRC)

        init {
            style.addSource(source)
            style.addLayer(LineLayer(LYR_LINE, SRC).withProperties(
                PropertyFactory.lineColor("#90A4AE"),
                PropertyFactory.lineWidth(1.3f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineDasharray(arrayOf(1.6f, 2.2f))
            ))
            style.addLayer(SymbolLayer(LYR_LABEL, SRC).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.textField(Expression.get(PROP_DATE)),
                PropertyFactory.textFont(arrayOf("NotoSans-Regular")),
                PropertyFactory.textSize(10f),
                PropertyFactory.textColor("#78909C"),
                PropertyFactory.textHaloColor("#F0F4F5"),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textAllowOverlap(false)
            ))
        }

        /** Replace the drawn history with these day-tracks (empty list clears). */
        fun set(tracks: List<DayTrack>) {
            val feats = ArrayList<Feature>()
            for (t in tracks) {
                val label = dayLabel(t.epochDay)
                for (seg in t.segments()) {
                    feats.add(Feature.fromGeometry(LineString.fromLngLats(
                        seg.map { Point.fromLngLat(it.lon, it.lat) }
                    )).apply { addStringProperty(PROP_DATE, label) })
                }
            }
            source.setGeoJson(FeatureCollection.fromFeatures(feats))
        }

        companion object {
            private const val SRC = "track-history-src"
            private const val LYR_LINE = "track-history-line"
            private const val LYR_LABEL = "track-history-labels"
            private const val PROP_DATE = "date"
        }
    }
}
