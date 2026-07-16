package com.dvladi.mynavvy.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.dvladi.mynavvy.game.NavData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the Simrad NSO navigation HUD overlay onto an android.graphics.Canvas (TRANSPARENT
 * background): four angled corner panels (STEER/DEPTH/SOG/COG) forming an octagonal aperture, a
 * centred data-bar box (DTW · WPT · TTG), and a white-tick heading tape with a red triangle cursor.
 *
 * Ported from simrad-nav-mockup/nav.py (portrait) + nav_landscape.py (landscape) — the two mocks use
 * the SAME absolute px, so panel/pad/font metrics are a fixed physical size: mock px * u, with
 * u = density/3 (the mock is 1080px @ xxhdpi). Only the data-bar/tape WIDTH scales with the screen.
 * Portrait/landscape chosen by aspect (w>h), like HelmRenderer. The chart, boat wedge and
 * route are real MapLibre layers underneath — NOT drawn here. Single-threaded (one draw at a time).
 */
class NavHudRenderer {

    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SimradGfx.NAV_PANEL }
    private val panelEdge = SimradGfx.paint(SimradGfx.NAV_PANEL_EDGE, fill = false, strokeW = 2f)
    private val dataBarFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SimradGfx.NAV_DATABAR }
    private val label = SimradGfx.textPaint(SimradGfx.NAV_LABEL, SimradGfx.BOLD, Paint.Align.LEFT)
    private val unit = SimradGfx.textPaint(SimradGfx.NAV_UNIT, align = Paint.Align.RIGHT)
    private val value = SimradGfx.textPaint(SimradGfx.NAV_VALUE, SimradGfx.CONDENSED, Paint.Align.LEFT)
    private val arrow = SimradGfx.paint(SimradGfx.NAV_ARROW)
    private val dbValue = SimradGfx.textPaint(SimradGfx.NAV_VALUE, SimradGfx.BOLD, Paint.Align.LEFT)
    private val dbUnit = SimradGfx.textPaint(SimradGfx.NAV_UNIT, SimradGfx.CONDENSED, Paint.Align.LEFT)
    private val tapeLabel = SimradGfx.textPaint(SimradGfx.NAV_TAPE_LABEL, SimradGfx.BOLD, Paint.Align.CENTER)
    private val tapeHalo = SimradGfx.textPaint(SimradGfx.NAV_TAPE_HALO, SimradGfx.BOLD, Paint.Align.CENTER)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SimradGfx.NAV_TICK_HALO }
    private val cursor = SimradGfx.paint(SimradGfx.NAV_RED)
    private val cursorEdge = SimradGfx.paint(SimradGfx.NAV_RED_EDGE, fill = false, strokeW = 2f)
    private val vb = Rect()
    private val path = Path()
    private val rf = RectF()
    private var insetPx = 0f                          // clears the app's top-left ☰ menu button

    /** [topLeftInsetPx] shifts the STEER panel's content right so the floating menu button doesn't cover it. */
    fun draw(c: Canvas, w: Float, h: Float, d: NavData, density: Float, topLeftInsetPx: Float = 0f) {
        if (w <= 0f || h <= 0f) return
        val u = density / 3f                         // mock px (1080 @ xxhdpi) -> device px
        insetPx = topLeftInsetPx
        drawPanels(c, w, h, d, u)
        val tapeDrop = if (h > w) 80f * u else 0f    // portrait: drop bar+tape so cursor meets panels
        drawDataBar(c, w, h, d, u, tapeDrop)
        val top = 116f * u + tapeDrop
        val midy = top + 70f * u
        if (d.cogDeg != null) drawHeadingTape(c, w, h, d.cogDeg, u, top, midy)
        drawCursor(c, w / 2f, u, top, midy)
    }

    // ---------------------------------------------------------------- corner panels
    private fun drawPanels(c: Canvas, w: Float, h: Float, d: NavData, u: Float) {
        // Both orientations must fit the widest real values (DEPTH "999.9", STEER "-180", SOG "99.9",
        // COG "360"). Landscape has room → WIDER boxes at full value size; portrait is narrow → same
        // box width but a SMALLER value font so the numbers fit with margin.
        val landscape = w > h
        val wp = (if (landscape) 560f else 412f) * u
        val hp = 252f * u
        val dx = (if (landscape) 130f else 112f) * u
        val pad = (if (landscape) 30f else 26f) * u
        val valW = (if (landscape) 430f else 286f) * u
        val valH = (if (landscape) 96f else 80f) * u
        val vcyTop = hp * 0.60f; val vcyBot = h - hp * 0.36f
        val ly = 16f * u; val lyb = h - hp + 16f * u; val uo = 14f * u
        label.textSize = 50f * u; unit.textSize = 34f * u

        // STEER (top-left): label + rudder arrow + signed value, shifted right to clear the ☰ button
        panelPoly(c, floatArrayOf(0f, 0f, wp, 0f, wp - dx, hp, 0f, hp))
        val steerLeft = max(pad, insetPx)
        label.textAlign = Paint.Align.LEFT
        c.drawText("STEER", steerLeft, ly - label.fontMetrics.ascent, label)
        val ay = vcyTop
        path.reset(); path.moveTo(steerLeft, ay); path.lineTo(steerLeft, ay - 26f * u); path.lineTo(steerLeft + 28f * u, ay - 13f * u); path.close()
        c.drawPath(path, arrow)
        val steerTxt = d.steerDeg?.roundToInt()?.toString() ?: "--"
        drawValue(c, steerTxt, vcyTop, valW - 56f * u, valH, leftX = steerLeft + 42f * u,
            color = if (d.steerOnCourse) SimradGfx.NAV_ON_COURSE else SimradGfx.NAV_VALUE)

        // DEPTH (top-right) — omitted in a Chart+Nav split (depth is shown on the chart pane instead).
        if (d.showDepth) {
            panelPoly(c, floatArrayOf(w, 0f, w - wp, 0f, w - wp + dx, hp, w, hp))
            head(c, 'R', h, w, wp, hp, dx, pad, ly, uo, "DEPTH", if (d.unitsFt) "ft" else "m", top = true)
            val depthTxt = when { d.depthDry -> "DRY"; d.depthDisp == null -> "--"; else -> fmt1(d.depthDisp) }
            drawValue(c, depthTxt, vcyTop, valW, valH, rightX = w - pad, color = d.depthColor)
        }

        // SOG (bottom-left)
        panelPoly(c, floatArrayOf(0f, h, wp, h, wp - dx, h - hp, 0f, h - hp))
        head(c, 'L', h, w, wp, hp, dx, pad, lyb, uo, "SOG", "kn", top = false)
        drawValue(c, d.sogKn?.let { fmt1(it) } ?: "--", vcyBot, valW, valH, leftX = pad)

        // COG (bottom-right)
        panelPoly(c, floatArrayOf(w, h, w - wp, h, w - wp + dx, h - hp, w, h - hp))
        head(c, 'R', h, w, wp, hp, dx, pad, lyb, uo, "COG", "°M", top = false)
        drawValue(c, d.cogDeg?.let { deg(it) } ?: "--", vcyBot, valW, valH, rightX = w - pad)
    }

    /** interior x-span of a corner panel at row y; corner side 'L'(left)/'R'(right), top/bottom by [top]. */
    private fun edges(side: Char, h: Float, w: Float, wp: Float, hp: Float, dx: Float, y: Float, top: Boolean): Pair<Float, Float> {
        val t = if (top) y / hp else (h - y) / hp
        return if (side == 'L') 0f to (wp - dx * t) else (w - wp + dx * t) to w
    }

    private fun head(c: Canvas, side: Char, h: Float, w: Float, wp: Float, hp: Float, dx: Float,
                     pad: Float, y: Float, uo: Float, name: String, unitTxt: String, top: Boolean) {
        val (xL, xR) = edges(side, h, w, wp, hp, dx, y, top)
        label.textAlign = Paint.Align.LEFT
        c.drawText(name, xL + pad, y - label.fontMetrics.ascent, label)
        if (unitTxt.isNotEmpty()) {
            unit.textAlign = Paint.Align.RIGHT
            c.drawText(unitTxt, xR - pad, y + uo - unit.fontMetrics.ascent, unit)
        }
    }

    private fun drawValue(c: Canvas, text: String, cy: Float, maxW: Float, maxH: Float,
                          leftX: Float? = null, rightX: Float? = null, color: Int = SimradGfx.NAV_VALUE) {
        value.color = color
        SimradGfx.fitText(value, text, maxW, maxH, outBounds = vb)
        val baseline = cy - (vb.top + vb.bottom) / 2f
        if (rightX != null) { value.textAlign = Paint.Align.RIGHT; c.drawText(text, rightX, baseline, value) }
        else { value.textAlign = Paint.Align.LEFT; c.drawText(text, leftX ?: 0f, baseline, value) }
    }

    private fun panelPoly(c: Canvas, pts: FloatArray) {
        path.reset(); path.moveTo(pts[0], pts[1])
        var i = 2; while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        path.close()
        c.drawPath(path, panelFill); c.drawPath(path, panelEdge)
    }

    // ---------------------------------------------------------------- data bar
    private fun drawDataBar(c: Canvas, w: Float, h: Float, d: NavData, u: Float, tapeDrop: Float) {
        val cx = w / 2f
        val landscape = w > h
        val wp = (if (landscape) 560f else 412f) * u; val dx = (if (landscape) 130f else 112f) * u
        val panelPad = (if (landscape) 30f else 26f) * u
        val hp = 252f * u; val pad0 = 40f * u; val gap = 44f * u
        val dtwTxt = d.dtwNm?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
        val wptTxt = d.wptName ?: "--"
        val ttgTxt = d.ttgHours?.let { clock(it) } ?: "--"
        val left = listOf(Triple(dtwTxt, dbValue, 46f), Triple(" NM", dbUnit, 30f))
        val centre = listOf(Triple(wptTxt, dbValue, 46f))
        val right = listOf(Triple(ttgTxt, dbValue, 40f), Triple(" hrs", dbUnit, 28f))
        fun measure(runs: List<Triple<String, Paint, Float>>, s: Float): Float {
            var t = 0f; for ((txt, p, sz) in runs) { p.textSize = sz * u * s; t += p.measureText(txt) }; return t
        }
        var lw = measure(left, 1f); var cw = measure(centre, 1f); var rw = measure(right, 1f)
        val barMin = max(0f, 0.70f * (w - 2f * wp))
        val desired = max(lw + cw + rw + 2f * gap + 2f * pad0, barMin)
        val depthX = (w - wp) + dx * (16f * u / hp) + panelPad
        val avail = 2f * (depthX - cx - 16f * u)
        var pad = pad0; var s = 1f; var bw = desired
        if (desired > avail && avail > 0f) {
            s = avail / desired; lw = measure(left, s); cw = measure(centre, s); rw = measure(right, s)
            pad *= s; bw = avail
        }
        val bh = max(64f * u, 104f * u * s); val x0 = cx - bw / 2f; val y0 = 4f * u + tapeDrop
        rf.set(x0, y0, x0 + bw, y0 + bh)
        c.drawRoundRect(rf, 12f * u, 12f * u, dataBarFill)
        c.drawRoundRect(rf, 12f * u, 12f * u, panelEdge)
        val base = y0 + bh / 2f
        fun row(runs: List<Triple<String, Paint, Float>>, startX: Float) {
            var x = startX
            for ((txt, p, sz) in runs) {
                p.textSize = sz * u * s; p.textAlign = Paint.Align.LEFT
                val fm = p.fontMetrics
                c.drawText(txt, x, base - (fm.ascent + fm.descent) / 2f, p); x += p.measureText(txt)
            }
        }
        row(left, x0 + pad); row(centre, cx - cw / 2f); row(right, x0 + bw - pad - rw)
    }

    // ---------------------------------------------------------------- heading tape
    private fun drawHeadingTape(c: Canvas, w: Float, h: Float, hdg: Double, u: Float, top: Float, midy: Float) {
        val cx = w / 2f; val wp = (if (w > h) 560f else 412f) * u
        val tw = max(452f * u, 0.70f * (w - 2f * wp)); val ppd = tw / 64f
        val d0 = Math.round(hdg).toInt() - 32
        for (dd in d0..d0 + 64) {
            val px = cx + ((dd - hdg).toFloat()) * ppd
            if (abs(px - cx) > tw / 2f) continue
            val hgt: Float; val wdt: Float; val col: Int
            when {
                dd % 10 == 0 -> { hgt = 30f * u; wdt = 3f; col = SimradGfx.NAV_TICK_MAJOR }
                dd % 5 == 0 -> { hgt = 22f * u; wdt = 2f; col = SimradGfx.NAV_TICK_MID }
                else -> { hgt = 13f * u; wdt = 1f; col = SimradGfx.NAV_TICK_MINOR }
            }
            tickHalo.strokeWidth = wdt + 2f
            c.drawLine(px, midy, px, midy + hgt, tickHalo)
            tick.color = col; tick.strokeWidth = wdt
            c.drawLine(px, midy, px, midy + hgt, tick)
        }
        tapeLabel.textSize = 44f * u; tapeHalo.textSize = 44f * u
        val gy = midy - 58f * u
        val baseline = gy - tapeLabel.fontMetrics.ascent
        for (dd in d0..d0 + 64) {
            if (dd % 30 != 0) continue
            val px = cx + ((dd - hdg).toFloat()) * ppd
            if (abs(px - cx) > tw / 2f - 10f * u) continue
            val t = labelFor(dd)
            for (o in HALO) c.drawText(t, px + o[0] * u, baseline + o[1] * u, tapeHalo)
            c.drawText(t, px, baseline, tapeLabel)
        }
    }

    private fun drawCursor(c: Canvas, cx: Float, u: Float, top: Float, midy: Float) {
        val cw = 32f * u; val tt = top - 2f * u; val bpt = midy - 14f * u
        path.reset(); path.moveTo(cx - cw, tt); path.lineTo(cx + cw, tt); path.lineTo(cx, bpt); path.close()
        c.drawPath(path, cursor); c.drawPath(path, cursorEdge)
    }

    // ---------------------------------------------------------------- helpers
    private fun labelFor(dd: Int): String {
        val n = ((dd % 360) + 360) % 360
        return when (n) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> n.toString() }
    }

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun deg(v: Double) = String.format(Locale.US, "%03d", (v.roundToInt() % 360 + 360) % 360)
    private fun clock(hours: Double): String {
        val s = (hours * 3600).roundToInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    companion object {
        private val HALO = arrayOf(
            floatArrayOf(-2f, 0f), floatArrayOf(2f, 0f), floatArrayOf(0f, -2f),
            floatArrayOf(0f, 2f), floatArrayOf(-2f, -2f), floatArrayOf(2f, 2f)
        )
    }
}
