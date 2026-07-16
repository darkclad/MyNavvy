package com.dvladi.mynavvy

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView

/**
 * Split view: shows two screens at once — stacked in portrait, side-by-side in landscape (the same
 * preset gives "2 up" tall and "2 across" wide). Gauge panes (Helm / Wind / Trip) reuse their
 * fragments unchanged. A MAP pane (Chart / Nav) is a transparent, click-through placeholder: the
 * single full-screen MapView shows through it (MainActivity pads the camera into that half), so we
 * never need a second map surface. At most one pane is a map — enforced by the offered combos.
 */
class SplitScreenFragment : Fragment() {

    private var root: LinearLayout? = null
    /** Per pane: the view id if it's a gauge container, or 0 for a transparent map pane. */
    private val paneContainerIds = ArrayList<Int>()

    private fun keys(): Array<String> =
        arguments?.getStringArray(ARG_WIDGETS) ?: arrayOf("helm", "wind")

    private fun isMap(key: String) = key == "chart" || key == "nav"

    private fun landscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val land = landscape()
        val keys = keys()
        val hasMap = keys.any { isMap(it) }
        if (paneContainerIds.isEmpty())
            keys.forEach { paneContainerIds.add(if (isMap(it)) 0 else View.generateViewId()) }

        val ll = LinearLayout(requireContext()).apply {
            // Transparent overall when a map pane is present, so the map shows through that half.
            setBackgroundColor(if (hasMap) Color.TRANSPARENT else Color.parseColor("#0b0f13"))
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isClickable = false
        }
        keys.indices.forEach { i ->
            if (i > 0) ll.addView(divider(land))
            if (paneContainerIds[i] == 0) {
                // Map pane: transparent + non-interactive so touches fall through to the MapView.
                ll.addView(View(requireContext()).apply { isClickable = false }, paneLp(land))
            } else {
                ll.addView(FragmentContainerView(requireContext()).apply { id = paneContainerIds[i] },
                    paneLp(land))
            }
        }
        root = ll
        return ll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (childFragmentManager.fragments.isEmpty()) {
            val keys = keys()
            childFragmentManager.beginTransaction().apply {
                keys.forEachIndexed { i, k -> if (paneContainerIds[i] != 0) add(paneContainerIds[i], widgetFor(k)) }
            }.commit()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val ll = root ?: return
        ll.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (i in 0 until ll.childCount) {
            val child = ll.getChildAt(i)
            // Panes carry weight 1 (set in paneLp); dividers carry weight 0 — reflow each accordingly.
            val isPane = ((child.layoutParams as? LinearLayout.LayoutParams)?.weight ?: 0f) > 0f
            child.updateLayoutParams<LinearLayout.LayoutParams> {
                if (isPane) {
                    width = if (land) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                    height = if (land) LinearLayout.LayoutParams.MATCH_PARENT else 0
                    weight = 1f
                } else {
                    width = if (land) dp(1) else LinearLayout.LayoutParams.MATCH_PARENT
                    height = if (land) LinearLayout.LayoutParams.MATCH_PARENT else dp(1)
                    weight = 0f
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); root = null }

    private fun paneLp(land: Boolean) = LinearLayout.LayoutParams(
        if (land) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
        if (land) LinearLayout.LayoutParams.MATCH_PARENT else 0, 1f)

    private fun divider(land: Boolean) = View(requireContext()).apply {
        setBackgroundColor(Color.parseColor("#22303A"))
        layoutParams = if (land) LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1), 0f)
    }

    private fun widgetFor(key: String): Fragment = when (key) {
        "wind" -> WindFragment()
        "trip" -> TripFragment()
        else -> HelmFragment()
    }

    companion object {
        private const val ARG_WIDGETS = "widgets"
        fun of(vararg keys: String) = SplitScreenFragment().apply {
            arguments = Bundle().apply { putStringArray(ARG_WIDGETS, keys) }
        }
    }
}
