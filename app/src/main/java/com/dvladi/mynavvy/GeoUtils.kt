package com.dvladi.mynavvy

import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle helpers in nautical units. */
object GeoUtils {
    private const val EARTH_KM = 6371.0088
    private const val KM_TO_NM = 0.5399568

    /** Great-circle distance in metres, raw coordinates (the one haversine in the app). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_KM * 1000.0 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Great-circle distance in nautical miles. */
    fun distanceNm(a: LatLng, b: LatLng): Double =
        distanceM(a.latitude, a.longitude, b.latitude, b.longitude) / 1000.0 * KM_TO_NM

    /** Initial true bearing a->b, degrees 0..360. */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val la1 = Math.toRadians(a.latitude)
        val la2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Great-circle destination from a point along a bearing for a distance in nm. */
    fun destination(from: LatLng, bearingDeg: Double, distNm: Double): LatLng {
        val r = distNm / 3440.065 // angular distance
        val brg = Math.toRadians(bearingDeg)
        val la1 = Math.toRadians(from.latitude)
        val lo1 = Math.toRadians(from.longitude)
        val la2 = Math.asin(sin(la1) * cos(r) + cos(la1) * sin(r) * cos(brg))
        val lo2 = lo1 + atan2(sin(brg) * sin(r) * cos(la1), cos(r) - sin(la1) * sin(la2))
        return LatLng(Math.toDegrees(la2), Math.toDegrees(lo2))
    }

    /**
     * Signed cross-track distance (nm) of point `p` from the great-circle leg `a`→`b`.
     * Positive = right of the intended track, negative = left. |value| = XTE magnitude.
     */
    fun crossTrackNm(a: LatLng, b: LatLng, p: LatLng): Double {
        val d13 = distanceNm(a, p) / 3440.065                 // angular distance a→p (radians)
        val t13 = Math.toRadians(bearingDeg(a, p))
        val t12 = Math.toRadians(bearingDeg(a, b))
        return Math.asin(sin(d13) * sin(t13 - t12)) * 3440.065
    }

    /** "2h 04m" / "47m" from a duration in hours. */
    fun formatHours(hours: Double): String {
        if (hours.isNaN() || hours.isInfinite() || hours <= 0) return "--"
        val totalMin = (hours * 60).roundToInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) String.format("%dh %02dm", h, m) else String.format("%dm", m)
    }

    /** "1:23:45" from milliseconds. */
    fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Latitude in chart form, e.g. 32°41.400'N. */
    fun formatLat(lat: Double): String = formatDeg(abs(lat), if (lat >= 0) "N" else "S")

    /** Longitude in chart form, e.g. 117°10.200'W. */
    fun formatLon(lon: Double): String = formatDeg(abs(lon), if (lon >= 0) "E" else "W")

    private fun formatDeg(v: Double, hemi: String): String {
        val deg = v.toInt()
        val min = (v - deg) * 60.0
        return String.format("%d°%06.3f'%s", deg, min, hemi)
    }
}
