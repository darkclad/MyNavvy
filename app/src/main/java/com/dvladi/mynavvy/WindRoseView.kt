package com.dvladi.mynavvy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A boat-fixed relative wind-angle dial (Veratron style): the ring reads 0° at the bow (top), 180°
 * at the stern (bottom), 90° at each beam, mirrored port (left) / starboard (right). The boat stays
 * pointing up; the true- and apparent-wind markers move to their angle off the bow. HDG shows in the
 * centre, with AWA (cyan) and TWA (yellow) call-out boxes below it.
 *
 * All angles are signed relative to the bow: negative = port, positive = starboard, ±180 = astern.
 */
class WindRoseView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var headingDeg: Double? = null
    private var twaDeg: Double? = null
    private var awaDeg: Double? = null

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3a4650"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA6B4"); strokeWidth = 3f
    }
    private val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55636d"); strokeWidth = 2f
    }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c9d3da"); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val stbdArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#43a047"); style = Paint.Style.STROKE; strokeWidth = 12f
    }
    private val portArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e53935"); style = Paint.Style.STROKE; strokeWidth = 12f
    }
    private val boat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4aa3ff"); style = Paint.Style.FILL
    }
    private val twMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffd54f"); style = Paint.Style.FILL
    }
    private val awMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#29b6f6"); style = Paint.Style.FILL
    }
    private val hdgLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA6B4"); textSize = 26f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val hdgValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 64f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true
    }
    private val boxText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 30f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setData(headingDeg: Double?, twaDeg: Double?, awaDeg: Double?) {
        this.headingDeg = headingDeg
        this.twaDeg = twaDeg
        this.awaDeg = awaDeg
        invalidate()
    }

    /** Recolour the dial chrome for the day (light) or night (dark) Helm theme. The semantic
     *  colours (port/stbd arcs, wind markers, boat) read on both and are left alone. */
    fun applyTheme(day: Boolean) {
        ring.color = Color.parseColor(if (day) "#9AA8B2" else "#3a4650")
        tick.color = Color.parseColor(if (day) "#4A5A64" else "#8FA6B4")
        tickMinor.color = Color.parseColor(if (day) "#AEBCC4" else "#55636d")
        labelP.color = Color.parseColor(if (day) "#2C3A43" else "#c9d3da")
        hdgLabel.color = Color.parseColor(if (day) "#4A5A64" else "#8FA6B4")
        hdgValue.color = Color.parseColor(if (day) "#0E1519" else "#FFFFFF")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 30f
        if (r <= 0) return

        canvas.drawCircle(cx, cy, r, ring)

        // Ticks every 15°, labels every 30°, mirrored port/starboard.
        var a = 0
        while (a <= 180) {
            for (sign in intArrayOf(1, -1)) {
                if ((a == 0 || a == 180) && sign == -1) continue
                val rel = a * sign
                val major = a % 30 == 0
                val outer = r
                val inner = r - if (major) 18f else 10f
                val (sx, sy) = ringXY(cx, cy, outer, rel.toDouble())
                val (ix, iy) = ringXY(cx, cy, inner, rel.toDouble())
                canvas.drawLine(sx, sy, ix, iy, if (major) tick else tickMinor)
                if (major) {
                    val (lx, ly) = ringXY(cx, cy, r - 40f, rel.toDouble())
                    canvas.drawText(a.toString(), lx, ly + 8f, labelP)
                }
            }
            a += 15
        }

        // Port / starboard tack arcs across the top (upwind sector).
        val arcRect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, -60f, 60f, false, stbdArc)   // top → starboard
        canvas.drawArc(arcRect, -120f, 60f, false, portArc)  // top → port

        // Boat icon at the bow (fixed, pointing up).
        val bl = r * 0.16f
        val boatPath = Path().apply {
            moveTo(cx, cy - r * 0.62f)
            lineTo(cx - bl * 0.7f, cy - r * 0.62f + bl * 1.5f)
            lineTo(cx + bl * 0.7f, cy - r * 0.62f + bl * 1.5f)
            close()
        }
        canvas.drawPath(boatPath, boat)

        // Wind markers (arrowheads on the ring pointing inward = wind coming FROM there).
        twaDeg?.let { drawMarker(canvas, cx, cy, r, it, twMark) }
        awaDeg?.let { drawMarker(canvas, cx, cy, r, it, awMark) }

        // Centre HDG — proportional sizing + placement above centre so it never collides with the
        // AWA/TWA boxes below.
        hdgLabel.textSize = (r * 0.13f).coerceIn(16f, 28f)
        hdgValue.textSize = (r * 0.30f).coerceIn(34f, 74f)
        canvas.drawText("HDG", cx, cy - r * 0.30f, hdgLabel)
        val hdgTxt = headingDeg?.let { String.format(Locale.US, "%03d°", it.roundToInt() % 360) } ?: "---°"
        canvas.drawText(hdgTxt, cx, cy - r * 0.30f + hdgValue.textSize, hdgValue)

        // AWA / TWA call-out boxes below the centre.
        val boxW = r * 0.66f
        val boxH = (r * 0.16f).coerceIn(34f, 52f)
        val boxTop = cy + r * 0.20f
        boxText.textSize = boxH * 0.52f
        val gap = 8f
        drawBox(canvas, cx - boxW - gap / 2, boxTop, boxW, boxH,
            Color.parseColor("#29b6f6"), "AWA", awaDeg)
        drawBox(canvas, cx + gap / 2, boxTop, boxW, boxH,
            Color.parseColor("#ffd54f"), "TWA", twaDeg)
    }

    private fun drawBox(c: Canvas, left: Float, top: Float, w: Float, h: Float,
                        color: Int, label: String, angle: Double?) {
        boxFill.color = color
        c.drawRoundRect(RectF(left, top, left + w, top + h), 6f, 6f, boxFill)
        val txt = angle?.let { String.format(Locale.US, "%s %+d°", label, it.roundToInt()) } ?: "$label --"
        c.drawText(txt, left + w / 2f, top + h * 0.68f, boxText)
    }

    private fun drawMarker(c: Canvas, cx: Float, cy: Float, r: Float, rel: Double, paint: Paint) {
        val (px, py) = ringXY(cx, cy, r, rel)
        val (ix, iy) = ringXY(cx, cy, r - 26f, rel)
        // Wedge pointing inward.
        val perp = Math.toRadians(rel + 90.0)
        val hw = 12f
        val path = Path().apply {
            moveTo(ix, iy)
            lineTo((px + hw * sin(perp)).toFloat(), (py - hw * cos(perp)).toFloat())
            lineTo((px - hw * sin(perp)).toFloat(), (py + hw * cos(perp)).toFloat())
            close()
        }
        c.drawPath(path, paint)
    }

    /** Ring coordinate for a bow-relative angle (0 top, +stbd clockwise, -port counter-clockwise). */
    private fun ringXY(cx: Float, cy: Float, radius: Float, rel: Double): Pair<Float, Float> {
        val a = Math.toRadians(rel)
        return (cx + radius * sin(a)).toFloat() to (cy - radius * cos(a)).toFloat()
    }
}
