package com.dvladi.mynavvy.nmea

import java.util.Calendar
import java.util.TimeZone

/**
 * One parsed NMEA 0183 sentence, reduced to what MyNavvy consumes. Which subclass a
 * sentence maps to decides its route into the app: [Fix]/[Course] ride the
 * WatchService position pipeline (they can replace phone GPS), everything else is
 * gauge data that doesn't fit an Android `Location`.
 */
sealed class NavUpdate {
    /** RMC/GLL position. RMC also carries SOG/COG (empty when the source has no way on). */
    data class Fix(
        val lat: Double, val lon: Double,
        val sogKn: Double? = null, val cogTrue: Double? = null,
        val utcMs: Long? = null,
    ) : NavUpdate()

    /** VTG course + speed over ground (no position). */
    data class Course(val cogTrue: Double?, val sogKn: Double?) : NavUpdate()

    /** DPT/DBT depth below transducer, metres. [offsetM] (DPT only): + = to waterline, - = to keel. */
    data class Depth(val meters: Double, val offsetM: Double? = null) : NavUpdate()

    /** VHW speed through water (+ true heading when the talker has a compass). */
    data class WaterSpeed(val stwKn: Double?, val headingTrue: Double? = null) : NavUpdate()

    /** MWV/MWD wind — source B (ESP32 gateway) only; the GO7 carries no wind.
     *  [relative] = angle is off the bow (MWV R); false = true direction (MWV T / MWD). */
    data class Wind(val speedKn: Double, val angleDeg: Double, val relative: Boolean) : NavUpdate()
}

/**
 * Pure NMEA 0183 sentence parser: `String -> NavUpdate?`. No Android dependencies —
 * unit-tested on the JVM against captured/boatsim sentences. Provider-agnostic: the
 * talker ID (GP/GN/SD/VW/II/…) is ignored, only the 3-letter sentence type matters.
 *
 * Returns null for: bad/missing checksum (a checksum is REQUIRED — this is nav data),
 * unknown sentence types, void fixes (RMC/GLL status V), and malformed fields. A null
 * is always "ignore this line", never an error.
 */
object NmeaParser {

