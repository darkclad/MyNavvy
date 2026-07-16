package com.dvladi.mynavvy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 48-hour tide-height curve. Two time markers: [nowMs] (red line + dot on the curve — the current
 * time, always shown) and [markerMs] (amber line — the selected forecast hour driven by the Wx-panel
 * slider). The full Weather screen only sets "now"; the compact Wx panel sets both.
 */
class TideGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var times = LongArray(0)
    private var hts = DoubleArray(0)
    private var markerMs = 0L  // selected forecast hour (amber)
    private var nowMs = 0L     // current time (red)
    /** Compact sparkline for the HUD tile: no text labels, tight padding, smaller marker. */
    var compact = false
    /** Axis labels in feet (true) or metres (false). Data is always supplied in feet. */
    var useFeet = true

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4aa3ff"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334aa3ff"); style = Paint.Style.FILL
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55ffffff"); strokeWidth = 1f
    }
    private val nowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744"); strokeWidth = 3f  // "now" line — red
    }
    private val nowDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744"); style = Paint.Style.FILL
    }
    private val nowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL // white ring so the dot pops off the curve
    }
    private val selLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffd54f"); strokeWidth = 2f  // selected forecast hour — amber
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; typeface = android.graphics.Typeface.MONOSPACE
    }

    private val FT_TO_M = 0.3048

    fun setData(t: LongArray, h: DoubleArray) { times = t; hts = h; invalidate() }
    /** The selected forecast hour (amber line) — Wx-panel slider position. */
    fun setMarker(ms: Long) { markerMs = ms; invalidate() }
    /** The current time (red line + dot on the curve) — always shown when in range. */
    fun setNow(ms: Long) { nowMs = ms; invalidate() }

    /** Linear-interpolated tide height at [ms], or null if outside the series — for the marker dot. */
    private fun interpAt(ms: Long): Double? {
        if (times.size < 2 || ms < times.first() || ms > times.last()) return null
        var lo = 0; var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[mid] <= ms) lo = mid else hi = mid
        }
        val span = (times[hi] - times[lo]).toDouble()
        if (span <= 0.0) return hts[lo]
        return hts[lo] + (hts[hi] - hts[lo]) * ((ms - times[lo]) / span)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val padL = if (compact) 3f else 8f
        val padR = if (compact) 3f else 8f
        val padT = if (compact) 5f else 22f
        val padB = if (compact) 5f else 22f
        if (times.size < 2) {
            if (!compact) canvas.drawText("Tide: no data", 12f, h / 2f, text)
            return
        }
        val t0 = times.first().toDouble(); val t1 = times.last().toDouble()
        var lo = hts.min(); var hi = hts.max()
        if (hi - lo < 0.5) { hi += 0.5; lo -= 0.5 }

        fun x(ms: Double): Float = (padL + (ms - t0) / (t1 - t0) * (w - padL - padR)).toFloat()
        fun y(ft: Double): Float = (padT + (1 - (ft - lo) / (hi - lo)) * (h - padT - padB)).toFloat()

        // zero-height baseline (MLLW)
        if (lo <= 0 && hi >= 0) {
            val y0 = y(0.0)
            canvas.drawLine(padL, y0, w - padR, y0, axis)
        }

        val path = Path(); val area = Path()
        area.moveTo(x(t0), h - padB)
        for (i in times.indices) {
            val px = x(times[i].toDouble()); val py = y(hts[i])
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            area.lineTo(px, py)
        }
        area.lineTo(x(t1), h - padB); area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        // Selected forecast hour (amber line) — only when it's meaningfully apart from "now".
        if (markerMs != 0L && markerMs in times.first()..times.last() &&
            Math.abs(markerMs - nowMs) > 60_000L) {
            val sx = x(markerMs.toDouble())
            canvas.drawLine(sx, padT, sx, h - padB, selLine)
        }
        // Current time (red line + a red dot riding on the tide curve) — always shown.
        if (nowMs != 0L && nowMs in times.first()..times.last()) {
            val mx = x(nowMs.toDouble())
            canvas.drawLine(mx, padT, mx, h - padB, nowLine)
            interpAt(nowMs)?.let { ht ->
                val my = y(ht)
                val rHalo = if (compact) 6f else 10f
                val rDot = if (compact) 4f else 7f
                canvas.drawCircle(mx, my, rHalo, nowHalo)
                canvas.drawCircle(mx, my, rDot, nowDot)
            }
        }
        if (compact) return   // sparkline: no axis text / date labels
        val u = if (useFeet) 1.0 else FT_TO_M
        val suffix = if (useFeet) "'" else " m"
        canvas.drawText(String.format(Locale.US, "%.1f%s", hi * u, suffix), 10f, padT - 2f, text)
        canvas.drawText(String.format(Locale.US, "%.1f%s", lo * u, suffix), 10f, h - 4f, text)
        // Top-right label: the selected hour if the slider set one, else "now".
        val labelMs = if (markerMs != 0L) markerMs else nowMs
        val d = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(labelMs))
        canvas.drawText(d, w - 170f, padT - 2f, text)
    }
}
