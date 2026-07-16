package com.dvladi.mynavvy.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * A Simrad data box, drawn onto a Canvas. Ported from the mock's `draw_box`
 * (simrad-mockup/simrad.py): label (italic) + unit header row → divider → big condensed value
 * (sized to the box, clock strings squeezed) → red bottom bracket. Proportions are scaled to the
 * box size so it lays out at any dimension. Owns its Paints; single-threaded (render thread) use.
 */
class BoxRenderer {

    private val bg = SimradGfx.paint(SimradGfx.BOX_BG)
    private val border = SimradGfx.paint(SimradGfx.BOX_BORDER, fill = false, strokeW = 1f)
    private val label = SimradGfx.textPaint(SimradGfx.LABEL, SimradGfx.LABEL_ITALIC, Paint.Align.LEFT)
    private val unit = SimradGfx.textPaint(SimradGfx.LABEL, align = Paint.Align.RIGHT)
    private val divider = SimradGfx.paint(SimradGfx.DIVIDER, fill = false, strokeW = 1f)
    private val value = SimradGfx.textPaint(SimradGfx.VALUE, SimradGfx.CONDENSED)
    private val bracket = SimradGfx.paint(SimradGfx.BRACKET, fill = false, strokeW = 2f)
    private val vb = Rect()

    fun draw(c: Canvas, x: Float, y: Float, w: Float, h: Float,
             labelTxt: String, unitTxt: String, valueTxt: String, valueColor: Int = SimradGfx.VALUE) {
        c.drawRect(x, y, x + w, y + h, bg)
        c.drawRect(x, y, x + w, y + h, border)
        val pad = w * 0.045f

        // header: label left (italic), unit right
        label.textSize = h * 0.154f
        val lfm = label.fontMetrics
        c.drawText(labelTxt, x + pad, y + pad * 0.4f - lfm.ascent, label)
        unit.textSize = h * 0.133f
        c.drawText(unitTxt, x + w - pad, y + pad * 0.4f - unit.fontMetrics.ascent, unit)

        // divider under the header
        val dy = y + h * 0.045f + label.textSize
        c.drawLine(x + pad, dy, x + w - pad, dy, divider)

        // big value: fills the slot between the divider and the bracket zone, so a COMPACT box reads
        // as full (the slack is pooled at the column bottom in the layout, not inside each box).
        // Clock strings squeezed ~11%.
        value.color = valueColor
        value.textScaleX = if (valueTxt.contains(':')) 0.89f else 1.0f
        val valTop = dy + h * 0.05f
        val valBottom = y + h - h * 0.16f
        SimradGfx.fitText(value, valueTxt, (w - 2 * pad) / value.textScaleX, valBottom - valTop, outBounds = vb)
        val cyv = (valTop + valBottom) / 2f
        c.drawText(valueTxt, x + w / 2f, cyv - (vb.top + vb.bottom) / 2f, value)
        value.textScaleX = 1.0f

        // red bracket near the box bottom, framing the value
        val bx0 = x + w * 0.033f; val bx1 = x + w - w * 0.033f
        val by = y + h - h * 0.055f
        val arm = h * 0.12f
        c.drawLine(bx0, by, bx1, by, bracket)
        c.drawLine(bx0, by, bx0, by - arm, bracket)
        c.drawLine(bx1, by, bx1, by - arm, bracket)
    }
}
