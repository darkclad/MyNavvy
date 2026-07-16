package com.dvladi.mynavvy

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * The "you are here" boat marker, drawn as vector layers (a heading wedge + a haloed dot) rather than
 * MapLibre's built-in location puck. The puck relies on runtime bitmap images, which don't render
 * reliably against this style (see [WeatherOverlay]) — it comes and goes / doesn't show at all. These
 * circle + fill layers always render, so the boat is always visible and never flickers.
 *
 * The dot is a fixed on-screen size (circle radius is in pixels). The heading wedge is ground geometry,
 * so [reproject] rebuilds it from the current metres-per-pixel to hold a steady on-screen size on zoom.
 */
class BoatMarker(style: Style) {

    private val wedgeSrc = GeoJsonSource(SRC_WEDGE)
    private val dotSrc = GeoJsonSource(SRC_DOT)

    private var pos: LatLng? = null
    private var cog: Double? = null
    private var metersPerPixel = 20.0

    init {
        style.addSource(wedgeSrc)
        style.addSource(dotSrc)

        // Heading wedge (only when moving), pointing along COG. Heading-yellow so it reads clearly
        // against the blue water (a blue wedge vanished into the sea).
        style.addLayer(
            FillLayer(LYR_WEDGE, SRC_WEDGE).withProperties(
                PropertyFactory.fillColor("#FFD24D"),
                PropertyFactory.fillOpacity(0.95f),
                PropertyFactory.fillOutlineColor("#12303f")
            )
        )
        // Position dot: white halo under a blue core with a white keyline — reads on any chart tint.
        style.addLayer(
            CircleLayer(LYR_HALO, SRC_DOT).withProperties(
                PropertyFactory.circleRadius(9.5f),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.9f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_CORE, SRC_DOT).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor("#1565C0"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.6f)
            )
        )
    }

    /** New fix: position, course-over-ground (null when stationary/unknown), and current ground scale. */
    fun update(lat: Double, lon: Double, bearing: Double?, metersPerPixel: Double) {
        pos = LatLng(lat, lon)
        cog = bearing
        this.metersPerPixel = metersPerPixel
        rebuild()
    }

    /** Zoom changed: rebuild the heading wedge at the new ground scale (the dot is size-independent). */
    fun reproject(metersPerPixel: Double) {
        if (pos == null) return
        this.metersPerPixel = metersPerPixel
        rebuild()
    }

    fun clear() {
        pos = null
        val empty = FeatureCollection.fromFeatures(emptyList())
        wedgeSrc.setGeoJson(empty); dotSrc.setGeoJson(empty)
    }

    private fun pxToNm(px: Double) = px * metersPerPixel / 1852.0

    private fun rebuild() {
        val p = pos ?: return
        dotSrc.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)))

        val course = cog
        if (course == null) {
            wedgeSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        // Arrow wedge: apex ahead along COG, two corners swept back — a boat pointing where it's going.
        val apex = GeoUtils.destination(p, course, pxToNm(APEX_PX))
        val rearL = GeoUtils.destination(p, (course + 133) % 360, pxToNm(REAR_PX))
        val rearR = GeoUtils.destination(p, (course + 227) % 360, pxToNm(REAR_PX))
        val ring = listOf(apex, rearL, rearR, apex).map { Point.fromLngLat(it.longitude, it.latitude) }
        wedgeSrc.setGeoJson(Polygon.fromLngLats(listOf(ring)))
    }

    companion object {
        private const val SRC_WEDGE = "boat-wedge-src"
        private const val SRC_DOT = "boat-dot-src"
        private const val LYR_WEDGE = "boat-wedge"
        private const val LYR_HALO = "boat-halo"
        private const val LYR_CORE = "boat-core"
        private const val APEX_PX = 22.0   // wedge reach ahead of the boat, in screen pixels
        private const val REAR_PX = 11.0
    }
}
