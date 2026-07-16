package com.dvladi.mynavvy.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Simrad NSO-Evo steering compass, drawn onto a Canvas. Ported ~1:1 from the pygame mock's
 * `draw_compass` (simrad-mockup/simrad.py). Radial order outer→inner:
 *   - FIXED gray outer bezel + 90° screen-cardinal notches + a red STEERING-DIRECTOR arrow at the
 *     top that rotates to (BTW − HDG) — turn that way to close the waypoint.
 *   - a dark GAP, then a SEMI-TRANSPARENT rotating card drawn "heading-down" (HDG at the BOTTOM):
 *     white N/E/S/W + 90°/30° notches, grey degree numbers + 10° minor notches, each label
 *     tangential to its notch; intercardinals inside.
 *   - an INNER black wind disk: red tick ring, a red wind needle (one pointed / one blunt end)
 *     pointing at the wind's from-direction, and the HDG number in the centre.
 *
 * Stateless across frames; owns its Paints (constructed once). Single-threaded use (render thread).
 */
class CompassRenderer {

    private val bezelFill = SimradGfx.paint(Color.rgb(88, 88, 92))
    private val bezelRing = SimradGfx.paint(Color.rgb(150, 152, 156), fill = false, strokeW = 1f)
    private val gapDark = SimradGfx.paint(Color.rgb(13, 13, 15))
    private val bezelNotch = SimradGfx.paint(Color.rgb(18, 18, 20), fill = false, strokeW = 5f)
    private val cardFace = SimradGfx.paint(Color.argb(115, 158, 160, 165))
    private val innerDisk = SimradGfx.paint(Color.rgb(9, 9, 11))
    private val tickMajor = SimradGfx.paint(Color.rgb(236, 240, 245), fill = false, strokeW = 3f)
    private val tickMid = SimradGfx.paint(Color.rgb(236, 240, 245), fill = false, strokeW = 2f)
    private val tickMinor = SimradGfx.paint(Color.rgb(105, 110, 116), fill = false, strokeW = 1f)
    private val degLabel = SimradGfx.textPaint(Color.rgb(150, 155, 161))
    private val cardLabel = SimradGfx.textPaint(Color.rgb(242, 246, 250)).apply { isFakeBoldText = true }
    private val interLabel = SimradGfx.textPaint(Color.rgb(224, 228, 233))
    private val red = SimradGfx.paint(SimradGfx.RED)
    private val windTick = SimradGfx.paint(Color.rgb(150, 95, 90), fill = false, strokeW = 1f)
    private val hdgValue = SimradGfx.textPaint(Color.WHITE, android.graphics.Typeface.DEFAULT_BOLD)
        .apply { isFakeBoldText = true }

