package com.dvladi.mynavvy

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Owns the on-map route (tap-added waypoints) and the recorded GPS track, plus the
 * MapLibre GeoJSON sources/layers that draw them. Also computes leg/route stats and
 * exports GPX. Created once, after the style has loaded.
 */
class RouteManager(style: org.maplibre.android.maps.Style) {

    data class TrackPoint(val lat: Double, val lon: Double, val timeMs: Long)

    /** A route waypoint: a stable id (for hit-test / remove / rename) + position + optional name. */
    data class Waypoint(val id: Long, val ll: LatLng, var name: String?)

    private val waypoints = ArrayList<Waypoint>()
    private var wpSeq = 0L
    private val track = ArrayList<TrackPoint>()
    private val trail = ArrayList<LatLng>()

    private val routeSource = GeoJsonSource(SRC_ROUTE)
    private val wpSource = GeoJsonSource(SRC_WP)
    private val trackSource = GeoJsonSource(SRC_TRACK)
    private val trailSource = GeoJsonSource(SRC_TRAIL)
    private val calcSource = GeoJsonSource(SRC_CALC)

    init {
        style.addSource(trailSource)
        style.addSource(trackSource)
        style.addSource(calcSource)
        style.addSource(routeSource)
        style.addSource(wpSource)

        // Always-on breadcrumb trail — drawn first so it sits beneath the recorded track/route.
        style.addLayer(
            LineLayer(LYR_TRAIL, SRC_TRAIL).withProperties(
                PropertyFactory.lineColor("#4FC3E8"),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.6f)
            )
        )
        style.addLayer(
            LineLayer(LYR_TRACK, SRC_TRACK).withProperties(
                PropertyFactory.lineColor("#7b1fa2"),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            LineLayer(LYR_CALC, SRC_CALC).withProperties(
                PropertyFactory.lineColor("#1faa59"),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.9f)
            )
        )
        style.addLayer(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#e91e63"),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))
            )
        )
        style.addLayer(
            CircleLayer(LYR_WP, SRC_WP).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor("#e91e63"),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
        // Waypoint name labels — only drawn for waypoints that have been named (rename).
        style.addLayer(
            SymbolLayer(LYR_WP_LABEL, SRC_WP).withProperties(
                PropertyFactory.textField(Expression.get(PROP_WNAME)),
                PropertyFactory.textFont(arrayOf("NotoSans-Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#ffffff"),
                PropertyFactory.textHaloColor("#101418"),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
                PropertyFactory.textOptional(true),
                PropertyFactory.textAllowOverlap(false)
            )
        )
        redrawRoute()
    }

    // --- Route (waypoints) --------------------------------------------------

    /** Append a waypoint; returns its new stable id. */
    fun addWaypoint(ll: LatLng): Long {
        val id = ++wpSeq
        waypoints.add(Waypoint(id, ll, null)); redrawRoute(); return id
    }
    fun undoWaypoint() { if (waypoints.isNotEmpty()) waypoints.removeAt(waypoints.size - 1); redrawRoute() }
    fun removeWaypoint(id: Long) { waypoints.removeAll { it.id == id }; redrawRoute() }
    fun renameWaypoint(id: Long, name: String) {
        waypoints.firstOrNull { it.id == id }?.let { it.name = name.trim().ifEmpty { null } }; redrawRoute()
    }
    fun waypointById(id: Long): Waypoint? = waypoints.firstOrNull { it.id == id }
    fun clearRoute() { waypoints.clear(); redrawRoute(); clearComputed() }
    fun waypointCount() = waypoints.size
    fun firstWaypoint(): LatLng? = waypoints.firstOrNull()?.ll
    fun lastWaypoint(): LatLng? = waypoints.lastOrNull()?.ll

    /** Snapshot of the current waypoint positions, in order — for the Passage & Route screen + nav. */
    fun waypoints(): List<LatLng> = waypoints.map { it.ll }

    fun setComputedRoute(pts: List<LatLng>) {
        if (pts.size >= 2) {
            calcSource.setGeoJson(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
        } else clearComputed()
    }
    fun clearComputed() = calcSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))

    fun routeDistanceNm(): Double {
        var d = 0.0
        for (i in 1 until waypoints.size) d += GeoUtils.distanceNm(waypoints[i - 1].ll, waypoints[i].ll)
        return d
    }

    /** Bearing/distance of the final leg, or null if <2 waypoints. */
    fun lastLeg(): Pair<Double, Double>? {
        if (waypoints.size < 2) return null
        val a = waypoints[waypoints.size - 2].ll
        val b = waypoints[waypoints.size - 1].ll
        return GeoUtils.bearingDeg(a, b) to GeoUtils.distanceNm(a, b)
    }

    private fun redrawRoute() {
        wpSource.setGeoJson(FeatureCollection.fromFeatures(
            waypoints.map { wp ->
                Feature.fromGeometry(Point.fromLngLat(wp.ll.longitude, wp.ll.latitude)).apply {
                    addNumberProperty(PROP_WID, wp.id)
                    wp.name?.let { addStringProperty(PROP_WNAME, it) }
                }
            }
        ))
        if (waypoints.size >= 2) {
            routeSource.setGeoJson(LineString.fromLngLats(
                waypoints.map { Point.fromLngLat(it.ll.longitude, it.ll.latitude) }
            ))
        } else {
            routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    // --- Track (recording) --------------------------------------------------

    fun startTrack() { track.clear(); redrawTrack() }
    fun clearTrack() { track.clear(); redrawTrack() }
    fun trackSize() = track.size
    fun trackStartMs() = if (track.isEmpty()) 0L else track.first().timeMs
    fun trackElapsedMs() = if (track.size < 1) 0L else track.last().timeMs - track.first().timeMs

    fun addTrackPoint(lat: Double, lon: Double, timeMs: Long) {
        // Drop near-duplicates: multiple location providers report the same fix.
        track.lastOrNull()?.let { last ->
            val movedM = GeoUtils.distanceNm(LatLng(last.lat, last.lon), LatLng(lat, lon)) * 1852.0
            if (movedM < 2.0 && timeMs - last.timeMs < 3000) return
        }
        track.add(TrackPoint(lat, lon, timeMs))
        redrawTrack()
    }

    fun trackDistanceNm(): Double {
        var d = 0.0
        for (i in 1 until track.size) {
            d += GeoUtils.distanceNm(
                LatLng(track[i - 1].lat, track[i - 1].lon),
                LatLng(track[i].lat, track[i].lon)
            )
        }
        return d
    }

    private fun redrawTrack() {
        if (track.size >= 2) {
            trackSource.setGeoJson(LineString.fromLngLats(
                track.map { Point.fromLngLat(it.lon, it.lat) }
            ))
        } else {
            trackSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    // --- Trail (always-on breadcrumb) ---------------------------------------
    // A rolling line of where the boat has been, kept regardless of GPX recording. Fed
    // every fix; capped so a long passage stays bounded. Cleared on a position discontinuity.

    fun addTrailPoint(lat: Double, lon: Double) {
        trail.lastOrNull()?.let { last ->
            if (GeoUtils.distanceNm(last, LatLng(lat, lon)) * 1852.0 < TRAIL_MIN_M) return
        }
        trail.add(LatLng(lat, lon))
        if (trail.size > TRAIL_MAX) trail.subList(0, trail.size - TRAIL_MAX).clear()
        redrawTrail()
    }

    fun clearTrail() { trail.clear(); redrawTrail() }

    private fun redrawTrail() {
        if (trail.size >= 2) {
            trailSource.setGeoJson(LineString.fromLngLats(
                trail.map { Point.fromLngLat(it.longitude, it.latitude) }
            ))
        } else {
            trailSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    // --- Export -------------------------------------------------------------

    fun hasExportable(): Boolean = waypoints.size >= 1 || track.size >= 1

    fun buildGpx(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MyNavvy\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        if (waypoints.size >= 1) {
            sb.append("  <rte><name>MyNavvy route</name>\n")
            waypoints.forEach {
                sb.append(String.format(Locale.US,
                    "    <rtept lat=\"%.6f\" lon=\"%.6f\"/>\n", it.ll.latitude, it.ll.longitude))
            }
            sb.append("  </rte>\n")
        }
        if (track.size >= 1) {
            sb.append("  <trk><name>MyNavvy track</name><trkseg>\n")
            track.forEach {
                sb.append(String.format(Locale.US,
                    "    <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n",
                    it.lat, it.lon, iso.format(it.timeMs)))
            }
            sb.append("  </trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    companion object {
        private const val SRC_ROUTE = "route-src"
        private const val SRC_WP = "wp-src"
        private const val SRC_TRACK = "track-src"
        private const val SRC_TRAIL = "trail-src"
        private const val SRC_CALC = "calc-src"
        private const val LYR_ROUTE = "route-line"
        /** Public so MainActivity can hit-test waypoints with queryRenderedFeatures. */
        const val LYR_WP = "wp-dots"
        /** Feature property carrying a waypoint's stable id (for tap → manage). */
        const val PROP_WID = "wid"
        private const val PROP_WNAME = "wname"
        private const val LYR_WP_LABEL = "wp-labels"
        private const val LYR_TRACK = "track-line"
        private const val LYR_TRAIL = "trail-line"
        private const val LYR_CALC = "calc-line"

        /** Minimum move between kept trail points (m) — thins the breadcrumb. */
        private const val TRAIL_MIN_M = 3.0
        /** Rolling cap on trail points so a long passage stays bounded. */
        private const val TRAIL_MAX = 4000
    }
}
