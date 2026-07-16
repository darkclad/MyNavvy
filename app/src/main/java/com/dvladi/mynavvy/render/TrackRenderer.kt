package com.dvladi.mynavvy.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The perspective "highway" track, drawn onto a Canvas. Ported from the mock's `draw_track`
 * (simrad-mockup/portrait.py), Model B:
 *   - a gray floor plane split by BLACK grid lines (16 columns × 20 perspective rungs);
 *   - a fixed-real-width XTE corridor (±`xte` NM) shown in perspective as the red/green lines;
 *   - `wpRange` = a user-selectable look-ahead "zoom" (the track DEPTH);
 *   - the waypoint dot pinned at the FAR edge while DTW ≥ range, descending toward the boat as
 *     DTW < range; a blue leg boat→waypoint; DTW/XTE labels; a zoom indicator top-left.
 *
 * Owns its Paints; single-threaded (render thread) use.
 */
class TrackRenderer {

    private val floor = SimradGfx.paint(Color.rgb(58, 59, 64))
    private val grid = SimradGfx.paint(Color.rgb(8, 8, 10), fill = false, strokeW = 1f)
    private val redLine = SimradGfx.paint(SimradGfx.RED, fill = false, strokeW = 4f)
    private val greenLine = SimradGfx.paint(SimradGfx.GREEN, fill = false, strokeW = 4f)
    private val courseLine = SimradGfx.paint(Color.argb(125, 210, 211, 214), fill = false, strokeW = 4f)
    private val boatFill = SimradGfx.paint(Color.argb(125, 240, 240, 245))
    private val blueLeg = SimradGfx.paint(Color.argb(125, 58, 134, 200), fill = false, strokeW = 4f)
    private val white = SimradGfx.paint(Color.rgb(250, 250, 252), fill = false, strokeW = 2f)
    private val whiteFill = SimradGfx.paint(Color.rgb(250, 250, 252))
    private val labelP = SimradGfx.textPaint(Color.rgb(244, 246, 249),
        android.graphics.Typeface.DEFAULT_BOLD, Paint.Align.CENTER)
    private val zoomLine = SimradGfx.paint(Color.rgb(160, 164, 170), fill = false, strokeW = 2f)
    private val zoomArrow = SimradGfx.paint(Color.rgb(160, 164, 170))
    private val zoomTxt = SimradGfx.textPaint(Color.rgb(198, 202, 208),
        android.graphics.Typeface.DEFAULT_BOLD, Paint.Align.LEFT)