    fun parse(sentence: String): NavUpdate? {
        val body = checksumValid(sentence.trim()) ?: return null
        val f = body.split(',')
        val address = f[0]
        if (address.length < 5) return null // proprietary ($P…) or junk
        return try {
            when (address.substring(address.length - 3)) {
                "RMC" -> parseRmc(f)
                "GLL" -> parseGll(f)
                "VTG" -> parseVtg(f)
                "DPT" -> parseDpt(f)
                "DBT" -> parseDbt(f)
                "VHW" -> parseVhw(f)
                "MWV" -> parseMwv(f)
                "MWD" -> parseMwd(f)
                else -> null
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Validate `$…*HH`; return the body between '$'/'!' and '*', or null. */
    private fun checksumValid(s: String): String? {
        if (s.length < 9 || (s[0] != '$' && s[0] != '!')) return null
        val star = s.lastIndexOf('*')
        if (star != s.length - 3) return null
        val body = s.substring(1, star)
        var cs = 0
        for (ch in body) cs = cs xor ch.code
        return if (s.substring(star + 1).equals(String.format("%02X", cs), ignoreCase = true))
            body else null
    }

    // --- field helpers (empty NMEA fields are common and mean "no data") ------

    private fun num(f: List<String>, i: Int): Double? =
        f.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()

    private fun str(f: List<String>, i: Int): String? =
        f.getOrNull(i)?.takeIf { it.isNotEmpty() }

    /** ddmm.mmmm / dddmm.mmmm + hemisphere -> signed degrees. */
    private fun latLon(v: String, hemi: String, degDigits: Int): Double {
        val deg = v.substring(0, degDigits).toDouble()
        val min = v.substring(degDigits).toDouble()
        val d = deg + min / 60.0
        return if (hemi == "S" || hemi == "W") -d else d
    }

    /** RMC hhmmss.ss + ddmmyy -> UTC epoch millis. */
    private fun utcMs(time: String?, date: String?): Long? {
        if (time == null || time.length < 6 || date == null || date.length != 6) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2000 + date.substring(4, 6).toInt(),          // year (GPS rollover-safe until 2100)
            date.substring(2, 4).toInt() - 1,                  // month is 0-based
            date.substring(0, 2).toInt(),
            time.substring(0, 2).toInt(),
            time.substring(2, 4).toInt(),
            time.substring(4, 6).toInt())
        val frac = time.substringAfter('.', "").take(3).padEnd(3, '0')
        return cal.timeInMillis + (frac.toIntOrNull() ?: 0)
    }

    // --- sentence bodies -------------------------------------------------------

    /** RMC: time, status(A/V), lat, N/S, lon, E/W, SOG kn, COG true, date, … */
    private fun parseRmc(f: List<String>): NavUpdate? {
        if (str(f, 2) != "A") return null // V = void: no fix, ignore
        val lat = latLon(f[3], f[4], 2)
        val lon = latLon(f[5], f[6], 3)
        return NavUpdate.Fix(lat, lon, sogKn = num(f, 7), cogTrue = num(f, 8),
            utcMs = utcMs(str(f, 1), str(f, 9)))
    }

    /** GLL: lat, N/S, lon, E/W, time, status(A/V), … Position only. */
    private fun parseGll(f: List<String>): NavUpdate? {
        if (str(f, 6) != "A") return null
        return NavUpdate.Fix(latLon(f[1], f[2], 2), latLon(f[3], f[4], 3))
    }

    /** VTG: cog,T,cogM,M,sog,N,kmh,K[,mode]. Pair-scan so unit-letter drift doesn't bite. */
    private fun parseVtg(f: List<String>): NavUpdate? {
        var cog: Double? = null
        var sog: Double? = null
        for (i in 1 until f.size - 1) {
            when (f[i + 1]) {
                "T" -> cog = cog ?: num(f, i)
                "N" -> sog = sog ?: num(f, i)
            }
        }
        if (cog == null && sog == null) return null
        return NavUpdate.Course(cog, sog)
    }

    /** DPT: depth m, transducer offset m. */
    private fun parseDpt(f: List<String>): NavUpdate? =
        num(f, 1)?.let { NavUpdate.Depth(it, offsetM = num(f, 2)) }

    /** DBT: depth ft, f, depth m, M, depth fathoms, F — metres field preferred. */
    private fun parseDbt(f: List<String>): NavUpdate? {
        val m = num(f, 3) ?: num(f, 1)?.times(0.3048) ?: return null
        return NavUpdate.Depth(m)
    }

    /** VHW: headingT, T, headingM, M, stw kn, N, kmh, K. */
    private fun parseVhw(f: List<String>): NavUpdate? {
        val stw = num(f, 5)
        val hdg = num(f, 1)
        if (stw == null && hdg == null) return null
        return NavUpdate.WaterSpeed(stw, hdg)
    }

    /** MWV: angle, R|T, speed, N|K|M (kn / km/h / m/s), A. */
    private fun parseMwv(f: List<String>): NavUpdate? {
        if (str(f, 5) != "A") return null
        val angle = num(f, 1) ?: return null
        val raw = num(f, 3) ?: return null
        val kn = when (str(f, 4)) {
            "N" -> raw
            "K" -> raw / 1.852
            "M" -> raw * 1.94384
            else -> return null
        }
        return NavUpdate.Wind(kn, angle, relative = str(f, 2) != "T")
    }

    /** MWD: dirT, T, dirM, M, speed kn, N, speed m/s, M. True wind direction. */
    private fun parseMwd(f: List<String>): NavUpdate? {
        val dir = num(f, 1) ?: return null
        val kn = num(f, 5) ?: num(f, 7)?.times(1.94384) ?: return null
        return NavUpdate.Wind(kn, dir, relative = false)
    }
}
