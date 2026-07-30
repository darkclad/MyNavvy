package com.dvladi.mynavvy

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Day / night display themes for the chart. The chart is the whole screen, so night vision at the
 * helm lives or dies here: [NIGHT] is the modern-marine low-blue look (dark, warm accents, colour
 * information kept), [NIGHT_RED] the traditional red-on-black (max dark adaptation, monochrome).
 *
 * Applied by re-colouring the loaded MapLibre style's layers at runtime (no re-tiling, instant
 * toggle) — the same mechanism [MainActivity.applySafetyShading] already uses for the depth ramp.
 */
enum class ChartTheme(val label: String) {
    DAY("Day"),
    NIGHT("Night"),
    NIGHT_RED("Night (red)");

    val palette: ChartPalette
        get() = when (this) {
            DAY -> ChartPalette.DAY
            NIGHT -> ChartPalette.NIGHT
            NIGHT_RED -> ChartPalette.NIGHT_RED
        }

    /**
     * Re-colour every themed layer of [style] for this theme. [safetyDepthM] (draft + under-keel
     * margin) sets the depth-ramp breakpoints, exactly as the old shader did. Missing layers are
     * skipped, so this is safe against the empty/offline style too.
     */
    fun applyTo(style: Style, safetyDepthM: Double) {
        val p = palette
        // Background + the OSM ocean fill both read as open water.
        style.getLayerAs<BackgroundLayer>("background-land")?.setProperties(PropertyFactory.backgroundColor(p.land))
        style.getLayerAs<FillLayer>("bm-ocean")?.setProperties(PropertyFactory.fillColor(p.deep))

        // Depth-graded water: DEPARE (open) + DRGARE (dredged channel) share one ramp.
        val depthFill = PropertyFactory.fillColor(
            Expression.step(
                Expression.coalesce(
                    Expression.toNumber(Expression.get("DRVAL1")),
                    Expression.literal(-999.0)
                ),
                Expression.color(p.deep),                               // unknown == deep (no seam offshore)
                Expression.stop(-100, Expression.color(p.dries)),       // dries at low water
                Expression.stop(0, Expression.color(p.shoal)),          // < safety: shallow
                Expression.stop(safetyDepthM, Expression.color(p.safe)),
                Expression.stop(safetyDepthM * 2.0, Expression.color(p.mid)),
                Expression.stop(safetyDepthM * 4.0, Expression.color(p.deep))
            )
        )
        for (b in 1..6) {
            style.getLayerAs<FillLayer>("DEPARE-b$b")?.setProperties(depthFill)
            style.getLayerAs<FillLayer>("DRGARE-b$b")?.setProperties(depthFill)
            style.getLayerAs<FillLayer>("LNDARE-b$b")?.setProperties(
                PropertyFactory.fillColor(p.land), PropertyFactory.fillOutlineColor(p.landEdge))
            style.getLayerAs<LineLayer>("DEPCNT-b$b")?.setProperties(
                PropertyFactory.lineColor(p.contour), PropertyFactory.lineOpacity(p.contourOpacity))
            style.getLayerAs<LineLayer>("COALNE-b$b")?.setProperties(PropertyFactory.lineColor(p.coast))
            style.getLayerAs<SymbolLayer>("SOUNDG-lbl-b$b")?.setProperties(
                PropertyFactory.textColor(p.soundingText), PropertyFactory.textHaloColor(p.soundingHalo))
            style.getLayerAs<SymbolLayer>("DEPCNT-lbl-b$b")?.setProperties(
                PropertyFactory.textColor(p.contour), PropertyFactory.textHaloColor(p.soundingHalo))
        }
        style.getLayerAs<CircleLayer>("navaids")?.setProperties(
            PropertyFactory.circleColor(p.navaid), PropertyFactory.circleStrokeColor(p.markStroke))
        style.getLayerAs<CircleLayer>("buoys-lateral")?.setProperties(
            PropertyFactory.circleColor(p.buoy), PropertyFactory.circleStrokeColor(p.markStroke))

        // OSM land detail (roads/towns/parks/labels) is day-coloured and bright; at night hide it
        // entirely — you navigate on water/depth/marks, and it removes a huge swath of bright pixels.
        val vis = PropertyFactory.visibility(if (p.hideBasemapDetail) Property.NONE else Property.VISIBLE)
        for (id in BASEMAP_DETAIL_LAYERS) style.getLayer(id)?.setProperties(vis)
    }

    companion object {
        fun from(name: String?): ChartTheme = runCatching { valueOf(name ?: DAY.name) }.getOrDefault(DAY)

        private val BASEMAP_DETAIL_LAYERS = arrayOf(
            "bm-landcover", "bm-park", "bm-water", "bm-waterway", "bm-building",
            "bm-road-casing", "bm-road", "bm-water-label", "bm-road-label",
            "bm-marina", "bm-marina-label", "bm-place-minor", "bm-place-major"
        )
    }
}

/**
 * What the user picked in Settings → Display. [AUTO] follows the sun (day / modern-night); the other
 * three force a specific [ChartTheme]. Night-red is manual-only — Auto never picks it (it's a taste,
 * not a lighting condition).
 */