    fun draw(c: Canvas, rx: Float, ry: Float, rw: Float, rh: Float,
             dtw: Double, wpRange: Double, xte: Double, hasWaypoint: Boolean = false,
             showZoom: Boolean = true) {
        val cx = rx + rw / 2f
        // Stroke/label/icon sizes scale by the SMALLER region dimension — the floor GEOMETRY spans
        // the full width, but scaling chrome by width alone makes it huge on a short, wide (landscape)
        // track. Geometry (halfT/halfB, corridor, grid) stays proportional to rw.
        val ref = min(rw, rh)
        val yF = ry + rh * 0.015f          // far (top, narrow) = wpRange ahead
        val yN = ry + rh                   // near (bottom, wide) = at the boat
        val halfT = 0.12f * rw; val halfB = 0.46f * rw
        val tlx = cx - halfT; val trx = cx + halfT
        val blx = cx - halfB; val brx = cx + halfB
        val nCol = 16; val nRow = 20
        fun lx(y: Float) = tlx + (blx - tlx) * (y - yF) / (yN - yF)
        fun rxAt(y: Float) = trx + (brx - trx) * (y - yF) / (yN - yF)
        fun px(u: Float, y: Float): Float { val l = lx(y); val r = rxAt(y); return l + (u + 1f) * 0.5f * (r - l) }

        // gray floor
        c.drawPath(Path().apply {
            moveTo(lx(yN), yN); lineTo(rxAt(yN), yN); lineTo(rxAt(yF), yF); lineTo(lx(yF), yF); close()
        }, floor)
        // longitudinal grid lines
        for (i in 0..nCol) {
            val u = -1f + 2f * i / nCol
            c.drawLine(px(u, yN), yN, px(u, yF), yF, grid)
        }
        // perspective rungs
        val tVP = (trx - tlx) / ((blx - tlx) - (brx - trx))
        val vpy = yF + tVP * (yN - yF)
        val ratio = Math.pow(((yF - vpy) / (yN - vpy)).toDouble(), 1.0 / nRow)
        for (k in 0..nRow) {
            val y = (vpy + (yN - vpy) * Math.pow(ratio, k.toDouble())).toFloat()
            c.drawLine(lx(y), y, rxAt(y), y, grid)
        }
        // XTE corridor lines (on the 4th/5th block boundary → u=±0.5), full grid height
        // Corridor border lines: in landscape the track is short + very wide, so ref (=rh) makes them
        // too thin — scale by width there instead. Portrait keeps the min-dimension scaling.
        val cw = if (rw > rh) max(6f, rw * 0.006f) else max(4f, ref * 0.013f)
        redLine.strokeWidth = cw; greenLine.strokeWidth = cw
        c.drawLine(px(-0.5f, yN), yN, px(-0.5f, yF), yF, redLine)
        c.drawLine(px(0.5f, yN), yN, px(0.5f, yF), yF, greenLine)

        // waypoint depth: perspective y for distance min(dtw,range) ahead (frac=1 → far edge).
        // Clamp inside the near edge so the dot (radius) never spills below the track.
        val dotR = 6f
        val frac = (min(dtw, wpRange) / wpRange).toFloat()
        val wpy = (vpy + (yN - vpy) * Math.pow(ratio, (frac * nRow).toDouble())).toFloat()
            .coerceIn(yF, yN - dotR)
        val boatY = ry + rh * 0.74f
        val aw = ref * 0.085f; val ah = rh * 0.045f
        val ax = cx; val ay = boatY - ah * 0.45f
        val arrow = Path().apply {
            moveTo(ax, ay); lineTo(ax - aw, ay + ah); lineTo(ax, ay + 0.62f * ah); lineTo(ax + aw, ay + ah); close()
        }
        courseLine.strokeWidth = ref * 0.03f
        c.drawLine(cx, yN, cx, boatY, courseLine)          // course line behind the boat
        c.drawPath(arrow, boatFill)                        // boat arrow fill (transparent)
        // Blue leg + waypoint dot only when a route waypoint is actually active (else nothing
        // "ahead" to draw — and it would otherwise sit on the near edge and poke out below).
        if (hasWaypoint) {
            blueLeg.strokeWidth = if (rw > rh) max(6f, rw * 0.005f) else max(3f, ref * 0.007f)
            c.drawLine(cx, ay - 2f, cx, wpy, blueLeg)      // blue leg boat → waypoint
            c.drawCircle(cx, wpy, dotR, whiteFill)         // waypoint dot
        }
        c.drawPath(arrow, white)                           // boat outline (solid white)

        // XTE / DTW labels along the near edge
        labelP.textSize = ref * 0.045f
        for ((xf, t) in listOf(
            0.13f to String.format(Locale.US, "%.2fNM", xte),
            0.5f to String.format(Locale.US, "%.2fNM", dtw),
            0.87f to String.format(Locale.US, "%.2fNM", xte))) {
            c.drawText(t, rx + xf * rw, yN - 6f - labelP.fontMetrics.descent, labelP)
        }

        // zoom (look-ahead range) indicator, top-left (portrait only; landscape has no clear corner)
        if (!showZoom) return
        val bx = rx + 16f
        c.drawLine(bx, ry + 12f, bx, ry + 46f, zoomLine)
        c.drawPath(Path().apply { moveTo(bx, ry + 9f); lineTo(bx - 4f, ry + 16f); lineTo(bx + 4f, ry + 16f); close() }, zoomArrow)
        c.drawPath(Path().apply { moveTo(bx, ry + 49f); lineTo(bx - 4f, ry + 42f); lineTo(bx + 4f, ry + 42f); close() }, zoomArrow)
        zoomTxt.textSize = ref * 0.05f
        val zt = if (wpRange < 1) String.format(Locale.US, "%.2f NM", wpRange)
                 else String.format(Locale.US, "%g NM", wpRange)
        c.drawText(zt, bx + 9f, ry + 18f - zoomTxt.fontMetrics.ascent, zoomTxt)
    }
}
