package com.dvladi.mynavvy

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Wind: a full-screen relative-wind dial ([WindRoseView]) plus true/apparent read-outs. The forecast
 * (Open-Meteo) gives true wind FROM-direction, speed and gust at the boat; heading = COG. True wind
 * angle (TWA) is the forecast bearing off the bow; apparent (AWA/AWS) is computed from TWA, true wind
 * speed and boat speed over ground — a phone has no masthead unit, so both are derived, not measured.
 */
class WindFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    private lateinit var rose: WindRoseView
    private lateinit var tvFrom: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvApparent: TextView


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F12"))
            // Top inset clears the floating ☰ menu button that overlays every screen.
            setPadding(dp(10), dp(60), dp(10), dp(12))
        }

        rose = WindRoseView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(rose)

        fun readout(): TextView = TextView(requireContext()).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(3))
        }
        tvFrom = readout(); tvSpeed = readout(); tvApparent = readout()
        root.addView(tvFrom); root.addView(tvSpeed); root.addView(tvApparent)

        root.addView(Button(requireContext()).apply {
            text = "Chart"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener { activity?.onBackPressedDispatcher?.onBackPressed() }
        })

        return root
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun render() {
        val act = activity as? MainActivity ?: return
        val wp = act.uiWindAtBoatNow()
        val loc = act.uiLastLocation()
        val hdg = loc?.takeIf { it.hasBearing() }?.bearing?.toDouble()

        if (wp == null) {
            rose.setData(hdg, null, null)
            tvFrom.text = "No wind forecast"
            tvSpeed.text = "Open Weather to fetch"
            tvApparent.text = ""
            return
        }

        val tws = wp.speedKn
        val bs = loc?.let { it.speed * 1.94384 } ?: 0.0
        // TWA: signed angle off the bow the true wind blows FROM (− port, + starboard).
        val twa = hdg?.let { norm180(wp.dirDeg - it) }
        // Apparent wind = true wind vector + the boat's own headwind (dead ahead at BS).
        var awa: Double? = null; var aws = tws
        if (twa != null) {
            val t = Math.toRadians(twa)
            val ax = tws * sin(t)
            val ay = tws * cos(t) + bs
            awa = Math.toDegrees(atan2(ax, ay))
            aws = hypot(ax, ay)
        }
        rose.setData(hdg, twa, awa)

        tvFrom.text = String.format(Locale.US, "FROM  %03d°T", wp.dirDeg.roundToInt() % 360)
        tvSpeed.text = String.format(Locale.US, "TWS %.0f kn   GUST %.0f kn", tws, wp.gustKn)
        tvApparent.text = awa?.let {
            String.format(Locale.US, "APP  %.0f kn  @ %+d°", aws, it.roundToInt())
        } ?: ""
    }

    /** Wrap a bearing difference into (−180, 180]. */
    private fun norm180(deg: Double): Double = ((deg % 360) + 540) % 360 - 180
}
