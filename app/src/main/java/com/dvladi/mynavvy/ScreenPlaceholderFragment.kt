package com.dvladi.mynavvy

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

/** A titled "coming soon" screen — a real fragment slot so the nav shell is wired end-to-end. */
class ScreenPlaceholderFragment : Fragment(R.layout.fragment_placeholder) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.title).text = arguments?.getString(ARG_TITLE)
        view.findViewById<TextView>(R.id.body).text = arguments?.getString(ARG_BODY)
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        fun of(title: String, body: String) = ScreenPlaceholderFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_BODY, body)
            }
        }
    }
}
