package com.dvladi.mynavvy.render

import android.graphics.Canvas
import android.graphics.RectF
import com.dvladi.mynavvy.game.HelmData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the Simrad NSO-Evo steering page onto an android.graphics.Canvas — used from a Compose
 * Canvas (via drawIntoCanvas { it.nativeCanvas }). Two layouts of the same elements, chosen by
 * aspect ratio:
 *   - PORTRAIT (portrait.py): left 2/3 = compass over track; right 1/3 = the six boxes stacked.
 *   - LANDSCAPE (simrad.py): 3 boxes down each side, compass top-centre, a wide track across the
 *     bottom passing under the boxes' empty lower quarter.
 *
 * Field order is canonical (DTW,SOG,TTD,BTW,VMGWPT,DEPTH); portrait stacks all six, landscape
 * splits 3/3. Holds its child renderers/Paints; single-threaded (one draw at a time) use.
 */
class HelmRenderer {

    private val compass = CompassRenderer()
    private val track = TrackRenderer()
    private val box = BoxRenderer()
    private val inclinometer = InclinometerRenderer()
    private val frame = SimradGfx.paint(SimradGfx.FRAME, fill = false, strokeW = 3f)

    companion object {
        /** Minimum even spacing between the stacked portrait data boxes, in dp. */
        const val BOX_GAP_DP = 8f
        /** ☰ menu button bottom (8dp margin + 44dp height); the compass starts just under it. */
        const val MENU_BOTTOM_DP = 54f
    }

    /** [density] px-per-dp, so inter-box spacing is a real dp value regardless of screen. */
    fun draw(c: Canvas, w: Float, h: Float, d: HelmData, density: Float = 3f,
             heelDeg: Float = 0f, trimDeg: Float = 0f) {
        if (w <= 0f || h <= 0f) return
        c.drawColor(SimradGfx.BG)
        if (w > h) drawLandscape(c, w, h, d) else drawPortrait(c, w, h, d, density, heelDeg, trimDeg)
        c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), w * 0.011f, w * 0.011f, frame)
    }

    /** portrait.py: compass+track on the left 2/3, six boxes stacked on the right 1/3. Height-adaptive
     *  so all SIX boxes always fit — even in a short split pane — with a compact compass and a large
     *  track. */
    private fun drawPortrait(c: Canvas, w: Float, h: Float, d: HelmData, density: Float,
                             heelDeg: Float, trimDeg: Float) {
        val m = w * 0.0178f
        val leftW = w * 2f / 3f
        val colX = leftW + w * 0.0067f
        val colW = w - colX - m
        val gap = BOX_GAP_DP * density

        // Heel/trim inclinometer strip reserved at the bottom (bounded so it's neither huge nor tiny).
        val incH = (h * 0.12f).coerceIn(colW * 0.34f, colW * 0.7f)
        val incTop = h - m - incH

        // Right column: SIX boxes sized to fit the height from the top down to the inclinometer, so
        // none are ever clipped. Capped at the old proportion so they don't balloon on a tall screen;
        // BoxRenderer scales its text to the box, so smaller boxes automatically get smaller text.
        val boxAreaBottom = incTop - gap
        val boxH = ((boxAreaBottom - m - 5f * gap) / 6f).coerceIn(colW * 0.30f, colW * 0.82f)
        val fields = buildFields(d)
        for (i in fields.indices)
            drawField(c, colX, m + i * (boxH + gap), colW, boxH, fields[i])

        // Compass: 1.5× smaller than the half-column, freeing the left area for a bigger track.
        val ro = (leftW - 2f * m) / 2f / 1.5f
        val cx = leftW / 2f
        val cy = MENU_BOTTOM_DP * density + ro
        compass.draw(c, cx, cy, ro, d.hdg, d.btw, d.windDir)

        // Track: from below the compass all the way down to the inclinometer — much taller than before.
        val trackTop = cy + ro + m
        track.draw(c, m, trackTop, leftW - 2f * m, boxAreaBottom - trackTop,
            d.dtwNm ?: 0.0, d.wpRangeNm, xteWidth(d), hasWaypoint = d.hasWaypoint)

        // Bottom strip (full width): heel & trim inclinometer.
        inclinometer.draw(c, m, incTop, w - 2f * m, incH, heelDeg, trimDeg)
    }

    /** simrad.py: 3 boxes each side, compass top-centre, a wide track across the bottom. */
    private fun drawLandscape(c: Canvas, w: Float, h: Float, d: HelmData) {
        val m = h * 0.02f
        val cw = w * 0.244f
        val lx = m
        val rx = w - m - cw
        val centreHalf = (w - 2f * (m + cw)) / 2f - m
        val ro = minOf(centreHalf, h * 0.30f)
        val cx = w / 2f
        val cy = m + ro
        val trackTop = cy + ro + m
        track.draw(c, m, trackTop, w - 2f * m, h - trackTop - m,
            d.dtwNm ?: 0.0, d.wpRangeNm, xteWidth(d), hasWaypoint = d.hasWaypoint, showZoom = false)
        compass.draw(c, cx, cy, ro, d.hdg, d.btw, d.windDir)

        val gap = h * 0.0386f
        val boxH = h * 0.221f
        val fields = buildFields(d)
        for (i in 0..2) {
            drawField(c, lx, m + i * (boxH + gap), cw, boxH, fields[i])
            drawField(c, rx, m + i * (boxH + gap), cw, boxH, fields[i + 3])
        }
    }

    // --- shared -------------------------------------------------------------

    private data class Field(val label: String, val unit: String, val value: String, val color: Int)

    private fun buildFields(d: HelmData): List<Field> = listOf(
        Field("DTW", "NM", d.dtwNm?.let { fmt2(it) } ?: "--", SimradGfx.VALUE),
        Field("SOG", "KN", d.sogKn?.let { fmt1(it) } ?: "--", SimradGfx.VALUE),
        Field("TTD", "HRS", d.ttdHours?.let { clock(it) } ?: "---", SimradGfx.VALUE),
        Field("BTW", "°M", d.btw?.let { deg(it) } ?: "---", SimradGfx.VALUE),
        Field("VMGWPT", "KN", d.vmgKn?.let { fmt2(it) } ?: "--", SimradGfx.VALUE),
        Field("DEPTH", if (d.unitsFt) "FT" else "M", d.depthDisp?.let { fmt1(it) } ?: "--", d.depthColor),
    )

    private fun drawField(c: Canvas, x: Float, y: Float, w: Float, h: Float, f: Field) =
        box.draw(c, x, y, w, h, f.label, f.unit, f.value, f.color)

    private fun xteWidth(d: HelmData) = d.xteNm?.let { abs(it) } ?: 0.05

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun deg(v: Double) = String.format(Locale.US, "%03d", (v.roundToInt() % 360 + 360) % 360)
    private fun clock(hours: Double): String {
        val s = (hours * 3600).roundToInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}
