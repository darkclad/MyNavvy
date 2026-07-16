package com.dvladi.mynavvy

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Weather & Tides: the full view behind the compact chart-screen Wx panel. Reads the loaded forecast
 * + tide snapshot from the activity and shows the 48 h tide curve, the next highs/lows, and the wind
 * forecast at the boat. "Download offline data" re-runs the pre-departure fetch.
 */
class WeatherFragment : Fragment(R.layout.fragment_weather) {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    // Once a second: tick the live clock and nudge the tide "now" marker (both cheap — no re-render,
    // so no flicker/scroll reset).
    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            view?.let { v ->
                v.findViewById<TextView>(R.id.tvNowClock).text = clockFmt.format(java.util.Date(now))
                v.findViewById<TideGraphView>(R.id.tideGraphFull).setNow(now)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.btnWxRefresh).setOnClickListener {
            (activity as? MainActivity)?.uiFetchOfflineData()
            // The fetch is async and updates the activity's snapshot; re-render once it likely landed.
            view.postDelayed({ if (isAdded) render() }, 2500)
        }
        view.findViewById<Button>(R.id.btnWxChart).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
        render()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun render() {
        val v = view ?: return
        val act = activity as? MainActivity
        val weather = act?.uiWeather()
        val tide = act?.uiTide()
        val loc = act?.uiLastLocation()
        val now = System.currentTimeMillis()

        val graph = v.findViewById<TideGraphView>(R.id.tideGraphFull)
        val tideBox = v.findViewById<LinearLayout>(R.id.tideContainer)
        val windBox = v.findViewById<LinearLayout>(R.id.windContainer)
        tideBox.removeAllViews()
        windBox.removeAllViews()

        // --- Status: cover + freshness ---
        val sb = StringBuilder()
        val windEnd = weather?.timesUtcMs?.lastOrNull()
        val tideEnd = tide?.timesMs?.lastOrNull()
        sb.append("Wind: ").append(coverText(windEnd, now))
        sb.append(freshText(act?.uiWeatherCachedAtMs("wind.json"), now))
        sb.append("\nTide: ").append(coverText(tideEnd, now))
        sb.append(freshText(act?.uiWeatherCachedAtMs("tide.json"), now))
        v.findViewById<TextView>(R.id.tvWxStatus).text = sb.toString()

        // --- Tide graph + next highs/lows ---
        // NOAA CO-OPS heights are feet; the Config unit toggle (feet/metres) drives the display.
        val useFeet = act?.uiUnitsFt() ?: true
        val toU = if (useFeet) 1.0 else 0.3048
        val unit = if (useFeet) "ft" else "m"
        if (tide != null && tide.timesMs.size >= 2) {
            graph.useFeet = useFeet
            graph.setData(tide.timesMs, tide.heightsFt)
            graph.setNow(now)
            val extrema = tideExtrema(tide.timesMs, tide.heightsFt).filter { it.ms >= now }.take(4)
            if (extrema.isEmpty()) tideBox.addView(note("No further highs/lows in range."))
            else extrema.forEach { e ->
                val label = if (e.high) "High" else "Low"
                val color = if (e.high) Color.parseColor("#8fd3ff") else Color.parseColor("#9fb3c0")
                tideBox.addView(kv("$label ${clock(e.ms)}", String.format(Locale.US, "%.1f %s", e.ft * toU, unit), color))
            }
        } else {
            tideBox.addView(note("No tide data — tap Download offline data."))
        }

        // --- Wind at the boat ---
        if (weather == null || weather.hourCount() == 0) {
            windBox.addView(note("No forecast — tap Download offline data."))
            return
        }
        val lat = loc?.latitude ?: SD_LAT
        val lon = loc?.longitude ?: SD_LON
        if (loc == null) windBox.addView(note("No GPS yet — wind shown for San Diego."))

        windBox.addView(windHeader())
        val startH = weather.nearestHourIndex(now)
        var h = startH
        var shown = 0
        // Next ~24 h in 3 h steps.
        while (h < weather.hourCount() && shown < 9) {
            val p = weather.nearest(lat, lon, h)
            val tMs = weather.timesUtcMs[h]
            if (p != null) {
                windBox.addView(windRow(tMs, p.dirDeg, p.speedKn, p.gustKn, h == startH))
                shown++
            }
            h += 3
        }
    }

