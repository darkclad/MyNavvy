package com.dvladi.mynavvy

import android.graphics.Color
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.roundToInt

/**
 * Draws the forecast wind field as standard meteorological **wind barbs**, one per grid point.
 * The shaft points toward where the wind blows FROM; ticks encode speed rounded to the nearest 5 kn
 * (half barb = 5 kn, full barb = 10 kn, pennant = 50 kn). Everything is tinted by speed as an extra
 * at-a-glance cue; calm (<3 kn) is just the station dot.
 *
 * Barbs are built as real geographic geometry (line + fill + circle layers) rather than icon symbols:
 * runtime-added bitmap icons don't render reliably against this style, but vector layers always do.
 * Because the geometry is in ground units, [reproject] rebuilds it from the current metres-per-pixel
 * so a barb stays a roughly constant on-screen size as you zoom — call it on camera-idle.
 */
class WeatherOverlay(style: Style) {

    private val precipSrc = GeoJsonSource(SRC_PRECIP) // rain grid points (mm)
    private val barbSrc = GeoJsonSource(SRC_BARB)   // shafts + full/half tick lines
    private val pennantSrc = GeoJsonSource(SRC_PEN) // 50-kn pennant triangles
    private val dotSrc = GeoJsonSource(SRC_DOT)     // station dots

    private var points: List<WeatherRepository.WindPoint> = emptyList()
    private var metersPerPixel = 20.0

    init {
        // Precipitation FIRST so it sits UNDER the wind barbs: a green→yellow→red rain heatmap.
        style.addSource(precipSrc)
        style.addLayer(
            HeatmapLayer(LYR_PRECIP, SRC_PRECIP).withProperties(
                PropertyFactory.heatmapWeight(Expression.interpolate(
                    Expression.linear(), Expression.get("mm"),
                    Expression.stop(0.0, 0.0), Expression.stop(2.0, 0.35),
                    Expression.stop(8.0, 0.8), Expression.stop(20.0, 1.0))),
                PropertyFactory.heatmapIntensity(1.0f),
                PropertyFactory.heatmapRadius(52f),
                PropertyFactory.heatmapOpacity(0.55f),
                PropertyFactory.heatmapColor(Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.stop(0.0, Expression.color(Color.TRANSPARENT)),
                    Expression.stop(0.15, Expression.color(Color.parseColor("#3bd16f"))),
                    Expression.stop(0.4, Expression.color(Color.parseColor("#e8d84a"))),
                    Expression.stop(0.7, Expression.color(Color.parseColor("#e8892a"))),
                    Expression.stop(1.0, Expression.color(Color.parseColor("#d53e4f")))))
            )
        )
        style.addSource(dotSrc)
        style.addSource(pennantSrc)
        style.addSource(barbSrc)

