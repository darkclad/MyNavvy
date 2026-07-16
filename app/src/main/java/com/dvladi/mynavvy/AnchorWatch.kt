package com.dvladi.mynavvy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * On-chart anchor watch: the drop point, the swing circle (alarm radius), a live rode line to the
 * boat and a breadcrumb of recent fixes so you can see the swing pattern. Mirrors [RouteManager] —
 * created once after the style has loaded, its layers sitting on top of the chart. MainActivity owns
 * the alarm logic and feeds this the numbers; this class only draws.
 */
class AnchorWatch(style: Style) {

    private var anchor: LatLng? = null
    private var radiusNm: Double = 0.0
    private val crumbs = ArrayList<LatLng>()

    private val anchorSrc = GeoJsonSource(SRC_ANCHOR)
    private val fillSrc = GeoJsonSource(SRC_FILL)
    private val ringSrc = GeoJsonSource(SRC_RING)
    private val rodeSrc = GeoJsonSource(SRC_RODE)
    private val crumbSrc = GeoJsonSource(SRC_CRUMB)

    // Kept so the swing circle can be recoloured green->red when the boat drags.
    private val fillLayer: FillLayer
    private val ringLayer: LineLayer

    init {
        style.addImage(ICON, makeAnchorIcon())
        // Order added = draw order, bottom to top: fill under ring under breadcrumb under rode
        // under the anchor marker (which must never be hidden).
        style.addSource(fillSrc)
        style.addSource(ringSrc)
        style.addSource(crumbSrc)
        style.addSource(rodeSrc)
        style.addSource(anchorSrc)

        fillLayer = FillLayer(LYR_FILL, SRC_FILL).withProperties(
            PropertyFactory.fillColor(HOLD_COLOR),
            PropertyFactory.fillOpacity(0.12f)
        )
        ringLayer = LineLayer(LYR_RING, SRC_RING).withProperties(
            PropertyFactory.lineColor(HOLD_COLOR),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineDasharray(arrayOf(3f, 2f))
        )
        style.addLayer(fillLayer)
        style.addLayer(ringLayer)
        style.addLayer(
            LineLayer(LYR_CRUMB, SRC_CRUMB).withProperties(
                PropertyFactory.lineColor("#ffd24d"),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            LineLayer(LYR_RODE, SRC_RODE).withProperties(
                PropertyFactory.lineColor("#ffffff"),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(0.9f)
            )
        )
        style.addLayer(
            SymbolLayer(LYR_ANCHOR, SRC_ANCHOR).withProperties(
                PropertyFactory.iconImage(ICON),
                PropertyFactory.iconSize(0.9f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    fun isSet(): Boolean = anchor != null
    fun anchorPos(): LatLng? = anchor
    fun radiusNm(): Double = radiusNm

    /** Drop the anchor at [at] with an alarm radius of [radiusNm] nm; clears any old swing. */
    fun drop(at: LatLng, radiusNm: Double) {
        anchor = at
        this.radiusNm = radiusNm
        crumbs.clear()
        setDragging(false)
        anchorSrc.setGeoJson(Feature.fromGeometry(Point.fromLngLat(at.longitude, at.latitude)))
        rodeSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        crumbSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        redrawSwing()
    }

    /** Resize the swing circle without moving the anchor (rode/radius override). */
    fun setRadiusNm(nm: Double) {
        radiusNm = nm
        if (anchor != null) redrawSwing()
    }

    fun raise() {
        anchor = null
        radiusNm = 0.0
        crumbs.clear()
        setDragging(false)
        anchorSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        fillSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        ringSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        rodeSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        crumbSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** Live boat position: redraw the rode line and extend the swing breadcrumb. */
    fun updateBoat(pos: LatLng) {
        val a = anchor ?: return
        rodeSrc.setGeoJson(LineString.fromLngLats(listOf(
            Point.fromLngLat(a.longitude, a.latitude),
            Point.fromLngLat(pos.longitude, pos.latitude)
        )))
        val last = crumbs.lastOrNull()
        // Only record a crumb once the boat has actually moved a metre or so — GPS jitter otherwise
        // fills the buffer with a stationary point.
        if (last == null || GeoUtils.distanceNm(last, pos) * 1852.0 > 1.0) {
            crumbs.add(pos)
            if (crumbs.size > MAX_CRUMBS) crumbs.removeAt(0)
            if (crumbs.size >= 2) {
                crumbSrc.setGeoJson(LineString.fromLngLats(
                    crumbs.map { Point.fromLngLat(it.longitude, it.latitude) }
                ))
            }
        }
    }

    /** Green swing circle while holding; red once the boat is outside it (or grounding). */
    fun setDragging(dragging: Boolean) {
        val c = if (dragging) DRAG_COLOR else HOLD_COLOR
        ringLayer.setProperties(PropertyFactory.lineColor(c))
        fillLayer.setProperties(PropertyFactory.fillColor(c))
    }

    private fun redrawSwing() {
        val a = anchor ?: return
        if (radiusNm <= 0.0) {
            ringSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            fillSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val ring = ArrayList<Point>()
        var b = 0
        while (b <= 360) {
            val p = GeoUtils.destination(a, b.toDouble(), radiusNm)
            ring.add(Point.fromLngLat(p.longitude, p.latitude))
            b += 10
        }
        ringSrc.setGeoJson(LineString.fromLngLats(ring))
        fillSrc.setGeoJson(Polygon.fromLngLats(listOf(ring)))
    }

    /** A small anchor glyph on a pale disc so it stays legible over dark deep water. */
    private fun makeAnchorIcon(): Bitmap {
        val s = 52
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Pale halo behind the glyph.
        c.drawCircle(s / 2f, s / 2f, s * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6F4FBFF")
            style = Paint.Style.FILL
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#12303f")
            strokeWidth = 3.4f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        val cx = s / 2f
        // Ring at the top of the shank.
        c.drawCircle(cx, s * 0.20f, s * 0.075f, p)
        // Shank.
        c.drawLine(cx, s * 0.28f, cx, s * 0.80f, p)
        // Stock (crossbar).
        c.drawLine(s * 0.30f, s * 0.37f, s * 0.70f, s * 0.37f, p)
        // Arms sweeping up to the flukes.
        val arms = Path().apply {
            moveTo(s * 0.22f, s * 0.62f)
            quadTo(s * 0.30f, s * 0.82f, cx, s * 0.80f)
            quadTo(s * 0.70f, s * 0.82f, s * 0.78f, s * 0.62f)
        }
        c.drawPath(arms, p)
        // Fluke barbs.
        c.drawLine(s * 0.22f, s * 0.62f, s * 0.16f, s * 0.55f, p)
        c.drawLine(s * 0.78f, s * 0.62f, s * 0.84f, s * 0.55f, p)
        return bmp
    }

    companion object {
        private const val HOLD_COLOR = "#1faa59" // holding: green
        private const val DRAG_COLOR = "#FF1744" // dragging / grounding: red
        private const val MAX_CRUMBS = 240        // ~ last few hours at 1 crumb per swing metre

        private const val SRC_ANCHOR = "anchor-src"
        private const val SRC_FILL = "anchor-fill-src"
        private const val SRC_RING = "anchor-ring-src"
        private const val SRC_RODE = "anchor-rode-src"
        private const val SRC_CRUMB = "anchor-crumb-src"
        private const val LYR_ANCHOR = "anchor-marker"
        private const val LYR_FILL = "anchor-swing-fill"
        private const val LYR_RING = "anchor-swing-ring"
        private const val LYR_RODE = "anchor-rode"
        private const val LYR_CRUMB = "anchor-crumb"
        private const val ICON = "anchor-icon"
    }
}
