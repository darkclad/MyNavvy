package com.dvladi.mynavvy.game

import android.graphics.Color

/**
 * An immutable snapshot of everything the Helm screen draws. Computed on the UI thread
 * (~1 Hz / on each fix) and handed to the render thread as one volatile reference, so the render
 * loop never reaches into live app state. All values nullable = "no data" (drawn as dashes).
 */
data class HelmData(
    val hdg: Double? = null,        // heading = COG → compass card + centre number
    val sogKn: Double? = null,
    val depthDisp: Double? = null,  // depth already in display units (m or ft)
    val depthColor: Int = Color.WHITE,
    val unitsFt: Boolean = false,
    val btw: Double? = null,        // bearing-to-waypoint → director arrow
    val dtwNm: Double? = null,      // distance-to-waypoint
    val ttdHours: Double? = null,   // time-to-destination
    val vmgKn: Double? = null,      // velocity-made-good to the waypoint
    val xteNm: Double? = null,      // cross-track error magnitude
    val wpRangeNm: Double = 0.25,   // look-ahead "zoom" range for the track
    val windDir: Double? = null,    // wind FROM direction → needle
    val hasWaypoint: Boolean = false
)

/** Supplies the latest [HelmData] snapshot. Implemented by the fragment/activity. */
fun interface HelmDataSource { fun snapshot(): HelmData }
