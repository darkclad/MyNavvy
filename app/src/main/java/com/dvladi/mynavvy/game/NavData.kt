package com.dvladi.mynavvy.game

import android.graphics.Color

/**
 * An immutable snapshot of everything the Simrad NAVIGATION HUD overlay draws (the four corner
 * panels + centre data-bar + heading tape) — ported from simrad-nav-mockup/nav.py + nav_landscape.py.
 * Built on the UI thread in MainActivity.refreshNavUi() and handed to the Compose overlay as one
 * state value. All value fields nullable = "no data" (drawn as dashes). The chart, boat wedge and
 * route are real MapLibre layers underneath, so they are NOT part of this snapshot.
 */
data class NavData(
    val steerDeg: Double? = null,       // STEER: signed shortest turn onto the WP bearing (- = port)
    val steerOnCourse: Boolean = false, // |steer| <= 5° → green
    val depthDisp: Double? = null,      // DEPTH already in display units (m or ft)
    val depthColor: Int = Color.WHITE,  // from under-keel-clearance colour
    val depthDry: Boolean = false,      // charted depth <= 0 → "DRY"
    val showDepth: Boolean = true,      // false hides the DEPTH panel (Chart+Nav: it's on the chart pane)
    val unitsFt: Boolean = false,       // DEPTH unit label m / ft
    val sogKn: Double? = null,          // SOG
    val cogDeg: Double? = null,         // COG value AND the heading feeding the tape
    val dtwNm: Double? = null,          // data-bar left: distance to active waypoint
    val wptName: String? = null,        // data-bar centre: active waypoint name e.g. "WP3"
    val ttgHours: Double? = null,       // data-bar right: time-to-go = dtw / sog
    val hasRoute: Boolean = false,
    val hasFix: Boolean = false
)