    // --- Tide extrema -------------------------------------------------------

    private data class Extremum(val ms: Long, val ft: Double, val high: Boolean)

    /** Local maxima/minima of the tide series (slope sign changes). */
    private fun tideExtrema(times: LongArray, hts: DoubleArray): List<Extremum> {
        val out = ArrayList<Extremum>()
        for (i in 1 until hts.size - 1) {
            val a = hts[i] - hts[i - 1]
            val b = hts[i + 1] - hts[i]
            if (a > 0 && b <= 0) out.add(Extremum(times[i], hts[i], true))
            else if (a < 0 && b >= 0) out.add(Extremum(times[i], hts[i], false))
        }
        return out
    }

    // --- Cover / freshness text --------------------------------------------

    private fun coverText(endMs: Long?, now: Long): String {
        if (endMs == null) return "none"
        val h = (endMs - now) / 3_600_000.0
        return if (h <= 0) "expired" else String.format(Locale.US, "to %s (%.0f h)", dayClock(endMs), h)
    }

    private fun freshText(cachedMs: Long?, now: Long): String {
        if (cachedMs == null) return ""
        val h = (now - cachedMs) / 3_600_000.0
        return when {
            h < 1.0 -> " · updated <1 h ago"
            h < 48.0 -> String.format(Locale.US, " · updated %.0f h ago", h)
            else -> String.format(Locale.US, " · updated %.0f d ago", h / 24.0)
        }
    }

    // --- Row builders -------------------------------------------------------

    private fun windHeader(): View = rowH().apply {
        addView(cell("Time", 1.2f, LABEL))
        addView(cell("Dir", 1.2f, LABEL, Gravity.END))
        addView(cell("Wind", 1.4f, LABEL, Gravity.END))
        addView(cell("Gust", 1.2f, LABEL, Gravity.END))
    }

    private fun windRow(tMs: Long, dir: Double, spd: Double, gust: Double, nowRow: Boolean): View =
        rowH().apply {
            val c = if (nowRow) Color.parseColor("#7CFF9E") else Color.WHITE
            addView(cell(if (nowRow) "now" else clock(tMs), 1.2f, c))
            addView(cell(String.format(Locale.US, "%03.0f°", dir), 1.2f, Color.WHITE, Gravity.END))
            addView(cell(String.format(Locale.US, "%.0f kn", spd), 1.4f, Color.WHITE, Gravity.END))
            addView(cell(String.format(Locale.US, "%.0f", gust), 1.2f, Color.parseColor("#9fb3c0"), Gravity.END))
        }

    private fun kv(k: String, value: String, keyColor: Int): View = rowH().apply {
        addView(cell(k, 2f, keyColor))
        addView(cell(value, 1f, Color.WHITE, Gravity.END))
    }

    private fun note(text: String): View = TextView(requireContext()).apply {
        this.text = text; setTextColor(Color.parseColor("#9fb3c0")); textSize = 13f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun cell(text: String, weight: Float, color: Int, gravity: Int = Gravity.START): TextView =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text; setTextColor(color); textSize = 13f
            this.gravity = gravity; typeface = Typeface.MONOSPACE
        }

    private fun rowH(): LinearLayout = LinearLayout(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    private fun dayClock(ms: Long) = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(ms))

    companion object {
        private val LABEL = Color.parseColor("#8FA6B4")
        private const val SD_LAT = 32.69
        private const val SD_LON = -117.20
    }
}