    /**
     * @param cx,cy centre; @param ro outer bezel radius.
     * @param hdg heading (COG) → centre number + card rotation; @param btw bearing-to-waypoint →
     *   director arrow; @param windDir wind FROM direction → needle. All nullable (fall back to a
     *   neutral placeholder pose).
     */
    fun draw(c: Canvas, cx: Float, cy: Float, ro: Float,
             hdg: Double?, btw: Double?, windDir: Double?) {
        if (ro <= 0f) return
        val h = hdg ?: 340.0
        val rbz = ro * 0.92f
        val rco = ro * 0.88f
        val rci = ro * 0.64f
        val rwi = rci * 0.48f
        val rwo = rci * 0.60f
        val off = 180.0 - h
        val band = rco - rci

        // fixed gray outer bezel
        c.drawCircle(cx, cy, ro, bezelFill)
        c.drawCircle(cx, cy, ro, bezelRing)
        c.drawCircle(cx, cy, rbz, gapDark)
        for (a in intArrayOf(0, 90, 180, 270)) {
            val o = pos(cx, cy, ro - 1f, a.toDouble()); val i = pos(cx, cy, rbz, a.toDouble())
            c.drawLine(o[0], o[1], i[0], i[1], bezelNotch)
        }

        // rotating card
        c.drawCircle(cx, cy, rco, cardFace)
        c.drawCircle(cx, cy, rci, innerDisk)
        var a = 0
        while (a < 360) {
            val deg = a + off
            val major = a % 90 == 0; val mid = a % 30 == 0
            val ln = if (major) band * 0.42f else if (mid) band * 0.30f else band * 0.16f
            val p = if (major) tickMajor else if (mid) tickMid else tickMinor
            val s = pos(cx, cy, rci, deg); val e = pos(cx, cy, rci + ln, deg)
            c.drawLine(s[0], s[1], e[0], e[1], p)
            a += 5
        }
        val rlbl = rco - band * 0.24f
        degLabel.textSize = ro * 0.077f
        for (d in intArrayOf(30, 60, 120, 150, 210, 240, 300, 330))
            rotatedText(c, d.toString(), pos(cx, cy, rlbl, d + off), (d + off), degLabel)
        cardLabel.textSize = ro * 0.117f
        for ((d, t) in listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W"))
            rotatedText(c, t, pos(cx, cy, rlbl, d + off), (d + off), cardLabel)
        interLabel.textSize = ro * 0.082f
        for ((d, t) in listOf(45 to "NE", 135 to "SE", 225 to "SW", 315 to "NW"))
            rotatedText(c, t, pos(cx, cy, rci * 0.74f, d + off), (d + off), interLabel)

        // red steering-director arrow (rotates to BTW − HDG; null → parks at top)
        val dirDeg = btw?.let { it - h } ?: 0.0
        c.save(); c.rotate(dirDeg.toFloat(), cx, cy)
        val dw = ro * 0.05f
        c.drawPath(Path().apply {
            moveTo(cx - dw, cy - ro); lineTo(cx + dw, cy - ro); lineTo(cx, cy - rbz + 2f); close()
        }, red)
        c.restore()

        // inner wind compass
        var w = 0
        while (w < 360) {
            val s = pos(cx, cy, rwo, w + off); val e = pos(cx, cy, rwi, w + off)
            c.drawLine(s[0], s[1], e[0], e[1], windTick); w += 6
        }
        val needleDeg = windDir?.let { it - h } ?: 0.0
        c.save(); c.rotate(needleDeg.toFloat(), cx, cy)
        val nl = rci - 6f; val rnb = rci * 0.70f
        c.drawPath(Path().apply {   // blunt end (top in default pose)
            moveTo(cx - 8f, cy - rnb); lineTo(cx + 8f, cy - rnb)
            lineTo(cx + 5f, cy - nl); lineTo(cx - 5f, cy - nl); close()
        }, red)
        c.drawPath(Path().apply {   // pointed end → wind from-direction
            moveTo(cx - 8f, cy + rnb); lineTo(cx + 8f, cy + rnb); lineTo(cx, cy + nl); close()
        }, red)
        c.restore()

        // heading number
        hdgValue.textSize = ro * 0.31f
        val txt = String.format(Locale.US, "%03d", (h.roundToInt() % 360 + 360) % 360)
        val fm = hdgValue.fontMetrics
        c.drawText(txt, cx, cy - (fm.ascent + fm.descent) / 2f, hdgValue)
    }

    private fun rotatedText(c: Canvas, s: String, at: FloatArray, deg: Double, p: Paint) {
        c.save(); c.rotate(deg.toFloat(), at[0], at[1])
        val fm = p.fontMetrics
        c.drawText(s, at[0], at[1] - (fm.ascent + fm.descent) / 2f, p)
        c.restore()
    }

    private fun pos(cx: Float, cy: Float, r: Float, deg: Double): FloatArray {
        val rad = Math.toRadians(deg)
        return floatArrayOf((cx + r * sin(rad)).toFloat(), (cy - r * cos(rad)).toFloat())
    }
}
