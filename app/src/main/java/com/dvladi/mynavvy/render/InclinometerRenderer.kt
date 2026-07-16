package com.dvladi.mynavvy.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Heel & trim inclinometer, drawn onto a Canvas — an artificial-horizon style attitude gauge fed by
 * the phone's gravity sensor. The horizon rotates with heel (roll) and shifts with trim (pitch);
 * a fixed boat mark + roll scale read against it, with big HEEL / TRIM numerics flanking the dial.
 *
 * Angles in degrees: heel + = starboard (lean right), trim + = bow-down (pitch forward).
 * Owns its Paints; single draw at a time.
 */
class InclinometerRenderer {

    private val bezel = SimradGfx.paint(Color.rgb(24, 26, 30))
    private val bezelRing = SimradGfx.paint(Color.rgb(70, 74, 80), fill = false, strokeW = 3f)
    private val sky = SimradGfx.paint(Color.rgb(46, 78, 108))
    private val sea = SimradGfx.paint(Color.rgb(18, 30, 38))
    private val horizon = SimradGfx.paint(Color.rgb(236, 240, 245), fill = false, strokeW = 3f)
    private val ladder = SimradGfx.paint(Color.rgb(210, 216, 222), fill = false, strokeW = 2f)
    private val tick = SimradGfx.paint(Color.rgb(210, 216, 222), fill = false, strokeW = 3f)
    private val tickMinor = SimradGfx.paint(Color.rgb(140, 148, 156), fill = false, strokeW = 2f)
    private val pointer = SimradGfx.paint(Color.rgb(245, 200, 40))
    private val boat = SimradGfx.paint(Color.rgb(245, 200, 40), fill = false, strokeW = 4f)
    private val boatDot = SimradGfx.paint(Color.rgb(245, 200, 40))
    private val labelP = SimradGfx.textPaint(Color.rgb(150, 160, 168), Typeface.DEFAULT, Paint.Align.CENTER)
    private val valueP = SimradGfx.textPaint(Color.WHITE, SimradGfx.CONDENSED, Paint.Align.CENTER)
        .apply { isFakeBoldText = true }

    fun draw(c: Canvas, rx: Float, ry: Float, rw: Float, rh: Float, heelDeg: Float, trimDeg: Float) {
        val cx = rx + rw / 2f
        val cy = ry + rh / 2f
        val r = min(rh * 0.46f, rw * 0.19f)
        if (r <= 4f) return
        val pxPerDeg = r * 0.03f
        val trim = trimDeg.coerceIn(-25f, 25f)

        // --- rotating horizon, clipped to the dial ---
        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        c.save()
        c.rotate(-heelDeg, cx, cy)
        c.translate(0f, trim * pxPerDeg)
        val big = r * 3f
        c.drawRect(cx - big, cy - big, cx + big, cy, sky)
        c.drawRect(cx - big, cy, cx + big, cy + big, sea)
        c.drawLine(cx - big, cy, cx + big, cy, horizon)
        for (p in intArrayOf(-20, -10, 10, 20)) {           // pitch ladder
            val yy = cy - p * pxPerDeg
            val half = if (p % 20 == 0) r * 0.28f else r * 0.16f
            c.drawLine(cx - half, yy, cx + half, yy, ladder)
        }
        c.restore()
        c.restore()

        // --- fixed roll scale around the top, + bezel ring ---
        c.drawCircle(cx, cy, r, bezelRing)
        for (a in intArrayOf(-45, -30, -20, -10, 0, 10, 20, 30, 45)) {
            val major = a % 30 == 0 || a == 0
            val len = if (major) r * 0.14f else r * 0.09f
            val rad = Math.toRadians(a.toDouble())
            val o0 = r; val o1 = r - len
            val sx = cx + (o0 * sin(rad)).toFloat(); val sy = cy - (o0 * cos(rad)).toFloat()
            val ex = cx + (o1 * sin(rad)).toFloat(); val ey = cy - (o1 * cos(rad)).toFloat()
            c.drawLine(sx, sy, ex, ey, if (major) tick else tickMinor)
        }
        // fixed top pointer (0-heel reference) + rotating heel pointer
        c.drawPath(Path().apply {
            moveTo(cx, cy - r + r * 0.14f); lineTo(cx - r * 0.06f, cy - r + r * 0.02f)
            lineTo(cx + r * 0.06f, cy - r + r * 0.02f); close()
        }, pointer)

        // --- fixed boat symbol (centre reference): wings + dot ---
        c.drawLine(cx - r * 0.42f, cy, cx - r * 0.12f, cy, boat)
        c.drawLine(cx + r * 0.12f, cy, cx + r * 0.42f, cy, boat)
        c.drawCircle(cx, cy, r * 0.04f, boatDot)

        // --- HEEL / TRIM numerics flanking the dial ---
        labelP.textSize = r * 0.20f
        valueP.textSize = r * 0.62f
        val leftCx = (rx + (cx - r)) / 2f
        val rightCx = ((cx + r) + (rx + rw)) / 2f
        drawReadout(c, leftCx, cy, r, "HEEL", fmtHeel(heelDeg))
        drawReadout(c, rightCx, cy, r, "TRIM", fmtTrim(trimDeg))
    }

    private fun drawReadout(c: Canvas, cx: Float, cy: Float, r: Float, label: String, value: String) {
        c.drawText(label, cx, cy - r * 0.42f, labelP)
        val fm = valueP.fontMetrics
        c.drawText(value, cx, cy + r * 0.1f - (fm.ascent + fm.descent) / 2f, valueP)
    }

    private fun fmtHeel(deg: Float): String {
        val v = deg.roundToInt()
        val side = if (v > 0) "S" else if (v < 0) "P" else ""
        return String.format(Locale.US, "%d°%s", abs(v), side)
    }

    private fun fmtTrim(deg: Float): String {
        val v = deg.roundToInt()
        val dir = if (v > 0) "↓" else if (v < 0) "↑" else ""
        return String.format(Locale.US, "%d°%s", abs(v), dir)
    }
}
