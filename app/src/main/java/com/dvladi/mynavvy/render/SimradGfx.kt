package com.dvladi.mynavvy.render

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Shared drawing helpers for the Simrad-style custom-drawn screens: the mock palette (matching
 * simrad-mockup/simrad.py), the condensed value typeface, and a glyph-accurate text-fitting helper.
 *
 * These are the game-loop equivalent of the pygame mock's module-level colours + `fit_font`.
 */
object SimradGfx {

    // --- mock palette (RGB from simrad.py) ---
    val BG = Color.rgb(5, 5, 5)
    val FRAME = Color.rgb(217, 154, 43)
    val BOX_BG = Color.rgb(10, 10, 10)
    val BOX_BORDER = Color.rgb(36, 36, 36)
    val DIVIDER = Color.rgb(70, 88, 106)
    val LABEL = Color.rgb(179, 187, 194)
    val VALUE = Color.rgb(245, 247, 249)
    val BRACKET = Color.rgb(224, 73, 44)
    val RED = Color.rgb(224, 73, 44)
    val GREEN = Color.rgb(46, 160, 67)
    val BLUE = Color.rgb(58, 134, 200)

    // --- nav-HUD palette (RGB from simrad-nav-mockup/nav.py) ---
    val NAV_PANEL = Color.argb(238, 16, 17, 19)   // corner panel fill (PANEL_BLK @ a=238)
    val NAV_DATABAR = Color.argb(242, 16, 17, 19) // data-bar box fill
    val NAV_PANEL_EDGE = Color.rgb(58, 60, 64)
    val NAV_LABEL = Color.rgb(232, 236, 240)
    val NAV_VALUE = Color.rgb(248, 250, 252)
    val NAV_UNIT = Color.rgb(190, 196, 202)
    val NAV_RED = Color.rgb(244, 59, 80)          // heading-tape cursor fill
    val NAV_RED_EDGE = Color.rgb(150, 22, 36)     // cursor stroke
    val NAV_ARROW = Color.rgb(196, 200, 204)      // STEER rudder arrow
    val NAV_ON_COURSE = Color.rgb(124, 255, 158)  // STEER value when |turn| <= 5°
    // heading-tape ticks (major / mid / minor) + light halo, and black labels + light halo
    val NAV_TICK_MAJOR = Color.rgb(20, 26, 32)
    val NAV_TICK_MID = Color.rgb(45, 52, 60)
    val NAV_TICK_MINOR = Color.rgb(78, 86, 94)
    val NAV_TICK_HALO = Color.rgb(245, 248, 250)
    val NAV_TAPE_LABEL = Color.rgb(14, 17, 21)
    val NAV_TAPE_HALO = Color.rgb(246, 249, 251)

    val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** Condensed value face — the Android stand-in for the mock's Bahnschrift (DIN-like tall digits). */
    val CONDENSED: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val CONDENSED_LIGHT: Typeface = Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)
    val LABEL_ITALIC: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)

    private val measureBounds = Rect()

    /**
     * Set `paint.textSize` so `text` fits within `maxW` wide and `maxH` tall, measured by the ACTUAL
     * rendered glyph bbox (not font line-height, which over-pads) — so `maxH` == desired cap height.
     * Mirrors the mock's `fit_font`. Returns the fitted glyph bounds (into `outBounds` if given).
     */
    fun fitText(paint: Paint, text: String, maxW: Float, maxH: Float, maxSize: Float = 400f,
                outBounds: Rect? = null): Rect {
        var s = maxSize
        while (s > 6f) {
            paint.textSize = s
            paint.getTextBounds(text, 0, text.length, measureBounds)
            if (measureBounds.width() <= maxW && measureBounds.height() <= maxH) break
            s -= 2f
        }
        outBounds?.set(measureBounds)
        return measureBounds
    }

    fun paint(color: Int, fill: Boolean = true, strokeW: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            if (!fill) strokeWidth = strokeW
        }

    fun textPaint(color: Int, tf: Typeface = Typeface.DEFAULT,
                  align: Paint.Align = Paint.Align.CENTER): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = tf; textAlign = align
        }
}
