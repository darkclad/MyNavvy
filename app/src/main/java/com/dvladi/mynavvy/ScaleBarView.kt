package com.dvladi.mynavvy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Google-Maps-style scale bar: a bar of a "nice" round distance whose on-screen length is
 * recomputed as the map zooms. Marine units — nautical miles, falling back to metres at
 * harbour zoom levels.
 *
 * Feed it [update] with the map's metres-per-pixel at the current camera latitude.
 */
class ScaleBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var metersPerPixel = 0.0
    private var useFeet = false

    /** Switch the close-in units (below half a mile) between metres and feet. nm is unchanged. */
    fun setUseFeet(feet: Boolean) {
        if (useFeet != feet) { useFeet = feet; invalidate() }
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000"); style = Paint.Style.STROKE; strokeWidth = 4.5f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * density; typeface = Typeface.MONOSPACE
    }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000"); textSize = 12f * density
        typeface = Typeface.MONOSPACE; style = Paint.Style.STROKE; strokeWidth = 3f * density
    }

    /** Called on every camera move. */
    fun update(metersPerPixel: Double) {
        if (metersPerPixel <= 0 || !metersPerPixel.isFinite()) return
        this.metersPerPixel = metersPerPixel
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize((260 * density).toInt(), widthSpec),
            resolveSize((30 * density).toInt(), heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (metersPerPixel <= 0 || width == 0) return
        val maxPx = min(width * 0.9f, 235f * density)
        val meters = niceDistance(maxPx) ?: return
        val barPx = (meters / metersPerPixel).toFloat()
        if (barPx < 8f) return

        val x0 = 2f * density
        val y = height - 8f * density
        val tick = 5f * density

        // bar with end ticks, drawn twice (halo then stroke) so it reads over any chart colour
        for (p in arrayOf(halo, stroke)) {
            canvas.drawLine(x0, y, x0 + barPx, y, p)
            canvas.drawLine(x0, y - tick, x0, y + 1f, p)
            canvas.drawLine(x0 + barPx, y - tick, x0 + barPx, y + 1f, p)
        }

        val label = labelFor(meters)
        val ty = y - 8f * density
        canvas.drawText(label, x0 + 2f * density, ty, textHalo)
        canvas.drawText(label, x0 + 2f * density, ty, text)
    }

    /** Largest preset distance whose bar still fits in [maxPx]. */
    private fun niceDistance(maxPx: Float): Double? {
        val steps = if (useFeet) STEPS_FT else STEPS_M
        for (i in steps.indices.reversed()) {
            val px = steps[i] / metersPerPixel
            if (px <= maxPx) return steps[i]
        }
        return steps.firstOrNull()
    }

    private fun labelFor(meters: Double): String {
        // Below half a mile, round metres/feet are what you actually judge anchoring / scope against.
        if (meters < 0.5 * NM) {
            return if (useFeet) "${(meters / FT).roundToInt()} ft"
            else "${meters.roundToInt()} m"
        }
        val nm = meters / NM
        return if (nm >= 1.0) String.format(Locale.US, "%.0f nm", nm)
        else String.format(Locale.US, "%.2f", nm).trimEnd('0').trimEnd('.') + " nm"
    }

    companion object {
        private const val NM = 1852.0
        private const val FT = 0.3048
        /**
         * Round distances in metres. Close in (anchoring, harbour) we step in round metres —
         * 0.1 nm is 185 m, which is useless for eyeballing a swinging circle. From half a mile
         * up we switch to nautical miles, which is what passage planning is done in.
         */
        private val STEPS_M = doubleArrayOf(
            5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
            0.5 * NM, 1 * NM, 2 * NM, 5 * NM, 10 * NM, 20 * NM,
            50 * NM, 100 * NM, 200 * NM, 500 * NM
        )
        /** Imperial: round feet close in (up to 1000 ft ≈ 0.16 nm), then nautical miles. */
        private val STEPS_FT = doubleArrayOf(
            10 * FT, 25 * FT, 50 * FT, 100 * FT, 250 * FT, 500 * FT, 1000 * FT,
            0.5 * NM, 1 * NM, 2 * NM, 5 * NM, 10 * NM, 20 * NM,
            50 * NM, 100 * NM, 200 * NM, 500 * NM
        )
    }
}