        // White halo under the coloured barb strokes so they read over dark water and pale land.
        style.addLayer(
            LineLayer(LYR_HALO, SRC_BARB).withProperties(
                PropertyFactory.lineColor("#F2FFFFFF"),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            LineLayer(LYR_BARB, SRC_BARB).withProperties(
                PropertyFactory.lineColor(speedRamp()),
                PropertyFactory.lineWidth(2.2f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            FillLayer(LYR_PEN, SRC_PEN).withProperties(
                PropertyFactory.fillColor(speedRamp()),
                PropertyFactory.fillOutlineColor("#F2FFFFFF")
            )
        )
        style.addLayer(
            CircleLayer(LYR_DOT, SRC_DOT).withProperties(
                PropertyFactory.circleRadius(2.6f),
                PropertyFactory.circleColor(speedRamp()),
                PropertyFactory.circleStrokeColor("#F2FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.4f)
            )
        )
    }

    /** New forecast hour: store the points and (re)build geometry at the given ground scale. */
    fun setWind(pts: List<WeatherRepository.WindPoint>, metersPerPixel: Double) {
        points = pts
        this.metersPerPixel = metersPerPixel
        rebuild()
    }

    /** Zoom changed: rebuild the barbs at the new ground scale so they keep their on-screen size. */
    fun reproject(metersPerPixel: Double) {
        if (points.isEmpty()) return
        this.metersPerPixel = metersPerPixel
        rebuild()
    }

    fun clear() {
        points = emptyList()
        val empty = FeatureCollection.fromFeatures(emptyList())
        precipSrc.setGeoJson(empty)
        barbSrc.setGeoJson(empty); pennantSrc.setGeoJson(empty); dotSrc.setGeoJson(empty)
    }

    // --- geometry ----------------------------------------------------------

    private fun pxToNm(px: Double) = px * metersPerPixel / 1852.0

    private fun rebuild() {
        val barbs = ArrayList<Feature>()
        val pennants = ArrayList<Feature>()
        val dots = ArrayList<Feature>()
        val precip = ArrayList<Feature>()

        for (w in points) {
            val station = LatLng(w.lat, w.lon)
            if (w.precipMm > 0.05) precip.add(
                Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat))
                    .apply { addNumberProperty("mm", w.precipMm) })
            val kn = (w.speedKn / 5.0).roundToInt() * 5   // barbs round to the nearest 5 kn
            dots.add(pt(station, w.speedKn))
            if (kn < 3) continue                          // calm: dot only

            val dir = w.dirDeg                            // shaft points toward the wind source
            val tip = GeoUtils.destination(station, dir, pxToNm(SHAFT_PX))
            barbs.add(line(listOf(station, tip), w.speedKn))

            var rem = kn
            var dpx = SHAFT_PX                            // pixel distance from station, working inward
            // Pennants (50 kn) — filled triangles at the tip.
            while (rem >= 50) {
                val b1 = GeoUtils.destination(station, dir, pxToNm(dpx))
                val b2 = GeoUtils.destination(station, dir, pxToNm(dpx - PENNANT_PX))
                val apex = GeoUtils.destination(b1, dir + TICK_DEG, pxToNm(BARB_PX))
                pennants.add(poly(listOf(b1, apex, b2), w.speedKn))
                rem -= 50; dpx -= (PENNANT_PX + 3.0)
            }
            if (kn >= 50) dpx -= 3.0
            // Full barbs (10 kn).
            while (rem >= 10) {
                val base = GeoUtils.destination(station, dir, pxToNm(dpx))
                val end = GeoUtils.destination(base, dir + TICK_DEG, pxToNm(BARB_PX))
                barbs.add(line(listOf(base, end), w.speedKn))
                rem -= 10; dpx -= SPACING_PX
            }
            // Half barb (5 kn) — inset from the tip if it's the only tick, so it's not read as the shaft end.
            if (rem >= 5) {
                if (kn == 5) dpx -= SPACING_PX
                val base = GeoUtils.destination(station, dir, pxToNm(dpx))
                val end = GeoUtils.destination(base, dir + TICK_DEG, pxToNm(BARB_PX * 0.55))
                barbs.add(line(listOf(base, end), w.speedKn))
            }
        }

        precipSrc.setGeoJson(FeatureCollection.fromFeatures(precip))
        barbSrc.setGeoJson(FeatureCollection.fromFeatures(barbs))
        pennantSrc.setGeoJson(FeatureCollection.fromFeatures(pennants))
        dotSrc.setGeoJson(FeatureCollection.fromFeatures(dots))
    }

    private fun pt(p: LatLng, spd: Double) =
        Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply { addNumberProperty("spd", spd) }

    private fun line(pts: List<LatLng>, spd: Double) =
        Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
            .apply { addNumberProperty("spd", spd) }

    private fun poly(ring: List<LatLng>, spd: Double) =
        Feature.fromGeometry(Polygon.fromLngLats(listOf((ring + ring.first()).map {
            Point.fromLngLat(it.longitude, it.latitude)
        }))).apply { addNumberProperty("spd", spd) }

    /** Shared speed → colour ramp (calm blue → gale red), driven by each feature's "spd" property. */
    private fun speedRamp(): Expression = Expression.interpolate(
        Expression.linear(), Expression.get("spd"),
        Expression.stop(0, Expression.color(Color.parseColor("#3288bd"))),
        Expression.stop(8, Expression.color(Color.parseColor("#66c2a5"))),
        Expression.stop(14, Expression.color(Color.parseColor("#8fb84a"))),
        Expression.stop(20, Expression.color(Color.parseColor("#fdae61"))),
        Expression.stop(28, Expression.color(Color.parseColor("#f46d43"))),
        Expression.stop(35, Expression.color(Color.parseColor("#d53e4f")))
    )

    companion object {
        private const val SRC_PRECIP = "wx-precip-src"
        private const val SRC_BARB = "wind-barb-src"
        private const val SRC_PEN = "wind-pen-src"
        private const val SRC_DOT = "wind-dot-src"
        private const val LYR_PRECIP = "wx-precip"
        private const val LYR_HALO = "wind-barb-halo"
        private const val LYR_BARB = "wind-barb"
        private const val LYR_PEN = "wind-pennant"
        private const val LYR_DOT = "wind-dot"

        // On-screen sizes in pixels (converted to ground units per zoom via metres-per-pixel).
        private const val SHAFT_PX = 30.0
        private const val BARB_PX = 13.0
        private const val SPACING_PX = 6.0
        private const val PENNANT_PX = 7.0
        private const val TICK_DEG = 65.0   // tick angle off the shaft, leaning toward the tip
    }
}
