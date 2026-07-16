package com.dvladi.mynavvy

import kotlin.math.abs

enum class BoatMode { SAIL, MOTOR, MOTORSAIL }

/**
 * Boat performance for routing. Sailing speed comes from a coarse cruising-monohull polar
 * (bilinear-interpolated). Motoring is a fixed cruise speed. Motor-sailing takes the better
 * of the two — matching how a motoring sailboat is actually run.
 */
class BoatModel(
    var mode: BoatMode = BoatMode.MOTORSAIL,
    var motorKn: Double = 6.0,
    var draftM: Double = 1.8,
    /** Water shallower than draft + this margin is treated as unnavigable. */
    var ukcMarginM: Double = 0.5
) {
    /** Pull the load-bearing numbers from the user's configured boat. */
    fun applyProfile(p: BoatProfile) {
        draftM = p.draftM
        motorKn = p.cruiseKn
        ukcMarginM = p.ukcMarginM
    }

    private val twsBins = doubleArrayOf(4.0, 8.0, 12.0, 16.0, 20.0, 25.0)
    private val twaRows = doubleArrayOf(0.0, 35.0, 45.0, 60.0, 75.0, 90.0, 110.0, 135.0, 150.0, 165.0, 180.0)
    // Boat speed (kn) by [TWA row][TWS col]. 0 = no-go.
    private val polar = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),   // 0  irons
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),   // 35 no-go
        doubleArrayOf(2.6, 4.2, 5.1, 5.6, 5.8, 5.9),   // 45 close-hauled
        doubleArrayOf(3.4, 5.1, 6.0, 6.5, 6.8, 7.0),   // 60
        doubleArrayOf(3.8, 5.5, 6.4, 6.9, 7.2, 7.4),   // 75
        doubleArrayOf(3.9, 5.7, 6.6, 7.1, 7.5, 7.8),   // 90
        doubleArrayOf(3.7, 5.5, 6.5, 7.2, 7.7, 8.1),   // 110
        doubleArrayOf(3.3, 5.0, 6.1, 6.9, 7.5, 8.0),   // 135
        doubleArrayOf(2.9, 4.5, 5.6, 6.4, 7.0, 7.6),   // 150
        doubleArrayOf(2.4, 3.8, 4.8, 5.6, 6.2, 6.8),   // 165
        doubleArrayOf(2.1, 3.4, 4.3, 5.1, 5.7, 6.3)    // 180 dead run
    )

    fun sailSpeedKn(twaDeg: Double, twsKn: Double): Double {
        var twa = ((twaDeg % 360) + 360) % 360
        if (twa > 180) twa = 360 - twa
        val (r0, r1, rf) = bracket(twaRows, twa)
        val (c0, c1, cf) = bracket(twsBins, twsKn)
        val a = polar[r0][c0] + (polar[r0][c1] - polar[r0][c0]) * cf
        val b = polar[r1][c0] + (polar[r1][c1] - polar[r1][c0]) * cf
        return a + (b - a) * rf
    }

    /** Effective boat speed (kn) for a true wind angle + speed, per current mode. */
    fun speedKn(twaDeg: Double, twsKn: Double): Double = when (mode) {
        BoatMode.MOTOR -> motorKn
        BoatMode.SAIL -> sailSpeedKn(twaDeg, twsKn)
        BoatMode.MOTORSAIL -> maxOf(sailSpeedKn(twaDeg, twsKn), motorKn)
    }

    private fun bracket(arr: DoubleArray, v: Double): Triple<Int, Int, Double> {
        if (v <= arr.first()) return Triple(0, 0, 0.0)
        if (v >= arr.last()) return Triple(arr.size - 1, arr.size - 1, 0.0)
        var i = 0
        while (i < arr.size - 1 && arr[i + 1] < v) i++
        return Triple(i, i + 1, (v - arr[i]) / (arr[i + 1] - arr[i]))
    }

    fun modeLabel(): String = when (mode) {
        BoatMode.SAIL -> "Sail"
        BoatMode.MOTOR -> "Motor"
        BoatMode.MOTORSAIL -> "Mtr+Sail"
    }
}

/** Smallest angle (0..180) between a heading and a wind-from direction — the true wind angle. */
fun trueWindAngle(headingDeg: Double, windFromDeg: Double): Double {
    var d = abs(headingDeg - windFromDeg) % 360.0
    if (d > 180) d = 360 - d
    return d
}
