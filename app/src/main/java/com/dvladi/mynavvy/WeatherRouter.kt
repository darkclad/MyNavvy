package com.dvladi.mynavvy

import org.maplibre.android.geometry.LatLng
import kotlin.math.abs

/**
 * Time-optimal isochrone router. From the start it expands a reachability front in fixed time
 * steps: from every front point it tries many headings, advances by the boat speed (polar +
 * mode) given the wind at that place/time, keeps only navigable moves (draft-aware), then prunes
 * the candidates to the outer envelope (farthest-from-start per bearing sector). Finishes when a
 * point can reach the destination within a step; backtracks the optimal path.
 *
 * Wind is time-varying (hourly). Currents are not yet modelled.
 */
class WeatherRouter(
    private val weather: WeatherRepository.Weather?,
    private val boat: BoatModel,
    private val grid: RoutingGrid?
) {
    data class Result(
        val route: List<LatLng>,
        val etaHours: Double,
        val reached: Boolean
    )

    private class Node(val p: LatLng, val parent: Node?, val tH: Double)

    fun route(
        start: LatLng,
        dest: LatLng,
        departMs: Long,
        stepMinutes: Double = 6.0,
        headingStepDeg: Double = 6.0,
        sectors: Int = 90
    ): Result {
        val dtH = stepMinutes / 60.0
        val straightNm = GeoUtils.distanceNm(start, dest)
        val maxHours = minOf(72.0, maxOf(6.0, straightNm / 1.2))
        val maxSteps = (maxHours / dtH).toInt()

        var front = listOf(Node(start, null, 0.0))
        var reached: Node? = null
        var tH = 0.0
        var step = 0

        while (step < maxSteps && reached == null) {
            val timeMs = departMs + (tH * 3_600_000L).toLong()
            val hIdx = weather?.nearestHourIndex(timeMs) ?: 0
            val candidates = ArrayList<Node>(front.size * 8)

            for (node in front) {
                val wind = weather?.nearest(node.p.latitude, node.p.longitude, hIdx)
                val tws = wind?.speedKn ?: 0.0
                val windFrom = wind?.dirDeg ?: 0.0

                // direct finish to destination?
                val toDest = GeoUtils.distanceNm(node.p, dest)
                val brgDest = GeoUtils.bearingDeg(node.p, dest)
                val vDest = boat.speedKn(trueWindAngle(brgDest, windFrom), tws)
                if (vDest > 0.05 && toDest <= vDest * dtH && segmentOk(node.p, dest)) {
                    reached = Node(dest, node, node.tH + toDest / vDest)
                    break
                }

                var heading = 0.0
                while (heading < 360.0) {
                    val v = boat.speedKn(trueWindAngle(heading, windFrom), tws)
                    if (v > 0.05) {
                        val np = GeoUtils.destination(node.p, heading, v * dtH)
                        if (segmentOk(node.p, np)) candidates.add(Node(np, node, node.tH + dtH))
                    }
                    heading += headingStepDeg
                }
            }
            if (reached != null) break
            if (candidates.isEmpty()) break
            front = pruneEnvelope(candidates, start, sectors)
            tH += dtH
            step++
        }

        val end = reached ?: front.minByOrNull { GeoUtils.distanceNm(it.p, dest) }
            ?: return Result(listOf(start, dest), 0.0, false)

        val path = ArrayList<LatLng>()
        var n: Node? = end
        while (n != null) { path.add(n.p); n = n.parent }
        path.reverse()
        // String-pull to remove isochrone zig-zag, then simplify for display.
        return Result(simplify(stringPull(path), 0.03), end.tH, reached != null)
    }

    /** Greedily replace runs of points with the farthest still-navigable straight shortcut. */
    private fun stringPull(pts: List<LatLng>): List<LatLng> {
        if (pts.size <= 2) return pts
        val out = ArrayList<LatLng>()
        out.add(pts.first())
        var i = 0
        while (i < pts.size - 1) {
            var j = pts.size - 1
            while (j > i + 1 && !segmentOk(pts[i], pts[j])) j--
            out.add(pts[j])
            i = j
        }
        return out
    }

    private fun segmentOk(a: LatLng, b: LatLng): Boolean {
        val g = grid ?: return true
        // Sample ~ every 0.1 nm so long shortcuts can't skip over a thin obstacle.
        val steps = maxOf(3, (GeoUtils.distanceNm(a, b) / 0.1).toInt())
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            val lat = a.latitude + (b.latitude - a.latitude) * f
            val lon = a.longitude + (b.longitude - a.longitude) * f
            if (!g.navigable(lat, lon, boat.draftM, boat.ukcMarginM)) return false
        }
        return true
    }

    private fun pruneEnvelope(cands: List<Node>, start: LatLng, sectors: Int): List<Node> {
        val best = arrayOfNulls<Node>(sectors)
        val bestDist = DoubleArray(sectors)
        for (c in cands) {
            val brg = GeoUtils.bearingDeg(start, c.p)
            val s = (brg / 360.0 * sectors).toInt().coerceIn(0, sectors - 1)
            val d = GeoUtils.distanceNm(start, c.p)
            if (best[s] == null || d > bestDist[s]) { best[s] = c; bestDist[s] = d }
        }
        return best.filterNotNull()
    }

    /** Douglas-Peucker simplify (epsilon in nm). */
    private fun simplify(pts: List<LatLng>, epsNm: Double): List<LatLng> {
        if (pts.size < 3) return pts
        var maxD = 0.0; var idx = 0
        for (i in 1 until pts.size - 1) {
            val d = perpNm(pts[i], pts.first(), pts.last())
            if (d > maxD) { maxD = d; idx = i }
        }
        return if (maxD > epsNm) {
            val left = simplify(pts.subList(0, idx + 1), epsNm)
            val right = simplify(pts.subList(idx, pts.size), epsNm)
            left.dropLast(1) + right
        } else listOf(pts.first(), pts.last())
    }

    private fun perpNm(p: LatLng, a: LatLng, b: LatLng): Double {
        // distance from p to segment a-b, approximated in nm via equirectangular projection
        val kx = 60.0 * kotlin.math.cos(Math.toRadians((a.latitude + b.latitude) / 2)) // nm per deg lon
        val ky = 60.0 // nm per deg lat
        val ax = a.longitude * kx; val ay = a.latitude * ky
        val bx = b.longitude * kx; val by = b.latitude * ky
        val px = p.longitude * kx; val py = p.latitude * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return kotlin.math.hypot(px - ax, py - ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