enum class ThemeMode(val label: String) {
    AUTO("Auto"),
    DAY("Day"),
    NIGHT("Night"),
    NIGHT_RED("Night (red)");

    companion object {
        fun from(name: String?): ThemeMode = runCatching { valueOf(name ?: AUTO.name) }.getOrDefault(AUTO)
    }
}

/**
 * Low-precision solar position — the OFFLINE FALLBACK for Auto day/night when the forecast's own
 * sunrise/sunset isn't cached for now. Standard J2000 approximation (good to ~arcminutes for decades
 * around 2000). Switches at the geometric horizon incl. refraction (-0.833°), i.e. sunrise/sunset,
 * to match the weather feed's times (which is the primary source).
 */
object SolarClock {
    private const val SUNSET_DEG = -0.833   // sun's centre at sunrise/sunset (atmospheric refraction)

    fun isNight(lat: Double, lon: Double, utcMillis: Long): Boolean =
        elevationDeg(lat, lon, utcMillis) < SUNSET_DEG

    /** Sun's elevation above the horizon (degrees) at [lat]/[lon] and the given UTC instant. */
    fun elevationDeg(lat: Double, lon: Double, utcMillis: Long): Double {
        val d = utcMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0   // days since J2000.0
        val g = Math.toRadians((357.529 + 0.98560028 * d).mod(360.0))  // sun mean anomaly
        val q = 280.459 + 0.98564736 * d                               // sun mean longitude
        val lSun = Math.toRadians((q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)).mod(360.0)) // ecliptic lon
        val e = Math.toRadians(23.439 - 0.00000036 * d)                // obliquity
        val ra = Math.atan2(Math.cos(e) * Math.sin(lSun), Math.cos(lSun))   // right ascension (rad)
        val dec = Math.asin(Math.sin(e) * Math.sin(lSun))                    // declination (rad)
        val gmst = (18.697374558 + 24.06570982441908 * d).mod(24.0)          // Greenwich sidereal (h)
        val lst = Math.toRadians(((gmst + lon / 15.0).mod(24.0)) * 15.0)     // local sidereal (rad)
        val ha = lst - ra                                                    // hour angle (rad)
        val latR = Math.toRadians(lat)
        val sinAlt = Math.sin(latR) * Math.sin(dec) + Math.cos(latR) * Math.cos(dec) * Math.cos(ha)
        return Math.toDegrees(Math.asin(sinAlt.coerceIn(-1.0, 1.0)))
    }
}

/** Every chart colour for one [ChartTheme] (ARGB ints), plus its screen-brightness + basemap rule. */
data class ChartPalette(
    val deep: Int, val mid: Int, val safe: Int, val shoal: Int, val dries: Int,
    val land: Int, val landEdge: Int, val coast: Int,
    val contour: Int, val contourOpacity: Float,
    val soundingText: Int, val soundingHalo: Int,
    val navaid: Int, val buoy: Int, val markStroke: Int,
    val hideBasemapDetail: Boolean,
    /** Window brightness 0..1, or -1 = leave to the system (day). */
    val screenBrightness: Float,
) {
    companion object {
        private fun c(hex: String) = Color.parseColor(hex)

        val DAY = ChartPalette(
            deep = c("#1b5e91"), mid = c("#4a90c2"), safe = c("#8fc0e2"), shoal = c("#cfe6f5"), dries = c("#7cc47f"),
            land = c("#f2e39c"), landEdge = c("#c9b063"), coast = c("#6b5a28"),
            contour = c("#ffffff"), contourOpacity = 0.45f, soundingText = c("#0d2230"), soundingHalo = c("#ffffff"),
            navaid = c("#d21f1f"), buoy = c("#1f7a1f"), markStroke = c("#ffffff"),
            hideBasemapDetail = false, screenBrightness = -1f)

        // Modern marine, low-blue: dark warm chart, colour information kept, amber accents.
        val NIGHT = ChartPalette(
            deep = c("#060a0e"), mid = c("#0e1c27"), safe = c("#17313f"), shoal = c("#2b5468"), dries = c("#26402a"),
            land = c("#2a2620"), landEdge = c("#4a3f28"), coast = c("#4a3f28"),
            contour = c("#7a5a2a"), contourOpacity = 0.65f, soundingText = c("#9c7a3c"), soundingHalo = c("#05080b"),
            navaid = c("#8a2a2a"), buoy = c("#2c6a2c"), markStroke = c("#4a3f28"),
            hideBasemapDetail = true, screenBrightness = 0.28f)

        // Traditional red-on-black: maximum dark adaptation, monochrome red.
        val NIGHT_RED = ChartPalette(
            deep = c("#060000"), mid = c("#210808"), safe = c("#3a0f0f"), shoal = c("#6a1c1c"), dries = c("#6e3a1a"),
            land = c("#2a1008"), landEdge = c("#8a3320"), coast = c("#8a3320"),
            contour = c("#9a3232"), contourOpacity = 0.7f, soundingText = c("#b24a4a"), soundingHalo = c("#050000"),
            navaid = c("#c84040"), buoy = c("#8a3a3a"), markStroke = c("#8a3320"),
            hideBasemapDetail = true, screenBrightness = 0.18f)
    }
}
