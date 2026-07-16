package com.dvladi.mynavvy

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Passage & Route: the tabular companion to the on-chart route planner. Reads the current waypoints
 * (a snapshot — the chart is hidden while this is up) and shows total distance/time, a per-leg table
 * of bearing / distance / arrival clock, and a departure-time scheduler that shifts the ETAs.
 */
class RouteFragment : Fragment(R.layout.fragment_route) {

    private var departMs = 0L
    private var cruiseKn = 6.0
    private val waypoints = ArrayList<LatLng>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = activity as? MainActivity
        waypoints.clear()
        act?.uiRouteWaypoints()?.let { waypoints.addAll(it) }
        cruiseKn = act?.uiCruiseKn()?.takeIf { it > 0.0 } ?: 6.0
        // Default departure = the last GPS fix time if we have one, else "now" (device clock).
        departMs = System.currentTimeMillis()

        view.findViewById<Button>(R.id.btnDepMinus).setOnClickListener { shiftDepart(-15) }
        view.findViewById<Button>(R.id.btnDepPlus).setOnClickListener { shiftDepart(+15) }
        view.findViewById<Button>(R.id.btnDepNow).setOnClickListener {
            departMs = System.currentTimeMillis(); render()
        }
        view.findViewById<Button>(R.id.btnRouteExport).setOnClickListener { act?.uiExportGpx() }
        view.findViewById<Button>(R.id.btnRouteClear).setOnClickListener {
            act?.uiClearRoute(); waypoints.clear(); render()
        }
        view.findViewById<Button>(R.id.btnRouteChart).setOnClickListener {
            act?.onBackPressedDispatcher?.onBackPressed()
        }
        render()
    }

    private fun shiftDepart(minutes: Int) {
        departMs += minutes * 60_000L
        render()
    }

    private fun render() {
        val v = view ?: return
        val summary = v.findViewById<TextView>(R.id.tvRouteSummary)
        val legs = v.findViewById<LinearLayout>(R.id.legContainer)
        val wps = v.findViewById<LinearLayout>(R.id.wpContainer)
        val departRow = v.findViewById<View>(R.id.departRow)
        v.findViewById<TextView>(R.id.tvDepart).text = clock(departMs)
        legs.removeAllViews()
        wps.removeAllViews()

        if (waypoints.size < 2) {
            summary.text = if (waypoints.isEmpty())
                "No route yet.\nLong-press the chart to drop waypoints."
            else "1 waypoint.\nLong-press the chart to add more."
            departRow.visibility = View.GONE
            waypoints.forEachIndexed { i, wp -> wps.addView(wpRow(i, wp)) }
            return
        }
        departRow.visibility = View.VISIBLE

        // Totals + per-leg cumulative arrival times.
        var totalNm = 0.0
        for (i in 1 until waypoints.size) totalNm += GeoUtils.distanceNm(waypoints[i - 1], waypoints[i])
        val totalH = totalNm / cruiseKn
        val arriveMs = departMs + (totalH * 3_600_000L).toLong()
        val legCount = waypoints.size - 1
        summary.text = String.format(
            Locale.US,
            "%d waypoints · %d leg%s\n%.1f nm  @ %.1f kn\nETA %s  ·  arrive %s",
            waypoints.size, legCount, if (legCount == 1) "" else "s", totalNm, cruiseKn,
            GeoUtils.formatHours(totalH), clock(arriveMs)
        )

        legs.addView(legHeader())
        var cumNm = 0.0
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]; val b = waypoints[i]
            val brg = GeoUtils.bearingDeg(a, b)
            val legNm = GeoUtils.distanceNm(a, b)
            cumNm += legNm
            val etaMs = departMs + (cumNm / cruiseKn * 3_600_000L).toLong()
            legs.addView(legRow(i, brg, legNm, etaMs, i == waypoints.size - 1))
        }
        waypoints.forEachIndexed { i, wp -> wps.addView(wpRow(i, wp)) }
    }

    // --- Row builders -------------------------------------------------------

    private fun cell(text: String, weight: Float, color: Int, gravity: Int = Gravity.START): TextView =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            setTextColor(color)
            textSize = 13f
            this.gravity = gravity
            typeface = android.graphics.Typeface.MONOSPACE
        }

    private fun row(): LinearLayout = LinearLayout(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun legHeader(): View = row().apply {
        addView(cell("Leg", 1.0f, LABEL))
        addView(cell("Brg", 1.2f, LABEL, Gravity.END))
        addView(cell("Dist", 1.4f, LABEL, Gravity.END))
        addView(cell("ETA", 1.4f, LABEL, Gravity.END))
    }

    private fun legRow(n: Int, brg: Double, legNm: Double, etaMs: Long, last: Boolean): View =
        row().apply {
            val markColor = if (last) Color.parseColor("#7CFF9E") else Color.WHITE
            addView(cell("$n→${n + 1}", 1.0f, markColor))
            addView(cell(String.format(Locale.US, "%03.0f°", brg), 1.2f, Color.WHITE, Gravity.END))
            addView(cell(String.format(Locale.US, "%.2f nm", legNm), 1.4f, Color.WHITE, Gravity.END))
            addView(cell(clock(etaMs), 1.4f, Color.parseColor("#8fd3ff"), Gravity.END))
        }

    private fun wpRow(i: Int, wp: LatLng): View = row().apply {
        addView(cell("${i + 1}", 0.5f, Color.parseColor("#8fd3ff")))
        addView(cell(GeoUtils.formatLat(wp.latitude), 2.2f, Color.WHITE))
        addView(cell(GeoUtils.formatLon(wp.longitude), 2.4f, Color.WHITE))
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    companion object {
        private val LABEL = Color.parseColor("#8FA6B4")
    }
}
