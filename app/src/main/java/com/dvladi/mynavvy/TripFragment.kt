package com.dvladi.mynavvy

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

/** Immutable snapshot of the current trip, computed by [MainActivity.uiTripStats]. */
data class TripStats(
    val startMs: Long, val elapsedMs: Long, val distNm: Double,
    val movingMs: Long, val avgKn: Double, val maxKn: Double
)

/**
 * Trip log: the running tally for the current passage — distance made good, time elapsed, time
 * underway (≈ engine hours for a motoring boat), and average / top speed over ground. The numbers
 * are accumulated live in [MainActivity] from each GPS fix and survive a restart; **Reset** starts a
 * fresh trip. A once-a-second ticker keeps the elapsed clock live.
 */
class TripFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    private lateinit var tvDist: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvUnderway: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvMax: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F12"))
            // Top inset clears the floating ☰ menu button that overlays every screen.
            setPadding(dp(10), dp(60), dp(10), dp(14))
        }

        root.addView(TextView(requireContext()).apply {
            text = "TRIP"; setTextColor(Color.parseColor("#8FA6B4")); textSize = 15f
            typeface = Typeface.MONOSPACE; letterSpacing = 0.15f
            setPadding(dp(8), 0, 0, dp(10))
        })

        val (distTile, dv) = tile("DISTANCE", "nm"); tvDist = dv
        val (elapTile, ev) = tile("ELAPSED", "h:mm"); tvElapsed = ev
        val (underTile, uv) = tile("UNDERWAY", "h:mm"); tvUnderway = uv
        val (avgTile, av) = tile("AVG SOG", "kn"); tvAvg = av
        val (maxTile, mv) = tile("MAX SOG", "kn"); tvMax = mv

        root.addView(rowOf(distTile, elapTile))
        root.addView(rowOf(underTile, avgTile))
        root.addView(rowOf(maxTile, spacer()))

        root.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(12), dp(6), 0)
            addView(Button(requireContext()).apply {
                text = "Reset trip"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { (activity as? MainActivity)?.uiResetTrip(); render() }
            })
            addView(Button(requireContext()).apply {
                text = "Chart"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { activity?.onBackPressedDispatcher?.onBackPressed() }
            })
        })

        return root
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun render() {
        val act = activity as? MainActivity ?: return
        val s = act.uiTripStats()
        tvDist.text = String.format(Locale.US, "%.1f", s.distNm)
        tvElapsed.text = fmtDuration(s.elapsedMs)
        tvUnderway.text = fmtDuration(s.movingMs)
        tvAvg.text = String.format(Locale.US, "%.1f", s.avgKn)
        tvMax.text = String.format(Locale.US, "%.1f", s.maxKn)
    }

    // --- View builders ------------------------------------------------------

    private fun rowOf(a: View, b: View) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a); addView(b)
    }

    private fun spacer() = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    /** A data tile: header row (label left, unit right) over a big monospace value. Returns the tile
     *  and its value TextView so [render] can update it. */
    private fun tile(label: String, unit: String): Pair<View, TextView> {
        val ctx = requireContext()
        val value = TextView(ctx).apply {
            text = "--"; setTextColor(Color.WHITE); textSize = 40f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(ctx).apply {
                text = label; setTextColor(Color.parseColor("#8FA6B4")); textSize = 13f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = unit; setTextColor(Color.parseColor("#8FA6B4")); textSize = 13f
                typeface = Typeface.MONOSPACE; gravity = Gravity.END
            })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#12181D"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#22303A"))
            }
            addView(head); addView(value)
        }
        return box to value
    }

    /** ms → "H:MM" (hours can exceed 24 for a multi-day passage). */
    private fun fmtDuration(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalMin / 60, totalMin % 60)
    }
}
